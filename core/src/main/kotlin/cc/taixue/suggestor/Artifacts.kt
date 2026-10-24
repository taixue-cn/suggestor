package cc.taixue.suggestor

import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.*
import java.util.zip.ZipFile

object Artifacts {
    fun download(suggestion: Suggestion, destination: Path) {
        suggestion.validate()
        var uri=URI(suggestion.url)
        var redirects=0
        while(true) {
            require(uri.scheme in setOf("http","https")) { "Unsupported download URL" }
            val connection=uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout=10000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("User-Agent","Suggestor/0.1.0")
            try {
                val status=connection.responseCode
                if(status in listOf(301,302,303,307,308)) {
                    require(redirects++<5) { "Too many redirects" }
                    uri=uri.resolve(requireNotNull(connection.getHeaderField("Location")));continue
                }
                require(status==200) { "Download returned HTTP $status" }
                require(connection.contentLengthLong<0 || connection.contentLengthLong==suggestion.size) { "File size mismatch" }
                var written=0L
                connection.inputStream.use { input -> Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        val n=input.read(buffer);if(n<0)break
                        written+=n;require(written<=suggestion.size) { "Download exceeds declared size" };output.write(buffer,0,n)
                    }
                } }
                verify(destination,suggestion);return
            } finally { connection.disconnect() }
        }
    }
    fun verify(path: Path,s: Suggestion) {
        require(Files.size(path)==s.size && equalHash(sha256(path),s.sha256)) { "Downloaded file hash/size mismatch: ${s.name}" }
        ZipFile(path.toFile()).use { zip ->
            when(s.kind) {
                Kind.MOD -> {
                    val entry=requireNotNull(zip.getEntry("fabric.mod.json")) { "Expected a Fabric mod" }
                    require(entry.size in 1..1024*1024) { "Invalid mod metadata size" }
                    val metadata=zip.getInputStream(entry).reader().use { Json.gson.fromJson(it,JsonObject::class.java) }
                    require(metadata.get("id").asString==s.targetId) { "Downloaded mod has another id" }
                    if(s.targetVersion.isNotEmpty()) require(metadata.get("version").asString==s.targetVersion) { "Mod version mismatch" }
                    require(metadata.get("environment")?.asString!="server") { "This mod is server-only" }
                }
                Kind.RESOURCE_PACK -> require(zip.getEntry("pack.mcmeta")!=null) { "Missing pack.mcmeta" }
                Kind.SHADER_PACK -> require(zip.entries().asSequence().any { it.name.startsWith("shaders/") }) { "Missing shaders directory" }
            }
        }
    }
}
