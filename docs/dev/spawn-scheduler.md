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
- 若同一交路组存在多条 `OPERATION` route，则组内 route 需要配置 `spawn_weight`（或显式 `spawn_enabled=true` 使用默认权重 1）；两个显式交路组各只有一条运营 route 时，可直接使用组 baseline 生成发车计划。
- `CREATE` route 会参与 SpawnPlan（用于“补车入线”）；要求首 stop 必须有 `CRET`。
- `RETURN` route 会参与 SpawnPlan（用于 Layover 复用、折返与站牌预测）。
- 带 `CRET` 的 `OPERATION` route 始终按 SpawnPlan 的 depot 尝试出库；即使同线路存在 `CREATE` route，也不再强制改为 Layover 复用。过密风险由 `SpawnControl`、拥挤度门控与闭塞门控统一处理。

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

TicketAssigner 会优先使用 `spawn_depots` 做均衡选择；若未配置，则回退为 route 的 `CRET` depot。同负载 depot 会按权重轮转，避免同一 tick 内连续票据都命中列表第一个 depot。

动态 depot（例如 `DYNAMIC:OP:D:DEPOT:[1:3]`）会在进入 depot 仲裁前固化为实际股道。选择实际股道时会同时看该线路在各股道的活跃列车数、本 tick 已选择次数、节点占用和 backoff；负载统计再把实际生成股道（如 `OP:D:DEPOT:2`）归并回配置项，避免动态配置永远显示“空闲”或固定命中首个实际股道。

Depot 选择会同时考虑：

- 当前 active train 数量与 depot 权重。
- 本 tick 已经选择过的 depot，避免同一轮连续压到同一个出库点。
- `DepotDispatchCoordinator` 记录的 backoff，刚被 gate/occupancy 阻塞的 depot 会被强烈降权；同线路还有其它候选 depot 时，下一次会优先尝试其它候选。
- 本轮 ready 的 depot 票据会再按本线路各 depot 的在线负载排序：能出库的票多于 `spawn.max-spawn-per-tick` 时，低负载 depot 的票先拿到实体化名额，避免同线路另一个 depot 的交路组长期拿不到名额。

Depot 级仲裁按“实际 depot 节点”执行，而不是按 line 或 route 执行。动态 depot 会先 materialize 成具体 `selectedDepotNodeId`，再进入全局 depot key 仲裁。同一个 depot 同 tick 只放行一张票据，其余票据以 `depot-backoff:<depot_key>` 延迟重试；多线路共享同一 depot 时，仲裁器会记录该 depot 上一次放行的 line，并在无长期饥饿票据时轮转到其它 line。同一线路多个交路组共享同一 depot 时，也会记录上一次放行的 route，避免固定排序导致某个交路组长期压住其它交路组。

自动与手动出车的列车名均使用 `<OP>-<LINE>-<PATTERN><DEST>-<SEQ>`。其中 `DEST` 取解析出的运营目的地 `code` 首字符，而不是 station name 或 route name；`FTA_DEST_CODE/FTA_DEST_NAME` tags 仍保留完整目的地信息。

`spawn.max-spawn-per-tick` 限的是**每拍实体化（调用 `DepotSpawner#spawn`）的列车数**，不是尝试的票数。每张到期票每拍都会试一次：
被闭塞挡在预检的出库票不生成实体、不占名额；折返复用（含 RETURN）不生成实体、不占名额。名额在唯一的实体化入口扣
（常规、RETURN 降级、pending 降级三条出库路径都经过它），用完后本拍其余要出库的票以 `spawn-per-tick-limit` 延迟重入队，且不增加 `attempts`；
pending 里到了降级时刻的折返票则原样留在 pending，不重置降级计时。调用了 spawn 就算用掉名额，哪怕 spawn 本身失败——区块加载与实体生成的开销已经花了。

以前按尝试计名额：一张出不了库的票（例如被车库咽喉挡住）每拍都排在最前、每拍都失败，全网其余的票一张都轮不到。
2026-09-27 实服一张 DS-1F_Full 出库票连试 19 次，同期 WS 在 CHT 的折返票被延后 41 次、一次都没试过，列车在空线路的终点干等。

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

`TicketAssigner` 在实际 spawn 前会先经过 `SpawnControl`，再构建一次“gate 占用请求”。占用门控统一走 `LaunchAuthorizationService`，分为 spawn 前只读 preview 与 spawn 后真实 acquire：

