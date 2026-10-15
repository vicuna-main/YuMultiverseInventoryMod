# Yu Multiverse Inventory

NeoForge 1.21.1 服务端多世界玩家数据隔离模组。它模仿 Multiverse-Inventories 的
`WorldSelector -> WorldGroup -> ShareSet` 语义，但所有玩家数据均通过 Minecraft/NeoForge
原生 NBT 读写，可用于纯 NeoForge 服务端以及 Youer、Arclight 等 NeoForge/Bukkit 混合端。

## 环境

- Minecraft 1.21.1
- Java 21
- 编译版本：NeoForge 21.1.244
- 运行范围：NeoForge 21.1.x（最低 21.1.244）
- Curios 9.5.1+1.21.1：可选软依赖

该模组不注册物品、方块或网络 payload，客户端无需安装。

## 功能

- 精确、glob（`dungeon_*`）和正则（`r=world_[0-9]+`）世界选择器。
- 同一世界可进入多个组，但同一个原子 share 只能命中一个组；歧义会阻止切换。
- 递归 share 别名和负向表达式，例如 `all,-statistics`。
- 可选按 survival、creative、adventure、spectator 分开 profile。
- 原版 share：主背包、盔甲、副手、末影箱、经验点/等级、生命、饱食、药水效果、重生点、
  上次位置、配方书、进度和统计。
- Curios 功能槽与 cosmetic 槽通过官方 Curios API 整体保存/恢复；未安装 Curios 时不会加载相关类。
- 每个 share 独立计算 `world/group + game mode` 作用域，只切换真正变化的部分。
- 切换前快照、原子 NBT 写入、SHA-256 校验、失败回滚、lost-found 和 revision 恢复。
- 配置合组冲突默认采用当前世界 profile，并归档其他旧 profile，避免静默合并造成复制。
- NeoForge 维度事件为主，轻量玩家 tick 仅比较维度/游戏模式作为混合端漏事件兜底。
- 混合端在安全的 NeoForge 登录边界初始化；退出在 Bukkit Quit 后做最终保存。
- 1.2.0 提供服务端[背包恢复中心](docs/recovery-center.md)：与 TombStoneDefender 1.5.0 通过 V2 先封存墓碑再提交物品；无关墓碑不遮住独立来源，领取条件单独展示，提醒有持久回执。

## 安装

将 `build/libs/yu_multiverse_inventory-<version>.jar` 放入服务端 `mods/`，然后启动一次生成：

```text
config/yu_multiverse_inventory.toml
```

背包及其他玩家数据不保存在 `config/` 中，而是保存在服务器当前存档的根目录：

```text
data/mwi/index.dat
data/mwi/revision_counters/<uuid>.dat
data/mwi/profiles/<uuid>/<scope>/<scope-id>/<game-mode>/<share>.dat
data/mwi/revisions/<uuid>/<revision>.dat
data/mwi/transactions/<uuid>/<revision>.dat
data/mwi/recovery_sources/<uuid>/<source>.dat
data/mwi/recovery_operations/<uuid>/<operation>.dat
data/mwi/archives/conflicts/...
data/mwi/archives/compatibility/...
data/mwi/lost_found/...
```

`profiles` 下的 `.dat` 是按玩家、world/group 作用域、游戏模式和 share 拆分的压缩 NBT。
主背包、盔甲、副手和末影箱分别对应 `inventory_contents`、`armor_contents`、`off_hand`、
`ender_chest`；路径中的作用域 ID 和 share 文件名会使用 Base64 URL 编码。`transactions`
是崩溃恢复所需的待提交事务，`revision_counters` 保存每名玩家的修订序号。请备份整个
`data/mwi/` 目录，不要在服务器运行时修改或删除这些 NBT 文件。

启动时若发现只有 `prepared` 日志而没有持久化事务，模组会把日志中的 `before` 快照写回
原作用域，并在玩家登录时清空尚未建立的目标 profile，避免把崩溃瞬间的半切换状态当成新背包。

