package cc.taixue.suggestor.client

import cc.taixue.suggestor.*
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.ModOrigin
import java.nio.file.*

object InventoryScanner {
    fun scan(game: Path): Inventory {
        val loader=FabricLoader.getInstance()
        val hashes=mutableMapOf<Path,String>()
        val mods=loader.allMods.map { mod ->
            var container=mod
            while(container.containingMod.isPresent)container=container.containingMod.get()
            val origin=container.origin
            val path=if(origin.kind==ModOrigin.Kind.PATH) origin.paths.singleOrNull()?.takeIf {
                Files.isRegularFile(it) && it.toAbsolutePath().normalize().parent==game.resolve("mods").toAbsolutePath().normalize() && !Files.isSymbolicLink(it)
            } else null
            Installed(Kind.MOD,mod.metadata.id,mod.metadata.name,mod.metadata.version.friendlyString,
                path?.let { hashes.getOrPut(it) { sha256(it) } }?:"",path?.fileName?.toString()?:"",
                mod.containingMod.map { it.metadata.id }.orElse(""))
        }
        val packs=listOf(Kind.RESOURCE_PACK,Kind.SHADER_PACK).flatMap { kind ->
            val directory=game.resolve(kind.directory)
            if(!Files.isDirectory(directory) || Files.isSymbolicLink(directory))emptyList() else
                Files.list(directory).use { files -> files.filter { Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".zip") }
                    .map { path ->
                        val name=path.fileName.toString()
                        Installed(kind,name,name,"",sha256(path),name)
                    }.toList() }
        }
        return Inventory(loader.getModContainer("minecraft").orElseThrow().metadata.version.friendlyString,
            loader.getModContainer("fabricloader").orElseThrow().metadata.version.friendlyString,mods+packs).also(Inventory::validate)
    }
}
