# 健康监控与自愈

## 目标
- 在高密度发车场景下，尽早发现“看起来在运行但实际卡住”的列车。
- 避免误报：把正常停站、正常红灯排队与异常停滞区分开。
- 自动修复采用分级恢复，避免一次性激进动作导致解挂或抖动。
- 对“双方都在线、互相阻塞”的活锁场景优先做非动车解锁恢复；健康监控只负责发现 stuck/deadlock，最终销毁必须交由 Smart Dispatcher 做 live hard blocker cycle 前置审查。

## 检测项
- `STALL`：信号为 `PROCEED`，且速度持续低于阈值（默认 30 秒）。
- `PROGRESS_STUCK`：`route index` 与“最近经过图节点（`lastPassedGraphNode`）”同时长时间不变（默认 60 秒）。
- 停站排除：`DwellRegistry` 中存在剩余停站时间，或列车正被按表扣在站里等点（`StationStopCoordinator#holdingForSchedule`）时，跳过上述检测。按表扣车最长 150 秒，不排除的话会在扣留期间派发恢复动作、一路升级到强制重发，把等点的车提前放走。

> 说明：在“线路定义只写关键站点、未写全经过 waypoint”的场景下，列车经过中间 waypoint 会更新 `lastPassedGraphNode`，并重置 stuck 计时，避免误报。

## 分级恢复
### STALL
1. `refreshSignalByName(train)`：先重新评估信号与控车指令。
2. `forceRelaunchByName(train)`：若仍持续异常，再升级强制重发。

### PROGRESS_STUCK
1. 先桥接到 Smart recovery：输出 `SMART_STUCK_TRAIN_DETECTED`、`SMART_RECOVERY_INPUT` 与 `SMART_RECOVERY_ACTION_ORDER`。
2. Smart recovery 按固定顺序尝试 `SMART_RELEASE_SELF_OWNED_STALE_RETAIN -> SMART_RELEASE_PHYSICAL_EDGE_RETAIN -> SMART_DRAIN_UNLOCK -> SMART_FORWARD_UNLOCK -> stale queue/follower hold -> destroy candidate`。任何候选都必须先经过 `SmartDispatcherModeGate`；OBSERVE_ONLY/OFF 只 trace，ENFORCE 才执行 effect gate 允许的动作。
3. 自持 stale/protective retain release 先释放同一逻辑列车的 CONFLICT retain；没有 CONFLICT 候选时，按车体实测覆盖释放已驶离的 NODE/EDGE 上本车的 `PROTECTIVE_RETAIN`（`SMART_RELEASE_PHYSICAL_EDGE_RETAIN`，只在 ENFORCE 下落地）。两条分支都不清 destination、不 invalidate token。
4. drain / forward unlock 只刷新本车本地授权或信号重判，不创建 `DRAIN_THROUGH`，也不把 `TOPOLOGY_EXIT_HINT` 升级为 drain authority。
5. 只有 Smart unlock 都没有候选时，才落到 deprecated fallback：非 STOP 信号下允许 `reissueDestinationByName(train)` / `forceRelaunchByName(train)`；STOP 信号下仍只做非动车 refresh / hard-stop。
6. 新鲜 blocker 快照只会在 STOP 宽限窗口内抑制恢复；超过 `health.progress-stop-grace-seconds` 后，即使 blocker 仍被信号 tick 持续刷新，也会进入 Smart recovery，避免“看似合法红灯等待”永久掩盖互卡。

