# ETA 模块（ETA Service）

本模块用于给 HUD / 内部文本控件（未来 internal placeholder）提供结构化 ETA 结果。

## 目标
- **唯一入口**：`EtaService`（不做采样，只读快照 + 图/占用快照 + 缓存）
- **运行时采样**：挂到控车 scheduler 的采样阶段（只写快照，不做 ETA 计算）
- **安全性**：ETA 使用占用“只读预览”接口，避免触发排队与运行时状态变化
- **Arriving 语义**：Approaching = Arriving
- **未发车 ETA**：`getForTicket(ticketId)` 仅读取“已生成但未发车”的票据队列快照（不读 SpawnPlan）
- **Layover 修正**：若起点存在待命列车，未发车 ETA 会使用 `readyAt` 修正最早发车时间
- **站牌预测**：站牌会合并未出票服务的预测结果（基于 SpawnManager 的计划与状态）
- **站牌目的地展示**：优先解析站点名称并显示为 `name (operator:station)`，缺失时回退到原始 destination 文本
- **站牌调试字段**：每行会输出 RouteId，便于核对线路解析结果
- **站牌查询输入**：`/fta eta board` 使用 `<operator> <stationCode>`（stationCode 允许跨 operator 重名，默认 horizon=10 分钟）

## 核心类
- `dispatcher/eta/EtaService`：对外查询入口（含 ticket/board 聚合）。
- `dispatcher/eta/EtaResult`：HUD/占位符输出结构。
- `dispatcher/eta/EtaTarget`：目标类型（下一站/指定站台/站点）。
- `dispatcher/eta/runtime/TrainRuntimeSnapshot`：运行时采样数据（包含 worldId + routeUuid，用于查询图快照与 RouteDefinition）。
- `dispatcher/eta/runtime/TrainSnapshotStore`：快照存储。
- `dispatcher/eta/runtime/EtaRuntimeSampler`：采样器（TrainCarts -> SnapshotStore）。
- `dispatcher/schedule/spawn/SpawnForecastSupport`：未出票服务预测（供站牌展示）。

## WaitEstimator（分钟级 delay 估算）
当前实现支持：`queuePos × intervalSec + switchPenaltySec`。
- `queuePos` 来自 `OccupancyQueueSupport#snapshotQueues`（若可用）。
- `intervalSec` 来自 `HeadwayRule#headwayFor`（按资源返回 headway）。
- `earliestTime` 仅作为“下限保护”，避免估算过小，但不作为主要来源（避免抖动）。

## 集成点（运行时）
建议在 `RuntimeSignalMonitor` 中，对每一辆列车：
1) `sampler.sample(...)` 写入快照
2) `dispatchService.handleSignalTick(...)` 执行控车

> 注意：采样频率建议 5~10 tick 一次；ETA 查询端本身还有 TTL 缓存，能进一步降低计算量。

## 站牌为空的常见原因
`/fta eta board` 会合并三类来源：运行中列车快照 + 已生成但未发车的票据 + 未出票服务预测。出现 “rows=0” 常见原因如下：

- **spawn 未启用/计划未刷新**：`spawnSettings.enabled=false` 或 SpawnMonitor 未运行时，SpawnPlan 与预测不会更新。
- **horizon 太短**：ETA 计算超出窗口会被过滤（默认 10 分钟）。
- **站点未映射到图节点**：RouteDefinition 只包含解析到的 nodeId；若站点未配置 `graphNodeId` 或解析失败，匹配会跳过。
- **线路/RouteDefinition 缺失**：route 未能解析到足够节点时会被跳过。
