package cc.taixue.suggestor

import java.net.URI
import java.util.UUID

enum class Kind(val directory: String, val extension: String) {
    MOD("mods", ".jar"), RESOURCE_PACK("resourcepacks", ".zip"), SHADER_PACK("shaderpacks", ".zip")
}
enum class Action { ADD, UPDATE, REMOVE }
enum class Level { SUGGESTED, RECOMMENDED, REQUIRED }
enum class MissingClientAction { KICK, REMIND, SILENT }
data class MissingClientPolicy(
    val action: MissingClientAction = MissingClientAction.SILENT,
    val message: String = "未检测到兼容的 Suggestor 客户端 MOD，请安装后重新连接。",
    val title: String = "推荐安装 Suggestor",
    val subtitle: String = "安装后可接收服务器的组件建议。"
) {
    fun validate() {
        require(MissingClientAction.entries.contains(action)) { "Invalid missing-client action" }
        require(message.length in 1..8000 && title.length in 1..1000 && subtitle.length in 1..1000)
    }
}
data class Installed(val kind: Kind, val id: String, val name: String, val version: String,
                     val sha256: String, val fileName: String, val parentId: String = "")
data class Inventory(val minecraft: String, val loader: String, val entries: List<Installed>) {
    fun validate() {
        require(entries.size <= 4096 && minecraft.length < 100 && loader.length < 100)
        entries.forEach {
            require(it.id.length in 1..256 && it.name.length <= 256 && it.version.length <= 256)
            require(it.parentId.length <= 256)
            if (it.sha256.isNotEmpty()) hash(it.sha256)
            if (it.fileName.isNotEmpty()) leaf(it.fileName)
        }
    }
}
data class Suggestion(
    val id: String = "", val revision: Int = 1, val kind: Kind = Kind.MOD,
    val action: Action = Action.ADD, val level: Level = Level.SUGGESTED,
    val targetId: String = "", val name: String = "", val reason: String = "",
    val targetVersion: String = "", val fromSha256: String = "", val fromVersions: List<String> = emptyList(),
    val url: String = "", val fileName: String = "", val sha256: String = "", val size: Long = 0,
    val minecraft: List<String> = emptyList(), val requires: List<String> = emptyList()
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) && revision > 0) { "Invalid suggestion id/revision" }
        require(targetId.length in 1..256 && name.length in 1..200 && reason.length in 1..4000) { "Missing target/name/reason: $id" }
        if (fromSha256.isNotEmpty()) hash(fromSha256)
        if (action == Action.REMOVE) hash(fromSha256) else {
            hash(sha256); leaf(fileName)
            require(fileName.endsWith(kind.extension) && size in 1..512L*1024*1024) { "Invalid artifact: $id" }
            val uri = URI(url)
            require(uri.scheme in setOf("http", "https") && uri.host != null) { "Expected HTTP/HTTPS URL: $id" }
            if(kind == Kind.MOD) require(targetVersion.isNotBlank()) { "MOD targetVersion is required: $id" }
            if(kind != Kind.MOD && action == Action.ADD) require(targetId == fileName) { "Pack ADD targetId must equal fileName: $id" }
        }
        require(!(kind == Kind.MOD && targetId in setOf("suggestor", "minecraft", "java", "fabricloader"))) {
            "Core loader/Suggestor changes require manual installation"
        }
    }
}
data class Ui(
    val title: String = "服务器建议", val introduction: String = "请查看适用于你的建议，可逐项选择。",
    val accept: String = "采纳所选建议", val ignore: String = "忽略 / 返回",
    val consentTitle: String = "允许提供组件清单？",
    val consentText: String = "此服务器希望检查已安装 MOD、资源包和光影包的名称、版本及文件哈希。",
    val restart: String = "文件已准备好，退出游戏后应用，请稍候再从启动器打开游戏。",
    val requiredDisconnect: String = "此服务器有尚未满足的必需项，请处理并重启游戏后重新连接。"
) {
    fun validate() = listOf(title,introduction,accept,ignore,consentTitle,consentText,restart,requiredDisconnect).forEach {
        require(it.length in 1..8000) { "Invalid UI text" }
    }
}
data class Audience(val all: Boolean = false, val players: List<String> = emptyList(), val permissions: List<String> = emptyList()) {
    fun matches(name: String, hasPermission: (String)->Boolean) =
        all || players.any { it.equals(name,true) } || permissions.any(hasPermission)
}
data class Assignment(val id: String = "", val audience: Audience = Audience(),
                      val suggestions: List<String> = emptyList(), val ui: Ui = Ui())
