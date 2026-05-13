# 健康监控与自愈

## 目标
- 在高密度发车场景下，尽早发现“看起来在运行但实际卡住”的列车。
- 避免误报：把正常停站、正常红灯排队与异常停滞区分开。
- 自动修复采用分级恢复，避免一次性激进动作导致解挂或抖动。
- 对“双方都在线、互相阻塞”的活锁场景优先做非动车解锁恢复；只有超过最终销毁阈值后，自动模式才会销毁互卡对中的稳定 leader。

## 检测项
- `STALL`：信号为 `PROCEED`，且速度持续低于阈值（默认 30 秒）。
- `PROGRESS_STUCK`：`route index` 与“最近经过图节点（`lastPassedGraphNode`）”同时长时间不变（默认 60 秒）。
- 停站排除：`DwellRegistry` 中存在剩余停站时间时，跳过上述检测。

> 说明：在“线路定义只写关键站点、未写全经过 waypoint”的场景下，列车经过中间 waypoint 会更新 `lastPassedGraphNode`，并重置 stuck 计时，避免误报。

## 分级恢复
### STALL
1. `refreshSignalByName(train)`：先重新评估信号与控车指令。
2. `forceRelaunchByName(train)`：若仍持续异常，再升级强制重发。

### PROGRESS_STUCK
1. `refreshSignalByName(train)`：先做轻量恢复。
2. 非 STOP 信号下才允许 `reissueDestinationByName(train)`：该入口必须重新通过 canEnter/acquire，并生成新的 movement authorization token 后才会写 destination。
3. 非 STOP 信号下最后才兜底 `forceRelaunchByName(train)`。
4. 当信号为 `STOP` 且超过宽限后，只执行 refresh/hard-stop，不 reissue destination、不 forceRelaunch。
5. 新鲜 blocker 快照只会在 STOP 宽限窗口内抑制恢复；超过 `health.progress-stop-grace-seconds` 后，即使 blocker 仍被信号 tick 持续刷新，也会进入非动车恢复链，避免“看似合法红灯等待”永久掩盖互卡。

### STOP 互卡解锁
1. 识别条件：两车均为 `STOP`、持续低速、双方 blocker 快照互相包含、方向已知且对向，并且优先要求命中同一个 `CONFLICT:single`。`UNKNOWN` 方向、cycle conflict、NODE/EDGE/`CONFLICT:switcher` 硬 blocker 不会进入 confirmed single 自动销毁 episode。
2. 互卡使用 `DeadlockEpisode` 追踪，key 为 `canonical(trainA, trainB, conflictKey)`；`firstSeenAt` 不随每 tick 重置，blocker 快照短暂抖动时会保留 `health.deadlock-episode-grace-seconds`。
3. 自动分级动作：第一次 `refreshSignal(A/B)`，第二次 `reapplyHardStop(A/B)`，超过 `health.deadlock-destroy-threshold-seconds` 后 `destroy stableLeader`。refresh/hard-stop 只是恢复动作，不再输出“已修复”语义。
4. `stableLeader` 优先选择 RETURN、CREATE、depot exit/depot spawn 附近、progress index 更小、低优先级且无乘客的列车；避开 dwell、departure gate、layover ready、manual maintenance hold、接近终点的列车。
5. destroy 后 episode 标记 `destroyAttempted`，同一 episode 不会连续销毁第二辆；RuntimeDispatchService 等 `GroupRemoveEvent -> handleTrainRemoved` 释放占用后再刷新 survivor。
6. 若 confirmed 条件缺少方向或 single conflict 证据，但 blocker 快照持续互相指向，系统会进入 weaker episode；weaker episode 使用 2 倍销毁阈值，避免 UNKNOWN direction 长期抖动导致永久卡死。
7. 一台列车停在道岔区并作为 blocker 阻塞多车时，健康监控只输出 `SWITCHER_OCCUPANT_BLOCKING_MANY` 诊断，不把该模式升级成 confirmed single，也不直接销毁 occupant。

