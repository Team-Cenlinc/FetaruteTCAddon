# Fable 诊断报告：动态调度（2026-09-18）

- 审计对象：HEAD `98cd49f`（`f0dafc8` 与 `98cd49f` 尚未上过实服）
- 证据：`../fetarute_experimental/logs/latest.log` = 第二十六轮（jar `3b77e88`，cap=24，21:30–23:16，预算丢弃 1,258,085 行）；
  `2026-09-17-3.log.gz` = 第二十五轮（`3b77e88`）；`2026-09-17-2.log.gz` = 第二十四轮（`fa79c1e`）
- 工作区**未改动**（`git status` 只有本目录）。A/B 全部在 scratchpad 副本里做；补丁在 `.handoff/fable-chain-fix.patch`，**未合入**。
- 实服 config **未动**。

---

## 结论（按优先级）

### F1 [CONFIRMED] 恢复链在第一步空转：「applied」被当成「effective」，割排队位一次都没轮到

**失败场景**：停车列车 A 触发 `PROGRESS_STUCK` → 链第一步 `applySmartSelfOwnedStaleRetainRelease` 走到 Phase 4
`applyPhysicalEdgeRetainRelease`，放掉 A 自己尽头处的两个尾保 → 返回 5 参 `SmartRecoveryActionResult(true, true, …)` →
`SmartRecoveryEffectiveness.defaultFor(applied=true)` 给出 `effective=true, reason=legacy-result-assumed-effective` →
`TrainHealthMonitor.shouldHoldForSafeCandidate` 见 effective 即**清零失败计数并 return** → 链结束。
下一 tick `handleSignalTick` 对停车列车重跑 `retainStopOccupancy`（layover 车走 `retainLayoverReadyOccupancy`），
把刚放掉的尾保**原样拿回**。每次恢复尝试都重复这一幕，排在链末的 `SMART_QUEUE_POSITION_YIELD` 永远不被调用。

**证据（第二十六轮）**：
- `SMART_PHYSICAL_EDGE_RETAIN_RELEASED`（必留）335 次，按 (train, resources) 去重后只有 8 组，**98% 是重复**：
  `SURC-WS-LC-1037` 171 次、`1857` 47 次、`0262` 41 次、`6219` 40 次，资源集每次一字不差。
  第二十四轮 171 次 / 16% 重复；第二十五轮共 2 次。
- 账本样本：`EDGE:SURC:S:CHT:3:001~002@6219` `23:02:02 release` → `23:02:03 acquire`。
- `SMART_QUEUE_POSITION_YIELD_SKIPPED` 按 (train) 去重、每条跳过出口都留痕（只有 `missing-input` 不留，而车名非空）；
  6219 在 38 次恢复尝试 / 17 分钟里 **0 行** ⇒ 该动作从未被调用。全轮仅 2 行（1037、0267，均 `not-queue-only-blocked`，正确）。
- 健康摘要 `HealthMonitor tick … stuck=2 fixed=2`：两辆都还卡着却计为「已修复」——第十五轮那条「已修复是假的」在链这一层复现。

**排除的替代解释**：
- 「链停在第三步 forward-unlock（candidate 但被方向拒绝）」——`SMART_FORWARD_UNLOCK_BLOCKED_BY_HARD_BLOCKER` 返回 `candidate=false`，
  且 `dff0cde` 已让「候选未落地」计数；链会继续。
- 「yield 跑了但静默跳过」——所有跳过出口都 trace；0 行只能是没调用。
- 「`SMART_RECOVERY_DECISION` 说明了什么」——它不在必留名单，全轮只剩 2 行；承重证据是必留的 RELEASED 计数。

**最窄修复（已在副本验证）**：`shouldHoldForSafeCandidate` 把 `reason == ASSUMED_EFFECTIVE_REASON` 视为**未测量**，
走 `countSafeCandidateFailure(…, "assumed-effective")`（沿用阈值 2），而不是清零计数。
- 新用例 `TrainHealthMonitorTest.assumedEffectiveButRepeatingCandidateMustNotStarveLaterRecoveryActions`：
  修前 **1/80 红**（`applySmartQueuePositionYield` 未被调用）；修后 **80/80 绿**；
  变异（`SAFE_CANDIDATE_FAILURE_THRESHOLD = Integer.MAX_VALUE`）→ 它与 `neverAppliedCandidate…` **同时红**（变异打在链续行判据上）；
  `DispatchObserverSideEffectTest` 3/3 绿；副本 `javap` 方法数仍 **997**。
