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
- **回送参与**：RETURN 线路会进入 SpawnPlan 并参与站牌预测；TicketAssigner 对 RETURN 仅尝试 Layover 复用，不从 Depot 生成列车
- **站牌目的地展示**：优先解析站点名称并显示为 `name (operator:station)`，缺失时回退到原始 destination 文本
- **站牌调试字段**：每行会输出 RouteId，便于核对线路解析结果
- **站牌终点区分**：选站口径统一由 `RouteTerminals` 定义（HUD、站牌、公开 API、列车命名共用）。End of Route 为交路的最后一个节点（常为车库或折返线；车库显示为 `LWN Depot`，ID 为 `OP:D:LWN`，与同代码车站 `OP:LWN` 区分），End of Operation 为退出营运前的最后一个车站——最后停靠的车站（回库途中只通过的车站、折返线上的 TERM 都不算），空则回退到 End of Route
- **回送线路**：当 RouteOperationType=RETURN 时，列车**到达该站时**若已越过运营终点，End of Operation 显示为 `回库 / Not in Service`（ID=`OUT_OF_SERVICE`）；运营终点及之前的站显示终点站名——站台乘客与车上看到的是同一个终点
- **站牌查询输入**：`/fta eta board` 使用 `<operator> <stationCode>`（stationCode 允许跨 operator 重名，默认 horizon=10 分钟）

## 核心类
- `dispatcher/eta/EtaService`：对外查询入口（含 ticket/board 聚合）。
- `dispatcher/eta/EtaResult`：HUD/占位符输出结构。
- `dispatcher/eta/EtaTarget`：目标类型（下一站/指定站台/站点）。
- `dispatcher/eta/runtime/TrainRuntimeSnapshot`：运行时采样数据（包含 worldId + routeUuid，用于查询图快照与 RouteDefinition）。
- `dispatcher/eta/runtime/TrainSnapshotStore`：快照存储。
- `dispatcher/eta/runtime/EtaRuntimeSampler`：采样器（TrainCarts -> SnapshotStore）。
- `dispatcher/schedule/spawn/SpawnForecastSupport`：未出票服务预测（供站牌展示）。

## 等待与延误（扣停感知）

ETA 的等待只看运行时**真实**停车状态（`RuntimeDispatchService#getActiveStopState`），不再用占用预判（lookahead preview）——
那套预判与现行准入口径不一致，车被扣住时报“无需等待”，畅通时又会误报阻塞，让 HUD 在进站时丢掉“即将到站”。

| 情形 | 计入 ETA 的等待 | 状态文本 |
|---|---|---|
| 正常停站（停站计时未到期） | 停站剩余秒数（计入 dwell） | 正常 |
| 时刻表早到等点 | 计划发车 − 现在（与站内扣留同口径：早于计划超过扣留上限不等） | 正常，原因 `WAIT` |
| 扣停（信号、占用、授权、尾保等） | 已扣秒数，上限 300 秒（“扣多久估多久”，解除后回落） | 满 1 分钟 `Delayed N m`，原因 `HOLD` + 阻塞类别 |
| 停站超时（门控卡住） | 停站计时结束（按表早到则计划发车）后超过 5 秒仍未发车，从超时那一刻起按扣停处理 | 同上 |
| 票据已过计划发车仍未发出 | 已超秒数，上限同扣停 | 满 1 分钟 `Delayed N m`，原因 `OVERDUE` |

被扣停时不报 Arriving，置信度 LOW。阻塞类别沿用原净空模型标签：站台节点 `PLATFORM`、道岔冲突 `THROAT`、单线走廊 `SINGLELINE`。

“已扣多久”不能用运行时停车状态的 `enteredAt` 量：停车状态在原因、明细或阻挡者变化时整体替换、起点随之重置
（停站期间明细每秒都在变）。采样器另记两个跨替换连续成立的时刻（`TrainRuntimeSnapshot#holdTimeline`）：
连续非例行停车的开始时刻、本站停站计时结束的时刻。是否扣停由 `EtaService#currentHold` 统一判定，公开 API 的扣停事件读同一个结果。

`EtaReason.WAIT` 的含义随之收窄：1.4.0 起只表示可预知的等待（按表等点、票据尚未到点）；占用、信号造成的等待改报 `HOLD`。

## 边内进度

采样器按相邻两次采样的平均速度积分“自上一个经过节点起已行驶的距离”（`TrainRuntimeSnapshot#traveledSinceLastPassedBlocks`），
经过新节点即清零，单次积分间隔按最多 2 秒计。ETA 从剩余路径前端扣掉这段距离——否则列车在一条边上行驶时 ETA 基本不动，过节点时再突跳。

## 停站与途中停车

ETA = 行程 + 停站 + 等待。停站的口径与运行时实际怎么停一致（`RouteStopPlan`，按 `waypoints()` 下标对齐停靠配置）：

| 情形 | 计入 ETA |
|---|---|
| 途中 STOP / TERMINATE 节点 | 该点 `dwell`；没配时取 `RouteStop.DEFAULT_DWELL_SECONDS`（20 秒，与 AutoStation、区间点停车、终到折返同一个值）。此前没配按 0 秒、TERMINATE 不计 |
| 途中 PASS 节点 | 0 |
| 本站停站计时进行中 | 停站计时剩余秒数 |
| 已到站、停站计时尚未注册（停稳前约 3 秒） | 本站计划停站（靠 `StationPresenceTracker` 识别在站）。此前这几秒按 0 计，ETA 先提前一整段停站再跳回 |
| 本站停站计时已结束（关门、等发车许可） | 0，超时部分按扣停处理（见上节） |

行程按途中停车点**拆段**：每段以进站限速收尾（接入进站限速配置时），下一段从静止起步。一路不停地算会少算每站的制动与起步
（默认参数约 5 秒一站）。是否停车只看 `passType`，不看 `dwell` 是否为 0。

## DYNAMIC 站台

ETA 与控车读同一份有效节点（`RuntimeDispatchService#resolveEffectiveWaypointsForEvent`）：

- 已选台：按选中的股道估算。拿交路里声明的占位股道（`OP:S:CODE:fromTrack`）来问 `PlatformNode` 也能定位到这一站、按实际股道算。
- 尚未选台：车站级估算——该站在图上存在的每条候选股道（与选台共用 `DynamicStopMatcher#candidateNodes`）各算一遍取最早。
  占位股道只是范围里的第一条，未必是列车会去的那条，甚至未必存在。
- 已经知道下标时用 `EtaTarget.StopIndex`（交路 0 起下标，与公开 API 停靠序号同一口径），不必按节点反查；
  同一节点在交路里出现两次时，按节点反查只能找到第一次。

## 其它口径
- 票据（未发车）ETA 同样计入中途停站时间，并从静止起步、途中停车点同样拆段。
- 无限速边的默认速度取 `graph.default-speed-blocks-per-second`，与控车同一个配置值。
- 实服第二十七轮 1067 段实测：站内“进站到发车”中位 24 秒（停站配置 20 秒），行车实测比模型快约 13%，两者大致抵消，
  因此加减速参数保持不变；偏早与卡住的主因是扣停与票据超时未计入、以及边内进度缺失。

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
