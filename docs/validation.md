# 0.1.0 验证记录

基线：Minecraft / Paper **26.3 build 218**，Java **25**，Fabric Loader **0.20.1**，Fabric API **0.165.0+26.3**，Cloth Config **26.3.159**。

## 自动化与真实运行

`./gradlew build :client:fixtureJar` 通过。9 个核心测试与 1 个真实子进程测试通过，0 失败。

在全新的 loopback Paper 实例、真实 Fabric 客户端与正式发行 JAR 上完成 **17 项**流程检查。测试通过公共 Cloth / Minecraft 控件执行玩家操作，独立 fixture 未打包到发行文件。结果提示故意停留三秒后再确认，验证新建议不会抢先覆盖结果。

- empty config is silent in real Fabric client
- name selector excludes other players
- three artifact types accepted through Cloth UI
- embedded child applies after real Minecraft exit
- restart result and update-remove workflow
- next process loads updated MOD and reports results
- all-player optional suggestion can be ignored
- permission selector excludes a player without permission
- permission selector includes permitted player
- required refusal disconnects
- required inventory refusal is separate from missing-client detection
- installed client is detected without requesting an inventory
- missing-client KICK uses configured message even with no suggestions
- missing-client REMIND sends configured title and retains connection
- missing-client SILENT sends no reminder and does not disconnect
- server audit contains offers decisions and offline completion receipts
- reloaded empty config returns to no-impact mode

确认了客户端 JAR 已内嵌完整更新子进程和 Kotlin 标准库；内嵌子进程与单独测试的更新程序字节一致。

## 发行文件 SHA-256

```
3e0c1dbd1672ec317b5213737106f695e65d10487a4f0019f800c1685ebac042  Suggestor-Fabric-0.1.0+mc26.3.jar
cf91b681c557b002c8e796c4fb07f6c608d3878f9c494585e473bcbe376a8f97  Suggestor-Paper-0.1.0+mc26.3.jar
31176798759850cb0a09ca5b0aa014bef02868835988f34ec23366f90cc8f6fc  suggestor-updater.jar
```

## arch 部署

已安装与隔离测试字节一致的 Paper JAR。在确认在线人数为 0 后执行正常停服保存，并通过原有启动脚本重启。

实际启动日志确认插件启用，`suggestor status` 返回：

```
Suggestor: 0 suggestions, 0 assignments, 0 sessions, 0 pending.
```

`plugins/Suggestor/config.json` 的建议和投放均为空，缺少客户端策略为 `SILENT`；未产生握手/组件清单审计文件。其余插件和业务配置没有被此部署脚本修改。

## 验证范围

已实测 Linux 上的 Paper/Fabric/Cloth 完整流程和进程退出后文件处理。Windows 使用同一套 Java 文件/进程 API，并选择 javaw.exe 启动内置子进程；尚未进行 Windows 实机回归，也未覆盖所有第三方启动器。客户端仅声明支持 26.3，不声明支持其他游戏版本。
