# Smart Dispatcher 监督层

`SmartDispatcherController` 是运行时调度与最终安全发布之间的确定性监督层。它不直接绕过 TrainCarts 控车，不替代 `SignalPublicationGate`，也不使用机器学习或随机策略；所有决策必须来自当前 tick 的可追踪快照、稳定排序和明确的安全原因。

## 模式隔离

`smart-dispatcher.mode` 控制 Smart Dispatcher / Traffic Control Supervisor 的副作用权限：

- `OFF`：不调用前方风险决策器，只输出最小 `SMART_DISPATCH_DISABLED` trace。
- `OBSERVE_ONLY`：默认值。允许输出 `SMART_DISPATCH_*` / `DEADLOCK_*` 诊断 trace，但禁止改变 visible aspect、target speed、destination、movement token、movement inhibitor、occupancy 或 destroy。
- `ENFORCE`：允许通过 effect class gate 的动作执行；`SIGNAL_ADVISORY` 可调整 visible aspect / target speed，`SIGNAL_CONSTRAINT` 可执行 local-only hold / entry block，`OCCUPANCY_MUTATION` 只允许明确 stale retain / queue 清理类 Smart action，`DESTROY_ACTION` 只允许 confirmed hard-cycle destroy。

所有 `DispatchDecision` 必须携带 effect class：

- `DIAGNOSTIC_ONLY`
- `SIGNAL_ADVISORY`
- `SIGNAL_CONSTRAINT`
- `AUTHORITY_PRECHECK`
- `OCCUPANCY_MUTATION`
- `DESTROY_ACTION`

模式相关 trace：

- `SMART_DISPATCH_MODE`
- `SMART_DISPATCH_ACTION_OBSERVED`
- `SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE`
- `SMART_DISPATCH_EFFECT_CLASS`
- `SMART_DISPATCH_DISABLED`
- `SMART_DISPATCH_ENFORCED`
- `SMART_TRAFFIC_CONTROL_GATE`
- `SMART_ACTION_SUPPRESSED_BY_MODE`
- `SMART_ACTION_ALLOWED_BY_EFFECT_GATE`

## 运行边界

每个 `RuntimeSignalMonitor` tick 会先汇总全局状态并输出：

- `SMART_DISPATCH_GLOBAL_SNAPSHOT`
- FTA managed train 数量与已解析 runtime group 数量
- progress / occupancy / blocker snapshot / movement token / inhibitor 计数
- `occupancyVersion` 与 `progressVersion`

随后每列车的局部信号评估仍由既有 runtime 流程完成：canonical `MovementPlanSnapshot` 与 canonical `ExpandedPathPlan` 继续作为 entry lookahead、占用申请和信号发布的事实来源。Runtime 会从同一 expanded path 派生短 `hardAuthorityWindow` 与更远的 `advisoryLookaheadWindow`；Smart Dispatcher 只在最终 visible aspect 发布前读取这些结果并输出监督建议。

## Step 1：Smart traffic control admission

Step 1 不恢复 legacy health recovery 作为主路径。自动恢复、入口阻塞、depot hold、station hold 与 destroy fallback 都必须先解释成 Smart action，再经过 `DispatchEffectClass` 与 `SmartDispatcherModeGate`。

长单线 / single conflict / depot 出口进入 long single 的入口规则：

- 已经在 single/controlled region 内的列车跳过 entry gate，输出 `SMART_ALREADY_INSIDE_REGION_BYPASS_ENTRY_GATE`，继续交给 drain / recovery 判断。
- 空 region 允许进入。
- 对向 occupant、UNKNOWN direction occupant、stalled same-direction leader、leader exit 不可见、下游 blocked region 都会在 ENFORCE 下执行 local-only hold。
- depot 出口进入 blocked long single 时只 HOLD_AT_DEPOT；station departure 进入 blocked long single 时只 HOLD_AT_STATION。
- local-only hold 不清 destination、不 invalidate movement token、不 release occupancy、不 destroy、不 reissue。

Step 1 recovery 核心 trace：

- `SMART_REGION_VIEW`
- `SMART_LONG_SINGLE_VIEW`
- `SMART_DEPOT_EXIT_VIEW`
- `SMART_REGION_DATA_UNKNOWN`
- `SMART_ADMISSION_CHECK`
- `SMART_ADMISSION_ALLOWED`
- `SMART_ADMISSION_BLOCKED`
- `SMART_ADMISSION_REASON`
- `SMART_DEPOT_LONG_SINGLE_HELD`
- `SMART_STATION_DEPARTURE_HELD`
- `SMART_ALREADY_INSIDE_REGION_BYPASS_ENTRY_GATE`

## Step 1：Downstream congestion gate

发车、离站、depot 出口与 destination reissue 前会检查 follower 是否将进入 stuck leader 所在的 single conflict / throat / downstream blocked region。若 follower 仍在 station/depot/entry 且没有安全 hold point，ENFORCE 下不创建进入 blocked region 的 destination。

核心 trace：

