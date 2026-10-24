# Suggestor

Kotlin 编写的 Paper 插件与 Fabric 客户端 MOD，用于向指定玩家建议安装、更新或移除 MOD、资源包和光影包。包名为 `cc.taixue.suggestor`。首版对应 Minecraft/Paper **26.3**、Java **25**。

客户端使用 **Cloth Config API** 的列表、开关和滚动界面，以及 Minecraft 自带的确认框。玩家只需像普通 MOD 一样安装；退出后的文件处理子进程已内置，无需额外安装程序。

## 安装

服务端将 `Suggestor-Paper-0.1.0+mc26.3.jar` 放入 `plugins/`，完整启动服务器。首次生成 `plugins/Suggestor/config.json`。

**默认 `suggestions: []`、`assignments: []`、`missingClient.action: "SILENT"`：不握手、不采集清单、不发弹窗、不限制或踢出任何玩家。** 不需要客户端安装本 MOD。

客户端将 `Suggestor-Fabric-0.1.0+mc26.3.jar` 放入当前游戏实例的 `mods/`，另装对应 26.3 的 Fabric Loader 0.20.1+、Fabric API、Cloth Config API 26.3.159+。本 MOD 已包含 Kotlin 标准库，不要求 Fabric Language Kotlin。

## 服务端配置

建议与投放分开维护：`suggestions` 定义操作，`assignments` 指定玩家范围、建议编号和弹窗文案。默认配置见 [examples/empty-config.json](examples/empty-config.json)；完整字段示例见 [examples/suggestions.json](examples/suggestions.json)，示例 URL 和哈希仅作说明，不要直接投放。

每条建议包括：

- `id`、`revision`：稳定编号及修订号。内容变化也会触发客户端重新确认。
- `kind`：`MOD`、`RESOURCE_PACK`、`SHADER_PACK`，对应 `mods/`、`resourcepacks/`、`shaderpacks/`。
- `action`：`ADD`、`UPDATE`、`REMOVE`。
- `level`：`SUGGESTED`、`RECOMMENDED`、`REQUIRED`。前两类可忽略，必需项未满足时限制游玩，拒绝或超时则断开。
- `targetId`：MOD 使用 Fabric mod ID；资源包、光影包使用原文件名，新增时必须等于 `fileName`。
- `name`、`reason`：展示名称与原因。
- `targetVersion`：MOD 下载文件的准确版本；包文件可作为展示说明。
- `fromSha256`、`fromVersions`：限定哪些已安装文件适用。版本为精确匹配列表；移除必须给出原文件 SHA-256。
- `url`、`fileName`、`sha256`、`size`：安装/更新的 HTTP 或 HTTPS 链接、目标文件名、SHA-256、准确字节数。
- `minecraft`：适用的游戏版本列表；空列表表示不额外限制。
- `requires`：一并采纳的其他建议 ID。确认页会列出完整操作，不会偷偷查找或下载额外依赖。

`assignments[].audience` 支持 `all: true`、`players: ["Alice"]`、`permissions: ["suggestor.audience.builder"]`。三个条件取并集，权限通过 Paper 的 `Player.hasPermission` 查询，可直接使用现有权限插件。玩家名不区分大小写，审计绑定实际连接 UUID。

多个投放命中时合并建议，相同操作采用最高要求等级；同一组件的不同目标文件会报配置冲突。界面使用配置顺序中第一个命中投放的 `ui`。权限和投放范围在加入、重载或管理员重发时重新计算；离开范围不会自动卸载已安装文件。

`ui` 可以覆盖标题 `title`、说明 `introduction`、确认按钮 `accept`、忽略按钮 `ignore`、清单授权标题 `consentTitle` 和说明 `consentText`、退出提示 `restart`、必需项断开说明 `requiredDisconnect`。省略的字段使用中文默认值。文件操作结果和错误依据客户端实际状态生成。

未安装或未响应兼容握手的客户端由 `missingClient` 独立控制：

```json
"missingClient": {
  "action": "SILENT",
  "message": "请安装 Suggestor 后重新连接，下载说明见服务器网站。",
  "title": "推荐安装 Suggestor",
  "subtitle": "安装后可接收服务器的组件建议。"
}
```

