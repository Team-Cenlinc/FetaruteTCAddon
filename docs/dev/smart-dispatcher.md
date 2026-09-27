# Smart Dispatcher 监督层

`SmartDispatcherController` 是运行时调度与最终安全发布之间的确定性监督层。它不直接绕过 TrainCarts 控车，不替代 `SignalPublicationGate`，也不使用机器学习或随机策略；所有决策必须来自当前 tick 的可追踪快照、稳定排序和明确的安全原因。

## 模式隔离

`smart-dispatcher.mode` 控制 Smart Dispatcher / Traffic Control Supervisor 的副作用权限：

- `OFF`：不调用前方风险决策器，只输出最小 `SMART_DISPATCH_DISABLED` trace。
- `OBSERVE_ONLY`：默认值。允许输出 `SMART_DISPATCH_*` / `DEADLOCK_*` 诊断 trace，但禁止改变 visible aspect、target speed、destination、movement token、movement inhibitor、occupancy 或 destroy。
- `ENFORCE`：允许通过 effect class gate 的动作执行；`SIGNAL_ADVISORY` 可调整 visible aspect / target speed，`SIGNAL_CONSTRAINT` 可执行 local-only hold / entry block，`SIGNAL_REEVALUATION_REQUEST` 只能唤醒下一 tick 的标准信号链路，`OCCUPANCY_MUTATION` 只允许已登记且可锁内复验的 stale retain/queue 清理或实体 switcher 排空授权，`DESTROY_ACTION` 只允许 confirmed hard-cycle destroy。

所有 `DispatchDecision` 必须携带 effect class：

- `DIAGNOSTIC_ONLY`
- `SIGNAL_ADVISORY`
- `SIGNAL_CONSTRAINT`
- `AUTHORITY_PRECHECK`
- `SIGNAL_REEVALUATION_REQUEST`
- `OCCUPANCY_MUTATION`
- `DESTROY_ACTION`

## 登记 action gate

effect class 只说明动作可能触达的边界，不能单独授权副作用。运行时、admission 与 HealthMonitor 都必须提交一个
`DispatchAction` 给 `SmartDispatcherModeGate.allows(...)`；未登记或标为 forbidden 的 action 即使带有允许的
effect class 也会被拒绝。当前关键动作包括：

- `PROCEED_WITH_CAUTION` / `CAUTION_SPEED_LIMIT`：前方风险的信号建议；
- `SMART_ADMISSION_HOLD`：Depot、站台或入口拒绝后的 local-only hold；
- `SMART_HEALTH_SIGNAL_RECOVERY`：健康监控的 refresh、hard-stop 与受限重启；
- `RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN`、`ACQUIRE_VERIFIED_SWITCHER_DRAIN_AUTHORITY`：经过资源和方向复验的占用动作；
- `EXECUTE_VERIFIED_DEADLOCK_DESTROY`：仅在 deadlock review 已通过且没有 safe alternative 时销毁选定 leader。

`FORCE_PROCEED`、一般性 `DESTROY_TRAIN`、清外部占用、清 destination、绕过 direction 等动作持续为 forbidden，不能由
Smart Dispatcher effect chain 执行。

## 实服验收矩阵

静态测试不能证明 TrainCarts 实际换 destination、过道岔或推进 route index。每次 Dispatcher/Signal 改动都必须用新
shadow JAR 跑一份完整日志；日志开头的 `buildId` / `sourceFingerprint` 必须与待验 JAR 的 `build-info.properties` 完全一致。
对每辆验收列车至少保留一次状态变化前后连续 120 秒日志，并同时检查 `SMART_SIGNAL_FINAL`、`SignalTrace` 与 TrainCarts
实际运行结果。

| 场景 | 通过证据 | 必须拒绝的假阳性 |
| --- | --- | --- |
| Depot 出库 | 受阻时出现 `SMART_ADMISSION_HOLD` 且不生成 destination/token；前车实际清空后，新的 `SignalTrace` 显示 destination 已提交、active token 与递增的 `routeIndexAfter`。 | 只有 `SMART_ACTION_ALLOWED_BY_EFFECT_GATE` 或 planner trace，没有列车实际离库/推进。 |
| 普通 Switcher | 冲突进入先后有明确的 `CONFLICT:switcher`/admission 证据；后一车只在前车释放后获得新 destination，并能通过咽喉使 `routeIndexAfter` 递增。 | 两车同一 hard switcher 资源同时获得 PROCEED，或只有 advisory trace。 |
| 同向跟驰 | 真实 NODE/EDGE 不重叠时，前后两车均能持续推进；日志保留同向方向证据，且 follower 不被伪造为 opposite hard conflict。 | 用 `SAME_DIRECTION_FOLLOW` 诊断或 queue trace 代替实际 TrainCarts 前进。 |
| 尽头折返 | 计划 RouteStop/terminal 先完成物理停靠；折返后才提交反向下一 destination，并由新的 `SignalTrace` 证明 route index/node 方向已推进。 | 仅看到 `PROCEED_WITH_CAUTION`，没有停靠后的下一跳 destination 和实际折返。 |