不要让 Multiverse-Inventories 与本模组同时管理同一批原版 share。推荐移除 MVInv 并使用
`import_and_replace`；若必须由 MVInv 管理原版数据，可将 `mvinv_compat.mode` 改为
`delegate_vanilla`，此时本模组只管理已注册的 mod 槽位，不提供跨两套系统的强事务保证。

## 配置

生成的 TOML 使用标准 `#` 注释说明主要区段，便于管理员直接阅读和注解。旧版
`yu_multiverse_inventory.json` 会在首次启动时自动转换为 TOML，并保留为 `.json.migrated` 备份。

首次启动的主要默认值如下：

```toml
schema_version = 1

[share_handling]
enable_gamemode_profiles = false
default_ungrouped_worlds = false
strict_unknown_mod_slots = true
always_write_world_profile = true
restore_last_location = false
default_group_name = "default"

[share_aliases]
inventory = ["inventory_contents", "armor_contents", "off_hand", "ender_chest"]
inventory_plus = ["inventory", "curios", "cosmetic_armor", "trash_slot"]
experience = ["xp", "lvl"]
stats = ["experience", "health", "hunger"]
all = ["inventory_plus", "stats", "bed_spawn", "potion_effects", "recipes", "advancements", "statistics"]

[groups.default]
worlds = ["world", "world_nether", "world_the_end"]
shares = ["all"]

[groups.resource]
worlds = ["resource", "resource_nether"]
shares = ["inventory_plus", "experience", "health"]

[groups.lobby]
worlds = ["lobby"]
shares = ["health", "hunger"]

[migration]
on_profile_conflict = "prefer_current_world"
archive_conflicts = true
notify_admins = true

[advancement_migration.aliases]
# "trailandtales_delight:tat_delight" = "minecraft:trailandtales_delightmain/tat_delight"

[advancement_migration.criterion_aliases."minecraft:trailandtales_delightmain/tat_delight"]
# old_criterion = "trail_and_tales"

[storage]
max_revisions = 20
cache_entries = 1024
cache_megabytes = 128
io_concurrency = 2
queue_capacity = 256
flush_timeout_seconds = 30

[mvinv_compat]
mode = "import_and_replace"
data_directory = "plugins/Multiverse-Inventories"
```

`restore_last_location` 默认关闭。关闭时不会注册上次位置适配器，因此登录、跨维度传送、配置热重载和 profile 迁移均不会恢复旧坐标；已有的 `last_location` profile 文件会保留。仅在明确需要按 world/group 恢复玩家上次位置时才应启用。

世界会同时匹配完整维度 ID、路径和 Bukkit 风格别名。例如主世界可写
`minecraft:overworld`、`overworld` 或 `world`。未被任何组共享的 share 自动使用独立 world profile。

配置会完整解析和校验后才激活。修改 `storage` 后需重启；其他配置可用 `/mwi reload` 热加载，
在线玩家按旧 profile key 到新 profile key 事务迁移。

进度 ID 或 criterion 因数据包、模组安装/卸载、升级而消失时，只跳过当前注册表无法识别的部分；
其他有效进度和其他 share 仍会正常恢复。被跳过的原始 payload 会先写入
`archives/compatibility`，下一次 checkpoint 会保存清理后的当前注册表状态。ID 仍合法但已改名时，可在
`advancement_migration` 中配置显式别名；目标必须存在于当前注册表。循环、空值和非法资源 ID 会使
配置候选被拒绝，`/mwi reload` 会显示别名 dry-run 摘要。

`bed_spawn` 保存的是维度 key，而不是“当前已加载世界”的引用。只要 NBT 字段及维度 ID 语法合法，
按需世界当前未加载、`auto-load: false`，甚至之后已被永久删除，都不会阻断 profile 恢复或触发
quarantine；MWI 不会为校验重生点而加载世界。玩家真正死亡重生时，目标不可用的处理继续交给
Minecraft/服务端平台的原生回退语义。缺字段、错误 NBT 类型、空或非法维度 ID、NaN/Infinity
角度仍会使当前 profile 事务严格失败并回滚。