### STOP 互卡解锁
1. 识别条件：两车均为 `STOP`、持续低速、双方 blocker 快照互相包含、方向已知且对向，并且优先要求命中同一个 `CONFLICT:single`。`UNKNOWN` 方向、cycle conflict、NODE/EDGE/`CONFLICT:switcher` 硬 blocker 不会进入 confirmed single 自动销毁 episode。
2. 互卡使用 `DeadlockEpisode` 追踪，key 为 `canonical(trainA, trainB, conflictKey)`；`firstSeenAt` 不随每 tick 重置，blocker 快照短暂抖动时会保留 `health.deadlock-episode-grace-seconds`。
3. 自动分级动作：第一次 `refreshSignal(A/B)`，第二次 `reapplyHardStop(A/B)`；超过 `health.deadlock-destroy-threshold-seconds` 后只提交 Smart Dispatcher destroy precheck。refresh/hard-stop 只是恢复动作，不再输出“已修复”语义。
4. `stableLeader` 优先选择 RETURN、CREATE、depot exit/depot spawn 附近、progress index 更小、低优先级且无乘客的列车；避开 dwell、departure gate、layover ready、manual maintenance hold、接近终点的列车。
5. Smart Dispatcher 只有在 confirmed live hard cycle 持续超过阈值、无安全 drain-through、无 stale release、无 forward unlock、无 priority scheduling 解法，且目标列车 alias-aware 解析到真实 runtime group 后，才允许 `destroy stableLeader`。
6. destroy 后会执行 post cleanup 与延迟 verification；同一 episode 不会连续销毁第二辆，survivor 会在清理后刷新信号。
7. 若 confirmed 条件缺少方向或 single conflict 证据，但 blocker 快照持续互相指向，系统会进入 weaker episode；weaker episode 仅诊断和重新取证，不再因为等待时间增长进入销毁。
8. 一台列车停在道岔区并作为 blocker 阻塞多车时，健康监控只输出 `SWITCHER_OCCUPANT_BLOCKING_MANY` 诊断，不把该模式升级成 confirmed single，也不直接销毁 occupant。
9. 互卡恢复链（refresh/hard-stop 之后）依次尝试：自持 stale retain 释放 → drain unlock → forward unlock → 排队位让位（A、B 两车都试）。
   - "链要不要停在这个动作上"与"要不要因此跳过销毁"分开判：链的去留按恢复语境——未经测量的"假定有效"要计数，连着两次没解开就往下走；销毁仍按原口径——本轮有动作落地且有效（含假定有效）就不销毁，记 `DEADLOCK_DESTROY_SKIPPED … safe-unlock-applied`。
   - 只有测量出来的有效才报"已修复"，假定有效只算派发了动作。
   - 背景：2026-09-27 实服 OFL，DS 与 MT 互卡，恢复层每 11 秒对 DS 释放一次车后保护占用（假定有效）、下一拍又被占回，17 分钟 320 次都停在第一步、每次报"已修复"；MT 只差 DS 在车库岔口的一个排队位，而排队位让位以前只在单车进度停滞链里，互卡配对只处理 A 车、B 车整个跳过，一次也没轮到。

### 普通长时间停滞 cleanup

普通 cleanup 用于“没有形成双方互卡，但一列受管车在区间中长期无任何进展”的场景。它与 confirmed deadlock 销毁分开取证，默认关闭，也不把正常 Gate Queue 等待当作故障。