稳定期日志预算：插件启动时只创建一个 Runtime Dispatch diagnostic gate，`RuntimeDispatchService`、占用事件桥、
信号重评估调度器、`SignalEvaluator` 与 `HealthMonitor` 必须共用该出口。所有观察 trace（包括 signal、lookahead、
occupancy、admission 与 `SignalTrace`）按稳定签名最多每 5 秒输出一次；tick、requestId、sequence、观察年龄 ageMs 或版本号变化不能
单独形成新行。观察 trace 在全部组件间还共享每分钟 120 行预算；预算恢复后的首条会先输出
`SMART_DISPATCH_DIAGNOSTICS_SUPPRESSED` 汇总。执行器审计（`SMART_DISPATCH_EXECUTOR_*`）以及 unlock reservation
的创建、重评估、成功/失败与回滚事务边界不受这两条限制，必须保留每次重新核验和完整事务链。候选、`PLAN_APPLY` 与逐 tick
progress 仍受门控。普通状态字段变化会立即产生新行，直到观察预算耗尽；限流只能减轻观察噪声，绝不能跳过一次实际授权复核或改变
调度决策。

`SMART_DISPATCH_DIAGNOSTICS_SUPPRESSED` 会同时输出 `repeat`、`budgetDrop`、`repeatKinds` 与 `budgetKinds`：前者表示
稳定签名的重复观察，后者表示预算已满时的新观察；每类最多报告四个 trace/`reason` 类别。它是最后一道控制台保护，绝不是
正常运行的验收信号。正常运行期不得为同一逻辑列车的等价 claim refresh、blocker 快照只读查询或无候选/无实际释放的
STOP-retain 生成生命周期事件；只有 owner、route、headway、role、走廊方向或实际资源集发生真实迁移时，才允许写入
`SMART_RESOURCE_LIFECYCLE` 或 retain shrink trace。`TRACE_ONLY` 的允许判定必须保留计算出的
`PROCEED`/`CAUTION` 语义，不得为了标记“未物理发布”伪造成 `STOP`。完整 path、edge 与 conflict 详情只在 trace 已确定
需要输出后格式化，禁止在被 gate 丢弃前构造大字符串。若稳定行车仍持续出现 suppressed 汇总，应把它当作待修复的上游事件
或诊断源，而不是调大预算或接受日志门控。

停止服务器后，FTA 日志不得出现 `Plugin attempted to register task while disabled`。`onDisable()` 只撤销 FTA API、授权门、wake-up、调度任务与存储，不再枚举 TrainCarts、重试离线编组销毁或调用实体化发车 `prepareForReplacement`。Depot 物理编组一旦返回精确 identity，就会在任何可失败的 tags/warm-up 初始化前写入 `FTA_MATERIALIZED_ROLLBACK_PENDING=true` 事务墓碑；初始化器必须保留该标记，外层事务也会在初始化结束或抛错时重新确认。只有 footprint 已 promotion 且发车票据提交成功后才清除。因此包括 4 秒 hydration 等待窗在内的未完成事务都会由持久标签保留到下次启动的 STOP_FIRST 恢复。只有插件仍启用且处于受控生命周期的 `/fta reload` 才同步执行物理收口。TrainCarts、BKCommonLib 或其他插件在 `Thread-0` 的关闭异常需单独记录，不得作为 FTA Dispatcher 成功或失败的证据。

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

同一轮全局 planner 只读取一次 occupancy claim 快照，并把这份不可变视图用于列车状态、保护性尾部证明、mutual-owner 诊断与执行前方向屏障。该视图不跨 tick 缓存；下一轮仍重新读取 live claims，避免运行图扩大后按列车重复复制整张占用表，也不引入图版本或进度失效问题。

随后每列车的局部信号评估仍由既有 runtime 流程完成：canonical `MovementPlanSnapshot` 与 canonical `ExpandedPathPlan` 继续作为 entry lookahead、占用申请和信号发布的事实来源。Runtime 会从同一 expanded path 派生短 `hardAuthorityWindow` 与更远的 `advisoryLookaheadWindow`；Smart Dispatcher 只在最终 visible aspect 发布前读取这些结果并输出监督建议。

