package cc.taixue.suggestor.client

import cc.taixue.suggestor.*
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.ModDependency
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Conservative dependency check before arming a transaction; never discovers or installs extra downloads. */
object Dependencies {
    fun validate(planRoot: Path,inventory: Inventory,selected: List<Suggestion>) {
        val plan=Json.read(planRoot.resolve("plan.json"),Plan::class.java)
        val changed=selected.filter { it.kind==Kind.MOD }.map { it.targetId }.toMutableSet()
        val loader=FabricLoader.getInstance()
        var expanded: Boolean
        do {
            expanded=false
            loader.allMods.forEach { mod ->
                if(mod.containingMod.map { it.metadata.id in changed }.orElse(false) && changed.add(mod.metadata.id))expanded=true
            }
        } while(expanded)
        val versions=inventory.entries.filter { it.kind==Kind.MOD && it.id !in changed }.associate { it.id to it.version }.toMutableMap()
        loader.allMods.filter { it.metadata.id !in changed }.forEach { mod ->
            mod.metadata.provides.forEach { versions.putIfAbsent(it,mod.metadata.version.friendlyString) }
        }
        val metadata=mutableListOf<JsonObject>()
        fun readLimited(input: InputStream,limit: Int): ByteArray {
            val bytes=input.readNBytes(limit+1);require(bytes.size<=limit) { "内嵌 MOD/元数据过大，需人工检查。" };return bytes
        }
        fun parseNested(bytes: ByteArray,depth: Int) {
            require(depth<=8 && metadata.size<1024) { "Too many nested mods" }
            val objectValue=ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var result: JsonObject?=null
                while(true) {
                    val entry=zip.nextEntry?:break
                    if(entry.name=="fabric.mod.json") { result=Json.gson.fromJson(readLimited(zip,1024*1024).toString(Charsets.UTF_8),JsonObject::class.java);break }
                };requireNotNull(result) { "Missing nested fabric.mod.json" }
            }
            if(objectValue.get("environment")?.asString=="server")return
            metadata.add(objectValue)
            val wanted=objectValue.getAsJsonArray("jars")?.map { it.asJsonObject.get("file").asString }?.toSet().orEmpty()
            val found=mutableSetOf<String>()
            if(wanted.isNotEmpty())ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while(true) {
                    val entry=zip.nextEntry?:break
                    if(entry.name in wanted) { found.add(entry.name);parseNested(readLimited(zip,64*1024*1024),depth+1) }
                }
            }
            require(found==wanted) { "Missing declared nested jar" }
        }
        plan.changes.filter { it.kind==Kind.MOD && it.action!=Action.REMOVE }.forEach { c ->
            ZipFile(planRoot.resolve("staged").resolve(c.stagedFile).toFile()).use { zip ->
                val root=zip.getInputStream(zip.getEntry("fabric.mod.json")).use { Json.gson.fromJson(readLimited(it,1024*1024).toString(Charsets.UTF_8),JsonObject::class.java) }
                metadata.add(root)
                root.getAsJsonArray("jars")?.forEach { nested ->
                    val entry=requireNotNull(zip.getEntry(nested.asJsonObject.get("file").asString)) { "Missing nested jar" }
                    zip.getInputStream(entry).use { parseNested(readLimited(it,64*1024*1024),1) }
                }
            }
        }
        metadata.forEach { value ->
            val id=value.get("id").asString;val version=value.get("version").asString
            val current=versions[id]
            if(current==null || Version.parse(version)>Version.parse(current))versions[id]=version
            value.getAsJsonArray("provides")?.forEach { versions.putIfAbsent(it.asString,version) }
        }
        fun satisfies(predicate: com.google.gson.JsonElement,id: String): Boolean {
            val version=versions[id]?:return false
            return (if(predicate.isJsonArray)predicate.asJsonArray.toList() else listOf(predicate)).any {
                VersionPredicate.parse(it.asString).test(Version.parse(version))
            }
        }
        loader.allMods.filter { it.metadata.id !in changed }.forEach { mod ->
            mod.metadata.dependencies.forEach { dep ->
                if(dep.kind==ModDependency.Kind.DEPENDS)
                    require(versions[dep.modId]?.let { dep.matches(Version.parse(it)) }==true) { "${mod.metadata.name} 仍需要 ${dep.modId}，无法执行此变更。" }
                if(dep.kind==ModDependency.Kind.BREAKS)
                    require(versions[dep.modId]?.let { dep.matches(Version.parse(it)) }!=true) { "${mod.metadata.name} 与 ${dep.modId} 冲突。" }
            }
        }
        metadata.forEach { value ->
            val id=value.get("id").asString
            value.getAsJsonObject("depends")?.entrySet()?.forEach { (dep,predicate) ->
                require(satisfies(predicate,dep)) { "$id 需要兼容的 $dep；请一并选择其依赖。" }
            }
            value.getAsJsonObject("breaks")?.entrySet()?.forEach { (dep,predicate) ->
                require(!satisfies(predicate,dep)) { "$id 与 $dep 冲突。" }
            }
        }
    }
}