- `SMART_DOWNSTREAM_CONGESTION_CHECK`
- `SMART_DOWNSTREAM_CONGESTION_DETECTED`
- `SMART_FOLLOWER_DEPARTURE_HELD`
- `SMART_FOLLOWER_DESTINATION_NOT_ISSUED`
- `SMART_FOLLOWER_ENTRY_BLOCKED_BY_STUCK_LEADER`

## Step 1：legacy recovery 边界

`TrainHealthMonitor` 仍可发现 stuck / deadlock episode，但旧 refresh / hard-stop / relaunch 梯度只保留为 deprecated fallback，并且同样受 Smart mode/effect gate 限制。OBSERVE_ONLY/OFF 只 trace，不执行恢复副作用。ENFORCE 下 destroy 仍必须通过 deadlock precheck、effect gate 与最后一步候选选择；safe drain、follower hold、stale release 或 stale queue purge 可解决时不得 destroy。

## Step 1.6：Smart recovery unlock 顺序

`PROGRESS_STUCK` 进入 Smart recovery 后不再只等待 destroy。当前顺序固定为：

1. `SMART_RELEASE_SELF_OWNED_STALE_RETAIN`：释放同一逻辑列车持有的 stale/protective CONFLICT retain，effect class 为 `OCCUPANCY_MUTATION`。
2. `SMART_DRAIN_UNLOCK`：列车已经在 controlled region 内且没有外部 hard blocker 时，只解除本车本地 inhibitor 并触发信号重判，effect class 为 `SIGNAL_CONSTRAINT`。
3. `SMART_FORWARD_UNLOCK`：`authority-window-exceeded` / movement token pending 且无 blocker 时刷新授权窗口，effect class 为 `SIGNAL_CONSTRAINT`。
4. stale queue purge / follower hold。
5. `SMART_DESTROY_CANDIDATE`：只有前面安全解锁都不可用、confirmed hard cycle 持续超过阈值且无 safe alternative 时才到达。

自持 retain release 只处理 `CONFLICT` claim，不释放 NODE/EDGE 车体占用，不清 destination，不 invalidate token，不 destroy。若同一资源上存在外部 owner 或外部队列条目，release 会 fail closed 并输出 skipped reason。self-owned continuation 判定中，`SELF + PROTECTIVE_RETAIN` / `SELF + HOLD_ONLY` 不得被归类为 external hard blocker 或 opposite single blocker；只有 different owner 的 opposite claim 才能阻止 continuation。

核心 trace：

- `SMART_RECOVERY_ACTION_ORDER`
- `SMART_UNLOCK_ATTEMPTED`
- `SMART_UNLOCK_SKIPPED`
- `SMART_UNLOCK_APPLIED`
- `SMART_STALE_SELF_RETAIN_RELEASE_CANDIDATE`
- `SMART_STALE_SELF_RETAIN_RELEASE_SUPPRESSED_BY_MODE`
- `SMART_STALE_SELF_RETAIN_RELEASE_APPLIED`
- `SMART_STALE_SELF_RETAIN_RELEASE_VERIFY`
- `SELF_OWNED_BLOCKER_FILTERED`
- `SELF_OWNED_RETAIN_IGNORED_FOR_CONTINUATION`
- `SELF_OWNED_OPPOSITE_REJECTED_AS_NOT_EXTERNAL`
- `SMART_DRAIN_UNLOCK_CANDIDATE`
- `SMART_DRAIN_UNLOCK_SUPPRESSED_BY_MODE`
- `SMART_DRAIN_UNLOCK_APPLIED`
- `SMART_DRAIN_UNLOCK_VERIFY`
- `SMART_DESTROY_NOT_REACHED_SAFE_UNLOCK_AVAILABLE`

核心 trace：

- `LEGACY_RECOVERY_DEPRECATED`
- `LEGACY_RECOVERY_SKIPPED_FOR_SMART_CONTROL`
- `SMART_RECOVERY_PATH_SELECTED`
- `SMART_STUCK_TRAIN_DETECTED`
- `SMART_RECOVERY_DECISION`
- `SMART_FORWARD_UNLOCK_CANDIDATE`
- `SMART_FORWARD_UNLOCK_APPLIED`
- `SMART_DRAIN_UNLOCK_CANDIDATE`
- `SMART_DRAIN_UNLOCK_APPLIED`
- `SMART_FOLLOWERS_BLOCKED_BEHIND_STUCK_TRAIN`
- `SMART_DESTROY_CANDIDATE`
- `SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE`
- `SMART_DESTROY_SUPPRESSED_BY_MODE`
- `SMART_DESTROY_EXECUTED`
- `SMART_DESTROY_VERIFY`

## 前方风险与制动预判

`ForwardSignalRiskSnapshot` 描述列车前方可见风险，包括 hard blocker、single conflict、switcher、station/terminal stop、edge speed drop、movement authority physical end 与 stale/protective-only 诊断项。

关键约束：

