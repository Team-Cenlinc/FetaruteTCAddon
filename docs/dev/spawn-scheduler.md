# 自动发车（SpawnManager / TicketAssigner）

本插件的自动发车遵循“需求生成（SpawnManager）与执行（TicketAssigner）分离”的设计：

- `SpawnManager`：只根据线路 `baseFrequency` 生成“发车票据（SpawnTicket）”，不直接生成列车。
- `TicketAssigner`：在运行时根据闭塞/占用结果尝试放行票据；若允许，则从 `CRET` 指定的 Depot 生成列车，并写入 `FTA_*` tags。

## 启用方式

在 `config.yml` 的 `spawn:` 段开启：

- `spawn.enabled: true`
- 调整 `tick-interval-ticks`、`max-spawn-per-tick` 等参数，避免一次性刷车导致卡顿。
- `queued-ticket-max-age-seconds` 与 `pending-layover-max-age-seconds` 默认均为 86400 秒，用于清理长期卡住的发车票据；设为 `0` 可禁用对应硬清理，但生产环境不建议关闭。

`SpawnMonitor` 对单次 tick 的运行时异常做了隔离：若 `TicketAssigner` 在某一轮抛出 `RuntimeException`，该轮会被跳过并记录错误日志，但不会导致后续自动发车循环中断。

## baseFrequency 来源

自动发车优先使用线路交路组基线 `spawn_groups[].baselineSec`；未配置时回退 `Line.spawnFreqBaselineSec`（`/fta line set --freqBaseline`）。

## Route 选择规则

每条线路可配置多条可发车的 `CREATE/OPERATION/RETURN` route，并通过“交路组 + 权重”保证长期比例正确：

- Route 必须能解析到 `CRET` 出库点（即停靠表首行的 depot nodeId）。
- 同一条线路中可配置多个 depot（见下方“线路 Depot 池”），允许跨 depot 混发。
- 若 route 首行不是 `CRET`（例如 `STOP` 作为 layover 复用起点），则不会参与 depot 一致性校验。
- 通过 route metadata 配置：
  - `spawn_weight: <int>`：仅 `OPERATION` 使用的权重（`>0` 参与分摊）。例如 `1` 与 `2` 表示长期约 1:2 的发车比例。
    - `CREATE/RETURN` 会固定按权重 `1` 参与，不读取 `spawn_weight`。
  - `spawn_enabled: true|false`：可选开关；若显式为 `false`，即使有 `spawn_weight` 也不会参与发车。
  - `spawn_group: <string>`：交路组标识。建议先显式创建交路组后再绑定（支持带空格组名，命令可用引号）。
- 若同一线路存在多条候选 route，但没有任何 route 配置 `spawn_weight`（也没有显式 `spawn_enabled=true`），为避免误发车将跳过该线路。
- `CREATE` route 会参与 SpawnPlan（用于“补车入线”）；要求首 stop 必须有 `CRET`。
- `RETURN` route 会参与 SpawnPlan（用于 Layover 复用、折返与站牌预测）。
- 当线路存在 `CREATE` route 时，带 `CRET` 的 `OPERATION` route 会优先走 Layover 复用，避免同方向持续 depot 补车导致过密。

### 显式交路组（Line.metadata）

交路组定义存放在 `Line.metadata.spawn_groups`，推荐使用命令维护，而不是手改 metadata：

- `spawn_groups: [{"name":"<group>","baselineSec":120,"maxOperationTrips":4}, ...]`
- `baselineSec` 可选；未配置时该组回退到 line baseline。
- `maxOperationTrips` 可选；配置后列车在该组完成指定圈数后，会优先进入 RETURN 回库流程。
- route 的 `spawn_group` 只负责“归属哪个组”，不再存储组基线。

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

超过限制时，将由 `SpawnControl` 延迟发车、Layover 复用或 reclaim-return。

### SpawnControl 发车闸门

`SpawnControl` 是本版本统一的动态发车闸门，命名上不再使用 Admission Control。它不生成票据、不直接创建 TrainCarts 实体，只负责给“准备放出一辆车”的动作分配短租约，避免多个入口各自判断容量导致瞬时超发。

纳入同一容量窗口的入口：

- 普通 `SPAWN`：`CREATE/OPERATION` 从 Depot 生成列车。
- `FALLBACK`：Layover pending 超时后的 depot 补发。
- `LAYOVER_REUSE`：折返/回库池中的既有列车复用。
- `RECLAIM_RETURN`：`ReclaimManager` 触发的回库 RETURN 复用。

容量快照由 `running + pending + spawnReserved + layoverReserved + reclaimReturn` 组成：

- `running` 来自 `RuntimeDispatchService` 的 progress 快照，Layover/RETURN 复用会排除当前候选列车，避免把“原车换 route”重复计数。
- `pending` 来自发车队列与 pending layover 队列，用于让已出票但尚未放行的服务先占住容量意图。
- `spawnReserved/layoverReserved/reclaimReturn` 是短租约，默认保留 5 秒，覆盖“TrainCarts 已生成/复用，但运行时 progress 与 Occupancy 还未完成下一轮刷新”的窗口。

