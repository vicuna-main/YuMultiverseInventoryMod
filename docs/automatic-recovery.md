# 自动修复与登录自愈（1.3.0）

本文记录 1.3.0 的存储恢复合同。1.4.0 的有界激活、独立恢复大厅与名称／时间入口见 [1.4.0 恢复说明](restore-1.4.md)。

本功能处理 MWI 档案、事务、修订记录和旧隔离标记的存储故障。墓碑恢复中心继续使用原有 V2 封存、领取凭证和幂等协议；自动文件修复不会重新领取墓碑、回滚已领取来源或向玩家补发物品。

## 实际行为

1. 玩家进入 `PlayerList.placeNewPlayer` 时先建立恢复保护，再接收混合端登录事件。原版读入的 NBT 和已启用适配器的现场快照作为诊断输入保留。
2. 后台工作线程按玩家合并重复检查，先等待此前提交队列完成，再校验同一份有大小限制的压缩快照。GZIP 尾部、NBT 解析、身份和 checksum 均必须通过，重新打开的文件还必须具有相同 SHA-256。失败最多连续读取三次，之后按配置间隔重试。
3. 旧 `quarantine` 只有在相关档案、历史和未决事务得到验证后才解除；整个旧目录移入诊断归档，保留原样本。不能以“目录存在”永久判坏，也不能以“当前文件能解压”单独判定可激活。
4. 损坏档案按 `player/scope/gameMode/share` 隔离。已经存在且健康的当前作用域可以继续使用，另一世界的坏档案继续等待；缺失目标需要迁移时，相关损坏候选仍会阻止首次模板生成。
5. 自动替换只接受正常提交协议留下的最新权威 after-image。旧修订、更旧备份、另一世界、另一玩家或身份不符的文件不能作为猜测来源。没有充分证据时保持 `WAITING_FOR_SOURCE`。
6. 修复前写入带 generation、目标原字节 hash 和来源版本的计划。每个目标替换前重新比较输入；中断后重启继续核验，旧计划遇到新故障会归档并重新规划。完整批次和 revision 高水位确认前禁止业务写入。
7. 所需档案仍不可用时，玩家保持连接，获得恢复提示并进入旁观展示。原始现场持久化前不清空展示。恢复期间阻止物品操作、能力传递、拾取、掉落、伤害、死亡、传送和改游戏模式；聊天及登录/注册命令仍允许。原版 `PlayerList.save`、`PlayerDataStorage.save` 和 MWI checkpoint/logout 均不保存展示状态，退出后的旧玩家实例也继续受写入保护。
8. 恢复完成后回到主线程，重新验证 generation，应用全部启用适配器并比对现场；成功后才恢复正常会话。失败的旧写入保留原顺序重放，不能通过清空错误标记假装成功。

## 配置和命令

原有 TOML 可直接加载，缺失的新字段使用下列默认值。`storage` 变更需重启。

```toml
[storage]
automatic_recovery = true
recovery_retry_seconds = 30
```

重试间隔允许 5–3600 秒。关闭 `automatic_recovery` 仍保留登录校验和写入保护，管理员可以手动请求同一恢复流程。

```text
/mwi recovery inspect <uuid>
/mwi recovery retry <uuid> --dry-run
/mwi recovery retry <uuid>
```

需要权限等级 2。前两个只读检查；`retry` 允许自动修复规则范围内的恢复，不提供强行解锁、删隔离目录或恢复旧库存的选项。命令输出状态、generation、健康/待恢复档案数、未决事务数、尝试次数和耗时。在线恢复会话的重试会复用已有任务。

## 持久化合同

所有新增目录都位于世界存档的 `data/mwi` 下。

| 目录 | 用途 |
| --- | --- |
| `recovery_incidents/<uuid>.dat` | 带 checksum 的活动故障、输入 hash、尝试次数、generation 和解决记录 |
| `committed_batches/<uuid>/<revision>.dat` | 正常提交的完整事务副本 |
| `committed_heads/<uuid>.dat` | 每个档案的最新已提交版本及文档 hash |
| `completed_revisions/<uuid>/<revision>.dat` | 已完成或已回滚修订的校验副本 |
| `exact_repairs/<uuid>.dat` | 尚未全部验证完成的精确修复计划 |
| `diagnostics/inputs` | 实际失败的原始字节，按 SHA-256 去重 |
| `diagnostics/recovery-sessions` | 恢复前现场及原版登录输入 |
| `diagnostics/quarantine-archive` | 已解除的旧隔离目录 |
| `diagnostics/completed-repairs`、`superseded-repairs` | 完成或被新故障替代的修复计划 |

