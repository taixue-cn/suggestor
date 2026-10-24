package cc.taixue.suggestor

import com.google.gson.GsonBuilder
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest

object Json {
    val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    fun <T> read(path: Path, type: Class<T>): T {
        require(Files.size(path) <= 4*1024*1024) { "JSON file too large" }
        return requireNotNull(gson.fromJson(Files.readString(path),type))
    }
    fun write(path: Path, value: Any) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val temp = Files.createTempFile(path.toAbsolutePath().parent,".suggestor-",".tmp")
        try {
            Files.writeString(temp,gson.toJson(value)+"\n")
            FileChannel.open(temp,StandardOpenOption.WRITE).use { it.force(true) }
            move(temp,path)
        } finally { Files.deleteIfExists(temp) }
    }
    fun move(from: Path, to: Path) {
        try { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from,to,StandardCopyOption.REPLACE_EXISTING) }
    }
}
fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