当前 route index 后存在非 `PASS` RouteStop 时，`ROUTE_STOP_OR_TERMINAL` 是停靠/折返行为边界，而不是前方实体 blocker。它只会产生速度建议（必要时为 `PROCEED_WITH_CAUTION`），不得仅因进入制动距离而发布 `STOP`；实际停靠仍由对应站点、终点或 TrainCarts destination 行为完成。没有 RouteStop 证明的裸交路终点，以及缺少路线、物理占用、方向或硬授权证据的情况继续 fail-closed 到 `STOP`。

Occupancy acquire/release 与 deadlock-resolved 事件不会发布预览信号，而是合并为下一 tick 的完整 runtime 重评估。trace throttle 只抑制相同 blocker graph/plan 的重复日志，不能跳过 executor gate；即使 plan key 未变化，active reservation、no-release cooldown、effect gate 与资源窗口也必须每轮重新检查，确保回滚后的 cooldown 到期能够重新尝试。

Planner 选择前会排除已有 reservation 的 cycle 与列车，再从剩余 accepted candidate 中选择最高分方案，避免一个暂不可重复执行的高分 cycle 长期压住其他可解锁 cycle。当前 reservation 索引和 movement token 都是每 cycle/每 train 单值，因此执行器无条件维持“同 cycle 或同 train 最多一个 active reservation”的幂等不变量；旧配置即使把 `one-active-reservation-per-cycle` 设为 `false`，也不会覆盖仍在观察、TTL 或 rollback 生命周期内的 reservation。

Planner 的 `resources/releaseResources` 表示 wait-for graph 中预计被释放的瓶颈，不是完整 Movement Authority。ENFORCE 选中 winner 后只记录短生命周期选择意图、向规范 Gate Queue priority 叠加有界且不覆盖人工优先级的 boost，并请求下一 tick 重评估；不得直接 acquire 这些 blocker、写 destination 或签发 token。成功反馈也必须同时看到：重评估后生成的 fresh active token、目标边界一致、destination 已提交、token 声明的全部硬资源仍由同一逻辑列车持有，以及瓶颈资源确已释放。任一条件缺失只回滚选择意图，不清除规范运行时已经签发的 token。

NODE/EDGE blocker 没有 `A_TO_B/B_TO_A` 这种二值走廊方向。Planner 会优先读取同一列车的 fresh live blocker 快照；若 bottleneck leader 本身没有 blocker 快照，则读取最近一次完整信号请求留下的 canonical `MovementPlanSnapshot`。后者只在配置的 movement-plan TTL 内有效，并且必须仍匹配 canonical train key、精确 route ID/index 与 last-passed/effective-start 进度窗口；其中 last-passed 的 `-` 表示“采样时确实不存在”，不是通配符。缓存路径会从真实进度锚点裁掉车后部分，锚点缺失或重复时拒绝使用。两种来源都要逐项验证 effective from/to、完整节点链、有向边链和物理 `EdgeId`，且每个待释放 NODE/EDGE 都必须由验证后的前向链覆盖，才生成 `CANONICAL_MOVEMENT_PLAN` 前向证明。

唯一的 rear 例外是 `CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN`。生产 builder 会在生成当前 forward plan 的同一图快照内，额外附带一条不参与资源申请或方向解析的 `canonicalRearRetainPathPlan`：若 last-passed 仍在当前 route leg 中间，它只覆盖当前 canonical waypoint 到真实 last-passed 的已走前缀；若 route index 刚推进到 waypoint，它只覆盖紧邻上一 waypoint 到当前 waypoint 的上一 leg。该路径必须唯一终止于真实 last-passed、节点不重复且每条有向边与物理 `EdgeId` 完整一致；不能从已经裁剪为 last-passed 起点的 forward plan 反向猜测 rear。待解释资源还必须严格位于该路径末端锚点之前，并且占用层当前仍存在同一逻辑列车、同一 route identity、角色为 `PROTECTIVE_RETAIN` 的 NODE/EDGE claim。缓存只保存路径候选，planner 每次使用时都重新与 live claims 取交集；`PHYSICAL_FOOTPRINT`、`HOLD_ONLY`、无 route、路径外、当前锚点本身或任意 conflict 均不成立。该证据只说明 leader 规范前进后会收缩自己的保护性尾部窗口，仍只允许创建“下一 tick 重新评估此列车”的短期 winner 意图，不能直接释放 claim、伪造成 `CorridorDirection` 或绕过完整 Movement Authority。由它创建的 priority 意图还会绑定原 route ID/index，进度身份漂移时立即回滚。

