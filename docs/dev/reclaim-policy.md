# 车辆回收策略 (Reclaim Policy)

## 背景
为实现“有限实体列车的循环复用”，列车在抵达终点站（TERM）后会进入待命（Layover）状态。但随着运营时间推移，如果待命列车过多或长期闲置，会占用服务器资源。
回收策略旨在自动清理这些闲置列车，将其调度回车库（Depot）或销毁点（DSTY）。

## 核心逻辑
由 `ReclaimManager` 定期扫描 `LayoverRegistry`：
1. **闲置超时**：列车待命超过 `reclaim.max-idle-seconds`。
2. **总量超限**：Minecraft 世界中已加载且有效的 TrainCarts group 数超过 `reclaim.max-active-trains`，优先回收待命最久的列车；离线持久化的 `TrainProperties` 不计入活跃车。
3. **生命周期到期**：若列车标签 `FTA_OP_TRIPS >= FTA_OP_MAX`，即使未超时也优先回收。

## 回收动作
- **生成 RETURN 票据**：为待回收列车分配一张 `RETURN` 类型的 `ServiceTicket`。
- **低优先级调度**：回收票据的优先级设为 `-10`（普通客运为 `0`，VIP/Depot发车可能更高），确保回收列车不会抢占正常客运列车的线路资源（Fairness）。
- **强制发车**：通过 `TicketAssigner.forceAssign` 绕过常规发车计划，直接尝试申请占用。
- **标签复位**：RETURN 或 CREATE 再次发车后，`FTA_OP_TRIPS` 会重置为 `0`，`FTA_OP_MAX` 继续沿用 route/group 的配置结果，供下一轮运营判断。

## 配置项
在 `config.yml` 的 `reclaim` 段落：
```yaml
reclaim:
  enabled: true                  # 是否启用回收策略
  max-idle-seconds: 300          # 待命超过多少秒触发回收
  max-active-trains: 50          # 全服最大活跃列车数
  check-interval-seconds: 60     # 检查周期
```

## 优先级与公平性 (Fairness)
调度占用请求现已支持优先级（Priority）：
- **Depot Spawn**：`0` (普通) 或更高 (视配置)
- **Operation (客运)**：`0` (默认)
- **Reclaim (回收)**：`-10` (低优先级)

当多列车竞争同一资源（如单线区间、道岔）时，`OccupancyManager` 的排队队列（Queue）会优先放行高优先级列车；优先级相同时按“先到先得”（FIFO）处理。

## 状态边界
- `ReclaimManager` 只处理 `LayoverRegistry` 里的待命列车，不会主动销毁正在运行的列车，也不会直接改写 signal。
- Reclaim 只按不可变 ticketId 重试自己创建且 routeId 一致的 RETURN attempt；Layover 的位置、readyAt 或 TrainCarts 名称刷新不会改变事务身份。若候选已被普通运营折返等其他 dispatch attempt 认领，本轮跳过，不能复用其 ticketId 改派 RETURN；同站同 tick 的两列车也各自持有独立票据。
- 若某条匹配 RETURN route 在建立 attempt 前即被预检拒绝，会清理该临时 ticket 并继续尝试下一条匹配 route；一旦 handoff attempt 已建立，则固定在原 route 上稳定重试，避免双事务。
- 运营商优先通过候选的 `FTA_ROUTE_ID -> Line -> Operator` 精确回溯；旧数据缺少 route UUID 时，只有全库恰好一个同 code Operator 才允许回退。不同公司使用相同 Operator code 时不会再按遍历顺序误选 RETURN route。
- 如果某列车在 rename / split 过渡态中尚未稳定为单一逻辑列车，通常会先被运行时巡检或 layover 清理链路处理；回收侧只在候选稳定可见时才会分配 RETURN 票据。
- 当一次回收成功后，本轮会立即扣减同方向供给计数，避免同方向候选被连续过回收。

## 常见问题
- **列车为何不回收？**
  - 检查 `enabled` 是否为 `true`。
  - 检查列车所在位置是否有可用的 `RETURN` 线路（即该运营商在当前站点是否有去往 DSTY 的路线）。
  - 检查线路资源是否被高优先级列车长期占用。
- **为什么 maxTrips 到了还没立刻回库？**
  - 回收扫描只在下一个回收 tick 触发。
  - 需要列车已进入 `LayoverRegistry`，才会分配 RETURN 票据。
