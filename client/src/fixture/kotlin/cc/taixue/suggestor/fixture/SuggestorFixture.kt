package cc.taixue.suggestor.fixture

import com.google.gson.Gson
import me.shedaniel.clothconfig2.gui.ClothConfigScreen
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.input.KeyEvent
import java.nio.file.*

/** Drives public Cloth/Minecraft widgets; excluded from the distributed production MOD. */
class SuggestorFixture : ClientModInitializer {
    private val mode=System.getProperty("suggestor.fixture.mode")
    private val report=Path.of(System.getProperty("suggestor.fixture.report"))
    private var ticks=0
    private var cooldown=0
    private val seen=mutableSetOf<String>()
    private var completed=false
    private var offerAt=0
    private var joinedTicks=0
    private var startupAt=0
    private var startupAcknowledged=false
    override fun onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register { message,_ ->
            if(message.string=="Fixture Missing Message")seen.add("chat:Fixture Missing Message")
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if(completed)return@register
            ticks++
                if(mc.player!=null)joinedTicks++ else joinedTicks=0
            try {
                check(ticks<3600) { "Fixture timed out; screens=$seen" }
                if(cooldown>0) { cooldown--;return@register }
                val screen=mc.gui.screen()
                if(startupAt>0 && !startupAcknowledged && screen is ClothConfigScreen)
                    error("A new suggestion hid unacknowledged startup results")
                if(screen is net.minecraft.client.gui.screens.DisconnectedScreen && mode !in setOf("nomod","refuse","decline")) {
                    check(mode in setOf("install","update") && screen.narrationMessage.string.contains("请退出游戏")) { screen.narrationMessage.string }
                }
                if(mode=="empty" && joinedTicks>80) {
                    check(seen.isEmpty()) { "Empty config displayed Suggestor UI" };finish(mc);return@register
                }
                if(mode=="silent" && joinedTicks>220) {
                    check(mc.connection!=null);check(seen.isEmpty());finish(mc);return@register
                }
                if(mode=="reminder" && mc.player!=null) {
                    val values=mc.gui.hud.javaClass.declaredFields.filter { net.minecraft.network.chat.Component::class.java.isAssignableFrom(it.type) }
                        .mapNotNull { it.isAccessible=true;(it.get(mc.gui.hud) as? net.minecraft.network.chat.Component)?.string }
                    if("Fixture Missing Title" in values) {
                        seen.add("title:Fixture Missing Title")
                    }
                    if("title:Fixture Missing Title" in seen && "chat:Fixture Missing Message" in seen) {
                        Screenshot.grab(mc.gameDirectory,"suggestor-reminder.png",mc.gameRenderer.mainRenderTarget(),1) {}
                        check(mc.connection!=null);finish(mc);return@register
                    }
                }
                if(screen is ClothConfigScreen) {
                    seen.add("offer:${screen.title.string}")
                    check(screen.title.string=="ARCH Fixture 建议") { "Server UI title was not used" }
                    if(offerAt==0) { offerAt=ticks;return@register }
                    if(ticks-offerAt<30)return@register
                    Screenshot.grab(mc.gameDirectory,"suggestor-$mode.png",mc.gameRenderer.mainRenderTarget(),1) {}
                    if(mode=="ignore" || mode=="refuse") {
                        if(mode=="ignore")screen.children().filterIsInstance<Button>().first { it.message.string=="忽略测试建议" }.onPress(KeyEvent(40,0,0))
                        else screen.keyPressed(KeyEvent(41,0,0)) // SDL Escape in 26.3.
                        cooldown=20
                    } else {
                        val entries=screen.categorizedEntries.values.flatten().filterIsInstance<BooleanListEntry>()
                        check(entries.size==3) { "Expected three artifact operations, got ${entries.size}" }
                        entries.filter { !it.value }.forEach { entry ->
                            entry.children().filterIsInstance<Button>().first().onPress(KeyEvent(257,0,0))
                            check(entry.value) { "Boolean widget did not toggle" }
                        }
                        val accept=screen.children().filterIsInstance<Button>().first { it.message.string=="采纳测试建议" }
                        accept.onPress(KeyEvent(257,0,0));cooldown=20
                    }
                } else if(screen is ConfirmScreen) {
                    val title=screen.title.string
                    when {
                        title=="Fixture Inventory" -> { seen.add("consent");click(screen,if(mode=="decline")"不允许" else "允许并记住此服务器") }
                        title=="采纳测试建议" -> { seen.add("confirmed");click(screen,"采纳测试建议") }
                        title=="组件变更已准备好" -> {
                            seen.add("ready");finish(mc,false);click(screen,"退出游戏并应用")
                        }
                        title=="上次的组件调整结果" -> {
                            seen.add("startup-result")
                            if(startupAt==0)startupAt=ticks
                            if(ticks-startupAt>60) { startupAcknowledged=true;click(screen,"知道了") }
                        }
                        title=="准备组件变更" || title=="检查组件" -> Unit
                        title=="组件变更未完成" || title=="无法读取服务器建议" || title=="服务器建议暂不可用" -> error(screen.narrationMessage.string)
                    }
                    cooldown=10
                }
                if(mode=="verify" && mc.player!=null && screen==null && ticks>150) {
                    check(FabricLoader.getInstance().getModContainer("suggested_fixture").orElseThrow().metadata.version.friendlyString=="2.0")
                    check(!Files.exists(FabricLoader.getInstance().gameDir.resolve("shaderpacks/fixture.zip")))
                    check("startup-result" in seen);finish(mc)
                }
                if(mode=="ignore" && seen.any { it.startsWith("offer:") } && screen==null && ticks-offerAt>50)finish(mc)
                if(mode=="refuse" && seen.any { it.startsWith("offer:") } && mc.connection==null && ticks-offerAt>50)finish(mc)
                if(mode=="nomod" && mc.connection==null && screen is net.minecraft.client.gui.screens.DisconnectedScreen) {
                    check(screen.narrationMessage.string.contains("Fixture Missing Message"));finish(mc)
                }
                if(mode=="decline" && mc.connection==null && screen is net.minecraft.client.gui.screens.DisconnectedScreen) {
                    check(screen.narrationMessage.string.contains("必须处理"));finish(mc)
                }
            } catch(e: Throwable) {
                completed=true;write(mapOf("status" to "FAIL","mode" to mode,"error" to (e.message?:e.toString()),"screens" to seen));e.printStackTrace();mc.stop()
            }
        }
    }
    private fun write(value: Any) { Files.writeString(report,Gson().toJson(value)) }
    private fun click(screen: ConfirmScreen,label: String) {
        screen.children().filterIsInstance<Button>().first { it.message.string==label }.onPress(KeyEvent(257,0,0))
    }
    private fun finish(mc: Minecraft,stop: Boolean=true) {
        completed=true
        write(mapOf("status" to "PASS","mode" to mode,"ticks" to ticks,"screens" to seen))
        if(stop)mc.stop()
    }
}