计划缺失、超过 TTL、route/index 已推进、last-passed 从缺失变为存在、路径少边/错边、起终点折叠、边链不连续或释放资源未被上述任一严格证据覆盖时继续以稳定原因码 fail-closed。`CONFLICT:single:*` 与 `YIELD_TO_HEAD_ON` 始终要求真实 single-corridor 方向；每个非 NODE/EDGE blocker（包括 switcher）也必须各自携带已知且彼此一致的 topology 方向。混合资源必须同时具备这些逐资源方向证据与 canonical 路径证明，已知 single 或另一个 conflict 都不能掩盖 UNKNOWN switcher。

## 安全状态分层

运行时必须区分以下事实，不能用单一 `occupied=true/false` 代替：

- `PHYSICAL_FOOTPRINT`：只描述已经有现场证据的物理保护，例如 Authority Handoff 尚未清出的旧进路，或实时车体实际命中的 sparse interlocking Zone；任何预测或 Smart action 都不能绕过。
- `PROTECTIVE_RETAIN/HOLD_ONLY`：停车时继续保留的保护窗口；它不是新的前进授权。
- `MOVEMENT_REQUIRED`：当前运动授权要求完整取得的资源；只有 fresh acquire 成功才能写入。
- queue / prediction / advisory：仅表达顺序或风险预览，不能独立驱动车辆，也不能把 STOP 提升为 PROCEED。
- `MovementAuthorizationToken`：最终可执行硬授权。远端 `destinationNode` 与本轮真实 `authorityEndNode/authorizedEdgeCount` 分开保存；destination 本身不构成授权，也不能扩大硬授权边界。token、destination、信号发布与 hard authority 必须来自同一轮有效决策。

跨不同图边或不同 switcher ID 的物理平交使用无方向的共享 `CONFLICT:interlocking:<hash>`。走廊 `A_TO_B/B_TO_A` 只用于单线方向锁，不能用于侧面平交兼容性。sparse catalog 不完整时整张世界图退化为同一个 fail-closed 联锁资源，避免因漏采样形成敌对双授权。普通轨道方块不会被持久化；它们的进路与列尾保护继续来自 Node/Edge、规范 Movement Plan 和真实车长。

位置保持（信号 tick 的当前位置保护、停车保持）按“当前节点 + 当前边”取资源，当前边上的 `interlocking:*` 由 `PositionZoneEvidence` 决定：列车行进中（含制动越过授权终点的那几格）或本轮整列足迹不完整时，按当前边整体保持；列车已停稳且足迹完整时，只保持车体实际压到的联锁区。停着的车要再往前走，必须重新以 `MOVEMENT_REQUIRED` 申请这些联锁区，所以放掉没压到的交叠格不会扩大任何授权。尾部保护与当前边的 NODE/EDGE/switcher/single 资源不受影响。背景：2026-09-27 实服 OFL 车库口，DS 车头刚越过 `MLU:1:004` 牌子，节点模型认为它进了通往岔口的那条边，边末端与 1 号库线的交叠格被整条扣住；车体离交叠格还有二十几格，回库的 MT 却进不了库，两车顶牛到关服。

共享物理联锁与 switcher/single 一起进入 Gate Queue，但联锁始终按无方向全局 winner 仲裁。priority 只提供每分 0.5 秒、最多 2 分钟的有界时间优势；首次等待时间形成固定 aging 排序键，使 `OPERATION > depot exit/CREATE > RETURN` 的短期优先关系成立，同时保证持续刷新的老等待者最终压过后来流量。`SMART_PENDING_WINNER_ARBITRATION` 会记录 winner 基础 priority、已等待秒数与优势上限；队列 winner 仍必须 fresh acquire 完整 admission/physical window，不能凭队头身份越过 live claim。

## 启动现场恢复事务

启动和重载恢复遵循 `STOP_FIRST -> HYDRATING -> READY`：