## 命令

所有命令默认需要权限等级 2：

```text
/mwi list
/mwi info [world|group]
/mwi reload
/mwi status
/mwi group create <name> <worlds-csv> <shares-csv>
/mwi group add-worlds <name> <worlds-csv>
/mwi group add-shares <name> <shares-csv>
/mwi group remove-shares <name> <shares-csv>
/mwi group check-conflicts
/mwi cache stats
/mwi cache invalidate player <uuid-or-known-name>
/mwi repair player <uuid-or-known-name> bed-spawn --dry-run
/mwi repair player <uuid-or-known-name> bed-spawn --clear <world:id|group:id|global:global|all>
/mwi repair player <uuid-or-known-name> advancements --dry-run
/mwi repair player <uuid-or-known-name> advancements --apply
/mwi repair missing-items scan player <uuid-or-known-name> --dry-run
/mwi repair missing-items apply player <uuid-or-known-name>
/mwi repair missing-items scan all --dry-run
/mwi repair missing-items apply all
/mwi repair player <uuid-or-known-name> missing-items --dry-run
/mwi repair player <uuid-or-known-name> missing-items --apply
/mwi quarantine missing-items list <uuid-or-known-name>
/mwi quarantine missing-items restore <uuid-or-known-name> <entry-id>
/mwi recover <online-player> <revision>
```

`repair ... --dry-run` 只读取并校验磁盘 profile，不修改文件、revision counter 或缓存；`--apply` 会先
为每个修改项创建 compatibility archive，再通过原有 checksum 和原子批事务提交，最后清除该玩家的
profile 缓存。离线玩家建议直接使用 UUID；名字只会从服务器已知的 profile cache 解析。

`bed-spawn --dry-run` 会列出每个 canonical profile 的 scope、game mode、profile revision、维度 key、
当前 loaded 状态和结构校验结果，不写磁盘、不分配 revision、也不改变缓存。`--clear` 只允许目标玩家
离线时执行；它不会把 `loaded=false` 自动视为应清除项，必须由管理员显式指定 scope 或 `all`。
实际清除会在同一可重放原子事务中归档原 profile、写入 `present=false`、更新 payload/envelope checksum
和 revision journal，并清除该玩家的全部 profile cache key。

从 1.0.4 升级后，先备份整个 `data/mwi/` 并替换 JAR。确认 1.0.5 已加载后，可撤销为规避登录故障
而设置的 Multiverse `auto-load: true` 与 YuDefender permanent-world 配置，恢复按需卸载；随后在目标世界
保持未加载的状态下验证玩家仍能登录。无需、也不建议为了升级而清除合法的 `bed_spawn`。

缺失物品修复覆盖主背包、盔甲、副手、末影箱、Curios 功能/外观槽、Cosmetic Armor 和
TrashSlot。只有能够从原始 NBT 明确证明 item 或 data component registry key 已不存在时才会
隔离单个 stack；格式、槽位、数量、codec、checksum 或 envelope 损坏仍严格失败。隔离前的原始
stack 会随完整 source profile 原子写入
`data/mwi/archives/compatibility/missing_items/<uuid>/`。restore 会重新验证 registry/component，并在
目标槽非空时拒绝覆盖。
`all` 使用最多 32 名玩家、约 100ms 的有界轮转批次；输出 `truncated=true` 时重复同一命令即可从
下一批继续，避免一次命令长期占用 server thread。

命令中的世界参数支持完整维度 ID、`/`、大小写旧世界名以及逗号分隔的世界选择器；名称包含空格时
使用引号，例如 `"Legacy World/Mining Zone"`。`info`、`group create` 和 `group add-worlds`
会补全已加载的世界、别名及已有选择器。`group create` 的世界列表和 share 列表是两个独立参数，
因此能够分别定位输入错误。

实时物品编辑（需要 2 级权限，目标玩家须在线）：