拒绝时会输出 `SpawnControl 阻塞` 诊断，包含 line、route、入口类型、reason 以及上述计数字段。发车失败会主动释放租约；发车成功后租约保留到 TTL 自动过期，防止同一 ticket 的正常 spawn 与 fallback 在短时间内重复占用容量。

超过 `spawn_max_trains` 或推断上限时，普通发车、fallback 补发、Layover 复用和 reclaim-return 都会延迟重试；这保证“列车复用”不会绕过线路最大车数。

### headway 分摊（交路组）

先确定交路组基线 `H_group`：

- 若 `spawn_groups[].baselineSec` 已配置，直接使用该值；
- 若未配置，回退 `Line.spawnFreqBaselineSec`（旧配置下可再按 `spawn_group_weight` 拆分）。

再按组内 route 分摊：

`H_route = H_group * W / spawn_weight`，其中 `W = sum(group 内 spawn_weight)`

说明：

- 同组 route 会自动错峰首发（避免同 tick 同时出票）。
- 若未配置 `spawn_group`，系统仍可按起点参与调度，但建议尽快显式分组，便于后续按组做运力治理。

### 配置建议（命令）

- 设置线路发车基准：`/fta line set <company> <operator> <line> --freqBaseline <sec>`
- 设置线路最大车数：`/fta line set <company> <operator> <line> --maxTrains <count>`
- 线路 Depot 管理：
  - `/fta line depot add <company> <operator> <line> <nodeId> [weight]`
  - `/fta line depot remove <company> <operator> <line> <nodeId>`
  - `/fta line depot list <company> <operator> <line>`
- 设置运营路由权重：`/fta route set <company> <operator> <line> <route> --spawn-weight <weight>`
  - `weight<=0` 会清除 `spawn_weight`（仅影响 `OPERATION` 路由的分摊；`CREATE/RETURN` 不受影响）
- 显式管理交路组（推荐）：
  - `/fta route group create <company> <operator> <line> <group> [baselineSec]`
  - `/fta route group list <company> <operator> <line>`
  - `/fta route group info <company> <operator> <line> <group>`
  - `/fta route group set <company> <operator> <line> <group> --baseline <sec>`
  - `/fta route group set <company> <operator> <line> <group> --baseline-clear`
  - `/fta route group set <company> <operator> <line> <group> --max-trips <count>`
  - `/fta route group set <company> <operator> <line> <group> --max-trips-clear`
  - `/fta route group routes <company> <operator> <line> <group>`
  - `/fta route group assign <company> <operator> <line> <group> <route>`
  - `/fta route group unassign <company> <operator> <line> <group> <route>`
  - `/fta route group delete <company> <operator> <line> <group>`
- 绑定 route 到交路组：`/fta route set <company> <operator> <line> <route> --spawn-group <group>`
- 通过 route 命令更新“所绑定组”的基线：
  - `--spawn-group-baseline <sec>`
  - `--spawn-group-baseline-clear`
  - `--spawn-group-clear`

## 运行时门控

`TicketAssigner` 在实际 spawn 前会先经过 `SpawnControl`，再构建一次“gate 占用请求”。占用门控采用 `canEnter(...) -> acquire(...)` 两段判定：

- `canEnter` 必须允许且没有 blocker；若 depot throat、长单线 conflict、lookover 分支或入库/出库混行资源被占住，会 requeue/hold，不会强行生成列车。
- `acquire` 再次确认后才预占用资源；若 acquire 阶段发现 blocker，同样释放 SpawnControl 租约并重试。
- 允许：生成列车，写入 `FTA_*` tags 与 `FTA_ROUTE_INDEX=0`，下发下一跳 destination，并触发一次 `RuntimeDispatchService.refreshSignal(...)`。
- 允许：生成列车后还会基于本次 `acquire` 的资源集合，主动刷新同资源上的其他列车信号（含冲突队列等待列车），缩短“新车出库后他车仍维持旧信号”的窗口。
- 不允许/失败：若 spawn 失败会释放已占用资源，票据按 `spawn.retry-delay-ticks` 延迟后重试。
- 每次重试会把 `SpawnTicket.attempts` +1，并记录 `lastError`（仅用于诊断）。
- 当 `SpawnTicket.attempts >= spawn.max-attempts` 时会放弃该票据并释放 backlog，避免无限重试。
- 队列票据会保留 `firstDueAt`。重试会推进 `dueAt/notBefore` 以避免压住队头，但不会推进 `firstDueAt`；当票据真实年龄超过 `spawn.queued-ticket-max-age-seconds` 时会被丢弃并释放 backlog。
- `OPERATION/CREATE` 票据在放行前会按“line + 方向”计算拥挤度：
  - 评分综合 `edgeBusyRate + routeTrainPressure + lineSignalPressure`。
  - 超过阈值会进入 `congestion-hold` 并延迟重试；低于释放阈值后自动解除（滞回防抖）。
  - `RETURN` 不受该门控影响，优先允许回库以释放网络压力。