1. 进入插件启动或 `/fta reload` 恢复入口时，同步枚举并对全部受管 live train 发布结构化硬 STOP、撤销旧 token；不能等待下一 tick。此时不启动健康自愈、周期信号、事件信号、自动发车或折返回收。
2. 从 TrainCarts `RailTracker#getRailInformation()` 读取车头到列尾的完整 `TrackedRail` 链。Route/Node 推导出的普通 EDGE/NODE 停止窗口写为逻辑 `HOLD_ONLY`；实时车体 cell 只查询 sparse Zone，实际命中的 `interlocking:*` 才写为 `PHYSICAL_FOOTPRINT`，不再要求普通 cell 能反查全线 Edge。整列位于同一条长 TCCoasters RailPath 时，使用公开线段投影 ABI 定位每节车的前后轮绝对位置，并读取真实 `cartLength` 补足轮轴到车体端部的距离，只栅格化本列车及边界余量覆盖的局部切片。跨多个 RailPath 时先保守栅格化完整 tracker 链，再从每节车当前 RailState 沿正反两个方向延伸“半车长 + 一格边界”。若该完整链已证明同世界、连续并覆盖每个 member，但自定义轨道的 `RailPath`、局部投影或 body-walk ABI 不可用，则以链中全部实际轨道方块作为更保守的 sparse Zone 查询输入；这不是 route/实体坐标推测，也不能用于不完整链。物理模型矛盾、disconnected、跨世界、成员覆盖不完整或 sparse catalog 不完整时仍视为证据不足。
3. `StartupOccupancyReconstructionSupport` 对完整请求集做一次原子替换。相同 sparse Zone 上的多个列车都保留独立 `PHYSICAL_FOOTPRINT`；普通逻辑资源保持 `HOLD_ONLY`，不得因输入顺序或互斥预检丢掉任一现场事实。
4. Route、index、graph、车长/路径请求、重复逻辑列车名或原子提交任一项不完整时保持全局 STOP，并在下一轮重新读取现场。
5. 物理快照提交后仍保持 `HYDRATING`。插件先成功启动全部监督组件，随后才以单个完成阶段进入 `READY` 并逐车 fresh acquire；任一组件启动失败，或进入 `READY` 后任一列车的首次刷新发生 runtime/ABI 异常，都会立即把全局门重新关闭到 `STOP_FIRST`、硬停本轮车队并请求完整恢复。`/fta reload` 使用同一个事务，不得在 reload 后直接恢复信号或发车任务。
6. 恢复完成后迟加载、重连或改名后的受管列车必须先完成单列原子现场水合；该提交同时替换本车逻辑 HOLD 与 sparse `PHYSICAL_FOOTPRINT`，并保留其他 owner/queue。水合资格同时绑定 canonical train key 与 TrainCarts 物理编组实例，不能由同名 split/link group 继承；若发现同一 key 的第二个 live group 或现场证据不足，则重新关闭全局授权门并触发完整扫描，不允许晚加载列车绕过恢复。
7. 只有 sparse Zone 现场资源进入启动 release guard；后续完整 `TrackedRail` 证明车体已经离开该 Zone 后，guard 才会收缩。普通 EDGE/NODE 的后方保护由规范 Route、当前 Node 与真实车长计算，不再依赖常驻 cell -> Edge 表。
8. READY 阶段的 Depot 新车使用独立的 `provisional -> promoted` 事务：实体化前捕获 recovery epoch，`DepotSpawner` 在物理 group 生成后立即把 identity 返回给上层，tags/warm-up 等可失败初始化只能在 group-aware 事务内执行。实体化后先取得完整硬授权并硬停车；物理 identity 一经取得就在任何可失败初始化前写入持久事务墓碑，初始化器规范化生命周期标签时继续保留并由外层 finally 复写确认。随后才绑定本次 physical identity 并首次刷新信号，只有真实 footprint promotion 与票据提交都成功后才清除墓碑。首次 `TrackedRail` 尚未就绪时只冻结该车，不把它误判为未知迟加载列车；真实 footprint 原子提交后才允许继续信号链并完成发车票据。票据、租约与硬授权最多等待 4 秒（小于发车租约的 5 秒 TTL），超时会先收容实体再重排票据。任何实体化后异常都会单向进入 `ROLLBACK_REQUIRED -> AWAITING_REMOVAL`：前一阶段硬停、安排销毁与回滚账务，后一阶段持续保留 ticket guard、按精确 identity 写入 runtime quarantine 并重试销毁，直到 TrainCarts 精确发出 GroupRemove 才完成清理。`group.isValid()==false` 也可能只是 GroupUnload，不能作为销毁证据；卸载前的 quarantine、claims 与 ticket guard 会保留，并通过持久化回滚标签转移到重载后的新物理身份。离线编组只接受 TrainCarts official offline store 的成功销毁结果，完整重启后也会先扫描 `TrainPropertiesStore` 中的 tombstone，收容完成前不得进入 READY；两个阶段都禁止再次刷新信号、promotion 或完成成功票据。同一 ticket 只允许存在一个未完成实体化事务；额外 group 使用独立物理恢复记录，但不拥有票据完成/重排权，逻辑 claims 要等同名隔离实体全部精确移除后才释放。epoch 在提交中变化时保留现场 claims 并拒绝 stale promotion；实体化失败达到普通尝试上限或通用队列 age 时仍保留班次并退避，不会静默消费票据。重载前会先全量隔离全部 pending identity，再开始任何销毁；若收口未完成则安全中止重载，现场恢复任务会继续推进旧 assigner 的物理收容。重载成功会按 ticket id 去重迁移 queue 与 layover pending，并原样迁移每个服务的 `nextDueAt` 与全局 sequence，避免重放历史班次。运行时物理闭锁接管期间，未复用该事务的 `/fta depot spawn` 手动旁路会被拒绝。
9. 普通运行时身份的 GroupRemove/Unload 清理必须匹配同一个物理编组实例；同名旧 split group 的迟到事件不得按 canonical name 清除存活组的 claim，而应保持现场快照并重新进入全局恢复。实体化回滚是更严格的例外：GroupUnload 只转入 offline 精确收容，不能释放 quarantine、claims 或 ticket guard。

