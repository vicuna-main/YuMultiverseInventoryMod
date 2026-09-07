# 1.0.6：坏存档启动隔离（本地候选）

修复单个玩家 profile 损坏导致存储构造失败、全服反复启动崩溃的问题。适用于所有 share 的压缩 NBT 存储，包括 advancements、背包和统计。

- 启动扫描仅接受正式的 UUID/scope/编码 scope ID/game mode/编码 share ID.dat 路径；忽略备份、临时文件及非规范路径。
- 启动、玩家完整读取、世界列表和单项加载共同验证 NBT、身份、版本、payload checksum 和 envelope checksum。旧元数据迁移前先验证外层校验，避免重写掩盖损坏。
- 压缩流读至 NBT 结束后的 EOF，检测 gzip CRC、尾部截断和多余解压数据。将由 IOException/NbtException 引起的 ReportedException 转换为存储异常；不捕获 Throwable 或吞掉未知程序错误。
- canonical profile、事务或历史修订的启动读取失败，只锁定对应玩家。该玩家不可分配 revision、保存、批提交或删除；其他玩家继续使用。损坏不返回 Optional.empty，不以空背包覆盖。
- 原文件留在原位置作为保护，原始字节副本和 SHA-256/路径/原因收据写入 profiles 树外的 data/mwi/quarantine/<UUID>。隔离目录即重启保护标记，归档中断或之后原文件被移走也不会被视为新档。超过 256 MiB 的异常文件保留原位并记录归档失败，避免无界内存分配。
- 保留既有有效事务重放和 prepared revision 回滚。无法校验 canonical 版本时不猜测历史版本或跨世界复制，保留未完成事务和计数器并阻止该玩家继续写入。这是保守隔离，不承诺任意损坏都能自动修复。

## 验证

- Java 21.0.10；仓库当前构建依赖 NeoForge 21.1.244。
- `gradlew.bat build --offline --console=plain` 成功，176 项测试通过，0 失败/错误/跳过；包含 JAR 类完整性检查。
- 新增 8 项测试：非法 tag 116 的真实 ReportedException 包装、CRC/截断、备份/临时文件过滤、运行时 payload 损坏、启动 envelope 损坏、隔离中断、事务/修订损坏隔离、缺失计数器时禁止回退。覆盖多次重启、原字节保留、其他玩家正常加载、禁止空档写回。
- 使用真实 Minecraft NBT 编解码和临时文件系统的存储集成测试；现有事务故障注入恢复测试继续通过。未运行真实 Youer 21.1.249 服务端、真实玩家登录或断电测试。
- 非法 tag/CRC 样本在本地合成；没有访问附件所述线上 Linux 证据目录，未验证其原始损坏样本。

## 写入审计和限制

现有写入先编码，再创建同目录 UUID 临时文件，循环写完、核对长度、force(true)，随后 atomic move 并持久化父目录；finally 清理正常异常退出的临时文件。同玩家持久化锁串行化写入，本次单项读取也参与该锁。进程强杀可能留下临时文件，扫描现在排除它们。

文件系统不支持 atomic move 时，现有实现仍回退到普通 replace；Windows 目录 fsync 的 IOException 仍由现有平台兼容分支忽略。因此不能承诺所有文件系统或断电条件下具有相同持久性。隔离不删除原文件，使副本/收据写入中断不会造成原始数据丢失。实际原文件损坏原因尚未证明，不能归因于硬件或其他模组。

隔离不自动解除。管理员应停服、保留隔离证据，恢复并验证该玩家的 profile 身份/双层校验和 revision、计数器与事务一致性，再将对应隔离目录移到扫描目录外的审计位置后重启；仅删除坏档或隔离标记不是修复。全局目录不可读、全局索引不能写入等故障仍可能阻止启动。

## 交付

本地 `build/libs/yu_multiverse_inventory-1.0.6.jar`，SHA-256：

`2ae1c422c09421bc4d3e2fbd2e4ff501950d6b86eeb10197bb3868954f9298dc`

基于 HEAD `abe4115ed41f7d9580de2738f5ce3d24f56ce5fc` 加当前工作区构建，包含此前已有未提交改动；本次未 commit、推送或操作线上服务器。