1. 候选必须持续没有 route index 或图节点进展，并已实际执行至少三轮分级恢复；第三次 progress 恢复后的同一轮检查不能立刻 cleanup，至少还要经过一个后续采样，并从最后一次 progress/stall recovery mutation 起等待完整的 recovery cooldown 观察窗。恢复链耗尽后会暂停这两类主动恢复，避免每轮重试不断刷新观察时钟。列车仍在移动、处于 dwell/departure gate/layover/manual hold、存在新鲜外部 blocker，或仍有 active unlock reservation 时都拒绝 cleanup。
2. Health 先对整批候选做纯排序，再最多选择一列；执行前由 Runtime 与 Smart Dispatcher 重新解析真实 TrainCarts group、FTA 管控标记、进度索引、速度、乘客树和 hold/blocker 状态。正常排队保护会同时读取最近授权 blocker 快照与 Gate Queue 的直接成员快照，任一证据仍新鲜都不允许删除；读取失败也按 fail-closed 处理。采样后刚恢复的列车因此不会被旧快照删除。
3. 排序优先保护玩家：空车始终排在载客车前；同类中依次优先 `RETURN`、`CREATE`、`OPERATION`，再优先 Depot 相关、未接近 route 终点、较低调度优先级与较早进度的列车。载客车还必须超过单独的更长阈值。
4. 每轮 health check 最多执行一次 cleanup，并有全局冷却。执行仍受 Smart Dispatcher `DESTROY_ACTION` mode gate 控制；只有 `ENFORCE` 会产生实体副作用，`OBSERVE_ONLY/OFF` 只保留诊断。
5. cleanup 不提前释放 NODE/EDGE/CONFLICT claim。只有 TrainCarts 确认实体移除后，`GroupRemoveEvent -> handleTrainRemoved -> OccupancyReleasedEvent` 才释放占用；SignalEvaluator 随后读取该资源的 Gate Queue 队首，并在下一 tick 请求完整授权重评估。没有已登记队列的后继车仍由正常周期信号与 spawn retry 恢复。

6. **等待环**（2026-09-30）：排队等前车的车永远算"还在等"，上面几条对它永远不成立；但几列停滞车首尾相接地互等时谁也等不到，而互卡处理只认两车对等的配对。
   所以普通候选之后（没有普通候选，或它被复审拒绝），再看停滞候选之间的等待关系（`StuckTrainCleanupPolicy#waitCycleTargets`）：节点是停满阈值、静止、记得在等谁的候选（载客宽限、恢复观察中的车也算环上一环），
   能清的只有环上除了"在等"之外全部够格、且等的每一列都在同一个环里的车，按第 3 条的顺序清一列（`STUCK_CLEANUP_WAIT_CYCLE train=… cycle=…`；载客车在环上时先清空车）。
   复审时运行时重新读 blocker（`WaitCycleEvidence`，与策略同一名字口径，含拆分别名）：挡住它的车此刻仍全在环里才放行，否则照旧 `waiting-on-live-blocker`，接着试环上下一列。
   环上某列同时还在等环外的车时清环上另一列；环的尾巴（等着环上的车、自己不在环上）不清；链尾是残留占用、终点待命、停站、扣车或仍在运行的车时不成环，一律不动。
   其余安全门（阈值、恢复耗尽、静止、载客宽限、Smart 复审、全局冷却与每轮一列）不变。
7. **告警自带结论**：停满清车阈值后，`PROGRESS_STUCK` 告警（WARN，必留）末尾附 `清车=…`：`待执行`、`恢复观察中`、`受控停车`、`载客宽限`、`列车在动`、`未开启`，
   排队时为 `等待 X(状态)`（最多点名三列；状态为 `停滞 N秒`、`运行中`、`终点待命`、`停站`、`发车扣车`、`人工扣车`、`残留占用`——运行时别名解析也认不出、且不在存活列车里）或 `排队(未记录前车)`。
   清车执行后另发一条 `停滞清车: 已销毁 …`（成环时附 `等待环=`）。2026-09-30 实服 MT-LN-3832 停了半小时，告警里只有"恢复尝试=38"，看不出清车为何不接——这条就是为此加的。
   调度自愈（残留占用清理）的 WARN 也附上被释放的车名（`trains=`，最多五个）：同一个名字反复出现，说明它的占用在不断复生。
   清车关着时结论是 `未开启`，有记录的前车时后面照样点名 `等待 X(状态)`：这一行常常是唯一留下的现场。