```text
/inv <player> [world] [page]
/inv inventory <player> [world] [page]
/inv armor <player> [world] [page]
/inv offhand <player> [world] [page]
/inv ender <player> [world] [page]
/inv curios <player> [world] [page]
/inv cosmetic <player> [world] [page]
/inv trash <player> [world] [page]
```

`world` 接受完整维度名及包含 `/` 的名称（例如 `playerworld/xxx`），也接受大小写旧名称；包含空格时
须使用引号。Curios 与时装盔甲编辑器使用
“说明行 + 物品行”布局：悬停彩色说明格可查看功能/外观类型、Curios 标识及槽位编号，正下方才是
对应物品槽；每页最多显示 27 个实际槽位，可用 `page` 分页。不匹配的 Curios 或盔甲物品会被原位
退回并给出提示。每次完整点击都会先作为一个操作实时应用并同步，然后原子持久化；失败时编辑区、
操作者背包和目标实时状态会一起回滚并关闭界面。丢弃键、界面外丢弃和创造模式克隆在编辑器中
被禁用；跨背包移动须使用普通的拿起、放下两次点击，Shift 移动、数字键交换、双击收集和拖拽
分配也会被拒绝，以确保物品移除端先持久化，不能绕过事务边界产生复制。若目标玩家、另一名管理员
或跨维度事务改变了当前页面，下一次点击会被取消，界面刷新到最新状态后要求重新确认；配置或槽位
拓扑变化则会安全关闭旧编辑器，避免把旧槽位写到新的位置。

停用 Multiverse-Inventories 后，可直接打开其离线 JSON 档案进行物品复原：

```text
/mvinvopen all <player-name-or-uuid> <world|group:name> [gamemode] [page]
/mvinvopen inventory <player-name-or-uuid> <world|group:name> [gamemode] [page]
/mvinvopen ender <player-name-or-uuid> <world|group:name> [gamemode] [page]
/mvinvopen armor <player-name-or-uuid> <world|group:name> [gamemode] [page]
/mvinvopen offhand <player-name-or-uuid> <world|group:name> [gamemode] [page]
/mvinvtoken restore
```

世界名可包含 `:`、`/` 和大写字母，例如 `playerworld/xxx`；包含空格时使用引号，例如
`"Legacy World/Mining Zone"`。组档案使用 `group:default`。命令会从离线 MVInv 档案目录补全世界和组名。
默认游戏模式为
`survival`。修改或拿走物品后会合并回最新 JSON，并在第一次修改前保留
`.pre-yu-mwi-recovery.bak` 原始备份。若旧插件目录不在服务端根目录下，可把
`mvinv_compat.data_directory` 设置为绝对路径，例如
`/mnt/disk1/mcserver/youer/1.21.1/online/plugins/Multiverse-Inventories`。

当前服务端已注册的模组物品会以真实物品打开并可直接取走。若档案中的物品 ID 或组件已不被
当前模组版本注册，Minecraft 无法创建对应的真实 `ItemStack`；编辑器会把它显示为可取走的恢复令牌，
并在令牌中无损保存完整原始物品 NBT。安装提供该物品的正确模组/版本后，手持令牌执行
`/mvinvtoken restore` 即可恢复原物品。令牌也可正常移动、保存和取走，不会锁定槽位。

MVInv 5.3.5 的档案格式只包含背包、末影箱、盔甲和副手，不包含 NeoForge capability
中的 Curios、TrashSlot 或 Cosmetic Armor 数据；这些槽位使用
`/mvinvopen curios|trash|fashion <online-player> [world] [page]`（等同对应 `/inv` 编辑器）
恢复本模组已经接管的档案。

从 1.1.0 起，`recover` 仅阻止已有恢复来源的同一玩家、同一物品 share 范围的历史回滚，以免绕过领取凭证；
普通墓碑存档凭证及其他玩家/范围不受影响。来源物品恢复使用 `/mwirecovery`。原有恢复仍会写入新 revision，
配置冲突、NBT 损坏或 apply 失败时回滚、断开玩家并保留诊断数据。

