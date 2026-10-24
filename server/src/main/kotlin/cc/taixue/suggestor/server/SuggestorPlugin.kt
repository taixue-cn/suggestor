package cc.taixue.suggestor.server

import cc.taixue.suggestor.*
import com.google.gson.JsonElement
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

class SuggestorPlugin : JavaPlugin(), Listener, PluginMessageListener {
    private var policy=Config()
    private val sessions=mutableMapOf<UUID,Session>()
    private val waiting=ConcurrentHashMap.newKeySet<UUID>()
    private val audit=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(2048),
        { task -> Thread(task,"suggestor-audit").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    private class Session(val id: String,val candidates: List<Suggestion>,val ui: Ui) {
        val assembler=Wire.Assembler()
        var inventory: Inventory?=null
        var present=false
        var offer: Offer?=null
        var accepted: Set<String>?=null
        var frames=0
        var window=System.nanoTime()
    }
    override fun onEnable() {
        Files.createDirectories(dataFolder.toPath())
        val path=dataFolder.toPath().resolve("config.json")
        if(!Files.exists(path)) Json.write(path,Config())
        try { policy=Json.read(path,Config::class.java).also(Config::validate) }
        catch(e: Exception) { logger.severe("Invalid config.json; Suggestor disabled: ${e.message}");server.pluginManager.disablePlugin(this);return }
        server.messenger.registerIncomingPluginChannel(this,Wire.CHANNEL,this)
        server.messenger.registerOutgoingPluginChannel(this,Wire.CHANNEL)
        server.pluginManager.registerEvents(this,this)
        logger.info("Suggestor ready: ${policy.suggestions.size} suggestions, ${policy.assignments.size} assignments; missing-client policy: ${policy.missingClient.action}.")
    }
    override fun onDisable() {
        sessions.clear();waiting.clear();audit.shutdown()
        audit.awaitTermination(5,TimeUnit.SECONDS)
    }
    private fun record(player: Player,event: String,data: Any) {
        val row=Json.gson.toJsonTree(mapOf("time" to Instant.now().toString(),"playerUuid" to player.uniqueId.toString(),
            "playerName" to player.name,"event" to event,"data" to data)).toString()+"\n"
        try { audit.execute {
            try { Files.writeString(dataFolder.toPath().resolve("audit.jsonl"),row,StandardOpenOption.CREATE,StandardOpenOption.APPEND) }
            catch(e: Exception) { logger.severe("Audit write failed: ${e.message}") }
        } } catch(_: RejectedExecutionException) { logger.severe("Audit queue full; event dropped: $event") }
    }
    @EventHandler fun join(event: PlayerJoinEvent) = begin(event.player)
    @EventHandler fun quit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId);waiting.remove(event.player.uniqueId) }
    private fun begin(player: Player) {
        sessions.remove(player.uniqueId);waiting.remove(player.uniqueId)
        val assignments=policy.audience(player.name,player::hasPermission)
        val candidates=policy.select(assignments)
        if(candidates.isEmpty() && policy.missingClient.action==MissingClientAction.SILENT)return
        val session=Session(UUID.randomUUID().toString(),candidates,assignments.firstOrNull()?.ui?:Ui())
        sessions[player.uniqueId]=session
        server.scheduler.runTaskLater(this,Runnable {
            if(sessions[player.uniqueId]===session && player.isOnline) send(player,session,"HELLO",Hello(policy.serverId,session.ui,candidates.isNotEmpty()))
        },20L)
        server.scheduler.runTaskLater(this,Runnable {
            if(sessions[player.uniqueId]===session && !session.present) {
                record(player,"missing_client",mapOf("session" to session.id,"action" to policy.missingClient.action))
                when(policy.missingClient.action) {
                    MissingClientAction.KICK -> player.kick(Component.text(policy.missingClient.message))
                    MissingClientAction.REMIND -> {
                        player.showTitle(Title.title(Component.text(policy.missingClient.title),Component.text(policy.missingClient.subtitle)))
                        player.sendMessage(Component.text(policy.missingClient.message))
                    }
                    MissingClientAction.SILENT -> Unit
                }
                sessions.remove(player.uniqueId);waiting.remove(player.uniqueId)
            }
        },policy.handshakeSeconds*20L)
    }
    private fun send(player: Player,session: Session,type: String,data: Any) {
        Wire.encode(Wire.message(session.id,type,data)).forEach { player.sendPluginMessage(this,Wire.CHANNEL,it) }
    }
    override fun onPluginMessageReceived(channel: String,player: Player,bytes: ByteArray) {
        val session=sessions[player.uniqueId]?:return
        try {
            if(System.nanoTime()-session.window>60_000_000_000L) { session.window=System.nanoTime();session.frames=0 }
            require(++session.frames<=400) { "Message rate exceeded" }
            val message=session.assembler.accept(bytes)?:return
            require(message.session==session.id) { "Stale session" }
            when(message.type) {
                "HELLO_ACK" -> {
                    require(!session.present && message.data.asString.length<=100)
                    session.present=true;record(player,"client_detected",message.data)
                    if(session.candidates.any { it.level==Level.REQUIRED })waiting.add(player.uniqueId)
                    server.scheduler.runTaskLater(this,Runnable {
                        if(sessions[player.uniqueId]===session && session.inventory==null && player.uniqueId in waiting) {
                            record(player,"inventory_timeout",mapOf("session" to session.id))
                            player.kick(Component.text(session.ui.requiredDisconnect))
                        }
                    },policy.decisionSeconds*20L)
                }
                "INVENTORY" -> inventory(player,session,Json.gson.fromJson(message.data,Inventory::class.java))
                "DECISION" -> decision(player,session,Json.gson.fromJson(message.data,Decision::class.java))
                "DISPLAYED" -> {
                    require(message.data.asString==session.offer?.batchId);record(player,"displayed",message.data)
                }
                "DECLINE_INVENTORY" -> {
                    record(player,"inventory_declined",emptyMap<String,String>())
                    if(player.uniqueId in waiting) player.kick(Component.text(session.ui.requiredDisconnect))
                }
                "READY", "FAILED", "RECEIPT" -> {
                    val receipt=Json.gson.fromJson(message.data,Receipt::class.java)
                    require(receipt.detail.length<=4000 && receipt.suggestions.size<=100 && receipt.status.length<=100)
                    UUID.fromString(receipt.transactionId)
                    if(message.type!="RECEIPT") {
                        require(receipt.batchId==session.offer?.batchId)
                        require(session.accepted!=null && receipt.suggestions.toSet()==session.accepted)
                    }
                    record(player,"client_${message.type.lowercase()}",receipt)
                    if(message.type=="READY" && player.uniqueId in waiting) player.kick(Component.text(session.ui.restart))
                    if(message.type=="RECEIPT")send(player,session,"RECEIPT_ACK",receipt.transactionId)
                }
                else -> throw IllegalArgumentException("Unknown message")
            }
        } catch(e: Exception) {
            record(player,"protocol_error",mapOf("message" to (e.message?:"invalid data").take(500)))
            if(player.uniqueId in waiting)player.kick(Component.text("Suggestor 无法完成检查，请重连或联系管理员。"))
        }
    }
    private fun inventory(player: Player,session: Session,inventory: Inventory) {
        require(session.present && session.inventory==null);inventory.validate()
        val suggestions=try { applicable(session.candidates,inventory) } catch(e: IllegalArgumentException) {
            logger.severe("Conflicting Suggestor assignments for ${player.name}: ${e.message}")
            send(player,session,"ERROR","服务器的建议配置存在冲突，请联系管理员。")
            record(player,"configuration_conflict",mapOf("message" to (e.message?:"")))
            if(player.uniqueId in waiting)player.kick(Component.text("服务器建议配置冲突，请联系管理员。"))
            return
        }
        session.inventory=inventory
        record(player,"inventory",inventory)
        if(suggestions.none { it.level==Level.REQUIRED })waiting.remove(player.uniqueId)
        val offer=Offer(UUID.randomUUID().toString(),session.ui,suggestions);session.offer=offer
        record(player,"offered",offer);send(player,session,"OFFER",offer)
        server.scheduler.runTaskLater(this,Runnable {
            if(sessions[player.uniqueId]===session && player.uniqueId in waiting) {
                record(player,"decision_timeout",mapOf("batch" to offer.batchId));player.kick(Component.text(session.ui.requiredDisconnect))
            }
        },policy.decisionSeconds*20L)
    }
    private fun decision(player: Player,session: Session,decision: Decision) {
        val offer=requireNotNull(session.offer)
        require(decision.batchId==offer.batchId && session.accepted==null)
        val ids=offer.suggestions.map { it.id }.toSet()
        val accepted=decision.accepted.toSet();val ignored=decision.ignored.toSet()
        require(accepted.size==decision.accepted.size && ignored.size==decision.ignored.size)
        require(accepted.intersect(ignored).isEmpty() && accepted+ignored==ids)
        offer.suggestions.filter { it.id in accepted }.forEach { s ->
            require(s.requires.all { it !in ids || it in accepted }) { "Dependency was not accepted" }
        }
        session.accepted=accepted;record(player,"decision",decision)
        if(offer.suggestions.any { it.level==Level.REQUIRED && it.id !in accepted })player.kick(Component.text(session.ui.requiredDisconnect))
    }
    override fun onCommand(sender: CommandSender,command: Command,label: String,args: Array<out String>): Boolean {
        if(!sender.hasPermission("suggestor.admin"))return true
        when(args.firstOrNull()) {
            "reload" -> try {
                val next=Json.read(dataFolder.toPath().resolve("config.json"),Config::class.java).also(Config::validate)
                policy=next;sessions.clear();waiting.clear();server.onlinePlayers.forEach(::begin)
                sender.sendMessage("Suggestor 已重载：${policy.suggestions.size} 条建议，${policy.assignments.size} 个投放。")
            } catch(e: Exception) { sender.sendMessage("配置无效，继续使用原配置：${e.message}") }
            "send" -> {
                val player=args.getOrNull(1)?.let(server::getPlayerExact)
                if(player==null)sender.sendMessage("用法：/suggestor send <在线玩家>")
                else { begin(player);sender.sendMessage("已按当前投放规则重新检查 ${player.name}。") }
            }
            else -> sender.sendMessage("Suggestor: ${policy.suggestions.size} suggestions, ${policy.assignments.size} assignments, ${sessions.size} sessions, ${waiting.size} pending.")
        }
        return true
    }
    private fun blocked(player: Player)=player.uniqueId in waiting
    @EventHandler(ignoreCancelled=true) fun move(e: PlayerMoveEvent) {
        if(blocked(e.player) && (e.from.x!=e.to.x || e.from.y!=e.to.y || e.from.z!=e.to.z))e.to=e.from
    }
    @EventHandler(ignoreCancelled=true) fun interact(e: PlayerInteractEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun interactEntity(e: PlayerInteractEntityEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun breakBlock(e: BlockBreakEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun placeBlock(e: BlockPlaceEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun drop(e: PlayerDropItemEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun pickup(e: EntityPickupItemEvent) { if((e.entity as? Player)?.let(::blocked)==true)e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun inventoryClick(e: InventoryClickEvent) { if((e.whoClicked as? Player)?.let(::blocked)==true)e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun inventoryDrag(e: InventoryDragEvent) { if((e.whoClicked as? Player)?.let(::blocked)==true)e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun command(e: PlayerCommandPreprocessEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun chat(e: AsyncChatEvent) { if(blocked(e.player))e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun damage(e: EntityDamageEvent) {
        if((e.entity as? Player)?.let(::blocked)==true)e.isCancelled=true
        if(e is EntityDamageByEntityEvent) {
            val source=if(e.damager is Projectile)(e.damager as Projectile).shooter else e.damager
            if((source as? Player)?.let(::blocked)==true)e.isCancelled=true
        }
    }
}