该事务保证旧授权不会在半水合快照上重放；现场已经重叠的列车会继续保持 STOP，等待真实通过/清空证据或人工处置，而不是由恢复顺序选出一个虚假的 owner。

## STOP 生命周期与审计

所有运行时 STOP 都必须进入 `RuntimeStopState`，至少记录 `reason/detail/releaseCondition/retryTrigger/invalidatesAuthority/blockers`。`/fta train debug` 会显示当前 STOP 生命周期。只有物理信号成功发布为 fresh authorized aspect，或已经确认物理层处于同一有效授权 aspect 时，才能清除该状态；发布失败不得提前清除。

中央发布入口若收到没有业务上下文的 STOP，会记录 `STOP_CONTEXT_MISSING`、撤销旧 Movement Authority 并等待安全状态恢复，不能用含糊的未分类原因继续运行。FTA split/member-remove 隔离记录 `ABNORMAL_PHYSICAL_QUARANTINE`；即使残编已经丢失 tag，也沿用已知逻辑 owner，只有 `FIELD_RECONSTRUCTION_COMMITTED` 后才允许解除。

关键 fail-closed 场景包括 Route/Graph/index 证据缺失、负 route index、启动恢复门、保护性 retain、publication gate local hold、movement authority 不足和外部物理 blocker。保护性 retain 可保留旧 token 供恢复审计，但可见信号仍必须发布 STOP，不能继续显示旧 CAUTION/PROCEED。

## 确定性代码验证矩阵

- [x] 敌对列车不能同时取得同一物理联锁 hard authority。
- [x] 胜者资源必须覆盖完整 admission/physical interlocking 窗口。
- [x] 物理联锁出口必须证明可容纳实时保守车长与停车净空；只让车头跨出冲突边不构成清出证据。
- [x] queue/priority 只决定顺序，不能覆盖物理 blocker。
- [x] 共享物理联锁的稳定 winner 会真正取得下一次资源，非 winner 不能插队。
- [x] priority 优势有界，等待 aging 防止后来高优先级流量造成永久饥饿。
- [x] STOP 具有原因、释放条件、重试触发器和 blocker 审计字段。
- [x] 联锁释放依赖真实 footprint/列尾清空，不依赖固定 TTL 猜测。
- [x] follower 必须能证明 leader 可达、方向一致且未占用同一硬窗口。
- [x] follower 的排空预测必须由 leader 的 active token、真实 authority boundary、已提交 destination 与实时硬 owner 共同证明；Route 展开路径与远端 destination 本身都不是授权。
- [x] 折返 handoff 保留旧 footprint，直到新旧进路的列尾清空证据成立。
- [x] physical occupancy、retain、movement authority、queue/advisory 分层建模。
- [x] 物理信号只有在 fresh hard authority 有效时才能发布非 STOP。
- [x] 已发布的非 STOP 若失去实时硬 owner，会在同一最终发布入口主动降级为 STOP。
- [x] `CAUTION`、`PROCEED_WITH_CAUTION` 与 `PROCEED` 全部要求 active Movement Authority；无 token 的事件 preview 不能驱动车辆。
- [x] 资源释放在下一 tick 触发完整授权重算，同 tick 重复事件按逻辑列车合并且不递归。
- [x] planner trace 去重不阻断 executor 复核；no-release cooldown 到期后相同 blocker graph 仍可重试。
- [x] active reservation cycle/train 不参与新候选竞争，不能压住其他 cycle 或覆盖既有 reservation/token。
- [x] 推进后按真实列车长度滑动/释放保护窗口。
- [x] 所有正常 claim shrink 都合并本轮实时 RailTracker 车体足迹；现场证据不完整时保留全部既有 claim。
- [x] 重启先冻结全部列车，再原子重建完整现场快照。
- [x] GroupLink 在 TrainCarts 完成编组变更前只关闭授权门并硬停，下一 tick 才读取最终物理编组并原子重建。
- [x] Route/Graph/index/footprint 不一致时 fail-closed。
- [x] FTA member-remove 在事件当下撤销旧 token、硬停并保留 claim，关闭延迟分类窗口。
- [x] 恢复失败自动重试；资源释放与进度变化继续触发正常重评估。
- [x] 周期巡检或事件重评估的 runtime/ABI 异常会关闭全局授权门，且不会继续执行 orphan cleanup。
- [x] LOOKAHEAD_PREVIEW 即使误入通用 acquire 入口也会被剥离，不可能持久化为硬 claim。
- [x] `/fta train debug` 与结构化 trace 可审计 STOP、资源、token 和 authority end。
- [x] 单测覆盖双车重叠、输入顺序、负 index、保护性 STOP、启动提交失败与重试。