8. **被推一下又停下按整段计**：进展（route index 或图节点变化）一出现，停滞计时与恢复次数就清零；而恢复动作（解锁、刷新放行、重发目的地、重新发车）
   常常只让车往前挪一个节点，车停在下一段前，计时又从零起，600 秒永远到不了。所以恢复动作报告生效后 `RECOVERY_PUSH_WINDOW`（30 秒）内的推进算"被推了一下"
   （`HEALTH_RECOVERY_PUSH_PROGRESS train=… pushes=… stuckSinceSeconds=…`）：停滞清车的计时、恢复冻结与"恢复已用尽"按整段停滞算——计时从第一次被推之前停下的时刻起，
   恢复次数累加各次停车的。车在这段时间之外还在动或又过了节点（自己开起来了）、或进站停站，这段停滞结束，回到原口径。
   互卡判定、STOP 宽限、恢复链的节奏仍按最后一次推进计；排队等活车、受控停车照旧不清。
   这段停滞里的 `PROGRESS_STUCK` 告警另附 `累计停滞=N秒 恢复后推进=K次 再停=<停因>(<持有者> 持有 <资源>)`，"再停"取运行时当前的停车记录，说的是推进之后这次为什么停；
   `等待 X(状态)` 里在等的车本身也在这样的停滞段时，状态写成 `停滞 N秒 累计 M秒`。

LWN 与 HHU 之间的 WS 进路按同一通用规则处理：仍在移动的 corridor owner 保留完整原子授权；对向/后继列车按 Gate Queue 等待。只有 owner 自身长期无进展、没有在等待另一列 live blocker、且安全恢复已经耗尽时，才可能成为普通 cleanup 候选。实现不按线路、车库或站名写特判。

### 互卡销毁诊断
- Health/runtime 桥接入口使用统一的 alias-aware 解析：先匹配 TrainCarts 精确名，再匹配 runtime active state、FTA 逻辑名、`FTA_TRAIN_NAME`/历史名与 split alias。解析不使用包含、前缀或编辑距离等模糊匹配。
- `destroyTrainByName`、`refreshSignalByName`、`reapplyHardStopByName`、`getTrainState`、`deadlockTrainContext` 共用同一解析结果，避免“state 能找到但 destroy 找不到”的分裂。
- 自动销毁链路会输出 `DEADLOCK_EPISODE_CREATED`、`DEADLOCK_GRAPH_SNAPSHOT`、`DEADLOCK_BLOCKER_CHAIN`、`DEADLOCK_DESTROY_PRECHECK`、`DEADLOCK_DESTROY_CANDIDATE_SELECTED`、`DEADLOCK_DESTROY_ATTEMPTED`、`DEADLOCK_DESTROY_RESULT`、`DEADLOCK_DESTROY_POST_CLEANUP`、`DEADLOCK_DESTROY_VERIFY_PASSED/FAILED` 与 `DEADLOCK_DESTROY_SKIPPED`。若没有销毁，trace 应能区分：未形成 episode、weak/protective-only/stale blocker、非同一 `CONFLICT:single`、方向 `UNKNOWN`、blocker 快照缺失/过期、解析失败、实体不存在或 TrainCarts destroy API 失败。
- 普通停滞 cleanup 使用独立的 `STUCK_CLEANUP_DESTROY_ATTEMPTED/RESULT/POST_CLEANUP/VERIFY_*` 事件与 `HEALTH_MONITOR_STUCK_CLEANUP` source，不冒充 confirmed deadlock 遥测。
- `STUCK_LEADER_FALLBACK` 中的后车只提供 stuck-leader 证据，诊断字段使用 `evidenceFollower`；它不是前车 Signal/Occupancy 的真实 blocker。
- `CURRENT_TRAIN_AUTHORITY_FALLBACK` 表示当前等待车自身授权失效，`train` 是当前等待车，`blockerTrain` 是它正在等待的实际 owner；不会把该 owner 写成 `evidenceFollower`，也不会据此设置 `followerStuckLeaderEvidencePresent`。
- `PLANNER_WAIT_FOR_EDGE_FALLBACK` 评估的是被其他车等待的目标，另一辆证据车写为 `evidenceWaiter`；`UNLOCK_NO_RELEASE_TIMEOUT_FALLBACK` 使用中性的 `counterpartTrain`。这些分类不额外授予解锁或销毁权限，仍经过原有安全门。
- `destroyTrainByName` 只有在解析到 `TrainProperties` 且实体 holder 有效时才返回成功；实体不存在时会记录 `ENTITY_NOT_FOUND`，不会把 no-op 伪报成已修复。
- `weaker:*`、stale retain、stale queue、protective-only claim 只能作为诊断或 stale cleanup 候选，不得作为 confirmed destroy 依据。

