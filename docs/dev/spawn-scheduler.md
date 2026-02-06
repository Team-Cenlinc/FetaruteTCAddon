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
- 同一条线路中可配置多个 depot（见下方“线路 Depot 池”），允许跨 depot 混发。
- 若 route 首行不是 `CRET`（例如 `STOP` 作为 layover 复用起点），则不会参与 depot 一致性校验。
- 通过 route metadata 配置：
  - `spawn_weight: <int>`：权重（`>0` 才视为可发车）。例如 `1` 与 `2` 表示长期约 1:2 的发车比例。
  - `spawn_enabled: true|false`：可选开关；若显式为 `false`，即使有 `spawn_weight` 也不会参与发车。
- 若同一线路存在多条候选 route，但没有任何 route 配置 `spawn_weight`（也没有显式 `spawn_enabled=true`），为避免误发车将跳过该线路。
- `RETURN` route 默认参与 SpawnPlan（用于 Layover 复用与站牌预测），但不会从 Depot 生成列车。

## 线路 Depot 池（多 Depot 支持）

可在 `Line.metadata` 中配置 `spawn_depots` 以集中管理一条线路的多个 depot：

- `spawn_depots` 支持字符串列表或对象列表：
  - `["OP:D:DEPOT:1", "OP:D:DEPOT:2"]`
  - `[{"nodeId":"OP:D:DEPOT:1","weight":2},{"nodeId":"OP:D:DEPOT:2"}]`
- `weight` 用于平衡分配（默认 1）。
- `enabled=false` 的条目会被忽略。

TicketAssigner 会优先使用 `spawn_depots` 做均衡选择；若未配置，则回退为 route 的 `CRET` depot。

## 线路最大车数（软限制）

可在 `Line.metadata` 中配置 `spawn_max_trains`：

- `spawn_max_trains: <int>`

未显式配置时，系统会尝试根据 `Line.spawnFreqBaselineSec` 与线路运行时间推断最大车数（优先使用 route.runtimeSeconds）。

超过限制时，将延迟发车并重试（不会影响 Layover 复用）。

### headway 分摊

线路的目标 headway 来自 `Line.spawnFreqBaselineSec`（秒），记为 `H`。

对同一线路中参与发车的 routes，设权重之和为 `W = sum(spawn_weight)`，则每条 route 的 ticket 生成间隔为：

`H_route = H * W / spawn_weight`

### 配置建议（命令）

- 设置线路发车基准：`/fta line set <company> <operator> <line> --freqBaseline <sec>`
- 设置线路最大车数：`/fta line set <company> <operator> <line> --maxTrains <count>`
- 线路 Depot 管理：
  - `/fta line depot add <company> <operator> <line> <nodeId> [weight]`
  - `/fta line depot remove <company> <operator> <line> <nodeId>`
  - `/fta line depot list <company> <operator> <line>`
- 设置运行图权重：`/fta route set <company> <operator> <line> <route> --spawn-weight <weight>`
  - `weight<=0` 会清除 `spawn_weight`（该 route 不再参与自动发车）

## 运行时门控

`TicketAssigner` 在实际 spawn 前会构建一次“gate 占用请求”，并调用 `OccupancyManager.acquire(...)` 预占用资源：

- 允许：生成列车，写入 `FTA_*` tags 与 `FTA_ROUTE_INDEX=0`，下发下一跳 destination，并触发一次 `RuntimeDispatchService.refreshSignal(...)`。
- 不允许/失败：若 spawn 失败会释放已占用资源，票据按 `spawn.retry-delay-ticks` 延迟后重试。
- 每次重试会把 `SpawnTicket.attempts` +1，并记录 `lastError`（仅用于诊断）。
- 当 `SpawnTicket.attempts >= spawn.max-attempts` 时会放弃该票据并释放 backlog，避免无限重试。