- **没跑全量 `check`**，只跑了这两个类。
- 配套但**未做**：① `SMART_RECOVERY_SAFE_CANDIDATE_FAILED_COUNT` 不在必留名单——不加进去（按 (train, action, failureKind) 去重）
  这条修复在下一轮日志里就不可见；② Phase 4 释放要有 per-(车, 资源集) 冷却（与 yield 同款），否则仍每秒空转一次；
  ③ 长期应把 Phase 4 的「有效」定义成「被挡的**那辆**车动了」，而不是「账本变了」。

### F2 [CONFIRMED] CHT 的 1026 秒是**排队位死锁**；交接假设「LAYOVER_RECHECK 没实现」不成立；`f0dafc8` 救不了这一对

**现场**：`SURC-WS-LC-6219` 22:58 到 `SURC:S:CHT:3`（单站台尽头折返）进 layover 池，
`Layover 发车受阻: train=6219` **2080/2080** 次 blocker 都是 `CONFLICT:switcher:SWITCHER:Towny:-137:90:368@SURC-WS-LC-1037`
（每秒一次，22:58:50–23:16:03，两条 CHT 始发线路各一半）。所以 layover **一直在被重检**——重检的实现是
`SimpleTicketAssigner` 每 spawn tick 调 `dispatchLayover`，不是 `RetryTrigger` 枚举。

**1037 拿的是排队位，不是占用**：
- `SMART_RESOURCE_LIFECYCLE` 里 1037 **从未** acquire 过 368（acquire 方向账本完整）；其 `holds=` 7 项 = `holdsByRole` 之和，无 368。
- 等待图：`SMART_DISPATCH_INPUT_EDGE_QUEUE_POSITION_ADMITTED blockedTrain=6219 blockerTrain=1037 resource=…368 role=QUEUE_POSITION` **492 次**。
- 1037 自己 `BLOCKED_BY_OCCUPANCY`，`liveCause=still-held …:5/5`，5 个阻塞全是 6219 的站台占用。
- 环被看见 486 次（`mutual:1037|6219`）。这是 587~705 桥「已在区内的车被排队者挡住」的**道岔版 + layover 重启版**：
  6219 对 368 是 ENTRY 相位，排队者按 ENTRY 规则算存在；而排队者要的东西在 6219 手里。
- 第二十五轮（同 jar，cap 16）此形态 **0 次**；cap=24 时出现。密度触发，但是真死锁。

**为什么 `f0dafc8` 不够**：割环候选是 `6219 FORWARD_TO_RELEASE_BLOCKER`，被 `INSUFFICIENT_DIRECTION_EVIDENCE` 拒绝，
`SMART_DISPATCH_DIRECTION_INFERENCE_FAILED reason=MOVEMENT_PLAN_MISSING`（9/9）。`f0dafc8` 从 `blockerSnapshots[6219].movementPlan`
取方向，而它是空的：`dispatchLayover` 每次尝试先 `blockerSnapshots.remove`，握手请求的 live 快照又被
`STALE_PROGRESS_CONTEXT` 拒（82 次：请求是新线路 idx 0，进度表还是旧线路 idx 21）。且对尽头折返车，
「前向到授权边界」物理上指向缓冲止挡——这类环只有 `SMART_QUEUE_POSITION_YIELD` 能解，而它被 F1 挡着。

**最窄修法**：先落 F1，让 yield 到得了。yield 本身的判据（A 只被 QUEUE_POSITION 挡、B 被 A 持有的资源挡、快照新鲜、冷却、mode 闸）
对这个现场全部成立（`progressWindowCurrent` 对未知窗口返回 true）。
**准入层替代方案（未实现，仅提案）**：道岔队列仲裁里，若排队者 B 的请求被请求者 A 以 obstructing 角色持有的资源挡住，B 不得排在 A 前面
（`d3f7215` CONTINUATION 相位的道岔亲戚）。但 `OccupancyQueueEntry` 不带 B 的请求资源，要改数据结构；先走 yield。

### F3 [CONFIRMED] `heldRecheckDue` 比的是全局版本 ⇒ 停车列车**每 tick** 完整重评估；5 秒节拍与注释里的开销上界都是死的

`heldTrainOccupancyVersion` 返回 `manager.version()`（全局）。实测 `SMART_DISPATCH_GLOBAL_SNAPSHOT` 的 `occupancyVersion`
每秒推进 **均值 63、最小 5.5、最大 153**（646 个区间）⇒ `lastVersion != version` 每个调度 tick 恒真。
不是缺陷（第二十五轮 +38% 靠它），但：① 它就是 F1 里「放掉的尾保 1 秒后被拿回」的机制；② 任何「被割的车下一 tick 重新入队」类的
振荡都会以 1 秒为周期。「一个量只能和同一个量比」的第七个形态：每车问题拿全局量答。建议只改注释与开销声明；若要真正按 5 秒节拍，
版本要按「该车请求触及的资源集」计。