- spawn 前 preview 必须允许且没有 blocker；若所选路径上的 depot throat、长单线 conflict，或入库/出库共享联锁资源被占住，会 requeue/hold，不会强行生成列车。仅位于非选定分支的远端物理边不会扩大本车 Movement Authority。
- spawn 后 acquire 再次确认并写入真实占用；若 acquire 阶段发现 blocker，会释放 SpawnControl 租约、释放该 train 的占用、销毁刚生成的 TrainCarts group，并延迟重试。
- gate 阻塞会记录具体 blocker 资源（如 `NODE:...@train`、`EDGE:...@train`、`CONFLICT:...@train`），并对实际 selected depot 写入短暂 backoff。单股道 depot 若没有其它候选，会在队列诊断中持续显示阻塞资源；若同线路还有其它 depot，后续选择会避开当前 backoff depot。
- 允许：生成列车，写入 `FTA_*` tags 与 `FTA_ROUTE_INDEX=0`，下发下一跳 destination，并触发一次 `RuntimeDispatchService.refreshSignal(...)`。
- TrainCarts spawn pattern 可能继承模板列车 tags；生成后会原子覆盖本次 owner、run/route/line/operator/depot/destination 等插件自有 key，删除本次缺失的可选 destination，并清除模板遗留的 launch/speed 冷却。禁止用 `addTags` 追加同名状态，否则读取顺序可能让新车沿用旧 depot 或旧 route 控车。
- 允许：生成列车后还会基于本次 `acquire` 的资源集合，主动刷新同资源上的其他列车信号（含冲突队列等待列车），缩短“新车出库后他车仍维持旧信号”的窗口。
- 不允许/失败：若 spawn 失败会释放已占用资源，票据按 `spawn.retry-delay-ticks` 延迟后重试。
- 每次重试会把 `SpawnTicket.attempts` +1，并记录 `lastError`（仅用于诊断）。
- 例外：被闭塞挡住（`gate-blocked:*`、`smart-depot-long-single-held`）是推迟不是失败——挡住它的是别的车。这类重试**不增加 `attempts`**，
  但丢掉本次选定的 depot（`SpawnTicket#blockedUntil`），下次重新挑，多 depot 线路的 backoff 才能生效。否则它约 100 秒就会被 `max-attempts` 丢掉：
  表定出库票自己的容差是 300 秒，首班出库票一丢整个交路就没有车（2026-09-27 实服 DS-1F_Full 即如此）。兜底仍在：表定票有自己的到期时刻，
  按间隔发车的票有 `queued-ticket-max-age-seconds`。
- 因 `spawn.max-spawn-per-tick` 超出本 tick 执行容量而延后的票据不算失败，只更新 `notBefore/lastError=spawn-per-tick-limit`，不增加 `attempts`。
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
- 出库车**一律强制 `keepChunksLoaded`**（`TrainCartsDepotSpawner#ensureKeepChunksLoaded`，物理编组生成后同一 tick 内设置；手动 `/fta depot spawn` 同样）。原因：TrainCarts 对未开启常驻加载的车，只要其 5x5 区块区内有未加载区块就在第一个物理 tick 卸载它，出库口附近无人时车会冻结在出库口，而 FTA 把卸载当作移除释放占用，下一班就在同一锚点叠放，苏醒时同坐标复原并被联挂（2026-09-30 实服事故）。spawn pattern 的存档没开常驻加载时会告警一次（按 pattern 去重）；设置失败不会冒泡，只留告警，出库事务不受影响。
- TrainCarts 配置 `keepChunksLoadedOnlyWhenMoving=true` 时，静止且不在等待动作中的车仍可被卸载，上一条对刚出库的车会失效，首次出库时会告警；请保持其为 `false`。
- 出库点附近若仍有离线（已卸载）的编组，会按区块粒度留一条 WARN（每车库最多每 10 分钟一次），**只观测不拦截**。