### 手动强制解锁（`/fta health check|heal`）
1. 手动触发时会额外执行一次“互卡优先”解锁，不等待 `progress stuck` 阈值窗口。
2. STOP 互卡动作顺序为 `refresh 双车 -> reapplyHardStop 双车`；不会 reissue destination 或 relaunch。
3. 仅对“互相阻塞”列车对生效；非互卡列车不会被强制重发。
4. 对“非互卡但 STOP 且有 blocker”的列车，会执行单车 `refresh -> reapplyHardStop`，不重新注入运动 destination。
5. 手动入口的 refresh/hard-stop 同样只表示“恢复动作已执行”，不会计入 `autoFixed/fixedCount`；只有后续 destroy verification 等真实闭环成功才允许输出“已修复”。

## STOP 宽限
- 当信号为 `STOP` 时，`progress stuck` 在宽限窗口内不判定异常（默认 60 秒）。
- 用于支持咽喉区排队等待，减少“红灯等待被误判为故障”的噪声告警。
- 宽限结束后不再因为 blocker 快照仍新鲜而跳过恢复；这类场景会先走 refresh，再尝试清理同车 single 反向残留，最后 hard-stop，但不会 reissue/relaunch，避免红灯状态下被健康监控强制动车。

## 恢复冷却
- 每列车每类恢复动作受冷却时间限制（默认 10 秒）。
- 目的：避免每次健康检查都重复修复，造成动作队列抖动。

## 配置项（`config.yml`）
- `health.check-interval-seconds`
- `health.auto-fix-enabled`
- `health.stall-threshold-seconds`
- `health.progress-stuck-threshold-seconds`
- `health.progress-stop-grace-seconds`
- `health.deadlock-threshold-seconds`
- `health.deadlock-destroy-enabled`（兼容保留的实体列车 destructive cleanup 总开关；同时控制 confirmed deadlock 与普通长时间停滞 cleanup）
- `health.deadlock-destroy-threshold-seconds`（0 表示禁用最终销毁兜底，默认 60 秒）
- `health.deadlock-destroy-cooldown-seconds`
- `health.stuck-cleanup-threshold-seconds`（空车默认 600 秒）
- `health.stuck-cleanup-passenger-threshold-seconds`（载客车默认 1800 秒，且不得短于空车阈值）
- `health.stuck-cleanup-cooldown-seconds`（默认 120 秒；每轮仍最多清理一列）
- `health.deadlock-episode-grace-seconds`（默认 15 秒）
- `health.deadlock-min-stop-seconds`
- `health.blocker-snapshot-max-age-seconds`
- `health.recovery-cooldown-seconds`
- `health.occupancy-timeout-minutes`
- `health.orphan-cleanup-enabled`
- `health.timeout-cleanup-enabled`

> `occupancy-timeout` 仅用于离线列车残留占用；在线列车占用不按“持有时长”清理，避免误删合法闭塞。