### F4 [CONFIRMED] 灯位主路径上**没有任何阶梯**

- `SignalAspectPolicy.defaultPolicy()` 的阶梯（≤5s → PROCEED_WITH_CAUTION，≤30s → CAUTION，否则 STOP）在
  `SimpleOccupancyManager` 的 **5 个调用点全部传 `Duration.ZERO`** ⇒ 永远 PROCEED。输入域是常量的守卫。
- `previewAdvisoryLookaheadReadOnly` 只有一档：风险非空 ⇒ `PROCEED_WITH_CAUTION`，否则 PROCEED；不按距离分级。
- `stageSignalAspectForAuthorityAndAdvisory` 二元；产出 `CAUTION` 的只有 `deriveBlockedAspect` 族（progress-trigger 支路）。
- 前瞻**确实看得更远**：`SIGNAL_CAUTION_REASON` 里 advisory 窗口 11–25 个资源 vs 硬窗口 3–9（几乎全部 advisory > hard），
  但 `nearestAdvisoryRisk=-` 433/449；原因分布 `artificial-window-trace-only` 607 / `no-advisory-risk` 248 /
  `stale-or-protective-risk` 24 / `outside-caution-braking-distance` 21。
- 「看见了却丢掉」还是「没看见」：只能靠 `98cd49f` 的 `advisorySignal/advisoryBlockers` 定论（见 U3）。
  **不要**以加大视野作为第一步——`outside-caution-braking-distance` 那支已经在丢「视野内但制动距离外」的风险，
  正确判据是「再不减速就来不及」，而不是「在视野内」。

### F5 [PLAUSIBLE] 其它「整轮只取一个值」的谓词（扫描：≥50 样本且唯一值；已剔除计数/时间/名字类字段）

| 谓词 | 实测 | 判断 |
|---|---|---|
| `SMART_NO_SAME_DIRECTION_UNLOCK_PLAN hardDeadlockEvidenceStrong` | false 448/448 | 需 `hardCycle && knownDirections`，等待图边 `direction=UNKNOWN` 371/393；`f0dafc8` 后可能变化，看下一轮 |
| `SMART_SWITCHER_CLAIM_LIFECYCLE direction / reservedAuthority` | UNKNOWN / unknown 194/194 | 道岔 claim 从不带方向；`SMART_DISPATCH_INPUT_EDGE switcherReason=MISSING` 367/393 ⇒ 道岔关系判据（HEAD_ON 等）基本不可满足 |
| `SMART_BLOCKING_SNAPSHOT selfRetainReleaseCandidate` | false 191/191 | 已知的 CONFLICT-only 判据；Phase 4 是实际路径，这个字段现在误导 |
| `SMART_DISPATCH_GLOBAL_SNAPSHOT pendingLayover` | 0 691/691（6219 在池里 17 分钟） | 它数的是 `pendingLayoverTicketCount()`，不是池大小；命名误导，建议加 `layoverCandidates=` |
| `SMART_DISPATCH_INPUT_EDGE relation=HARD_OCCUPANCY` 配 `role=QUEUE_POSITION` | 6219 那条边 | 排队位被标成硬阻塞 ⇒ `SMART_RECOVERY_INPUT hardBlockers=[1037]`，DRAIN/FORWARD unlock 因「hard-blocker-present」退出；今天无害（该走 yield），但是形态 3 |
| `SIGNAL_CAUTION_REASON nearestHardAuthorityBlocker` | `-` 449/449 | 该 trace 只在非硬阻塞路径发，字段恒空，可删 |
| `SMART_UNLOCK_PLAN_APPLY planKind` | FORWARD_TO_AUTHORITY_BOUNDARY 54/54 | 其它 planKind 从未落地；未查原因 |
| `SMART_ADMISSION_AUTHORITY_CONSISTENCY contradictionDetected / authorityWindowValid` | false / true 254/254 | 可能本来就健康；未定 |

---

## Task 1：每个 retryTrigger / releaseCondition 的真实实现

`RuntimeStopState.retryTrigger()` 与 `releaseCondition()` **只被 trace 与 CLI 读**，不驱动任何控制——这一点交接说得对。
但每个触发都另有实现路径；「枚举没人读」≠「重检不存在」：

