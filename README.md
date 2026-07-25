# Yu Multiverse Inventory

NeoForge 1.21.1 服务端多世界玩家数据隔离模组。它模仿 Multiverse-Inventories 的
`WorldSelector -> WorldGroup -> ShareSet` 语义，但所有玩家数据均通过 Minecraft/NeoForge
原生 NBT 读写，可用于纯 NeoForge 服务端以及 Youer、Arclight 等 NeoForge/Bukkit 混合端。

## 环境

- Minecraft 1.21.1
- Java 21
- 编译版本：NeoForge 21.1.235
- 运行范围：NeoForge 21.1.x（最低 21.1.0）
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

## 安装

将 `build/libs/yu_multiverse_inventory-1.0.0.jar` 放入服务端 `mods/`，然后启动一次生成：

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
data/mwi/archives/conflicts/...
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

## 命令

所有命令默认需要权限等级 2：

```text
/mwi list
/mwi info [world|group]
/mwi reload
/mwi group create <name> <worlds-csv> <shares-csv>
/mwi group add-worlds <name> <worlds-csv>
/mwi group add-shares <name> <shares-csv>
/mwi group remove-shares <name> <shares-csv>
/mwi group check-conflicts
/mwi cache stats
/mwi recover <online-player> <revision>
```

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

`recover` 恢复指定 revision 的事务前快照，并立即写入一个新 revision。配置冲突、NBT 损坏、
缺失严格 mod 槽适配器或任一 apply 失败时，玩家旧 live 状态会回滚；为避免继续产生不一致，
服务器会断开该玩家并保留诊断数据。

## 构建与验证

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

生成 jar 位于 `build/libs/`。

## 模块边界

本仓库实现 NeoForge 权威数据层与 Curios 集成，不包含可选 Bukkit bridge。因此 Vault economy、
PlaceholderAPI、LuckPerms/Bukkit 权限节点和自动读取 MVInv YAML 需要单独的服务端插件桥接；
`economy`、`cosmetic_armor`、`trash_slot`、`fashion_slots`、`accessory_slots` 只有在对应适配器注册后才会被管理。
