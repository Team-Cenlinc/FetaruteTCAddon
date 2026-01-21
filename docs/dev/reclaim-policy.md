# 车辆回收策略 (Reclaim Policy)

## 背景
为实现“有限实体列车的循环复用”，列车在抵达终点站（TERM）后会进入待命（Layover）状态。但随着运营时间推移，如果待命列车过多或长期闲置，会占用服务器资源。
回收策略旨在自动清理这些闲置列车，将其调度回车库（Depot）或销毁点（DSTY）。

## 核心逻辑
由 `ReclaimManager` 定期扫描 `LayoverRegistry`：
1. **闲置超时**：列车待命超过 `reclaim.max-idle-seconds`。
2. **总量超限**：全服活跃列车数超过 `reclaim.max-active-trains`，优先回收待命最久的列车。

## 回收动作
- **生成 RETURN 票据**：为待回收列车分配一张 `RETURN` 类型的 `ServiceTicket`。
- **低优先级调度**：回收票据的优先级设为 `-10`（普通客运为 `0`，VIP/Depot发车可能更高），确保回收列车不会抢占正常客运列车的线路资源（Fairness）。
- **强制发车**：通过 `TicketAssigner.forceAssign` 绕过常规发车计划，直接尝试申请占用。

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

## 常见问题
- **列车为何不回收？**
  - 检查 `enabled` 是否为 `true`。
  - 检查列车所在位置是否有可用的 `RETURN` 线路（即该运营商在当前站点是否有去往 DSTY 的路线）。
  - 检查线路资源是否被高优先级列车长期占用。