| RetryTrigger | 停因 | 实际实现 | 证据 |
|---|---|---|---|
| OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK | occupancyHold、HARD_BLOCKER_STOP、ACQUIRE_FAILED | `heldRecheckDue`（实际每 tick，F3） | 第二十五轮空等 141s→个位数 |
| PERIODIC_RECHECK | 其余 hardStop（AUTHORIZATION_FAILURE 等） | 同上 | |
| OCCUPANCY_OR_PROGRESS_CHANGE_OR_PERIODIC_RECHECK | PUBLICATION_GATE_LOCAL_STOP | 同上 + progress trigger | |
| GRAPH_ROUTE_OR_PROGRESS_REFRESH | SINGLE_CORRIDOR_FAIL_CLOSED、SAFETY_STATE_UNAVAILABLE、STOP_CONTEXT_MISSING | 同上 | |
| HEALTH_CHECK_OR_OCCUPANCY_CHANGE | DEADLOCK_CONFIRMED_WAITING | 同上 + 健康监控 | |
| LAYOVER_RECHECK | LAYOVER_HOLD | `SimpleTicketAssigner` 每 spawn tick → `dispatchLayover`；`handleSignalTick` 那支无条件 STOP 是设计 | 2080 次 / 1033 s |
| TERMINAL_EVENT | TERMINAL_HOLD | 终点分支停稳后注册 layover（REUSE_AT_TERM），否则 DSTY/回库 | 122 lifecycle，无长保持 |
| DWELL_OR_DOOR_EVENT | DEPARTURE_GATE_HOLD、WAYPOINT_STOP_HOLD、DWELL_ACTIVE | AutoStation 门事件 + 门锁 180s 周期清扫 | 2466 enter，最长 77s |
| STARTUP_RECONSTRUCTION_RETRY | ABNORMAL_PHYSICAL_QUARANTINE | 重建提交 | |

`ReleaseCondition` 全部是文档。建议在 Javadoc 上标「diagnostic only」，**不要**加读它的代码。

---

## Task 4：`RuntimeDispatchService` 的拆分提案（30,603 行 / 112 字段 / 997 方法）

方法名前缀普查：`trace*` 247、`resolve*` 160、`smart*` 96、`is/has/should*` 146、`apply*` 81、`handle*` 48、`record*` 39、
`clear*` 36、`retain*` 35、`update*` 33、`signal*` 32、`build*` 27、`remember*` 25、`release*` 25。

按「能防住哪几种失败形态」排序（术语按 codebase-design：模块 / 接口 / 接缝 / 深度）：

1. **恢复动作接缝** `RecoveryActionCatalog`（`applySmart*`/`rollback*`/`drain*`/`unlock*`，≈200 方法）。
   接口只有一个：`attempt(SmartRecoveryInput) → Result`，而 `Result.effectiveness` 是**代数类型**
   `Measured(effective, evidence) | Unmeasured`——F1 在类型层面就写不出来。链执行器（`TrainHealthMonitor.tryFixProgressStuck`）
   已经是这个接口的调用方，测试接缝现成（本轮 45 行就写出判别用例）。防形态 5。**排第一**：今天两条确认发现都在这里。
2. **诊断接缝** `DispatchTraceWriter`（`trace*`/`summarize*`/`describe*`，≈270 方法，27%）。接口 `emit(token, fields)`，
   必留名单、去重键、on-change 三件事**在同一处**决定——「新 trace 忘了进名单」已经发生两次，`SAFE_CANDIDATE_FAILED_COUNT` 是第三次候选。
   防形态 1。深度来自：调用方只需要知道 token，通道策略全在实现里。
3. **授权构造接缝** `AuthorizationPlanner`（`resolve*`/`build*`/`evaluate*`/`select*`，≈220）。纯函数：
   (列车状态, 图, 线路, 进度锚点) → `OccupancyRequest`。进度锚点做成一个值对象贯穿，禁止沿途 `.orElse(别的量)`——
   六次「一个量和另一个量比」有四次在这一带。防形态 3。
4. **layover/折返接缝** `TurnbackCoordinator`（`dispatchLayover`/`retainLayoverReadyOccupancy`/`handleLayoverRegistrationIfNeeded`，≈50）。
   F2 里「每次尝试 wipe 快照」「握手快照被判 stale」「`handleSignalTick` 对 layover 车特判」都在这里；把「layover 重检」做成接口方法，
   而不是 ticket assigner 的副作用。防形态 5（触发器只是文档）。
