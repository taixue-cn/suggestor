package cc.taixue.suggestor.client

import cc.taixue.suggestor.*
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.gui.AbstractConfigScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.*
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.*
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.Executors

data class Preferences(val trusted: MutableSet<String> = mutableSetOf(),val ignored: MutableSet<String> = mutableSetOf(),
                       val seenResults: MutableSet<String> = mutableSetOf(),val reported: MutableSet<String> = mutableSetOf())
class SuggestorClient : ClientModInitializer {
    private val logger=LoggerFactory.getLogger("Suggestor")
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"suggestor-download").apply { isDaemon=true } }
    private val game=FabricLoader.getInstance().gameDir.toAbsolutePath().normalize()
    private val state=Transactions.state(game)
    private lateinit var preferences: Preferences
    private lateinit var lockChannel: FileChannel
    private lateinit var instanceLock: FileLock
    private var assembler=Wire.Assembler()
    private var session: String?=null
    private var serverKey=""
    private var serverId=""
    private var inventory: Inventory?=null
    private var activeOffer: Offer?=null
    private var decisionScreen: Screen?=null
    private var parentScreen: Screen?=null
    private var decisionSent=false
    private var queuedScreen: Screen?=null
    private var presentAfter=0L
    private var startupChecked=false
    private var startupNotice: Screen?=null
    private var afterStartup: (() -> Unit)?=null
    private val helpers=mutableListOf<Process>()
    data class Frame(val bytes: ByteArray): CustomPacketPayload {
        override fun type()=TYPE
        companion object {
            val TYPE=CustomPacketPayload.Type<Frame>(Identifier.parse(Wire.CHANNEL))
            val CODEC: StreamCodec<FriendlyByteBuf,Frame> = StreamCodec.of(
                { buffer,frame -> buffer.writeBytes(frame.bytes) },
                { buffer ->
                    require(buffer.readableBytes()<=Wire.CHUNK+Wire.HEADER)
                    Frame(ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) })
                })
        }
    }
    override fun onInitializeClient() {
        val pair=Transactions.lock(game);lockChannel=pair.first;instanceLock=pair.second
        val path=state.resolve("preferences.json")
        preferences=if(Files.exists(path))runCatching { Json.read(path,Preferences::class.java) }.getOrElse {
            logger.warn("Cannot read preferences; keeping the damaged file and asking for consent again",it)
            Files.copy(path,path.resolveSibling("preferences-damaged-${System.currentTimeMillis()}.json"))
            Preferences()
        } else Preferences()
        PayloadTypeRegistry.serverboundPlay().register(Frame.TYPE,Frame.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(Frame.TYPE,Frame.CODEC)
        ClientPlayNetworking.registerGlobalReceiver(Frame.TYPE) { frame,context ->
            context.client().execute {
                try { assembler.accept(frame.bytes)?.let(::receive) }
                catch(e: Exception) { logger.warn("Invalid server suggestion",e);info("无法读取服务器建议",e.message?:"协议不兼容") }
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _,_,_ ->
            assembler=Wire.Assembler();session=null;activeOffer=null;decisionScreen=null;decisionSent=false
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _,_ -> session=null;decisionScreen=null;activeOffer=null }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
        worker.execute {
            Transactions.directories(game).filter { Files.exists(it.resolve("ready.json")) && !Files.exists(it.resolve("result.json")) }.forEach { root ->
                try {
                    val helperPath=root.resolve("helper.json")
                    val helper=if(Files.exists(helperPath)) Json.read(helperPath,com.google.gson.JsonObject::class.java) else null
                    val process=helper?.get("pid")?.asLong?.let { ProcessHandle.of(it).orElse(null) }
                    val alive=process?.isAlive==true && process.info().startInstant().map { it.toString()==helper.get("started").asString }.orElse(false)
                    if(!alive) {
                        val plan=Json.read(root.resolve("plan.json"),Plan::class.java)
                        val current=ProcessHandle.current()
                        Json.write(root.resolve("plan.json"),plan.copy(parentPid=current.pid(),parentStarted=current.info().startInstant().orElseThrow().toString()))
                        launchHelper(root)
                    }
                } catch(e: Exception) { logger.warn("Cannot resume pending transaction: $root",e) }
            }
        }
        logger.info("Suggestor initialized; UI provided by Cloth Config API")
    }
    private fun persist()=Json.write(state.resolve("preferences.json"),preferences)
    private fun enqueue(screen: Screen,delayMillis: Long=0) {
        queuedScreen=screen;presentAfter=System.nanoTime()+delayMillis*1_000_000
    }
    private fun tick(mc: Minecraft) {
        if(!startupChecked && (mc.gui.screen() is TitleScreen || (mc.player!=null && mc.gui.screen()==null))) {
            checkStartupResults(mc)
        }
        // Quick Play may replace the title screen while joining; keep unacknowledged results available.
        if(startupNotice!=null && queuedScreen==null && mc.gui.screen()==null && mc.player!=null)enqueue(startupNotice!!)
        val screen=queuedScreen
        if(screen!=null && System.nanoTime()>=presentAfter && mc.gui.overlay()==null) {
            queuedScreen=null;mc.gui.setScreen(screen)
        }
        if(decisionScreen!=null && !decisionSent && mc.gui.screen()===parentScreen) {
            decisionSent=true;activeOffer?.let { decide(it,emptySet()) };decisionScreen=null
        }
    }
    private fun checkStartupResults(mc: Minecraft) {
        if(startupChecked)return
        startupChecked=true
        val acknowledged=mutableListOf<String>()
        val results=Transactions.directories(game).mapNotNull { root ->
            val id=root.fileName.toString()
            if(id in preferences.seenResults)return@mapNotNull null
            if(!Files.exists(root.resolve("result.json"))) {
                if(Files.exists(root.resolve("ready.json"))) "上次有尚未完成的变更。退出本次游戏后可重试；记录：$root"
                else { acknowledged.add(id);"上次的准备过程未完成，原文件未变更。请重新连接服务器重试；记录：$root" }
            } else {
                acknowledged.add(id)
                runCatching {
                    val result=Json.read(root.resolve("result.json"),ApplyResult::class.java)
                    "${if(result.success)"已完成" else "未完成"}：${result.detail}"
                }.getOrElse { "无法读取处理结果，请查看记录：$root" }
            }
        }
        if(results.isEmpty())return
        val notice=ConfirmScreen({ _ ->
            preferences.seenResults.addAll(acknowledged);persist()
            startupNotice=null;queuedScreen=null;mc.gui.setScreen(null)
            val next=afterStartup;afterStartup=null;next?.invoke()
        },text("上次的组件调整结果"),text(results.joinToString("\n\n")),text("知道了"),text("返回"))
        startupNotice=notice;enqueue(notice)
    }
    private fun receive(message: Wire.Message) {
        if(message.type=="HELLO") {
            val hello=Json.gson.fromJson(message.data,Hello::class.java);hello.ui.validate();UUID.fromString(hello.serverId)
            session=message.session;serverId=hello.serverId
            serverKey=(Minecraft.getInstance().currentServer?.ip?:"unknown")+"|"+serverId
            send("HELLO_ACK","0.1.0")
            val captured=session
            checkStartupResults(Minecraft.getInstance())
            val proceed={
                if(session==captured) {
                    if(!hello.requestInventory)reportResults()
                    else if(serverKey in preferences.trusted)collect(captured)
                    else enqueue(ConfirmScreen({ accepted ->
                        if(session==captured) {
                            if(accepted) { preferences.trusted.add(serverKey);persist();collect(captured) }
                            else { send("DECLINE_INVENTORY",true);Minecraft.getInstance().gui.setScreen(null) }
                        }
                    },text(hello.ui.consentTitle),text(hello.ui.consentText+"\n\n只会上报组件信息，不会上报本机绝对路径。"),text("允许并记住此服务器"),text("不允许")))
                }
            }
            if(startupNotice!=null)afterStartup=proceed else proceed()
            return
        }
        if(message.session!=session)return
        when(message.type) {
            "OFFER" -> {
                val offer=Json.gson.fromJson(message.data,Offer::class.java)
                require(offer.suggestions.size<=100);offer.ui.validate();offer.suggestions.forEach(Suggestion::validate)
                activeOffer=offer
                val applicable=applicable(offer.suggestions,requireNotNull(inventory))
                val visible=applicable.filter { it.level==Level.REQUIRED || ignoreKey(it) !in preferences.ignored }
                if(visible.isEmpty()) { decide(offer,emptySet());return }
                showOffer(offer,visible)
            }
            "ERROR" -> info("服务器建议暂不可用",message.data.asString.take(4000))
            "RECEIPT_ACK" -> { preferences.reported.add(message.data.asString);persist() }
        }
    }
    private fun collect(captured: String?) {
        info("检查组件", "正在读取本地组件清单，请稍候。")
        worker.execute {
            try {
                val snapshot=InventoryScanner.scan(game)
                Minecraft.getInstance().execute {
                    if(session==captured) {
                        queuedScreen=null
                        inventory=snapshot;send("INVENTORY",snapshot);reportResults()
                        Minecraft.getInstance().gui.setScreen(null)
                    }
                }
            } catch(e: Exception) { Minecraft.getInstance().execute { info("无法读取组件清单",e.message?:"读取失败") } }
        }
    }
    private fun ignoreKey(s: Suggestion): String {
        val digest=MessageDigest.getInstance("SHA-256").digest(Json.gson.toJson(s).toByteArray()).joinToString("") { "%02x".format(it) }
        return "$serverKey|${s.id}|${s.revision}|$digest"
    }
    private fun showOffer(offer: Offer,visible: List<Suggestion>) {
        val mc=Minecraft.getInstance();parentScreen=mc.gui.screen();decisionSent=false
        val selected=mutableSetOf<String>()
        val builder=ConfigBuilder.create().setParentScreen(parentScreen).setTitle(text(offer.ui.title)).setDoesConfirmSave(false)
        val entries=builder.entryBuilder()
        val category=builder.getOrCreateCategory(text("建议（${visible.size}）"))
        category.addEntry(entries.startTextDescription(text(offer.ui.introduction)).build())
        visible.forEach { s ->
            val operation=when(s.action) { Action.ADD->"安装";Action.UPDATE->"更新";Action.REMOVE->"移除" }
            val kind=when(s.kind) { Kind.MOD->"MOD";Kind.RESOURCE_PACK->"资源包";Kind.SHADER_PACK->"光影包" }
            val level=when(s.level) { Level.SUGGESTED->"普通建议";Level.RECOMMENDED->"强烈推荐";Level.REQUIRED->"必须满足" }
            val current=inventory?.entries?.firstOrNull { it.kind==s.kind && it.id==s.targetId }?.version.orEmpty()
            val version=if(s.action==Action.UPDATE)" $current → ${s.targetVersion}" else " ${s.targetVersion}"
            val toggle=entries.startBooleanToggle(text("$operation $kind：${s.name}$version"),s.level==Level.REQUIRED)
                .setYesNoTextSupplier { text(if(it)"采纳" else "忽略") }
                .setSaveConsumer { if(it)selected.add(s.id) else selected.remove(s.id) }
                .setTooltip(text(s.reason)).build()
            category.addEntry(toggle)
            val source=if(s.url.isNotBlank())"\n下载来源：${java.net.URI(s.url).host} · ${s.size/1024} KB" else "\n原文件将保留备份。"
            val related=s.requires.mapNotNull { id -> offer.suggestions.find { it.id==id }?.name }
            val dependency=if(related.isEmpty())"" else "\n关联建议：${related.joinToString()}（采纳时会一并确认）"
            category.addEntry(entries.startTextDescription(text("$level · ${s.reason}$source$dependency")).build())
        }
        category.addEntry(entries.startTextDescription(text("文件在退出游戏后处理。资源包和光影包安装后，可在对应设置中启用。" )).build())
        builder.setAfterInitConsumer { screen ->
            val buttons=Screens.getWidgets(screen)
            val cancel=buttons.first { it.message.string in listOf(Component.translatable("gui.cancel").string,
                Component.translatable("text.cloth-config.cancel_discard").string) }
            val save=buttons.single { it!==cancel && it.y==cancel.y }
            // Cloth's save button rewrites its caption while rendering; use a standard button and its public save pipeline.
            buttons.remove(save)
            buttons.add(Button.builder(text(offer.ui.accept)) { (screen as AbstractConfigScreen).saveAll(true) }
                .bounds(save.x,save.y,save.width,save.height).build())
            cancel.message=text(offer.ui.ignore)
        }
        builder.setSavingRunnable {
            decisionSent=true
            val expanded=selected.toMutableSet()
            fun include(id: String) { offer.suggestions.find { it.id==id }?.requires?.forEach { dep ->
                if(offer.suggestions.any { it.id==dep } && expanded.add(dep))include(dep)
            } }
            selected.forEach(::include)
            // Schedule after Cloth returns to its parent so its close cannot overwrite the confirmation.
            mc.execute { confirm(offer,expanded) }
        }
        decisionScreen=builder.build();enqueue(decisionScreen!!)
        send("DISPLAYED",offer.batchId)
    }
    private fun confirm(offer: Offer,selected: Set<String>) {
        if(selected.isEmpty()) { decide(offer,selected);return }
        val changes=offer.suggestions.filter { it.id in selected }
        val summary=changes.take(3).joinToString("\n") {
            val action=when(it.action) { Action.ADD->"安装";Action.UPDATE->"更新";Action.REMOVE->"移除" }
            "$action：${it.name.take(32)}"
        }+if(changes.size>3)"\n以及另外 ${changes.size-3} 项。" else ""
        enqueue(ConfirmScreen({ yes ->
            if(yes)decide(offer,selected) else showOffer(offer,offer.suggestions)
        },text(offer.ui.accept),text("将处理 ${selected.size} 项组件变更：\n$summary\n\n完整列表和原因可返回上一页查看。\n下载完成后，在退出游戏时应用。"),text(offer.ui.accept),text("返回检查")))
    }
    private fun decide(offer: Offer,selected: Set<String>) {
        decisionScreen=null;decisionSent=true
        val accepted=offer.suggestions.filter { it.id in selected };val ignored=offer.suggestions.filter { it.id !in selected }
        send("DECISION",Decision(offer.batchId,accepted.map { it.id },ignored.map { it.id }))
        ignored.filter { it.level!=Level.REQUIRED }.forEach { preferences.ignored.add(ignoreKey(it)) };persist()
        if(accepted.isEmpty()) { Minecraft.getInstance().gui.setScreen(null);return }
        val capturedServer=serverId;val capturedSession=session;val snapshot=requireNotNull(inventory)
        info("准备组件变更","正在下载和校验 ${accepted.size} 项变更；完成前会保留原文件。")
        worker.execute {
            var root: Path?=null
            try {
                root=Transactions.prepare(game,capturedServer,offer,accepted,snapshot)
                Dependencies.validate(root,snapshot,accepted)
                launchHelper(root)
                Transactions.ready(root)
                val plan=Json.read(root.resolve("plan.json"),Plan::class.java)
                Minecraft.getInstance().execute {
                    if(session==capturedSession)send("READY",Receipt(offer.batchId,plan.id,"WAITING_FOR_EXIT",accepted.map { it.id }))
                    enqueue(ConfirmScreen({ exit ->
                        if(exit)Minecraft.getInstance().stop() else Minecraft.getInstance().gui.setScreen(null)
                    },text("组件变更已准备好"),text(offer.ui.restart),text("退出游戏并应用"),text("稍后退出")),750)
                }
            } catch(e: Exception) {
                logger.warn("Cannot prepare changes",e)
                val failed=root?:(e as? PreparationException)?.root
                failed?.let { Json.write(it.resolve("result.json"),ApplyResult(it.fileName.toString(),false,true,e.message?:"准备失败，原文件未变更。")) }
                val receipt=Receipt(offer.batchId,failed?.fileName?.toString()?:UUID.randomUUID().toString(),"PREPARATION_FAILED",accepted.map { it.id },networkDetail(e.message?:"准备失败"))
                Minecraft.getInstance().execute {
                    if(session==capturedSession)send("FAILED",receipt)
                    info("组件变更未完成",receipt.detail)
                }
            }
        }
    }
    private fun launchHelper(root: Path) {
        val jar=root.resolve("updater.jar")
        javaClass.getResourceAsStream("/META-INF/suggestor/updater.jar").use { input ->
            requireNotNull(input) { "Missing embedded updater" };Files.copy(input,jar,StandardCopyOption.REPLACE_EXISTING)
        }
        Files.deleteIfExists(root.resolve("helper.json"))
        val windows=System.getProperty("os.name").lowercase().contains("win")
        val java=Path.of(System.getProperty("java.home"),"bin",if(windows)"javaw.exe" else "java")
        val process=ProcessBuilder(java.toString(),"-cp",jar.toString(),"cc.taixue.suggestor.updater.Updater",root.toString())
            .directory(game.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("helper.log").toFile()).start()
        helpers.add(process)
        val deadline=System.nanoTime()+15_000_000_000L
        while(!Files.exists(root.resolve("helper.json"))) {
            check(process.isAlive && System.nanoTime()<deadline) { "后台文件处理进程未能启动；请查看 ${root.resolve("helper.log")}" }
            Thread.sleep(50)
        }
    }
    private fun reportResults() {
        if(serverKey !in preferences.trusted)return
        Transactions.directories(game).forEach { root ->
            if(!Files.exists(root.resolve("result.json")))return@forEach
            runCatching {
                val plan=Json.read(root.resolve("plan.json"),Plan::class.java)
                if(plan.serverId==serverId && plan.id !in preferences.reported) {
                    val result=Json.read(root.resolve("result.json"),ApplyResult::class.java)
                    send("RECEIPT",Receipt(plan.batchId,plan.id,if(result.success)"FILES_APPLIED" else "APPLICATION_FAILED",plan.changes.map { it.suggestionId },networkDetail(result.detail)))
                }
            }.onFailure { logger.warn("Cannot read transaction receipt: $root",it) }
        }
    }
    private fun networkDetail(detail: String): String = detail.replace(game.toString(),"[当前实例]")
        .replace(game.toRealPath().toString(),"[当前实例]").take(4000)
    private fun send(type: String,data: Any) {
        val id=session?:return
        if(Minecraft.getInstance().connection==null)return
        Wire.encode(Wire.message(id,type,data)).forEach { ClientPlayNetworking.send(Frame(it)) }
    }
    private fun info(title: String,body: String) {
        enqueue(ConfirmScreen({ _ -> Minecraft.getInstance().gui.setScreen(null) },text(title),text(body),text("知道了"),text("返回")))
    }
    private fun text(value: String)=Component.literal(value)
}
