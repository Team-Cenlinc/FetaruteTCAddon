# 自动发车（SpawnManager / TicketAssigner）

本插件的自动发车遵循“需求生成（SpawnManager）与执行（TicketAssigner）分离”的设计：

- `SpawnManager`：只根据线路 `baseFrequency` 生成“发车票据（SpawnTicket）”，不直接生成列车。
- `TicketAssigner`：在运行时根据闭塞/占用结果尝试放行票据；若允许，则从 `CRET` 指定的 Depot 生成列车，并写入 `FTA_*` tags。

## 启用方式

在 `config.yml` 的 `spawn:` 段开启：

- `spawn.enabled: true`
- 调整 `tick-interval-ticks`、`max-spawn-per-tick` 等参数，避免一次性刷车导致卡顿。

## baseFrequency 来源

自动发车使用 `Line.spawnFreqBaselineSec`（`/fta line set --freqBaseline`）作为目标发车间隔（秒）。

## Route 选择规则

每条线路可配置多条可发车的 `OPERATION` route，并通过权重保证长期比例正确：

- Route 必须能解析到 `CRET` 出库点（即停靠表首行的 depot nodeId）。
- 同一条线路中，所有“可发车 route”必须共享同一个 `CRET depotNodeId`（不允许跨 depot 混发）；否则该线路会被跳过并输出调试日志。
- 通过 route metadata 配置：
  - `spawn_weight: <int>`：权重（`>0` 才视为可发车）。例如 `1` 与 `2` 表示长期约 1:2 的发车比例。
  - `spawn_enabled: true|false`：可选开关；若显式为 `false`，即使有 `spawn_weight` 也不会参与发车。
- 若同一线路存在多条候选 route，但没有任何 route 配置 `spawn_weight`（也没有显式 `spawn_enabled=true`），为避免误发车将跳过该线路。

### headway 分摊

线路的目标 headway 来自 `Line.spawnFreqBaselineSec`（秒），记为 `H`。

对同一线路中参与发车的 routes，设权重之和为 `W = sum(spawn_weight)`，则每条 route 的 ticket 生成间隔为：

`H_route = H * W / spawn_weight`

### 配置建议（命令）

- 设置线路发车基准：`/fta line set <company> <operator> <line> --freqBaseline <sec>`
- 设置运行图权重：`/fta route set <company> <operator> <line> <route> --spawn-weight <weight>`
  - `weight<=0` 会清除 `spawn_weight`（该 route 不再参与自动发车）

## 运行时门控

`TicketAssigner` 在实际 spawn 前会构建一次“gate 占用请求”，并调用 `OccupancyManager.canEnter(...)` 判定是否允许出车：

- 允许：生成列车，写入 `FTA_*` tags 与 `FTA_ROUTE_INDEX=0`，下发下一跳 destination，并触发一次 `RuntimeDispatchService.refreshSignal(...)`。
- 不允许/失败：票据按 `spawn.retry-delay-ticks` 延迟后重试。

> 当前版本仅实现 “Depot 生成列车（SpawnRequest）” 的自动化；`TERM/layover` 的复用与 ServiceTicket 分配属于后续扩展。
