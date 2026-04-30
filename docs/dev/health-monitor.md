# 健康监控与自愈

## 目标
- 在高密度发车场景下，尽早发现“看起来在运行但实际卡住”的列车。
- 避免误报：把正常停站、正常红灯排队与异常停滞区分开。
- 自动修复采用分级恢复，避免一次性激进动作导致解挂或抖动。
- 对“双方都在线、互相阻塞”的活锁场景优先做解锁恢复；多轮恢复后仍保持同一互卡关系时，才销毁一侧列车，释放不可恢复的对顶/重叠状态。

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
2. `reissueDestinationByName(train)`：重下发下一跳 destination，修复“目标丢失/被覆盖”。
3. `forceRelaunchByName(train)`：最后兜底重发。
4. 当信号为 `STOP` 且超过宽限后，同样会按分级策略升级（不再只停留在 refresh）。
5. 新鲜 blocker 快照只会在 STOP 宽限窗口内抑制恢复；超过 `health.progress-stop-grace-seconds` 后，即使 blocker 仍被信号 tick 持续刷新，也会进入恢复链，避免“看似合法红灯等待”永久掩盖互卡。

### STOP 互卡解锁
1. 识别条件：列车处于 `STOP`、持续低速、且最近 blockers 显示与另一列车“互相阻塞”。
2. 只由互卡对中的一侧执行（稳定 leader 选择），避免两车同时抢修导致抖动。
3. 分级动作：`refresh 双车 -> reissue 单车 -> relaunch 单车 -> destroy 单车`。
4. `reissue` 或 `relaunch` 返回失败时仍会推进互卡阶段；否则故障列车可能反复回到 refresh，永远到不了 destroy 兜底。
5. `destroy` 只由互卡对 leader 执行，优先删除 leader，失败时再尝试对端；占用释放仍等待 TrainCarts 的移除事件，避免实体尚未消失时后车抢占同一段轨道。

### 手动强制解锁（`/fta health check|heal`）
1. 手动触发时会额外执行一次“互卡优先”解锁，不等待 `progress stuck` 阈值窗口。
2. 动作顺序为 `refresh 双车 -> reissue 双车 -> relaunch`，优先非破坏动作。
3. 仅对“互相阻塞”列车对生效；非互卡列车不会被强制重发。
4. 对“非互卡但 STOP 且有 blocker”的列车，会执行单车 `refresh -> reissue -> relaunch` 兜底链路。

## STOP 宽限
- 当信号为 `STOP` 时，`progress stuck` 在宽限窗口内不判定异常（默认 180 秒）。
- 用于支持咽喉区排队等待，减少“红灯等待被误判为故障”的噪声告警。
- 宽限结束后不再因为 blocker 快照仍新鲜而跳过恢复；这类场景会先走 refresh/reissue/relaunch，只有确认双方互相阻塞且多轮恢复无效时才进入 destroy。

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
- 信号 tick 在 `canEnter=false` 时会收缩占用到“停站保护窗口”（当前节点 + rear guard）。
- 目的：减少红灯等待期间对前方资源的长期占用，降低同向互卡概率。
- `HealthMonitor` 每次 `tick/check/heal` 都会先收集当前 TrainCarts 存活列车名，并调用 `RuntimeDispatchService.cleanupOrphanOccupancyClaimsWithReport(...)` 清理 progress、运行时占用、layover、departure gate、blocker snapshot 与动态站台缓存残留，然后再执行 `TrainHealthMonitor` 与 `OccupancyHealer`。
- 这条兜底链路用于覆盖 `/train destroyall` 或其他未触发 `GroupRemoveEvent` 的全服列车消失场景：即使事件侧没有逐车回调，`/fta health heal` 与周期 health tick 也能按“当前存活列车集合”释放孤儿 claim 和脱管 progress。
- `OccupancyHealer` 仍负责传统的占用超时/孤儿 claim 诊断；运行时 cleanup 负责与 progress、layover、departure gate 同步，避免只释放 occupancy 但保留调度状态。
- `RuntimeSignalMonitor` 对普通非 FTA TrainCarts 列车只做脱轨安全兜底：明确 `TrainStatus.Derailed` 时销毁实体，但不会把普通列车加入 dispatch、ETA 或 orphan active 集合；事件侧 `MemberRemoveEvent` 也只有在源/目标编组已带 derailed 状态时才清理普通列车。