### Depot Movement Authority
- `TicketAssigner` 会先把本次实际选择的 depot（`selectedDepotNodeId`/`depotNodeId`）写入 route 第 0 个节点，再从这条有向选定路径构建 spawn gate；预览、spawn 后 acquire 与首次 signal refresh 使用同一份路径上下文。
- 若出库路径进入 `SWITCHER` 或显式咽喉，授权会原子覆盖从 depot 到首个可证明清出点的 NODE、EDGE 与所选路径内全部道岔 `CONFLICT`。找不到清出点时 fail-closed，不生成列车。
- 调度图是无向拓扑，但 Movement Authority 不是无向半径：不得把 depot 周边 BFS 扫到的分支或远端物理 EDGE 提升为本车硬授权。否则无关支线会扩大锁闭，甚至因无法证明方向而让所有出库请求永久 STOP。
- 回库车或交叉进路通过双方选定路径共享的 `switcher:*`、单线 section/corridor 等冲突令牌阻止出库；仅占用不在本次授权内的远端物理 EDGE 不应阻止生成。
- 回归覆盖：实际 depot 覆盖 route 原始首节点、短 lookahead 仍完整持有到道岔清出点、分支回库车持有共享 switcher 时阻止生成，以及远端非授权 EDGE 不扩大出库锁闭。

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

## 手动提前出车（按表运行的线路）

`/fta depot spawn <company> <operator> <line> <route> <node>` 对按表运行的线路不直接生成列车，而是把这条线路从该车库开出的下一班提前出票（`TimetableSpawnManager#issueEarly`），照常经发车侧实体化，出库后在车库股道上等到计划时刻再走。不按表运行的线路仍拒绝手动出车。

- **只提前下一班**：在未来 60 分钟内（最早提前量更大时取它）找这条线路从该车库开出的最早一班（交路的出库走行，写首班载客线路也认；或首站就是车库的车次，同一车库不同股道都认）；计划时刻已过、票还在车库等出库的那一班也算下一班。这一班已经出票或交路已有车在跑时回复“已安排出车”，不往后找；整趟取消的车次不算。
- **最早提前量**：离计划发车超过 `spawn.early-spawn-max-lead-minutes`（默认 15 分钟）时不出票，回复最早可提前出车的时刻。
- **挑股道**（`SimpleTicketAssigner#chooseEarlySpawnTrack`，判定在 `EarlySpawnYard`）：出库点写成 DYNAMIC 时在该写法覆盖的股道里挑（刚出库失败、正在退避的股道只在别无可选时才用），固定写法时用命令里指定的股道；股道空不空按占用判断。命令出票时挑一次，发车侧每次出车前（`prepareDepotSpawn`）再按此刻的车库情况复查一次：挡别的车就推迟，到计划时刻后按正点出车；DYNAMIC 写法保留在票上，复查时可以换股道；提前出车不按线路的 `spawn_depots` 均衡。车在车库等候期间（到计划发车为止）别的车对车库的使用都要留出股道：
  - 要出库的票：已在队列里等出库的、发车侧等折返复用（超时会降级从车库出车）的、时刻表的出库走行与首站即车库的车次、按间隔发车线路的预测；只算首站带 CRET 的线路，票没指定出库点而线路配了 `spawn_depots` 时按车库池算。
  - 按表回库的车辆交路：按回库线路 DSTY 的写法，查不到时按时刻表记下的回库点。
  - 正在回库的列车：线路以 DSTY 收尾、剩下只有不停车的途经点。
  - 占掉某条股道后只要有一次使用再无空股道可用，这条股道就不能选；能选的有多条时挑被最少次使用列为可用的那条。选不出时拒绝出车并说明会挡住谁。别的车只是短暂经过股道，剩一条空股道就够，不按件数累计。
  - 晚点、尚未进入回库段的按表列车不在检查范围内：它们到达车库时若股道仍被占用，由闭塞挡在车库外，最多等到提前出的车按计划开走。
- **车库扣车**：出库后挂发车门控 `early-spawn`（每 60 秒重挂，避开门控 180 秒兜底失效；驾驶员接车的门控不覆盖，接车放开后接着扣），健康检查按“按表扣车”看待；列车被推动但没越过车库节点时接着扣，越过才算开走；到计划时刻按会话号放行并刷新信号。
- **重启找回**：放行时刻与交路意图写在列车标签 `FTA_EARLY_HOLD`（`放行 epoch 秒[;交路意图]`）上。启动恢复在打开授权门之前按标签重新扣住、重新绑回车辆交路（交路已有别的车、或时刻表重新发布后交路已不存在时不绑），之后发车检查开始的 5 分钟内每 10 秒再扫一次已加载的列车兜底；放行时刻已过的只清掉标签，按普通在线列车处理。不绑回交路的话，这一班到点时会再出一辆车。

## Schedule Backbone 第一阶段