data class Config(val serverId: String = UUID.randomUUID().toString(), val handshakeSeconds: Int = 30,
                  val decisionSeconds: Int = 180, val suggestions: List<Suggestion> = emptyList(),
                  val assignments: List<Assignment> = emptyList(), val missingClient: MissingClientPolicy = MissingClientPolicy()) {
    fun validate() {
        UUID.fromString(serverId)
        missingClient.validate()
        require(handshakeSeconds in 5..300 && decisionSeconds in 30..1800)
        require(suggestions.size <= 100 && assignments.size <= 100)
        suggestions.forEach(Suggestion::validate)
        val byId = suggestions.associateBy { it.id }
        require(byId.size == suggestions.size) { "Duplicate suggestion id" }
        fun visit(id: String, chain: Set<String>) {
            require(id !in chain) { "Dependency cycle: $id" }
            val s = requireNotNull(byId[id]) { "Unknown suggestion dependency: $id" }
            s.requires.forEach { visit(it,chain+id) }
        }
        suggestions.forEach { visit(it.id,emptySet()) }
        require(assignments.map { it.id }.distinct().size == assignments.size) { "Duplicate assignment id" }
        assignments.forEach { a ->
            require(a.id.isNotBlank()); a.ui.validate()
            require(a.audience.players.size<=4096 && a.audience.permissions.size<=256)
            a.audience.players.forEach { require(it.length in 1..64) }
            a.audience.permissions.forEach { require(it.isNotBlank() && it.length<=256) }
            a.suggestions.forEach { require(it in byId) { "Unknown suggestion: $it" } }
        }
    }
    fun audience(name: String, permission: (String)->Boolean) = assignments.filter { it.audience.matches(name,permission) }
    fun select(assignments: List<Assignment>): List<Suggestion> {
        val byId = suggestions.associateBy { it.id }
        val ids = assignments.flatMap { it.suggestions }.toMutableSet()
        fun expand(id: String) { byId.getValue(id).requires.forEach { if(ids.add(it)) expand(it) } }
        ids.toList().forEach(::expand)
        return ids.map(byId::getValue)
    }
}
data class Hello(val serverId: String, val ui: Ui, val requestInventory: Boolean = true)
data class Offer(val batchId: String, val ui: Ui, val suggestions: List<Suggestion>)
data class Decision(val batchId: String, val accepted: List<String>, val ignored: List<String>)
data class Receipt(val batchId: String, val transactionId: String, val status: String,
                   val suggestions: List<String>, val detail: String = "")

fun applies(s: Suggestion, inventory: Inventory): Boolean {
    if(s.minecraft.isNotEmpty() && inventory.minecraft !in s.minecraft) return false
    val installed = inventory.entries.filter { it.kind == s.kind && it.id == s.targetId }
    if(s.action == Action.ADD) return installed.isEmpty()
    if(s.action == Action.UPDATE && installed.any { equalHash(it.sha256,s.sha256) }) return false
    return installed.any { (s.fromSha256.isEmpty() || equalHash(it.sha256,s.fromSha256)) &&
        (s.fromVersions.isEmpty() || it.version in s.fromVersions) }
}
fun applicable(suggestions: List<Suggestion>, inventory: Inventory): List<Suggestion> {
    val result = suggestions.filter { applies(it,inventory) }
    val groups=result.groupBy { it.kind to it.targetId }
    val aliases=groups.values.flatMap { group -> group.map { it.id to group.first().id } }.toMap()
    return groups.values.map { group ->
        fun operation(s: Suggestion)=s.copy(id="",revision=1,level=Level.SUGGESTED,name="",reason="",requires=emptyList())
        require(group.map(::operation).distinct().size==1) { "Conflicting suggestions for the same target" }
        group.first().copy(level=group.maxOf { it.level },reason=group.map { it.reason }.distinct().joinToString("\n"),
            requires=group.flatMap { it.requires }.map { aliases[it]?:it }.distinct())
    }
}
fun leaf(name: String) {
    require(name.length in 1..255 && !Regex("""[<>:"/\\|?*\p{Cntrl}]""").containsMatchIn(name) && name !in setOf(".","..") &&
        !name.endsWith('.') && !name.endsWith(' ') &&
        !name.uppercase().matches(Regex("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))) { "Unsafe filename: $name" }
}
fun hash(value: String) { require(value.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256" } }
fun equalHash(a: String, b: String) = a.isNotEmpty() && a.equals(b,true)