### 出库区块加载
- Depot 出车前会加载 depot 周边区块，并持有约 10 秒的 plugin chunk ticket，避免刚加载即卸载导致 spawn 失败。

### Depot lookover
- Depot 出车会对起步段的道岔执行“lookover”：把道岔分支边一并占用，避免刚出库即被其他列车抢占分支。
- lookover 优先追加“走廊冲突 + 方向”资源以允许同向放行；方向无法判定时回退为 EDGE 资源。

> 当前版本已实现两条执行路径：
> - 停靠表首行为 `CRET`：从 Depot 生成列车。
> - 停靠表首行不是 `CRET`：以首站 nodeId 作为起点，尝试从 Layover 池复用列车。

### Layover 复用触发
- 当 Layover 池暂无可用列车时，票据会进入待命队列（不会持续 requeue 刷屏）。
- 一旦列车进入 Layover，将立即触发一次复用尝试，减少等待延迟。
- **超时清理**：票据在待命队列中等待超过 300 秒仍无法复用时，会被丢弃并记录警告日志，防止无候选列车的票据永久积压。

### DYNAMIC 首站支持

Route 首站可以是 DYNAMIC 类型（动态选择站台/轨道）：

- 若首站的 `waypointNodeId` 为空但 `notes` 包含 DYNAMIC 指令，会解析 DYNAMIC 规范并生成 placeholder NodeId
- 例如：`DYNAMIC:SURC:S:PPK:[1:3]` 生成 placeholder `SURC:S:PPK:1`
- Layover 复用时，placeholder 与实际列车位置通过"站点级匹配"（同站不同站台）进行匹配

详见 [DYNAMIC Route Stops](dynamic-route-stops.md)

## 诊断命令

### `/fta spawn plan [limit]`

查看当前的发车计划（SpawnPlan）：

```
/fta spawn plan
[FTA] 发车计划 (count=4, built=2026-01-31T12:00:00Z)
- SURC/MT/MT-1N_ShortC headway=2m30s depot=SURC:S:PPK:1
- SURC/MT/MT-1S_ShortC headway=2m30s depot=SURC:S:OFL:1
- SURC/LT/LT-1N        headway=5m0s  depot=SURC:D:DEPOT:1
- SURC/LT/LT-1S        headway=5m0s  depot=SURC:D:DEPOT:2
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| operator/line/route | 服务标识 |
| headway | 发车间隔（如 `2m30s` 表示每 2 分 30 秒发一班） |
| depot | 出库点 NodeId（DYNAMIC 首站显示 placeholder） |

### `/fta spawn queue [limit]`

查看发车队列（待发票据）：

```
/fta spawn queue
[FTA] 发车队列 (count=3)
- SURC/MT/MT-1N_ShortC due=30s notBefore=30s attempts=0
- SURC/MT/MT-1S_ShortC due=1m15s notBefore=1m15s attempts=0
- SURC/LT/LT-1N        due=2m0s notBefore=2m0s attempts=0
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| due | 计划发车时间（相对当前） |
| notBefore | 最早可发车时间（重试时会延迟） |
| attempts | 尝试次数（0 表示首次） |

### `/fta spawn pending [limit]`

查看折返待发票据（等待 Layover 列车或占用释放）：

```
/fta spawn pending

### `/fta spawn reset`
清空发车队列与待发列表，并重置 SpawnPlan/状态（下一 tick 会重新生成）：

/fta spawn reset
[FTA] 折返待发 (count=1)
- SURC/MT/MT-1N_ShortC due=已过期 30s attempts=2 error=无可用 Layover 列车
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| due | 原计划发车时间 |
| attempts | 尝试次数 |
| error | 上次失败原因 |

## 相关文件

- `StorageSpawnManager.java`：SpawnPlan 构建与票据生成
- `SimpleTicketAssigner.java`：票据放行与 Layover 复用
- `FtaSpawnCommand.java`：诊断命令实现
- `DynamicStopMatcher.java`：DYNAMIC 规范解析
