# Layover 方向供需控制与重试公平性

本文说明 2026-02 的两项调度改动，目标是降低高频场景下的“单路由连发”和方向性堆车导致的阻塞链。

## 1. 重试票据公平性修复

### 问题
- 原逻辑在 `SpawnTicket#withRetry` 中保留旧 `dueAt`。
- 当某条路由反复 `gate-blocked` 时，该票据会长期停留队头，挤压同权重其他路由，表现为连续尝试同一路由。

### 修复
- 重试时将 `dueAt` 推进到 `notBefore`（重试窗口）：
  - 避免旧票据永久占队头。
  - 让同权重路由在高阻塞下仍有机会被轮询。

## 2. Layover 方向供需回库

### 背景
- 高频时某一方向可能持续累积 `LayoverCandidate`，但该方向的 pending 需求并不高。
- 过量待命列车会放大站前占用竞争，增加 STOP 连锁和健康修复抖动。

### 新策略
- 在 `ReclaimManager` 增加“方向供需失衡回收”：
  - 供给：`LayoverRegistry.snapshot()` 中同方向待命列车数。
  - 需求：`TicketAssigner.snapshotPendingTickets()` 中同方向 pending 票据数。
  - 方向 key：优先用 `TerminalKeyResolver.extractStationKey(...)` 聚合到站点级（忽略股道）。
- 当满足以下条件触发回库：
  - 候选列车闲置时间 >= `120s`
  - `supply - demand > 1`
- 每次回库成功后立即扣减方向供给计数，避免单轮过回收。

### 兼容性
- 回库派发改为调用 `TicketAssigner#forceAssign(...)` 接口，不再耦合具体实现类。

## 3. 与死锁/阻塞的关系

- 该改动不替代 occupancy/信号层的闭塞逻辑。
- 主要通过两条路径减轻高频死锁体感：
  - 减少同一路由重试饥饿（避免“队列只看见一条路由”）。
  - 减少方向性过量待命列车（降低入口冲突与 STOP 链长度）。