## 定时推进机制
- HealthMonitor 由独立 Bukkit 定时任务每 1 秒调用一次 `tick()`。
- 实际检查频率由 `health.check-interval-seconds` 控制：`tick()` 会在该间隔到达时才执行完整检查。
- 控制台对同一列车、同一类型的稳定健康状态每分钟最多提醒一次；活动与恢复之间的状态转换会立即输出，不影响检查、恢复动作或 `/fta health alerts` 的诊断入口。
- 健康告警包含 `train=<列车名>`；未关联具体列车的全局告警使用 `train=-`。节点车头触发日志也包含 TrainCarts 车名和事件类型，便于与运行时规范列车名、改名记录关联。
- 设计上已与 `RuntimeSignalMonitor` 解耦，避免“运行时监控先启动、health 尚未初始化”导致周期检查失效。
- `/fta health check` 与 `/fta health heal` 会手动触发即时检查，并附带一次强制互卡解锁尝试。

## 与调度占用的配合
- 信号 tick 在 `canEnter=false` 或硬 STOP 时会保留当前位置保护窗口（当前节点 + rear guard + 当前单线 corridor claim）。
- 目的：撤销 TrainCarts 运动意图的同时，继续持有仍处于单线走廊内的 `CONFLICT:single`，避免对向列车因窗口滑动看不到 blocker 而冒进。
- 若列车长期 STOP 且请求方向已经与自持 single claim/queue 的旧方向相反，健康恢复只会按当前授权请求清理该同车残留；方向未知、其他列车 blocker、NODE/EDGE 硬占用或普通对向会车仍保持 fail-closed。
- 硬 STOP 下发 speedLimit=0、撤销运动授权并安装 inhibitor，直到完整授权链重新签发有效 token。destination 是否清除由具体停车原因决定；图、交路等安全证据暂缺时保留原 destination 与现场 claim，但旧 destination 不再具备动车权限。
- `HealthMonitor` 每次 `tick/check/heal` 都会先收集当前 TrainCarts 存活列车名，并调用 `RuntimeDispatchService.cleanupOrphanOccupancyClaimsWithReport(...)` 清理 progress、运行时占用、layover、departure gate、blocker snapshot 与动态站台缓存残留，然后再执行 `TrainHealthMonitor` 与 `OccupancyHealer`。
- 普通 stuck cleanup 与互卡 destroy 都不会在发起 `train.destroy()` 时提前释放现场占用；后车恢复以精确实体移除事件为提交点，再由 Gate Queue 队首下一 tick 重评估，避免旧实体仍在轨道上时出现抢占窗口。
- 这条兜底链路用于覆盖 `/train destroyall` 或其他未触发 `GroupRemoveEvent` 的全服列车消失场景：即使事件侧没有逐车回调，`/fta health heal` 与周期 health tick 也能按“当前存活列车集合”释放孤儿 claim 和脱管 progress。
- `OccupancyHealer` 仍负责传统的占用超时/孤儿 claim 诊断；运行时 cleanup 负责与 progress、layover、departure gate 同步，避免只释放 occupancy 但保留调度状态。
- `RuntimeSignalMonitor` 对普通非 FTA TrainCarts 列车只做脱轨安全兜底：明确 `TrainStatus.Derailed` 时销毁实体，但不会把普通列车加入 dispatch、ETA 或 orphan active 集合；事件侧 `MemberRemoveEvent` 也只有在源/目标编组已带 derailed 状态时才清理普通列车。
- FTA 列车的 `MemberRemoveEvent` 当下只隔离、硬停相关物理编组；下一 tick 由 `RuntimeDispatchListener` 分类：源编组与被移除成员此刻所在的编组里仍存活的，逐个进入异常清理（隔离 + 全局现场重建 + 销毁实体），实体移除后隔离解除、重建完成。
  查被移除成员的编组只用 `hasInitializedGroup()`：成员没有编组时 `getGroup()` 会让 TrainCarts 给它新建一个，实体已死时直接抛异常。查不到编组的成员只计入诊断明细 `unresolvedMembers`，源编组照常清理——
  2026-09-28 实服一节车厢实体死亡，分类抛异常，异常编组没进清理，隔离永不解除，全局重建每秒以 `result=abnormal-physical-quarantine` 失败，全网冻结。分类正常时全局冻结约 1 秒。
