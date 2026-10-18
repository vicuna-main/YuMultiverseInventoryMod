# 1.4.0 自动恢复与管理员时间恢复

本版将存储核验与玩家激活分开。只有应用、完整依赖验证、权威提交及玩家存档回读全部成功，才解除登录保护并显示 `ACTIVE`。`RESOLVED` 单独表示后端存储核验结果。

## 管理员入口

需要原有 `/mwi` 的 2 级命令权限，可从游戏、控制台或 RCON 使用：

```text
/mwi restore 玩家名 list
/mwi restore 玩家名 status
/mwi restore 玩家名 latest
/mwi restore 玩家名 20261018-153012
/mwi restore 玩家名 20261018-153012.125
```

名称不区分大小写，仅采用已有在线身份和本机用户名缓存；未知或重名身份拒绝执行，不生成猜测 UUID。Tab 补全提供已知玩家和当前完整恢复点。时间采用 `storage.restore_time_zone`，默认 `Asia/Shanghai`；列表同时显示 UTC 偏移。同秒多份记录需要毫秒时间，日期或时间仍有歧义时拒绝选择。

`latest` 复核当前应激活的权威状态，不回退到更老、更满的背包。已有 `PREPARED` 历史任务时明确提示冲突，需查询状态或继续原目标。明确指定历史时间才允许回档。历史回档会先保留当前状态，并将完整目标、范围及原状态摘要持久化为 `PREPARED`。离线结果只报告“已准备”，上线后才执行应用与存档回读。范围或当前权威数据变化时拒绝覆盖。

任务异步运行，最初的“已接收”不是完成结果。重复同一目标复用现有任务或永久操作凭证，不重复回档。旧的 `mwi recover <在线玩家> <revision>` 命令已接入同一入口；旧 UUID inspect/retry 命令继续可用。

玩家可使用 `/mwistatus` 查看自己的进度。无法激活时保留原档案并进入独立 `yu_multiverse_inventory:recovery_lobby` 维度，可移动和使用帮助，物品交互、外部传送及临时状态落盘继续受保护。如果维度未加载，消息明确提示受保护会话尚未进入大厅。

## 校验与托管范围

- 文件 checksum、envelope、提交身份与 revision 校验保持严格。
- 物品采用当前注册表 `ItemStack.CODEC` 解码。数量、槽位、容量、物品 ID、组件、时装皮肤开关和隐藏标志必须一致；未知 codec 或缺失注册项不能用空物品冒充成功。
- 唯一允许的非字节一致规则是 SFM **4.34.0** `sfm:labels` 内坐标集合的排列变化，包括 AE2 储存元件中的嵌套物品。其他列表仍按原顺序比较，重复或类型错误坐标拒绝规范化。
- 等价后像必须再次应用并精确稳定，才在同一 WAL 内归档原件、更新当前权威 profile 及参与的当前世界镜像，不改写未参与世界。
- Curios **9.5.1** 已知槽位类型不可用时，仅在全部功能槽和外观槽物品均能无损解码的预检条件下，整体交给现有恢复中心托管。清空正式装备、所有物品与永久领取凭证共用同一个 WAL。其他不确定变化进入待处理状态。
- 相同来源摘要和环境版本的应用失败最多尝试 3 次，之后为 `ACTION_REQUIRED`；后台健康或重复 retry 不重置预算。来源、恢复目标或模组环境改变后重新核验。

自动激活遇到缺失物品／组件时保留原档案等待处理。既有 `missing-items` 显式修复命令仍保留原有迁移策略；不要把修复出的空槽当作该物品已经恢复。

## 恢复点与兼容

新增 `restore_points` manifest 保存每项 ProfileKey、payload/document/事务摘要和引用 revision；保留窗口会连同其冷数据依赖保留。旧 committed batch 只有在一个已验证事务中具备全部所需档案时才可用于完整恢复，增量记录不拿今天的数据补齐。

`activation_tasks` 保存激活预算与错误；`restore_requests` 保存待执行请求；`restore_operations` 保存永久去重凭证。这些目录、`recovery_sources`、`recovery_operations` 与原有事务、档案及归档共同组成恢复数据，不可只备份单个 profile。已有墓碑领取／补回／托管凭证涉及的范围，不允许回滚到该凭证之前而重新开放来源。

未决 WAL、可靠来源不足、当前范围无法确定、历史依赖缺失或摘要不一致时保留待处理状态。管理员 list/status 可查看原因，不报告恢复成功。

## 验证方式

```powershell
.\gradlew.bat test build --offline --dependency-verification strict
.\gradlew.bat test -PrestoreFixture=C:/private/restore-fixture --offline --dependency-verification strict
.\gradlew.bat runGameTestServer -PautomaticRecoveryGameTests -PrecoveryGameDir=build/restore-gametest --offline --dependency-verification strict --no-configuration-cache
```

私有 fixture 目录包含 `curios-expected.nbt`、`curios-actual.nbt`、`curios-second-round.nbt`；不提交到 Git。此 JUnit fixture 用例检验结构规则，真实注册表解码另在隔离服务器验证。

GameTest 运行目录 `mods/` 需要 Curios 9.5.1、Cosmetic Armor Reworked 1.21.1-v1、TrashSlot 21.1.11 及 Balm 21.0.64。测试包括健康／损坏读档、保护会话重连、管理员名称时间入口、离线准备、重复目标、Curios 整体托管与重复领取、应用失败预算，以及时装栏／垃圾槽的历史恢复、保存、重登和世界往返。以日志 `All 10 required tests passed` 为成功依据。

故障注入测试覆盖 WAL 耐久化、profile、archive、恢复点、heads、托管来源和操作凭证边界。它模拟 I/O 中断及重启重放；不等同于逐边界真实进程强杀或真实客户端界面验收。

## 升级与回退

维护部署前停止玩家写入并备份完整世界、playerdata、所有 MWI 数据与配置，记录旧 JAR 和新 JAR 的 SHA-256。删除模组目录中的旧版本副本只应由维护部署流程执行，不可同时装入两个版本。安装后先检查恢复维度、适配器数量、待处理事务和指定玩家状态，再进行业务验收。

1.4.0 增加旁路索引／任务文件，不批量重写旧 profile checksum。程序回退不得把玩家数据覆盖为修复前快照。旧版不能继续执行新 PREPARED／CHECKPOINT 任务；有此类未完成任务时先保留停写状态处理。回退程序后仍须保留新的凭证和归档，不能删除去重记录来“解锁”。本次研发交付不包含线上部署。