- `KICK`：在 `handshakeSeconds` 内未收到 MOD 存在回执时断开，断开原因使用 `message`。
- `REMIND`：保留连接，显示可配置 Title 和副标题，并发送 `message` 聊天提醒。
- `SILENT`：不提醒、不踢出未响应客户端。

此策略是全服设置。显式启用 KICK/REMIND 时，即使没有建议，也会只检查 MOD 是否存在；没有待检查建议时，不索取组件清单。默认 SILENT 且无命中建议时完全不发握手。有建议但未装 MOD 时也按此策略处理，不把它自动等同于拒绝某条必需建议。已经检测到 MOD 的玩家，是否授权清单、是否满足必需项另行判断。

管理员命令：`/suggestor status`、`/suggestor reload`、`/suggestor send <在线玩家>`，权限 `suggestor.admin`，默认仅 OP。无效重载保留原配置。

## 玩家流程

1. 命中投放后协商协议，首次连接时确认是否向该服务器提供组件清单。信任按连接地址和服务器 ID 分开保存。
2. 上报已加载 MOD 的 ID、名称、版本、文件哈希及包含关系，以及 ZIP 形式的资源包、光影包清单；不上报本机绝对路径。
3. 在 Cloth Config 界面逐项选择，确认完整变更清单。被忽略的普通/强烈推荐在内容不变时不重复强弹；必需项始终检查。
4. 后台下载、校验文件类型、哈希、版本及声明依赖。下载失败不动原文件。
5. 玩家选择现在退出或稍后退出。内置子进程等待准确的游戏进程结束，再备份和修改文件。
6. 下次启动展示执行结果，重新连接时补报回执并重新检查实际清单。点击采纳或下载成功不会令必需项直接满足。

资源包和光影包安装后可在对应设置中选择启用；本软件不改动玩家的当前资源包/光影设置。必需项检查的是组件是否安装/已加载，不要求玩家启用某个光影。

## 文件与恢复

客户端数据均位于当前实例 `.suggestor/`。每个事务的 `plan.json` 固定已确认操作，`staged/` 保存下载，`backup/` 保存原文件，`journal.json` 记录已开始的文件变更，`result.json` 保存结果，`helper.log` 保存子进程日志。

更新必须先准备好全部文件再执行；应用失败尝试恢复整组操作。崩溃中断会在后续退出时进行恢复。应用前重新核对原文件哈希，手动改过的文件不会直接覆盖。仅支持当前实例目录内的普通 JAR/ZIP 文件，内嵌 MOD 通过其外层 MOD 更新；不会单独删除内嵌库、自动升级 Suggestor/加载器或操作目录外的组件。

如游戏在 Suggestor 初始化之前就因某个新 MOD 崩溃，无法显示游戏内结果。先退出所有使用该实例的游戏进程，查看事务 `plan.json` 的原文件名，再从对应 `backup/` 恢复；不要把整个备份目录原样复制到 `mods/`。重新启动前应等待退出后的文件处理完成。

MOD 自动处理会检查声明的依赖与冲突，但无法预知所有运行时兼容性问题。备份不会自动删除。

## 日志与协议

服务端 `plugins/Suggestor/audit.jsonl` 记录给谁发送了哪些建议、展示回执、采纳/忽略、超时、准备完成和离线补报。客户端状态与重新连接的清单检查分开记录。客户端数据可以伪造，本软件不提供反作弊证明。

[docs/protocol.md](docs/protocol.md) 说明握手、分片及状态。HTTP 和 HTTPS 都允许；SHA-256 验证文件与清单一致，不代表发布来源可信。

## 构建与验证

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew build :client:fixtureJar
```

发行文件来自 `server/build/libs/Suggestor-Paper-0.1.0+mc26.3.jar` 和 `client/build/libs/Suggestor-Fabric-0.1.0+mc26.3.jar`。不要分发普通开发 JAR、sources JAR 或 `SuggestorFixture.jar`。

核心测试覆盖投放、条件、冲突、分片、HTTP、文件校验、各类型增删改、恢复和路径边界；子进程测试实际启动两个独立 Java 进程验证退出等待。`scripts/integration.py` 使用独立 Paper、真实 Fabric 客户端和 Cloth 控件验证完整流程，fixture 不进入发行包。服务器运行时、客户端缓存库和 Xvfb 路径由参数指定。

验证结果和部署记录见 [docs/validation.md](docs/validation.md)。