第一阶段新增了独立的 `ServiceTrip`/`ScheduledStop`/`ScheduleWindow` 计划模型，用于把现有 headway 配置输出成可审阅、可导出的未来车次窗口。模型只保存计划意图：

- `ServiceTrip`：一趟服务车次，包含 source、company/operator/line/route、plannedDeparture、plannedStops、priority 与 depot candidates。
- `ScheduledStop`：计划停靠点，包含 sequence、station/node、planned arrival/departure 与 dwell。
- `TripSource`：`SCHEDULED`、`ON_DEMAND`、`RECOVERY`、`MANUAL`，用于把静态骨干、按需叠加和恢复车次放进同一模型。
- `ScheduleWindow`：某个未来时间窗的计划快照。

这些模型不保存 delay、current index、hold state 或 occupancy claim；这些 mutable 状态仍属于 runtime。

`SchedulePlanner` 目前只覆盖既有配置能表达的 headway 计划：line baseline、route spawn weight、route group baseline/maxTrips 与 line depot pool。它不会生成 TrainCarts `destinationRoute`，也不会改变现有动态下一跳 destination 策略。

### Schedule artifacts/export

`SchedulePlanner` 的输出可以导出为两类 artifact：

- CSV：人工审阅格式，stop-level，每行一个 `ScheduledStop`。CSV 便于运营人员检查班次、停站顺序、depot candidates 与 planned departure，但不作为 runtime source of truth。
- JSON snapshot：机器可读格式，面向后续 Runtime/PIDS/ETA/HUD 消费。JSON 时间字段统一使用 epoch millis，并在字段名中标注 `_epoch_millis`；Optional 为空时省略字段，不输出 `null`。

未来如果引入数据库快照，建议使用 `schedule_windows`、`schedule_trips`、`schedule_stops` 三层结构承接同一模型，但第一阶段不为了表结构阻塞计划模型落地。

## 诊断命令

### `/fta spawn plan [limit]`

查看当前的发车计划预览。存储可用时输出 `ScheduleWindow` 中的未来 `ServiceTrip`，否则回退显示旧的 `SpawnPlan` 服务快照：

```
/fta spawn plan
[FTA] 发车计划 (count=4, built=2026-01-31T12:00:00Z)
- SCHEDULED depart=2026-01-31T12:00:00Z SURC/MT/MT-1N_ShortC depots=SURC:S:PPK:1
- SCHEDULED depart=2026-01-31T12:02:30Z SURC/MT/MT-1S_ShortC depots=SURC:S:OFL:1
- SCHEDULED depart=2026-01-31T12:05:00Z SURC/LT/LT-1N depots=SURC:D:DEPOT:1
- SCHEDULED depart=2026-01-31T12:10:00Z SURC/LT/LT-1S depots=SURC:D:DEPOT:2
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| source | 车次来源：`SCHEDULED` / `ON_DEMAND` / `RECOVERY` / `MANUAL` |
| depart | 计划发车时间 |
| operator/line/route | 服务标识 |
| depots | 候选出库点；带权重时显示为 `nodeId*weight` |

输出会附带下一步动作：

- `[队列]`：执行只读 `/fta spawn queue`。
- `[pending]`：执行只读 `/fta spawn pending`。
- `[diagnose]`：执行只读 `/fta spawn diagnose`。
- `[debug]`：执行只读 `/fta spawn debug`，汇总运行时队列与重试原因。
- `[预览CSV]`：执行 `/fta spawn export csv 12`，只在聊天中预览 CSV 前几行，不写文件、不改变发车队列。
- 每条服务行的 `[Route]` 会打开 route 信息；`[调试]` 只填充 `/fta route debug ...`，需要运维确认后回车。
- 点击动作会统一给 company/operator/line/route/train 等参数加双引号；相关命令参数已改为 quote-aware，允许中文、空格或其他文本继续作为同一个参数解析。

### `/fta spawn export csv [limit]`

使用已有 `ScheduleCsvExporter` 将未来 1 小时的 `ScheduleWindow` 输出为 stop-level CSV 预览。该命令只把表头与前 `limit` 行发到聊天，适合快速核对 planned departure、停站与 depot candidates；完整导出仍建议通过后续文件导出命令或外部工具完成。

### `/fta spawn diagnose [limit]`

查看发车配置诊断。该命令不只显示已经进入 `SpawnPlan` 的服务，也会列出每条 ACTIVE/非 ACTIVE line 下的发车相关 route，并解释它是否会参与发车计划：

```
/fta spawn diagnose
[FTA] 发车配置诊断 (lines=1, groups=2, routes=2)
[FTA] SURC/MT lineBaseline=- status=ACTIVE
  group=short-a baseline=120s routes=MT-A
  group=short-b baseline=180s routes=MT-B
  - route=MT-A op=OPERATION group=short-a weight=- start=SURC:D:DEPOT:1 depots=SURC:D:DEPOT:1,SURC:D:DEPOT:2 participates=是 selectedDepot=SURC:D:DEPOT:2 backoff=- gate=- reasons=-
  - route=MT-B op=OPERATION group=short-b weight=- start=SURC:D:DEPOT:2 depots=SURC:D:DEPOT:1,SURC:D:DEPOT:2 participates=是 selectedDepot=- backoff=depot-backoff:surc:d:depot:1 gate=NODE:SURC:D:DEPOT:1@Train-Depot reasons=-