提交副本保留最近 `max_revisions` 个版本，并额外保留每个当前 head 所引用的版本；因此冷门世界的唯一最新副本不会因全局版本增长被淘汰。已完成修订副本随正常修订保留策略清理。诊断证据不自动删除，运维应监控其容量；数据可能包含玩家完整物品和状态，不应提交 Git 或公开上传。

诊断存储暂时不可写时保留内存中的首次失败输入，继续保护会话，待证据成功落盘后再解除。进程本身崩溃且磁盘始终拒绝写入时，无法保证这份尚未落盘的内存证据仍然存在。

## 升级和回滚

生产安装前正常停服，备份整个世界、原版 `playerdata`、完整 `data/mwi`、配置和旧 JAR，记录 SHA-256。不要只复制 `profiles`；事务、heads、修订高水位和恢复中心领取凭证必须保持同一快照。

替换 JAR 后先检查启动日志，再用目标混合端、AuthMe/YuDefender 的实际登录入口验证：进入世界、正常背包、恢复等待、重新连接、切换世界、保存和重启。使用管理员诊断命令查看剩余待恢复项；未恢复档案不得手动清零或删除标记。

回滚应先停服并恢复升级前的一致快照及旧 JAR。**不能仅降级 JAR 后继续使用恢复展示期间的存档**：旧版不识别新的保护和修复计划。若升级后已有新业务数据，应先离线评估差异，不能直接覆盖为旧快照。

## 验证入口与边界

```powershell
.\gradlew.bat build --offline --dependency-verification strict --console=plain
.\gradlew.bat runGameTestServer -PautomaticRecoveryGameTests --offline --dependency-verification strict --no-configuration-cache --console=plain
```

首次 GameTest 运行如本机无资源缓存，需要先联网准备 Gradle/Minecraft 依赖；下载失败不属于测试通过。测试世界默认在 `build/automatic-recovery-gametest`，可用 `-PrecoveryGameDir=<隔离目录>` 指定。完整 Curios JAR 可放入该测试目录的 `mods` 中；测试代码不会进入发布 JAR。Gradle 会要求日志出现全部必需 GameTest 通过的收据，服务端以零退出但测试未执行仍算失败。

单元测试覆盖残留隔离、96 个合成档案与 revision 108/203、精确副本缺失、不跨身份恢复、健康/损坏作用域隔离、修复中断、新故障 generation、证据写失败、保留策略及并发队列。服务端 GameTest 通过实际 `PlayerList.placeNewPlayer` 和网络连接对象验证登录、恢复、退出再进入以及原版保存拦截。

这些合成测试不能替代生产玩家原始故障样本或 AuthMe/YuDefender 完整整合服验收。该实现保留这些部署验收边界，不把本地测试结果当作生产恢复完成。

### 2026-10-17 本地候选验证

| 验证 | 结果 |
| --- | --- |
| 完整 `build`、JAR 类完整性、单元测试 | 208 项，0 失败、0 错误、0 跳过 |
| NeoForge 21.1.244 + Curios 9.5.1 服务端 GameTest | 5 项必需测试全部通过，Gradle 正常结束 |
| Youer 1.21.1-4ef79ee0 / NeoForge 21.1.259，隔离协议客户端 | 正常登录、7 个物品保存、残留隔离解除、缺副本保持在线、原数据返回后恢复 7 个物品 |
| Youer 恢复等待时 `save-all` | 原版玩家文件 SHA-256 不变，损坏 canonical 字节不变 |
| Youer 管理员改模式、同世界/跨世界传送 | 实际模式仍为旁观、位置与维度不变；以实体状态读回判定，不以命令成功文本判定 |
| 实际生产玩家故障样本、目标 NeoForge 21.1.250、AuthMe + YuDefender 整合登录 | 未验证，未部署 |

协议客户端未实现 NeoForge 的全部配方数据编码，收到过非致命配方解析警告；本次依据客户端库存包、恢复提示、持续连接及服务端实体/文件读回验证上述范围，没有把它当作完整图形客户端验收。Curios 的网络握手要求安装对应客户端模组，因此其适配器验证使用带 Curios 的服务端 GameTest。

混合端实测发现并修复了两处普通单元测试不会发现的问题：Curios 在玩家构造期间查询能力时服务端字段尚未赋值；Paper/Youer 的游戏模式和传送扩展重载绕过原版同名入口。扩展拦截通过可选方法描述符实现，纯 NeoForge 不需要 Bukkit 依赖。
