# Protocol 1

通道 `suggestor:main`，两端使用相同原始字节布局，不加入额外 byte-array 长度前缀。每个分片为 big-endian：magic `0x53554731`、消息 UUID 两个 int64、index int32、count int32、总字节数 int32，再接最多 24,000 字节。完整 UTF-8 JSON 最大 512 KiB，分片流超时 10 秒，不接受交叉消息或重复分片。接收方不会按未校验长度分配内存。

JSON 信封包含 `protocol: 1`、本次连接随机 `session`、`type`、`data`。服务端会话绑定真实 Player；更换连接/重载后，旧会话不再有效。原始帧每会话每分钟最多 400 个。

| 方向 | 类型 | 内容 |
| --- | --- | --- |
| S→C | HELLO | serverId、requestInventory、清单授权和建议 UI 文案 |
| C→S | HELLO_ACK | MOD 存在和协议可用的回执，不包含组件清单 |
| C→S | INVENTORY | minecraft、loader、entries；仅用户授权后发送 |
| C→S | DECLINE_INVENTORY | 拒绝提供清单 |
| S→C | OFFER | batchId、ui、具体 suggestions；空列表不显示建议 UI |
| C→S | DISPLAYED | batchId |
| C→S | DECISION | batchId、accepted IDs、ignored IDs；必须完整覆盖本批次 |
| C→S | READY | 已准备并启动助手，仍等待游戏退出 |
| C→S | FAILED | 下载、校验或准备失败 |
| C→S | RECEIPT | 之前事务的离线执行结果；属于客户端声明 |
| S→C | RECEIPT_ACK | 已记录事务 ID，客户端停止重复补报 |
| S→C | ERROR | 配置冲突等错误说明 |

默认 SILENT 且没有命中建议的玩家不创建会话、不发送 HELLO。显式 KICK/REMIND 策略会给所有玩家发送仅用于存在检测的 HELLO；无建议时 requestInventory 为 false。未响应者按 missingClient 策略踢出、Title/聊天提醒或静默放行。HELLO_ACK 与清单授权独立，避免把拒绝清单当作未安装。检测到 MOD 且存在必需项时，服务端对等待玩家取消移动、交互、物品/背包操作、方块变更、伤害、聊天和命令；这发生在 PLAY 阶段，不宣称玩家从未进入世界。清单证明当前必需规则不再适用时放行；用户拒绝、握手超时或决策超时则断开。READY 仍不放行，需要退出、应用并重新连接。

服务端审计中的 `client_*` 事件为客户端声明；`inventory` 和 `offered` 保存每次重新检查的依据。不会把采纳、文件落盘、运行时已加载当成同一状态。