以上勾选项表示代码与确定性测试覆盖，不等同于当前生产服务器已经运行新 Jar。`console_2026-07-18_22-24-57.log` 与 `latest.log` 的 build fingerprint 仍属于旧实现，只能作为故障基线，不能作为本轮修复的通过证据。

## 新 Jar 实服验证矩阵

以下项目必须在部署本轮 Jar、明确设置 `smart-dispatcher.mode: ENFORCE`、执行覆盖完整的 `/fta graph build`，并由 `/fta graph info` 确认 physical footprint coverage 完整后验证。启动日志中的 `SMART_DISPATCH_BUILD_FINGERPRINT` 也必须显示 `smartDispatcherMode=ENFORCE`，且其 `buildTime/buildId` 中的 `src-<hash>` 必须与交付 Jar 内 `build-info.properties` 一致；该源码哈希覆盖未提交的 `src/main`、构建脚本和版本配置，不能再只凭 Git HEAD 判断是否部署了本轮一万多行 WIP。`OBSERVE_ONLY` 只能验证 trace，不能作为入口阻塞、同向跟驰或自动恢复的通过证据：

- [ ] SPB-JBS-WSD：MT 与 DS 敌对进路竞争同一 `CONFLICT:interlocking:*`，任一时刻只能一列取得 fresh hard authority。
- [ ] SPB-JBS-WSD：输家保持可审计 STOP，胜者列尾清空后由 release event 自动重试并通过，无需人工 refresh/destroy。
- [ ] SPB-JBS-WSD：重载或重启时，两列现场 footprint 先原子重建，再恢复信号、健康和发车组件。
- [ ] PPK-RVS：复查平交/咽喉资源映射、释放时机与既有同向跟驰，无吞吐回归。
- [ ] 5108：上一 leg 的 `NODE:SWITCHER:Towny:502:74:996` 仍为同 route `PROTECTIVE_RETAIN` 时，只能生成 `CANONICAL_MOVEMENT_PLAN_WITH_REAR_RETAIN` winner 与下一 tick 规范重评估；planner 不直接释放 claim，列车取得 fresh token 并实际前进后才允许尾部窗口收缩。
- [ ] 7327：上一 leg 的 `NODE:SURC:S:RVS:1` 仍为同 route `PROTECTIVE_RETAIN` 时，同样必须由规范信号链取得 fresh token/destination 后排空；不得因 station 类型、route index 为 0 或路径裁剪而退化为永久 STOP。
- [ ] winner 存活期间主动改变 route、index 或 last-passed 锚点，必须立即出现 `reason=canonical-progress-window-moved` rollback，旧 priority 意图不能等待 TTL 才消失。
- [ ] 按“事故双车 -> 4 至 6 列 -> 目标运行图”分阶段加车，在同一硬件与相同图快照下记录 Paper MSPT/TPS；`RailGraphPathFinder.shortestPath` 不应成为主线程主要热点，目标列车数下不得出现持续超过 50 ms 的 tick。若前一 canonical leg 展开成为明显热点，再基于 profiler 证据设计图版本绑定的路径复用，当前版本不预置跨 tick 缓存。
- [ ] 日志包含新 build fingerprint、精确 interlocking key、claim lifecycle、STOP release condition 与最终授权 token；不得再出现双方同时 `PROCEED`。

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