```

`depots` 来自线路 depot pool；未配置时回退 route 起点。`selectedDepot/backoff/gate` 来自当前 queue/pending 中同 route 的 live ticket 摘要，因此没有排队票据时显示 `-`。这让运维可以在配置诊断页同时看到“候选 depot 是否正确”和“当前是否被某个实际 depot 的 backoff 或 gate 资源卡住”。

常见 `reasons`：

- `缺 baseline`：交路组与线路都没有配置发车基线。
- `缺 spawn_weight`：同一交路组内存在多条 `OPERATION` route，但本 route 未配置 `spawn_weight`，且未显式 `spawn_enabled=true`。
- `CREATE 缺 CRET`：`CREATE` route 首停靠点没有 `CRET`。
- `spawn_group 不存在`：route 绑定了 line 中不存在的显式交路组；这不一定阻止当前实现生成计划，但属于应修复的配置漂移。
- `首站无法解析`：首停靠点既没有固定 `waypointNodeId`，也不是可解析的 `DYNAMIC` stop。

### `/fta spawn debug [limit]`

查看发车运行时汇总。该命令不展开每张票据，而是把 `SpawnPlan`、主队列、pending layover、成功/重试计数与错误分布放在同一屏，适合长期运行后判断是“没有出票”还是“已经出票但持续退避”：

```
/fta spawn debug
[FTA] 发车运行时诊断 (plan=8, queue=4, pending=2, enabled=是, maxSpawn=1, maxGenerate=2, success=120, retry=9)
- reason=depot-backoff:surc:d:depot:1 count=3
- reason=gate-blocked:STOP blockers=NODE:SURC:D:DEPOT:1@Train-Depot count=2
- requeueError=spawn-per-tick-limit count=4
```

字段口径：

| 字段 | 说明 |
|------|------|
| plan | 当前 `SpawnPlan` 中可发车服务数量 |
| queue | 已出票但还在 `SpawnManager` 队列中的票据数量 |
| pending | 等待 Layover 复用或 fallback 的票据数量 |
| success/retry | `SimpleTicketAssigner` 累计成功/重试计数 |
| reason | 当前 queue/pending 按票据状态归纳出的原因，例如 `not-before:45s`、`depot-backoff:*`、`gate-blocked:*` |
| requeueError | 历史重试错误分布，来自执行层诊断计数 |

若 `plan` 正常但 `queue/pending` 长期为 0，通常是尚未到 due、`SpawnMonitor` 没有 tick、队列刚被 reset，或 `max-generate-per-tick/max-backlog-per-service` 让其它服务占满生成窗口。若 `queue/pending` 持续增长，则优先查看 `reason/requeueError`。

### `/fta depot schedule check "<nodeId>" [limit]`

按 depot 节点检查“这个 depot 接下来会不会发车，以及为什么没发”。该命令会校验 nodeId 必须是已注册的 Depot 节点，然后输出三层数据：

- 未来 1 小时 `ScheduleWindow` 中匹配该 depot 的 `ServiceTrip`。
- 当前 `SpawnPlan` 中匹配该 depot 的 `SpawnService`。
- 当前 queue/pending 中已经选中该 depot，或候选 depot 能匹配该节点的票据。

示例：

```
/fta depot schedule check "SURC:D:DEPOT:1" 12
[FTA] Depot 发车检查 (node=SURC:D:DEPOT:1, trips=2, services=2, queue=1, pending=1)
- spawn.enabled=是 tick=20 maxSpawn=1 maxGenerate=2
- trip depart=2026-01-31T12:00:00Z SURC/MT/MT-1N depots=SURC:D:DEPOT:1*2;SURC:D:DEPOT:2
- service SURC/MT/MT-1N headway=2m0s depots=SURC:D:DEPOT:1*2,SURC:D:DEPOT:2
- queue SURC/MT/MT-1N due=已过期 15s notBefore=30s attempts=0 selectedDepot=SURC:D:DEPOT:1 reason=depot-backoff:surc:d:depot:1
- reason depot-backoff:surc:d:depot:1 count=1
```

匹配规则与运行时一致：线路 `spawn_depots` 优先于 route 默认 `CRET`；动态 depot（如 `DYNAMIC:OP:D:DEPOT:[1:3]`）会匹配具体股道节点。若输出显示“没有匹配计划”，重点检查 route 首站/`CRET`、线路 depot pool 与 `/fta spawn diagnose` 的 `depots/reasons`。若有计划但无 queue/pending，则说明静态配置已匹配，但运行时还没有出票或刚被重置。

### `/fta spawn queue [limit]`

查看发车队列（待发票据）：

```
/fta spawn queue
[FTA] 发车队列 (count=3)
- SURC/MT/MT-1N_ShortC due=30s notBefore=30s attempts=0 candidates=SURC:D:DEPOT:1,SURC:D:DEPOT:2 selectedDepot=SURC:D:DEPOT:2 backoff=- gate=-
- SURC/MT/MT-1S_ShortC due=1m15s notBefore=1m15s attempts=0 candidates=SURC:D:DEPOT:1 selectedDepot=- backoff=depot-backoff:surc:d:depot:1 gate=NODE:SURC:D:DEPOT:1@Train-Depot
- SURC/LT/LT-1N        due=2m0s notBefore=2m0s attempts=0 candidates=SURC:D:DEPOT:3 selectedDepot=- backoff=- gate=-
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| due | 计划发车时间（相对当前） |
| notBefore | 最早可发车时间（重试时会延迟） |
| attempts | 尝试次数（0 表示首次） |
| candidates | 线路 depot pool 或 route `CRET` 推导的候选 depot |
| selectedDepot | 本票据已 materialize 的实际 depot；`-` 表示尚未选定 |
| backoff | depot 仲裁或 gate 阻塞产生的退避原因 |
| gate | 上次 gate/occupancy 阻塞资源；包含资源类型、key 与占用 owner |