### 从 1.0.2 升级到 1.0.3

停服备份 `data/mwi/` 后替换 JAR。1.0.3 修复两类数据安全问题：

- 登录阶段的 vanilla live state 不再因为 `clean_shutdown=false` 被当作当前 group/world 的权威档案。
  已存在的 canonical profile 始终优先；只有某个 share 从未存在过任何 MWI profile 时，才允许用未知来源的
  login live state 做首次 bootstrap。混合端 pre-login 传送也会先恢复事件 source scope 的 canonical state，
  避免把上一次 session 的 playerdata 写进 lobby/default 等其他组。
- hunger/health/xp 等 scalar payload 的 capture/apply 契约已对齐。合法的 modded saturation（例如 `20.6`）
  不再被 vanilla `[0,20]` 上限误判为损坏；`NaN`/Infinity、负值和明显异常值仍然拒绝。旧 1.0.2
  hunger profile 无需批量改 checksum，升级后可直接读取。

1.0.3 的 prepared login revision 另外保存真实 `canonical_before`，崩溃恢复时不会再把未知 login live
快照写回 canonical target。旧格式 revision 仍保持可读。

无别名时，合法但已过期的进度或 criterion 会在加载时归档并跳过；需要保留改名后的进度时，先配置
alias，执行 `/mwi reload`，再对受影响玩家运行 repair dry-run 和 apply。真正的 NBT 类型错误、非法 ID/
时间、重复原生条目或 checksum 错误仍会严格失败，不会被兼容模式掩盖。

物品注册表漂移默认使用 `item_migration.missing_item_policy = "quarantine_and_continue"`：坏 stack
原位变为空槽，其他槽不移动并继续恢复。需要旧行为时可改为 `strict_fail`。确定性改名可配置：

```toml
[item_migration]
missing_item_policy = "quarantine_and_continue"

[item_migration.aliases]
"oldmod:old_item" = "newmod:new_item"
```

alias 目标必须存在且完整 stack 能由当前 codec 接受，否则仍进入 quarantine；非法 ID、空值和循环
会使 `/mwi reload` 拒绝候选配置。

### 从 1.0.3 升级到 1.0.4

1.0.4 将 hunger exhaustion 的单字段数值异常改为可恢复错误。负数、NaN/Infinity 或超过安全上限的值
会被归一化为 `0`，同时写回玩家实时 `FoodData` 并输出包含玩家、处理阶段、原因、原值和修复值的 WARN。
MWI 会继续当前登录、保存或世界切换事务，不再因为这一项隔离或踢出玩家；缺失字段、错误 NBT 类型及
其他结构性损坏仍保持严格失败。

## 构建与验证

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

生成 jar 位于 `build/libs/`。

GitHub Actions 在 Linux 和 Windows 上执行 `build --dependency-verification strict`。
下载的依赖和插件继续按 `gradle/verification-metadata.xml` 校验；仅对 NeoGradle 在本次构建中
生成的 Minecraft 1.21.1 资源 JAR 和 NeoForge 21.1.244 重编译 JAR 使用精确的本地产物规则，
避免把某台机器的中间产物哈希当作其他平台的下载校验值。这两个内部依赖组只能从
NeoGradle 的本地仓库解析，不能回退到远端仓库。
Linux 的 Minecraft 依赖额外包含 Netty epoll，使用 `gradle/linux.lockfile`；其他系统继续使用
`gradle.lockfile`。升级依赖后须在对应平台运行 `--write-locks` 并提交各自锁文件。

## 模块边界

本仓库实现 NeoForge 权威数据层与 Curios 集成，不包含可选 Bukkit bridge。因此 Vault economy、
PlaceholderAPI、LuckPerms/Bukkit 权限节点和自动读取 MVInv YAML 需要单独的服务端插件桥接；
`economy`、`cosmetic_armor`、`trash_slot`、`fashion_slots`、`accessory_slots` 只有在对应适配器注册后才会被管理。
