# MWI 1.5.0：独立版与 1.4.0 恢复问题修复

适用前提：移除 Tombstone、TombStoneDefender，服务端已开启死亡不掉落。此版本仅修改 MWI，保留现有隔离配置和数据目录。

整合包实测前置条件：EnigmaticDefender **1.0.2** 声明 Tombstone **9.4.8+** 为必需依赖，单独移除 Tombstone 会在
NeoForge 加载阶段失败。该 JAR 需要停用或另行提供不依赖墓碑的版本；MWI 无法替它满足依赖。本地独立版验证副本停用了它，未修改其代码或线上安装。

## 保留与移除

保留世界/共享组/游戏模式隔离、原版各 share、Curios、时装栏、垃圾槽、原子保存、事务恢复、隔离归档、
自动恢复、受保护恢复大厅、玩家名/时间恢复和 MWI 原生装备托管。死亡不掉落不替代这些功能。

删除 RecoveryBridge V1/V2、墓碑专用交接/领取/外部结案与 guard 扩展。历史外部来源只读，既有物品凭证、
未决事务及防回档复制门禁保留。没有把死亡事件变成新补发来源。

## 对应本次事故问题

| 问题 | 本版行为 |
| --- | --- |
| M01：药水 `neoforge:cures` 换序 | 仅该已知集合及 `hidden_effect` 内同名集合按成员规范化；重复、类型错误、成员/时长/其他字段变化仍拒绝。文件校验不放宽 |
| M02：吸收值被上限钳为零 | 健康档案 schema 2 保存基础容量与永久修饰符；装备/药水应用后再设置并读取真实吸收值。旧档案的既有护盾用可随消耗收缩的临时容量保全，不增加永久基础上限；切换健康范围清除该临时容量 |
| M03：Curios 接受编码但拒绝穿戴 | Curios 9.5.1 全套物品可无损解码时整体转入原生托管，正式装备清空和托管额度同一事务提交，供玩家领取后重新穿戴 |
| M04：重试产生重复待发物品 | 事务内拦截重建拒收队列；保护期间禁止队列发放。已有不明队列保持原样并阻止激活，等待核对 |
| M05：SFM 嵌套标签换序 | 保留 1.4.0 的 SFM 4.34.0 坐标集合规则及二次稳定性检查 |
| M06：修错共享范围 | 按原定返回维度和游戏模式刷新实际所需 ProfileKey；状态显示完整 scope/share；临时恢复大厅不覆盖返回范围 |
| M07：存储恢复但激活预算仍耗尽 | 明确区分 RESOLVED 与 ACTIVE。仅实际输入、必要迁移来源、恢复目标或规则/环境变化才能重置失败预算；无关组修改和反复 retry 不重置 |
| M08：保护状态消息与 RCON 结果 | 保护会话直接查看进度；`status` 在同一次 RCON 响应内返回状态、范围和错误。恢复命令先持久化受理和最终结果；断开控制台后仍可查询 |

任何等价后像都需重新应用并验证稳定，原件归档、正式档案及参与的世界镜像在同一 WAL 中提交。
Curios 托管是一整份装备，不是自动恢复穿戴。缺失物品注册、未知组件、异常待发队列或无法满足的依赖仍会阻止激活，不能伪造 ACTIVE。

卸载模组后可能留下失效药水。仅在效果注册项不存在、所属 namespace 的模组也未安装时，通用迁移流程才将原始效果
归档至 `removed-effects`，并在同一事务中保存剩余有效效果。仍安装的模组缺少效果注册项、非法 ID、重复效果或损坏结构继续拒绝。
该迁移不宣称前后效果等价，不按特定模组名称放行，也不自动删除缺失物品。

## 操作和验收

```text
/mwi restore 玩家名 list
/mwi restore 玩家名 latest
/mwi restore 玩家名 20261018-153012.125
/mwi restore 玩家名 status
/mwistatus
```

`latest` 复核当前权威数据；指定时间才是历史回档。初始“已接收”和离线“已准备”不是玩家激活成功。
`status` 同步读取有上限的任务/结果文件；`list` 仍异步读取大恢复点，RCON 需查询状态获取恢复操作最终结果。

```powershell
.\gradlew.bat test build --offline --dependency-verification strict
.\gradlew.bat test -PserverIncidentFixture=C:/private/server-incident -PrestoreFixture=C:/private/sfm-fixture --offline --dependency-verification strict
.\gradlew.bat runGameTestServer -PautomaticRecoveryGameTests -PrecoveryGameDir=build/standalone-gametest --offline --dependency-verification strict --no-configuration-cache
.\gradlew.bat runGameTestServer -PautomaticRecoveryGameTests -PstandaloneSmoke -PrecoveryGameDir=build/vanilla-smoke --offline --dependency-verification strict --no-configuration-cache
```

事故样本留在仓库外，测试只读原始 NBT。GameTest 目录需 Curios 9.5.1、Cosmetic Armor Reworked 1.21.1-v1、
TrashSlot 21.1.11 与 Balm 21.0.64。测试覆盖真实服务端登录边界、保存/重登、历史恢复、背包托管、失败预算、
RCON 回读及 keepInventory 死亡复活；嵌入式测试连接不等于图形客户端或线上插件组合验收。

本次不处理报告中的 SFM 方块崩溃、AdvancedAE、JVM 原生崩溃、YuDefender 或 PlaceholderAPI 映射问题。
不批量修写线上玩家记录，不部署候选包，不对已有不明待发物品做猜测去重。交付验证结果见 PR/随包收据。