5. **信号 tick 接缝** `SignalTickPipeline`（`handleSignalTick` + `stage*` + `validateFinalSignalAuthorization`，≈100），
   显式四段：准入 → 前瞻 → 灯位 → 发布，每段输出本 tick 的新 record。防形态 4（入场值当当前值）；F4 的三个「没有阶梯」会在同一段里并排可见。

不建议先动的：`SimpleOccupancyManager`（6,832 行）与它的 `obstructs` 真值表是安全核心，任何拆分先把 `ObstructsTruthTableTest` 抬到接缝上。

---

## 未定项与判定它们的单次测量（第二十七轮）

| # | 未定 | 单次测量 |
|---|---|---|
| U1 | yield 到得了之后，layover 车的 `blockerSnapshots` 是否新鲜在场（`dispatchLayover` 每次 wipe；握手 live 快照被拒 82 次；但等待图曾拿到 age≈4s 的快照 492 次） | `grep "SMART_QUEUE_POSITION_YIELD_SKIPPED train=<最长保持车>"`：`no-blocker-snapshot`/`stale-blocker-snapshot` ⇒ 要改 `dispatchLayover` 的 wipe；`no-proven-queue-cycle` ⇒ 对方快照不新鲜；`YIELDED` ⇒ 解了 |
| U2 | `f0dafc8` 对非 layover 对子（`0267|1857`、`0262|7379`，PHI 侧尾保链）有没有给出方向 | `grep -c SMART_DISPATCH_CYCLE_CANDIDATE`；`SMART_DISPATCH_DIRECTION_INFERENCE_FAILED reason=` 分布 |
| U3 | 突发红灯是「前瞻没看见」还是「看见了被压平」 | `SIGNAL_ASPECT_STAGING result=STOP` 行的 `advisorySignal`/`advisoryBlockers`（`98cd49f`）；建议 `ADVISORY_CAUTION_SKIPPED` 那行也带 `advisorySignal` |
| U4 | `contradictionDetected=false` 254/254 是健康还是死判据 | 未设计探针，低优先 |
| U5 | F3 的每 tick 重评估会不会让被割的排队者在 1 秒内重新入队、抢在 layover 车前面 | `YIELDED` 之后 N 秒内是否出现 `Layover 发车成功: train=<同车>` |

**可证伪预测**：不合入 F1 补丁 ⇒ 只要出现 ≥120s 长保持，`SMART_PHYSICAL_EDGE_RETAIN_RELEASED` 的重复率仍 ≥90%，
且最长保持车的 `YIELD_SKIPPED` 仍 0 行。合入 ⇒ 该车在 ≈3 次恢复尝试内出现 `YIELD_SKIPPED`/`YIELDED`。

---

## 我做了什么 / 没做什么

- 读了四个记忆文件、交接 prompt、`3b77e88`/`f0dafc8`/`dff0cde` 三个 diff，以及涉事代码路径。
- 对第二十六轮日志做了：单车轨迹、账本核对、等待图边字段分布、常量字段扫描、跨轮（24/25/26）Phase 4 重复率、`occupancyVersion` 速率。
- 在 scratchpad 副本里：写判别用例 → 红 → 落最窄修复 → 绿 → 变异 → 红 → 还原 → 绿；导出补丁到 `.handoff/fable-chain-fix.patch`。
- **没有**：改工作区、跑全量 `check`、改实服 config、提交。
- 记忆文件已更新：`dynamic-dispatch-stabilization.md`（顶部新节）、`dispatch-fix-invariants.md`（applied ≠ effective）、
  `dispatch-disproven-hypotheses.md`（#19）、`MEMORY.md`。

---

## 追记（2026-09-18，用户要求修复）

F1 修复已落到工作区，**未提交**：
- `TrainHealthMonitor.shouldHoldForSafeCandidate`：`ASSUMED_EFFECTIVE_REASON` 视为未测量，走 `countSafeCandidateFailure(…, "assumed-effective")`；
- `RuntimeDispatchService.SmartRecoveryEffectiveness.ASSUMED_EFFECTIVE_REASON` 常量；
- `SMART_RECOVERY_SAFE_CANDIDATE_FAILED_COUNT` 进 `RuntimeDispatchDiagnosticGate` 必留名单，生产端按 (train, action, conflict, kind, count) 去重；
- 用例 `assumedEffectiveButRepeatingCandidateMustNotStarveLaterRecoveryActions`（链续行 + 去重两条断言，两次变异各自红）。
- `spotlessApply check`：1864 用例 / 0 失败；`javap` 方法数 997。Phase 4 冷却与「按被挡车定义有效」**未做**。