### `/fta spawn pending [limit]`

查看折返待发票据（等待 Layover 列车或占用释放）：

```
/fta spawn pending
[FTA] 折返待发 (count=1)
- SURC/MT/MT-1N_ShortC due=已过期 30s attempts=2 candidates=SURC:D:DEPOT:1 selectedDepot=SURC:D:DEPOT:1 gate=CONFLICT:switcher:abc@Train-Depot error=gate-blocked
```

输出字段说明：

| 字段 | 说明 |
|------|------|
| due | 原计划发车时间 |
| attempts | 尝试次数 |
| candidates | pending 票据可 fallback 的 depot 候选 |
| selectedDepot | fallback 已 materialize 的实际 depot |
| gate | 上次 gate/occupancy 阻塞资源 |
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

## 静态 Schedule 接入边界

当前 `SchedulePlanner`、`ScheduleWindow`、`ServiceTrip` 与 `ScheduleCsvExporter` 只用于 `/fta spawn plan`、`/fta spawn export csv` 等预览/导出命令。它们会读取存储配置并生成只读未来窗口，但不会写入 runtime spawn queue，也不会改变 `StorageSpawnManager.pollDueTickets(...)` 的出票状态。

`ServiceTripSpawnAdapter` 目前是预留适配层，提供 `ServiceTrip -> SpawnService/SpawnTicket` 的转换工具；主运行时 tick 与 `SimpleTicketAssigner` 尚未调用它。因此静态 schedule 预览/导出不会直接影响 depot spawn、selectedDepot、backoff 或 gate 结果。真正执行路径仍是 `StorageSpawnManager.pollDueTickets(...) -> SimpleTicketAssigner -> Depot spawn gate/occupancy -> TrainCartsDepotSpawner`。

## 按需票（叫车）

`TripSource.ON_DEMAND` 的票由叫车服务出，不由服务生成：不占服务 backlog，服务不在发车计划里也照留，按表运行的交路上照常放行；带入路下标的按需票在交路中途的区间点生成。详见 [player-call.md](player-call.md)。
