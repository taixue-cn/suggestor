package cc.taixue.suggestor

import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

data class FileChange(val suggestionId: String, val kind: Kind, val action: Action,
                      val oldFile: String, val oldHash: String, val newFile: String,
                      val newHash: String, val stagedFile: String,
                      val targetId: String = "", val targetVersion: String = "")
data class Plan(val id: String, val serverId: String, val batchId: String, val gameDirectory: String,
                val parentPid: Long, val parentStarted: String, val changes: List<FileChange>)
data class ApplyResult(val transactionId: String, val success: Boolean, val recovered: Boolean, val detail: String)
data class Journal(val complete: Boolean = false, val started: List<Int> = emptyList())
class PreparationException(val root: Path, cause: Exception) : RuntimeException(cause.message,cause)

object Transactions {
    fun state(game: Path): Path = game.resolve(".suggestor")
    fun directories(game: Path): List<Path> {
        val root=state(game).resolve("transactions")
        if(!Files.exists(root))return emptyList()
        return Files.list(root).use { files -> files.filter { Files.isRegularFile(it.resolve("plan.json"),LinkOption.NOFOLLOW_LINKS) }.sorted().toList() }
    }
    fun prepare(game: Path,serverId: String,offer: Offer,selected: List<Suggestion>,inventory: Inventory): Path {
        require(selected.isNotEmpty())
        require(directories(game).none { Files.exists(it.resolve("ready.json")) && !Files.exists(it.resolve("result.json")) }) {
            "已有等待退出后应用的变更，请先退出并重新启动游戏。"
        }
        val ids=selected.map { it.id }.toSet()
        selected.forEach { s ->
            s.validate();require(applies(s,inventory)) { "Local state changed: ${s.name}" }
            s.requires.forEach { dependency ->
                val other=offer.suggestions.find { it.id==dependency }
                require(other==null || !applies(other,inventory) || dependency in ids) { "Missing selected dependency: $dependency" }
            }
        }
        val root=state(game).resolve("transactions").resolve(UUID.randomUUID().toString())
        Files.createDirectories(root.resolve("staged"))
        val process=ProcessHandle.current()
        val initial=Plan(root.fileName.toString(),serverId,offer.batchId,game.toRealPath().toString(),process.pid(),
            process.info().startInstant().orElseThrow().toString(),emptyList())
        Json.write(root.resolve("plan.json"),initial)
        try {
            val changes=selected.mapIndexed { index,s ->
                val matches=inventory.entries.filter { it.kind==s.kind && it.id==s.targetId }
                val old=if(s.action==Action.ADD)null else matches.singleOrNull()
                    ?: throw IllegalArgumentException("Cannot identify one local file: ${s.name}")
                require(old==null || old.parentId.isEmpty()) { "内嵌 MOD 需要通过包含它的主 MOD 更新：${s.name}" }
                if(old!=null) {
                    leaf(old.fileName);hash(old.sha256)
                    require(equalHash(sha256(safeFile(game,s.kind,old.fileName)),old.sha256)) { "Original file changed" }
                }
                val newFile=if(s.action==Action.REMOVE)"" else s.fileName
                if(newFile.isNotEmpty()) {
                    val target=safeFile(game,s.kind,newFile)
                    require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS) || old?.fileName==newFile) { "Target file already exists: $newFile" }
                }
                FileChange(s.id,s.kind,s.action,old?.fileName?:"",old?.sha256?:"",newFile,s.sha256,"$index${s.kind.extension}",s.targetId,s.targetVersion)
            }
            val writes=changes.flatMap { c -> listOf(c.oldFile,c.newFile).filter { it.isNotEmpty() }.distinct().map { c.kind to it.lowercase() } }
            require(writes.distinct().size==writes.size) { "Selected operations modify the same file" }
            Json.write(root.resolve("plan.json"),initial.copy(changes=changes))
            selected.forEachIndexed { index,s ->
                if(s.action!=Action.REMOVE) Artifacts.download(s,root.resolve("staged/$index${s.kind.extension}"))
            }
            return root
        } catch(e: Exception) {
            Json.write(root.resolve("preparation-error.json"),mapOf("error" to (e.message?:e.javaClass.simpleName)))
            Json.write(root.resolve("result.json"),ApplyResult(initial.id,false,true,e.message?:"准备失败，原文件未变更。"))
            throw PreparationException(root,e)
        }
    }
    fun ready(root: Path) = Json.write(root.resolve("ready.json"),mapOf("ready" to true))
    fun safeFile(game: Path,kind: Kind,name: String): Path {
        leaf(name)
        val directory=game.resolve(kind.directory)
        require(!Files.isSymbolicLink(directory)) { "Symbolic-link target directory is not supported" }
        Files.createDirectories(directory)
        val target=directory.resolve(name)
        require(!Files.isSymbolicLink(target)) { "Symbolic-link artifact is not supported" }
        require(directory.toRealPath().startsWith(game.toRealPath())) { "Artifact outside this instance" }
        return target
    }
    fun apply(root: Path): ApplyResult {
        val plan=Json.read(root.resolve("plan.json"),Plan::class.java)
        UUID.fromString(plan.id)
        val game=Path.of(plan.gameDirectory).toRealPath()
        require(root.toRealPath()==state(game).resolve("transactions").resolve(plan.id).toRealPath())
        require(plan.changes.isNotEmpty() && plan.changes.size<=100)
        if(Files.exists(root.resolve("result.json")))return Json.read(root.resolve("result.json"),ApplyResult::class.java)
        val journalPath=root.resolve("journal.json")
        val previous=if(Files.exists(journalPath)) Json.read(journalPath,Journal::class.java) else Journal()
        if(previous.complete) return ApplyResult(plan.id,true,false,"变更已应用。").also { Json.write(root.resolve("result.json"),it) }
        val started=previous.started.toMutableList()
        try {
            if(started.isNotEmpty()) throw IllegalStateException("上次应用被中断，恢复原文件。")
            plan.changes.forEach { c ->
                leaf(c.stagedFile)
                if(c.oldFile.isNotEmpty()) require(equalHash(sha256(safeFile(game,c.kind,c.oldFile)),c.oldHash)) { "Original file changed: ${c.oldFile}" }
                if(c.newFile.isNotEmpty()) {
                    val target=safeFile(game,c.kind,c.newFile)
                    require(!Files.exists(target) || c.oldFile==c.newFile) { "Target already exists" }
                    require(equalHash(sha256(root.resolve("staged").resolve(c.stagedFile)),c.newHash)) { "Staged file changed" }
                }
            }
            Files.createDirectories(root.resolve("backup"))
            plan.changes.forEachIndexed { index,c ->
                started.add(index);Json.write(journalPath,Journal(false,started.toList()))
                if(c.oldFile.isNotEmpty()) Json.move(safeFile(game,c.kind,c.oldFile),root.resolve("backup/$index${c.kind.extension}"))
                if(c.newFile.isNotEmpty()) Json.move(root.resolve("staged").resolve(c.stagedFile),safeFile(game,c.kind,c.newFile))
            }
            Json.write(journalPath,Journal(true,started.toList()))
            return ApplyResult(plan.id,true,false,"文件变更已应用；资源包和光影包可在对应设置中启用。")
        } catch(e: Exception) {
            try {
                started.asReversed().forEach { index ->
                    val c=plan.changes[index]
                    val backup=root.resolve("backup/$index${c.kind.extension}")
                    val staged=root.resolve("staged").resolve(c.stagedFile)
                    // A missing staged file proves the install rename occurred, including a crash before journal update.
                    if(c.newFile.isNotEmpty() && !Files.exists(staged)) {
                        val target=safeFile(game,c.kind,c.newFile)
                        if(Files.exists(target)) {
                            require(equalHash(sha256(target),c.newHash)) { "New file was modified; manual recovery needed" }
                            Json.move(target,staged)
                        }
                    }
                    if(Files.exists(backup)) {
                        val original=safeFile(game,c.kind,c.oldFile)
                        require(!Files.exists(original)) { "Original path occupied; backup retained" }
                        Json.move(backup,original)
                    }
                }
                return ApplyResult(plan.id,false,true,e.message?:"操作失败，原文件已恢复。")
            } catch(rollback: Exception) {
                return ApplyResult(plan.id,false,false,"${e.message}; 恢复未完成：${rollback.message}。备份位于 $root")
            }
        }
    }
    fun persistApply(root: Path): ApplyResult = apply(root).also { Json.write(root.resolve("result.json"),it) }
    fun lock(game: Path): Pair<FileChannel,java.nio.channels.FileLock> {
        Files.createDirectories(state(game))
        val channel=FileChannel.open(state(game).resolve("instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
        try { return channel to requireNotNull(channel.tryLock()) { "另一个 Suggestor 实例正在处理文件，请稍后启动。" } }
        catch(e: Exception) { channel.close();throw e }
    }
}
