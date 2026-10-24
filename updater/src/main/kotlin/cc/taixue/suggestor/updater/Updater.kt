package cc.taixue.suggestor.updater

import cc.taixue.suggestor.*
import java.nio.file.*

object Updater {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==1)
        val root=Path.of(args[0]).toRealPath()
        val plan=Json.read(root.resolve("plan.json"),Plan::class.java)
        Json.write(root.resolve("helper.json"),mapOf("pid" to ProcessHandle.current().pid(),
            "started" to ProcessHandle.current().info().startInstant().orElseThrow().toString()))
        val parent=ProcessHandle.of(plan.parentPid).orElse(null)
        if(parent!=null && parent.info().startInstant().map { it.toString()==plan.parentStarted }.orElse(false)) parent.onExit().join()
        if(!Files.exists(root.resolve("ready.json")))return
        try {
            val (channel,lock)=Transactions.lock(Path.of(plan.gameDirectory))
            channel.use { lock.use { Transactions.persistApply(root) } }
        } catch(e: Exception) {
            Json.write(root.resolve("result.json"),ApplyResult(plan.id,false,false,e.message?:"无法应用文件变更"))
        }
    }
}
