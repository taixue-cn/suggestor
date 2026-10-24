package cc.taixue.suggestor

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.*
import java.util.UUID
import java.util.zip.*

class CoreTest {
    @TempDir lateinit var game: Path
    private val digest="a".repeat(64)
    private fun suggestion(kind: Kind=Kind.MOD,action: Action=Action.ADD)=Suggestion(
        id="example",kind=kind,action=action,targetId=if(kind==Kind.MOD)"example" else "example.zip",name="Example",reason="Test reason",
        targetVersion="2.0",fromSha256=if(action==Action.REMOVE)digest else "",url="http://localhost/example",
        fileName="example${kind.extension}",sha256=digest,size=100)
    @Test fun `empty config is inert and audience selection uses union`() {
        val config=Config();config.validate()
        assertTrue(config.select(config.audience("Alice") { true }).isEmpty())
        assertEquals(MissingClientAction.SILENT,config.missingClient.action)
        val audience=Audience(players=listOf("Alice"),permissions=listOf("example.builder"))
        assertTrue(audience.matches("alice") { false });assertTrue(audience.matches("Bob") { it=="example.builder" })
        assertFalse(audience.matches("Bob") { false });assertTrue(Audience(all=true).matches("Anyone") { false })
    }
    @Test fun `update is satisfied only by current inventory not consent`() {
        val s=suggestion(action=Action.UPDATE).copy(level=Level.REQUIRED)
        val old=Installed(Kind.MOD,"example","Example","1.0","b".repeat(64),"example.jar")
        val inventory=Inventory("26.3","0.20.1",listOf(old))
        assertTrue(applies(s,inventory))
        assertFalse(applies(s,inventory.copy(entries=listOf(old.copy(sha256=digest,version="2.0")))))
        assertTrue(applies(suggestion(),inventory.copy(entries=emptyList())))
        assertFalse(applies(suggestion(),inventory))
    }
    @Test fun `conflicting and cyclic configuration cannot execute`() {
        val s=suggestion()
        assertThrows(IllegalArgumentException::class.java) { applicable(listOf(s,s.copy(id="other",sha256="b".repeat(64))),Inventory("26.3","",emptyList())) }
        assertEquals(Level.REQUIRED,applicable(listOf(s,s.copy(id="other",level=Level.REQUIRED)),Inventory("26.3","",emptyList())).single().level)
        assertThrows(IllegalArgumentException::class.java) { Config(suggestions=listOf(s.copy(requires=listOf("example")))).validate() }
        assertThrows(IllegalArgumentException::class.java) { s.copy(action=Action.REMOVE,fromSha256="").validate() }
        listOf("../outside.jar","C:\\outside.jar","CON.jar","test.jar.","a/b.jar").forEach {
            assertThrows(IllegalArgumentException::class.java) { leaf(it) }
        }
        leaf("[26.3] Example & Tools.jar")
    }
    @Test fun `wire round trips large inventories with bounded chunks`() {
        val message=Wire.message("session","INVENTORY",mapOf("text" to "模组".repeat(20000)))
        val frames=Wire.encode(message);assertTrue(frames.size>1);assertTrue(frames.all { it.size<32766 })
        val assembler=Wire.Assembler();var result: Wire.Message?=null
        frames.reversed().forEach { result=assembler.accept(it)?:result }
        assertEquals(message,result)
        val malformed=frames.first().clone();ByteBuffer.wrap(malformed).putInt(28,Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { Wire.Assembler().accept(malformed) }
        val duplicate=Wire.Assembler();duplicate.accept(frames.first())
        assertThrows(IllegalArgumentException::class.java) { duplicate.accept(frames.first()) }
    }
    private fun zip(kind: Kind,path: Path): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            val name=when(kind) { Kind.MOD->"fabric.mod.json";Kind.RESOURCE_PACK->"pack.mcmeta";Kind.SHADER_PACK->"shaders/example.fsh" }
            output.putNextEntry(ZipEntry(name))
            output.write(if(kind==Kind.MOD)"""{"id":"example","version":"2.0","environment":"client"}""".toByteArray() else "{}".toByteArray())
            output.closeEntry()
        };return path
    }
    @Test fun `HTTP downloads all artifact kinds and rejects hash mismatches`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val artifacts=Kind.entries.associateWith { zip(it,game.resolve("input/${it.name}${it.extension}")) }
        server.createContext("/") { exchange ->
            val bytes=Files.readAllBytes(artifacts.getValue(Kind.valueOf(exchange.requestURI.path.substring(1))))
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        };server.start()
        try {
            Kind.entries.forEach { kind ->
                val source=artifacts.getValue(kind)
                val s=suggestion(kind).copy(url="http://127.0.0.1:${server.address.port}/${kind.name}",sha256=sha256(source),size=Files.size(source))
                val output=game.resolve("download-${kind.name}${kind.extension}")
                Artifacts.download(s,output);assertEquals(sha256(source),sha256(output))
                assertThrows(IllegalArgumentException::class.java) { Artifacts.download(s.copy(sha256=digest),game.resolve("bad-${kind.name}")) }
            }
        } finally { server.stop(0) }
    }
    private fun plan(changes: List<FileChange>): Path {
        val id=UUID.randomUUID().toString();val root=Transactions.state(game).resolve("transactions/$id")
        Files.createDirectories(root.resolve("staged"))
        Json.write(root.resolve("plan.json"),Plan(id,UUID.randomUUID().toString(),"batch",game.toRealPath().toString(),0,"",changes))
        Transactions.ready(root);return root
    }
    @Test fun `add update remove for every artifact kind retain backups and are idempotent`() {
        Kind.entries.forEach { kind ->
            val old=Transactions.safeFile(game,kind,"old${kind.extension}");Files.writeString(old,"old")
            val remove=Transactions.safeFile(game,kind,"remove${kind.extension}");Files.writeString(remove,"remove")
            val new=zip(kind,game.resolve("input/new-${kind.name}${kind.extension}"));val newHash=sha256(new)
            val root=plan(listOf(
                FileChange("add",kind,Action.ADD,"","","added${kind.extension}",newHash,"0${kind.extension}"),
                FileChange("update",kind,Action.UPDATE,old.fileName.toString(),sha256(old),old.fileName.toString(),newHash,"1${kind.extension}"),
                FileChange("remove",kind,Action.REMOVE,remove.fileName.toString(),sha256(remove),"","","2${kind.extension}")))
            Files.copy(new,root.resolve("staged/0${kind.extension}"));Files.copy(new,root.resolve("staged/1${kind.extension}"))
            val result=Transactions.persistApply(root);assertTrue(result.success,result.detail)
            assertEquals(newHash,sha256(old));assertFalse(Files.exists(remove))
            assertEquals("old",Files.readString(root.resolve("backup/1${kind.extension}")))
            assertEquals(result,Transactions.persistApply(root))
        }
    }
    @Test fun `interrupted update restores original and changed originals are not deleted`() {
        val old=Transactions.safeFile(game,Kind.MOD,"example.jar");Files.writeString(old,"original")
        val oldHash=sha256(old);val staged=game.resolve("new.jar");Files.writeString(staged,"replacement");val newHash=sha256(staged)
        val change=FileChange("update",Kind.MOD,Action.UPDATE,"example.jar",oldHash,"example.jar",newHash,"0.jar")
        val root=plan(listOf(change));Files.createDirectories(root.resolve("backup"))
        Files.move(old,root.resolve("backup/0.jar"));Files.move(staged,old)
        Json.write(root.resolve("journal.json"),Journal(false,listOf(0)))
        val result=Transactions.persistApply(root)
        assertFalse(result.success);assertTrue(result.recovered);assertEquals("original",Files.readString(old))
        val next=plan(listOf(change));Files.writeString(next.resolve("staged/0.jar"),"replacement");Files.writeString(old,"manually changed")
        assertFalse(Transactions.persistApply(next).success);assertEquals("manually changed",Files.readString(old))
    }
    @Test fun `symlinks cannot escape the game instance`() {
        val outside=Files.createTempDirectory("suggestor-outside")
        try {
            Files.createSymbolicLink(game.resolve("mods"),outside)
            assertThrows(IllegalArgumentException::class.java) { Transactions.safeFile(game,Kind.MOD,"example.jar") }
        } finally { Files.deleteIfExists(game.resolve("mods"));Files.delete(outside) }
    }
    @Test fun `failed preparation is durable without arming any file changes`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(404,-1);exchange.close() };server.start()
        try {
            val s=suggestion().copy(url="http://127.0.0.1:${server.address.port}/missing.jar")
            val error=assertThrows(PreparationException::class.java) {
                Transactions.prepare(game,UUID.randomUUID().toString(),Offer("batch",Ui(),listOf(s)),listOf(s),Inventory("26.3","0.20.1",emptyList()))
            }
            assertFalse(Files.exists(error.root.resolve("ready.json")))
            assertFalse(Json.read(error.root.resolve("result.json"),ApplyResult::class.java).success)
            assertFalse(Files.exists(game.resolve("mods/example.jar")))
        } finally { server.stop(0) }
    }
}
