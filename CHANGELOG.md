# Changelog

## Unreleased

- `bed_spawn` 恢复不再把语法合法但当前未加载的维度 key 判为损坏；按需/已删除世界不会阻断登录、触发 quarantine 或隐式加载世界，结构性 NBT 与非法维度 ID 仍严格失败。
- 新增离线 `bed-spawn --dry-run/--clear` 管理命令；dry-run 零持久化/缓存副作用，clear 使用 revision、双层 checksum、原子归档批事务并失效玩家全部 profile cache。
- 离线名字解析改为只遍历 MWI 已知 UUID 并调用 profile cache 的 UUID 查询，不再因名字参数触发远程 profile lookup。

- hunger exhaustion 出现负数、NaN/Infinity 或明显越界值时，自动归一化为 `0` 并同步修复玩家实时状态；日志记录玩家、阶段、原因、原值和修复值，事务继续执行，不再因此隔离或踢出玩家。

- 统计类型或统计值随模组移除而缺失时跳过该条历史统计并输出明确 WARN，不再中断玩家登录；非法 ID、重复项和结构损坏仍严格失败。

- 将合法但当前不存在的 item/data component registry key 分类为可恢复漂移；逐槽隔离并保留同一 payload 的全部有效物品，覆盖 vanilla、Curios、Cosmetic Armor 与 TrashSlot 的现代/legacy NBT。
- 新增 `item_migration` 策略与物品 ID alias；默认 `quarantine_and_continue`，保留 `strict_fail`，配置支持热重载并拒绝非法值和循环。
- missing-item 原始 stack、source ProfileKey、revision/checksum 和类型化原因写入 `archives/compatibility/missing_items`；归档与 sanitized canonical profile 使用同一可重放原子事务。
- 新增离线 missing-items dry-run/apply、quarantine list/restore 和聚合指标；restore 会重新验证 registry/component 并拒绝覆盖非空目标槽。

- 将合法但已从当前注册表消失的 advancement/criterion 视为可恢复的 schema drift，不再使整个玩家 profile 事务失败或触发 quarantine。
- 新增 advancement 与按目标 advancement 限定的 criterion 显式迁移别名；支持安全热重载、循环与非法值校验，以及 alias 碰撞的确定性最早时间合并。
- 在应用前完成完整 advancement payload 解码；结构、类型、ID、时间、原生重复项和 checksum 损坏仍严格失败并按原事务语义回滚。
- 新增聚合限频 WARN、兼容性指标及 `archives/compatibility` 原始 payload 审计归档。
- 新增离线 advancement repair dry-run/apply 与玩家缓存失效命令；apply 使用现有 revision、checksum 和原子批提交机制。
- 新增 `ProfileStore.invalidate`、`invalidatePlayer` 与无缓存/磁盘副作用的 profile inspection API。