### 生命周期标签（交路组运力治理）

动态交路组会给列车写入以下标签：

- `FTA_OP_TRIPS`：当前列车已完成的运营圈数（OPERATION 成功发车时递增）。
- `FTA_OP_MAX`：当前列车允许的最大运营圈数（来自交路组 `maxOperationTrips`）。
- `FTA_SPAWN_GROUP`：列车归属的交路组名。

`ReclaimManager` 会优先检查 `FTA_OP_TRIPS >= FTA_OP_MAX`，满足时触发 RETURN 回库，从而实现“补车入线 -> 运营若干圈 -> 回库摘车”的闭环。

补充说明：

- `CREATE` / `RETURN` 发车会把 `FTA_OP_TRIPS` 重置为 `0`，避免回库车或补车沿用上一段运营圈数。
- `FTA_OP_MAX` 会优先读取 route 上的显式覆盖，再回退到 line 的 `spawn_groups[].maxOperationTrips`。
- 这个标签链路只影响回收判定，不会直接改写 signal / occupancy。

### 出库区块加载
- Depot 出车前会加载 depot 周边区块，并持有约 10 秒的 plugin chunk ticket，避免刚加载即卸载导致 spawn 失败。

### Depot lookover
- Depot 出车会对起步段的道岔执行“lookover”：把道岔分支边一并占用，避免刚出库即被其他列车抢占分支。
- lookover 优先追加“走廊冲突 + 方向”资源以允许同向放行；方向无法判定时回退为“全方向冲突”资源（而不是仅 EDGE），避免道岔门控放宽。
- `TicketAssigner` 会优先以“实际 depot 节点”（`selectedDepotNodeId`/`depotNodeId`）作为 lookover 锚点，而不是盲目使用 `route.waypoints()[0]`。
- 当 route 首节点不是 depot 时，lookover 仍会覆盖 depot 周边道岔区，并使用加深窗口（`max(6, max(lookahead, switcherZone*3))`，上限 24 边）用于拦截“回库车已进道岔区但未离开”场景。

> 当前版本已实现两条执行路径：
> - 停靠表首行为 `CRET`：从 Depot 生成列车。
> - 停靠表首行不是 `CRET`：以首站 nodeId 作为起点，尝试从 Layover 池复用列车。

### Layover 复用触发
- 当 Layover 池暂无可用列车时，票据会进入待命队列（不会持续 requeue 刷屏）。
- 一旦列车进入 Layover，将立即触发一次复用尝试，减少等待延迟。
- 待命队列在同一 `line + terminal` 维度按 route 轮转尝试，避免同权重 route 长期偏斜。
- 即时复用路径（票据尚未进入 pending）同样会在同一 `line + terminal` 维度执行 route 轮转，避免固定处理顺序导致连续命中同一路由。
- 复用时会遍历所有 `readyAt <= now` 的候选列车；若首个候选被门控阻塞，会继续尝试下一个候选。
- **超时刷新**：票据在待命队列中等待超过 300 秒时，不会直接丢弃；系统会刷新等待窗口并记录告警，避免 route 饥饿。
- **降级补发**：若配置了 `spawn.layover-fallback-multiplier > 0`，且等待时长达到 `baseHeadway * multiplier`，仅对“首站可从 depot 发车”的 RETURN 票据尝试补发。首站为站点（含 `DYNAMIC:*:S:*`）时只刷新等待窗口，不做 depot fallback。
- **硬最大等待**：pending 会同时记录首次入队时间。即使 300 秒刷新窗口或非 depot fallback 刷新了当前等待窗口，只要真实等待时间超过 `spawn.pending-layover-max-age-seconds`，系统就会放弃该票据并释放 backlog，避免一周级别的幽灵 pending 在服务器长跑后堆积。
- 若 pending 票据对应 route 定义已删除，系统会直接完成该票据并释放 backlog，避免“幽灵 pending”长期占位。
- fallback 成功后会立即清理 pending，同一张票据不会被重复补发。

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
[FTA] 折返待发 (count=1)
- SURC/MT/MT-1N_ShortC due=已过期 30s attempts=2 error=无可用 Layover 列车
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| due | 原计划发车时间 |
| attempts | 尝试次数 |
| error | 上次失败原因 |

### `/fta spawn reset`

清空发车队列与待发列表，并重置 SpawnPlan/状态（下一 tick 会重新生成）：

```
/fta spawn reset
```

## 相关文件

- `StorageSpawnManager.java`：SpawnPlan 构建与票据生成
- `SpawnControl.java`：统一发车闸门与短租约容量窗口
- `SimpleTicketAssigner.java`：票据放行与 Layover 复用
- `FtaSpawnCommand.java`：诊断命令实现
- `DynamicStopMatcher.java`：DYNAMIC 规范解析