`TrainHealthMonitor` 仍可发现 stuck / deadlock episode。refresh、hard-stop、relaunch 与最终 destroy 都必须作为登记 action
进入同一个 type-safe gate；HealthMonitor 不得再以字符串 action 或 effect class 直接取得权限。OBSERVE_ONLY/OFF
只 trace，不执行恢复副作用。ENFORCE 下 destroy 仍必须通过 deadlock precheck、effect gate 与最后一步候选选择；safe drain、follower hold、stale release 或 stale queue purge 可解决时不得 destroy。

## Step 1.6：Smart recovery unlock 顺序

`PROGRESS_STUCK` 进入 Smart recovery 后不再只等待 destroy。当前顺序固定为：

1. `SMART_RELEASE_SELF_OWNED_STALE_RETAIN`：释放同一逻辑列车持有的 stale/protective CONFLICT retain，effect class 为 `OCCUPANCY_MUTATION`。
2. `SMART_DRAIN_UNLOCK`：普通分支在列车已经位于 controlled region 且没有外部 hard blocker 时，只解除本车本地 inhibitor 并触发信号重判，effect class 为 `SIGNAL_CONSTRAINT`。若 Health 已确认 live switcher cycle，且同一进度窗口的完整 blocked request 仍新鲜，系统会先证明本车实体占有 switcher NODE、首条有向边驶向出口、出口 NODE/EDGE 均在 hard authority 内且没有其他外车硬 blocker，再通过 `ACQUIRE_VERIFIED_SWITCHER_DRAIN_AUTHORITY` 的 `OCCUPANCY_MUTATION` gate 与 `SimpleOccupancyManager.acquire` 锁内复判取得排空授权。证明不完整、版本变化或 `OBSERVE_ONLY/OFF` 时不修改 occupancy。
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

- `SMART_HEALTH_EFFECT_EXECUTION`
- `SMART_HEALTH_EFFECT_SUPPRESSED`
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

运行时不存在 Smart Dispatcher 私有的多因素评分器。所有进路竞争都先由 `DispatchPriorityResolver` 把人工
`FTA_PRIORITY`、Route 运营类型与发车来源解析成单一 request priority，再交给占用层 Gate Queue 仲裁。

Gate Queue 使用稳定 `entryOrder` 和首次等待时间，不依赖 `HashMap` 遍历顺序。priority 只换算为有上限的时间优势；持续刷新的等待者保留首次等待时间，因此最终可通过 aging 压过后来流量。队头身份只决定申请顺序，winner 仍必须重新取得完整 hard authority 与物理联锁资源，不能覆盖 confirmed blocker、active opposite conflict、invalid movement token 或 `SignalPublicationGate`。

核心 trace 为 `SMART_PRIORITY_RESOLVED` 与 `SMART_PENDING_WINNER_ARBITRATION`；队列详情可通过 `/fta occupancy queue` 查看。

排队资历只在“排到了”时用掉：列车以 `MOVEMENT_REQUIRED` 取得该资源后出队，此后它的自持 claim 本来就不再看队列；停车保持、尾部保护以 `HOLD_ONLY/PROTECTIVE_RETAIN` 接纳同一资源只是原地占着，本车仍在等前进授权，排队条目与首次等待时间原样保留。以前两种接纳都删排队，停着的车每拍被删、下一拍又以新的 firstSeen 入队，上一段“持续刷新的等待者保留首次等待时间”并不成立，仲裁永远输给后到的车。

道岔上的车出清不排队：请求带有当场复核通过的 `VERIFIED_SWITCHER_OCCUPANT` 证据（`VerifiedSwitcherDrainClaims`：当前节点就是该道岔、本车持有道岔节点、计划从道岔驶向出口、出口路径没有外车硬占用）时，该道岔的 Gate Queue 对它不设卡，trace `SWITCHER_OCCUPANT_QUEUE_BYPASS` 记录越过的队头。占用者必须先开走，岔外的车才进得来；排队只决定尚未进岔者的先后。外车 live claim 仍按原规则判，只以 `HOLD_ONLY` 挂着道岔、车头尚未越过它的车照旧排队。背景：2026-09-27 实服 SPB 汇合岔 `-566:77:1179`，MT-LP-6727 车身在岔上，被还在 WSD:2 的 MT-LP-7340 排在后面：7340 等 6727 让出车体压着的节点，6727 等 7340 让出队头，互等到关服，并连带堵住 8574、1317、0085 与 PPK 折返。出清证明每拍都成立，只是排队检查不看它。

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