- `ARTIFICIAL_WINDOW_LIMIT` 只输出 `SIGNAL_ARTIFICIAL_WINDOW_IGNORED_FOR_ASPECT`，不能单独降级 visible signal。
- stale / unknown / protective-only risk 不能直接产生 STOP，只能触发重新取证、stale release 或 authority recalculation 候选。
- 真实物理风险进入 advisory horizon 且当前 hard authority clear 时，优先输出 `PROCEED_WITH_CAUTION` 或 `CAUTION_SPEED_LIMIT`，并给出 `distanceToCaution`、`distanceToStop` 和 `targetSpeed`。
- 只有已经进入 stop distance、movement token invalid、movement inhibited、hard safety unknown、当前 index invalid、required resources missing、active hard blocker 已在当前边界等情况才允许直接 STOP。
- `SIGNAL_CAUTION_REASON` 会同时输出 hard/advisory 资源数量、最近 advisory risk 与最近 hard authority blocker，用来判断红灯来自当前硬窗口还是远端前瞻。

核心 trace：

- `SMART_DISPATCH_FORWARD_RISK`
- `SIGNAL_FORWARD_RISK_SNAPSHOT`
- `SIGNAL_CAUTION_CANDIDATE`
- `SIGNAL_CAUTION_ACCEPTED`
- `SIGNAL_CAUTION_REJECTED`
- `SIGNAL_CAUTION_APPLIED`
- `SIGNAL_CAUTION_SKIPPED`
- `SIGNAL_CAUTION_REASON`
- `SIGNAL_BRAKING_PROFILE`
- `SIGNAL_TARGET_SPEED`
- `SIGNAL_STOP_DISTANCE`
- `SIGNAL_CAUTION_DISTANCE`
- `SIGNAL_PROCEED_TO_STOP_WITHOUT_CAUTION`
- `SIGNAL_PROCEED_TO_STOP_ALLOWED_REASON`

## 确定性优先级

`SmartDispatcherController#scorePriority` 用稳定字段计算竞争同一资源时的优先级，不依赖 `HashMap` 顺序。评分因素包括：

- 已经 physical inside conflict 的列车
- 距 conflict exit 更近的列车
- fresh movement authority
- compatible self-owned claim
- 当前速度较高且安全可控
- 等待时间 aging
- route/service priority
- dwell ready-to-depart
- 会阻塞更多下游列车

禁止项：

- priority 不得覆盖 confirmed hard blocker
- priority 不得覆盖 active opposite conflict
- priority 不得覆盖 invalid movement token
- priority 不得把 stale/protective-only claim 升级为 hard blocker
- priority 不得绕过 `SignalPublicationGate` final safety check

核心 trace：

- `SMART_DISPATCH_PRIORITY_SCORE`
- `SMART_DISPATCH_PRIORITY_REASON`
- `SMART_DISPATCH_STARVATION_AGING`
- `SMART_DISPATCH_PRIORITY_WINNER`
- `SMART_DISPATCH_PRIORITY_LOSER`

## blocker graph 与 destroy 前置审查

Health 只负责发现 stuck/deadlock episode；destroy 必须经过 Smart Dispatcher 审查。审查要求：

- confirmed hard-blocker cycle 已存在
- cycle 持续超过阈值
- 所有 blocker 均为 live hard blocker
- 没有 safe drain-through 候选
- 没有 stale release 候选
- 没有 forward unlock 候选
- 没有 priority scheduling 解法
- 目标列车 alias-aware 解析到真实 runtime group
- 目标列车没有最近 progress
- 目标列车仍是 FTA-managed，或明确确认是 orphan stuck train

`weaker:*`、missing blocker snapshot、stale retain、stale queue、protective-only claim 只能作为诊断，不得确认 deadlock 或触发 destroy。

核心 trace：

- `DEADLOCK_GRAPH_SNAPSHOT`
- `DEADLOCK_BLOCKER_CHAIN`
- `DEADLOCK_CYCLE_CONFIRMED`
- `DEADLOCK_CYCLE_REJECTED`
- `DEADLOCK_STALE_BLOCKER_IGNORED`
- `DEADLOCK_BLOCKER_MISSING`
- `DEADLOCK_BLOCKER_ALIAS_RESOLVED`
- `DEADLOCK_DESTROY_PRECHECK`
- `DEADLOCK_DESTROY_CANDIDATE`
- `DEADLOCK_DESTROY_SKIPPED`

## destroy 后验证

`destroyTrainByName` 成功提交 TrainCarts destroy 后，runtime 会延迟执行 cleanup 与 verification。验证项：

- runtime group 不再存在
- FTA progress / layover managed state 不再引用目标列车
- occupancy manager 不再引用目标列车或 alias
- single conflict queue 不再引用目标列车或 alias
- switcher retain/protected claims 不再引用目标列车或 alias
- deadlock blocker graph 不再包含目标列车
- health episode 以 destroyed 语义关闭

核心 trace：

- `DEADLOCK_DESTROY_POST_CLEANUP`
- `DEADLOCK_DESTROY_VERIFY_PASSED`
- `DEADLOCK_DESTROY_VERIFY_FAILED`
- `DESTROY_INCOMPLETE`
