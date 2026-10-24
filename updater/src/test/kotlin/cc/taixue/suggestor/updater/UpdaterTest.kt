package cc.taixue.suggestor.updater

import cc.taixue.suggestor.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

class UpdaterTest {
    @TempDir lateinit var game: Path
    @Test fun `real child waits for exact parent exit and leaves durable result`() {
        val source=game.resolve("ParentFixture.java")
        Files.writeString(source,"public class ParentFixture { public static void main(String[] args) throws Exception { Thread.sleep(120000); } }")
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",game.toString(),source.toString()))
        val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString()
        val parent=ProcessBuilder(java,"-cp",game.toString(),"ParentFixture").start()
        var child: Process?=null
        try {
            val id=UUID.randomUUID().toString();val root=Transactions.state(game).resolve("transactions/$id")
            Files.createDirectories(root.resolve("staged"))
            val staged=root.resolve("staged/0.zip");Files.writeString(staged,"prepared file")
            val plan=Plan(id,UUID.randomUUID().toString(),"batch",game.toRealPath().toString(),parent.pid(),
                parent.info().startInstant().orElseThrow().toString(),listOf(
                    FileChange("shader",Kind.SHADER_PACK,Action.ADD,"","","example.zip",sha256(staged),"0.zip")))
            Json.write(root.resolve("plan.json"),plan);Transactions.ready(root)
            child=ProcessBuilder(java,"-cp",System.getProperty("suggestor.updaterJar"),"cc.taixue.suggestor.updater.Updater",root.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("helper.log").toFile()).start()
            val deadline=System.nanoTime()+10_000_000_000L
            while(!Files.exists(root.resolve("helper.json")) && child.isAlive && System.nanoTime()<deadline)Thread.sleep(25)
            assertTrue(Files.exists(root.resolve("helper.json")));assertTrue(child.isAlive)
            assertFalse(Files.exists(game.resolve("shaderpacks/example.zip")))
            parent.destroy();assertTrue(parent.waitFor(10,TimeUnit.SECONDS))
            assertTrue(child.waitFor(15,TimeUnit.SECONDS));assertEquals(0,child.exitValue())
            val result=Json.read(root.resolve("result.json"),ApplyResult::class.java)
            assertTrue(result.success,result.detail);assertEquals("prepared file",Files.readString(game.resolve("shaderpacks/example.zip")))
        } finally { parent.destroyForcibly();child?.takeIf { it.isAlive }?.destroyForcibly() }
    }
}