### 互卡销毁诊断
- Health/runtime 桥接入口使用统一的 alias-aware 解析：先匹配 TrainCarts 精确名，再匹配 runtime active state、FTA 逻辑名、`FTA_TRAIN_NAME`/历史名与 split alias。解析不使用包含、前缀或编辑距离等模糊匹配。
- `destroyTrainByName`、`refreshSignalByName`、`reapplyHardStopByName`、`getTrainState`、`deadlockTrainContext` 共用同一解析结果，避免“state 能找到但 destroy 找不到”的分裂。
- 自动销毁链路会输出 `DEADLOCK_EPISODE_CREATED`、`DEADLOCK_DESTROY_CANDIDATE_SELECTED`、`DEADLOCK_DESTROY_ATTEMPTED`、`DEADLOCK_DESTROY_RESULT` 与 `DEADLOCK_DESTROY_SKIPPED`。若没有销毁，trace 应能区分：未形成 episode、weak 阈值未到、非同一 `CONFLICT:single`、方向 `UNKNOWN`、blocker 快照缺失/过期、解析失败、实体不存在或 TrainCarts destroy API 失败。
- `destroyTrainByName` 只有在解析到 `TrainProperties` 且实体 holder 有效时才返回成功；实体不存在时会记录 `ENTITY_NOT_FOUND`，不会把 no-op 伪报成已修复。

### 手动强制解锁（`/fta health check|heal`）
1. 手动触发时会额外执行一次“互卡优先”解锁，不等待 `progress stuck` 阈值窗口。
2. STOP 互卡动作顺序为 `refresh 双车 -> reapplyHardStop 双车`；不会 reissue destination 或 relaunch。
3. 仅对“互相阻塞”列车对生效；非互卡列车不会被强制重发。
4. 对“非互卡但 STOP 且有 blocker”的列车，会执行单车 `refresh -> reapplyHardStop`，不重新注入运动 destination。

## STOP 宽限
- 当信号为 `STOP` 时，`progress stuck` 在宽限窗口内不判定异常（默认 60 秒）。
- 用于支持咽喉区排队等待，减少“红灯等待被误判为故障”的噪声告警。
- 宽限结束后不再因为 blocker 快照仍新鲜而跳过恢复；这类场景会先走 refresh/hard-stop，但不会 reissue/relaunch，避免红灯状态下被健康监控强制动车。

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
- `health.deadlock-destroy-enabled`
- `health.deadlock-destroy-threshold-seconds`（0 表示禁用最终销毁兜底，默认 60 秒）
- `health.deadlock-destroy-cooldown-seconds`
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
- 设计上已与 `RuntimeSignalMonitor` 解耦，避免“运行时监控先启动、health 尚未初始化”导致周期检查失效。
- `/fta health check` 与 `/fta health heal` 会手动触发即时检查，并附带一次强制互卡解锁尝试。

## 与调度占用的配合
- 信号 tick 在 `canEnter=false` 或硬 STOP 时会保留当前位置保护窗口（当前节点 + rear guard + 当前单线 corridor claim）。
- 目的：撤销 TrainCarts 运动意图的同时，继续持有仍处于单线走廊内的 `CONFLICT:single`，避免对向列车因窗口滑动看不到 blocker 而冒进。
- 硬 STOP 会清空 TrainCarts destination route 和 destination、下发 speedLimit=0、清运动授权 token，并禁止 health reissue，直到下一次 fresh acquire 成功。
- `HealthMonitor` 每次 `tick/check/heal` 都会先收集当前 TrainCarts 存活列车名，并调用 `RuntimeDispatchService.cleanupOrphanOccupancyClaimsWithReport(...)` 清理 progress、运行时占用、layover、departure gate、blocker snapshot 与动态站台缓存残留，然后再执行 `TrainHealthMonitor` 与 `OccupancyHealer`。
- 这条兜底链路用于覆盖 `/train destroyall` 或其他未触发 `GroupRemoveEvent` 的全服列车消失场景：即使事件侧没有逐车回调，`/fta health heal` 与周期 health tick 也能按“当前存活列车集合”释放孤儿 claim 和脱管 progress。
- `OccupancyHealer` 仍负责传统的占用超时/孤儿 claim 诊断；运行时 cleanup 负责与 progress、layover、departure gate 同步，避免只释放 occupancy 但保留调度状态。
- `RuntimeSignalMonitor` 对普通非 FTA TrainCarts 列车只做脱轨安全兜底：明确 `TrainStatus.Derailed` 时销毁实体，但不会把普通列车加入 dispatch、ETA 或 orphan active 集合；事件侧 `MemberRemoveEvent` 也只有在源/目标编组已带 derailed 状态时才清理普通列车。
