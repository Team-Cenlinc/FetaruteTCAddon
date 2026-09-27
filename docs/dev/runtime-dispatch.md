# 运行时调度接入说明

## 目标
- 让占用/闭塞成为“真正会挡车、会放行”的运行时硬约束。
- Dispatcher 负责编排 route progress、出发/移动授权，并且只在授权成功后写普通下一跳 destination。
- SignalSystem 只回答信号、安全约束、blocker 与距离，不直接控车。
- RuntimeTrainController 负责把信号和速度包络落到 TrainCarts（限速/停车/发车）。

## 职责边界
- `SignalEvaluator` / SignalSystem：只产出 `SignalDecision`、`SignalAspect`、blocker、距离与原因；没有可靠只读 preview 时 fail-closed，不写 destination、不 launch、不 acquire。
- `RuntimeTrainController`：只执行 STOP/CAUTION/PROCEED_WITH_CAUTION/PROCEED 的控车动作与 approach envelope 计算；不选择 DYNAMIC 站台，不推进 routeIndex，不拥有占用资源。
- `RuntimeDispatchService`：作为调度编排器，负责 route progress、RouteStop action 意图、DYNAMIC materialize、Layover/Depot/Station 授权，以及授权成功后的 destination commit。
- `RouteStopActionResolver`：只解析 CHANGE/DYNAMIC/DSTY 等 notes/action 意图，不修改 TrainCarts、routeIndex 或 occupancy。
- `DynamicDestinationResolver` / `DynamicPlatformAllocator`：只选择 DYNAMIC effective node；不写 destination，不 launch，不 stop，不 acquire。
- `MovementAuthorizationCoordinator`：封装普通移动授权顺序，返回授权结果；不写 destination、不控车、不推进 routeIndex。

## 运行时流程
1) 推进点触发：解析当前节点与 RouteStop action → 提交已确认的到达索引与实际图节点 → 构建前向 `movementRequiredRequest` 与当前位置 `protectiveRetainRequest` → preview/canEnter。普通 PASS 后续选台、缺图或构建失败不能撤销已发生的到达事实。
2) 允许进入且通过 hard-blocker 抑制检查：acquire 前向必须资源 → 重新评估 acquire 结果 → 生成 pending movement authorization token → 提交下一跳 destination → 激活 token → 发车/限速；若 acquire 或 destination commit 阶段被同 tick 竞争抢占，会释放本轮前向资源并硬 STOP。
3) 不允许进入：保留当前位置保护资源 → 对 confirmed hard blocker 清空 TrainCarts destination route/destination → speedLimit=0 + hard stop → 清除 movement token；仅非物理 CONFLICT 的 protective-only retain 可进入同向/过期诊断，外车占用同一 EDGE/NODE 时仍按硬 blocker 处理
4) 出站门控（站台/TERM）会额外检查优先级让行：若单线/道岔冲突队列存在更高优先级列车，则保持停站等待；若占用层返回 `allowed=true` 但没有 `conflictRelease` 标记且 blockers 中仍有其他列车的 NODE/EDGE 硬占用，则先回退 STOP，不会写入前向占用窗口。
5) DYNAMIC 目标解析采用 `NOT_APPLICABLE / SELECTED / BLOCKED` 三态结果。`BLOCKED` 表示已经声明 DYNAMIC，但当前无法安全 materialize（例如容量耗尽、定义无效，或图、当前位置、占用证据缺失）：运行中列车保持 STOP、清除占位 destination，并撤回当前全部纯 queue entry；不得让声明占位节点进入可写的 `canEnter/acquire/queue` 链路。
6) 所有运行时可写授权请求与 Depot spawn gate 共用 DYNAMIC materialization boundary：已选定的首个 DYNAMIC 目标是本轮授权终点；更远处尚未解析的 DYNAMIC 会在占位节点前截断；紧邻目标尚未解析时直接 fail-closed。普通 lookahead 与原子联锁扩展都不得越过该边界，spawn 成功后的首个 destination 也必须来自同一份实际节点序列。若紧邻 DYNAMIC 位于一条超过 `DynamicPlatformAllocator.ALLOCATION_EDGE_THRESHOLD` 的物理路径之后，分配器仍会在当前边界完成候选选择；否则 routeIndex 无法前进到预选窗口，会形成自锁。这项例外只创建站台预订，不能扩大硬授权、绕过占用判定或直接写入绿灯；未来 DYNAMIC 继续受预选距离限制。

## 出发授权入口
- `LaunchAuthorizationService` 是闭塞出发授权的统一入口，顺序固定为：构建请求 → preview/canEnter → hard blocker 抑制 → acquire → 写 destination/launch/refresh 或 hold STOP。
- 当前已接入：Station/TERM 出站门控、Layover 复用发车、Depot spawn 前 preview 与 spawn 后 acquire。
- 运行中 progress tick 的普通移动授权已由 `MovementAuthorizationCoordinator` 收敛顺序；signal tick 仍由 `RuntimeDispatchService` 编排请求与诊断，但控车落地委托 `RuntimeTrainController`。
- 后续 Linked-Route 只能在构建 `AuthorizationPlan` 前后挂接 route 切换上下文，不应绕过本服务直接 launch；本轮未实现 Linked-Route 解析或切换。

## Waypoint 停站
- waypoint 节点在 RouteStop 标记为 STOP/TERMINATE 时也会执行停站（PASS 则直接通过）。
- 停站时长优先使用 `dwell=<秒>`，缺失时回退为 20 秒默认值。
- Waypoint STOP/TERMINATE 停站仍只在 `GROUP_ENTER` 触发，避免过早点刹导致居中不稳；已声明的普通 transit/PASS Waypoint 会在车头 `MEMBER_ENTER` 立即推进，覆盖 TrainCarts 未送达 `GROUP_ENTER` 的 TCCoasters 事件边界。随后重复事件由同节点/同索引去重窗吸收。
- 停站期间会保持 STOP 信号；STOP waypoint dwell handoff 属于明确行为例外，可提前写入下一跳 destination，确保发车时直接走寻路方向，但不会在此处放行或发车。
- 停站期间保留“当前节点 + 尾部保护（车身 + 车尾之后 `runtime.rear-guard-edges` 段边）”的占用，并同步刷新前方冲突队列位次，避免后车在等待窗口内抢占发车顺序。
- 运行时只要检测到列车仍在 dwell 窗口，就会强制维持 STOP（不依赖当前 index 再次命中 RouteStop），避免”停站后被提前放行”。
- 信号 tick 中门控等待、dwell 窗口、waypoint 停站三种”保持 STOP”场景统一由 `holdStopAtCurrentNode` 处理，保留当前占用 + 下发 STOP 控车。
- 对 STOP/TERM waypoint 的进站控车采用 handoff：信号 tick 不强制 STOP，而是把目标速度上限压到 `runtime.approach-speed-bps`（approaching）。
- 仅当存在前方 blocker（红灯/占用阻塞）时，才使用“到 blocker 的距离”触发进一步减速/停车；不使用到下一节点距离，避免提前刹停在牌子前。
- 停稳判定：连续 `1` tick 未移动即视为停稳；若超过 `400` ticks 未停稳则进入超时兜底。
- 仅在停稳后才会执行居中、启动 dwell，并通过 `addActionWaitState()` 真正 hold 住列车。
- Waypoint 居中会强制使用 train-sign 语义调用 `centerTrain()`，确保以整个 `MinecartGroup` 为中心对齐，不退化为单车居中。

## AutoStation PASS
- AutoStation 的 STOP/TERMINATE 仍由停稳后的 `handleStationArrival` 推进，避免提前推进导致列车跳站。
- AutoStation 持有 `DepartureGate` 的中间站只推进 routeIndex 与 DYNAMIC effective node，不在 dwell/WaitState 窗口提前写下一跳 TrainCarts destination；下一跳 destination 由门控释放后的 signal tick 在最终授权通过时提交，避免静止停站期间被 TrainCarts 按出口方向反向。
- DYNAMIC 尽头站优先把空闲站台分配给进站列车；站台全满时，等待列车撤回投机性前向排队，让已停靠列车优先取得出站进路。容量等待不会新建前向 claim；当前位置缺少保护时只补 `NODE/HOLD_ONLY`，已有 NODE、EDGE、CONFLICT、PHYSICAL_FOOTPRINT 等真实 claim 原样保留。
- AutoStation 的 PASS 不会进入停站/开门流程，因此运行时监听器会在牌子触发时确认当前 RouteStop 为 PASS，并立即执行普通推进，避免列车 destination 卡在被通过的站台。
- `[train]` AutoStation 使用 `GROUP_ENTER` 推进 PASS；`[cart]` AutoStation 仅处理车头 `MEMBER_ENTER`，避免长编组重复推进。

## 发车方向
- 发车方向以 TrainCarts 的寻路结果为准（根据当前 destination 计算下一跳 junction）。
- 调度层不再写入 `FTA_LAUNCH_DIR` 等方向 tag，避免两套方向逻辑相互覆盖。

## 信号变化监测
- `RuntimeSignalMonitor` 每 tick 接收轻量 heartbeat；`runtime.dispatch-tick-interval-ticks` 只限制新一轮候选快照的开始频率。
- 一份候选快照的列车处理采用 5 ms 协作预算。预算耗尽时保留 FIFO 游标，下一 tick 继续处理同一快照；不会为追赶周期而重新扫描、插队或丢弃候选。
- 只有完整候选周期结束后才执行 orphan、ETA snapshot 与 dwell 收尾，且收尾前重新读取当前 FTA 编组 owner，避免 Depot materialization、改名或实体销毁期间误删新授权。
- `RuntimeSignalMonitor` 只负责巡检、异常清理与 ETA 采样，实际信号控制仍由 `RuntimeDispatchService.handleSignalTick(...)` 完成。
- 完整 `handleSignalTick(...)` 只在首次观测、列车正运动或物理运动状态发生变化时由周期巡检调用。稳定静止列车已经完成本轮 STOP/queue 决定，周期巡检不得把它重新送入方向解析、进路构建或占用触碰。资源释放会唤醒直接队首以及在该资源上显式登记的动态容量等待者；Gate Queue 资格变化仍只唤醒该队首。没有新资源事实的持续静止异常由 `TrainHealthMonitor` 的冷却恢复路径处理。
- 对运行中列车重新评估 canEnter，信号变化时会触发发车/限速。
- 即便信号未变化，也会刷新限速（用于边限速变化或阻塞解除后的速度恢复）。
- 同一 active Movement Authority 下，即使列车仍物理静止，信号重评估也不会重写 TrainCarts action queue、destination、token 或 occupancy。执行层只接受一次具有相同参数的 launch；物理推进、STOP/撤销或新授权才会消费该 pending command。静止故障恢复由独立的 `TrainHealthMonitor` 以阈值、阶段和冷却执行，周期信号检查绝不能充当重试计时器。
- 发车/加速动作会做节流（`runtime.launch-cooldown-ticks`），避免动作队列膨胀。
- 降低 `speedLimit` 属于安全上限，执行层不会再用速度命令限幅延迟它；列车仍在运动且目标速度下降时，会补发一次 TrainCarts launch 控速动作，让 approach/限速按加减速度平滑收敛。若 `/fta train debug` 显示 `edge_limit`、`edge_speed_lookahead`、`movement_authority` 或 approach limiter，写入的 cap 应立即反映该限制。
- 硬 STOP 下发 speedLimit=0、清动作队列、调用 TrainCarts hard stop，并撤销旧 destination 的运动授权；destination 的清除由具体停因和配置决定。普通占用等待与硬停车分别保留其恢复契约。非物理 CONFLICT 上的 protective-only retain 可在同一 single-corridor 方向已证明一致时降级为跟驰/过期诊断；外车持有同一 EDGE/NODE 时不适用该放宽。
- 到达入口与周期信号、出站门控共用安全状态停车规则：交路定义缺失时不猜测新索引，立即硬停车；普通 PASS 已确认到达但图快照缺失时，先提交到达事实再硬停车。原有 claim 与 destination 保留，恢复后必须重新通过 acquire、token 与最终信号发布门。
- STOP waypoint dwell handoff 与计划进站 approach 不走 hard STOP；它们可以继续使用 planned-stop 减速曲线，但 movement token 与 hard STOP 抑制状态会隔离闭塞红灯和计划停站语义。仅当当前 route index 后已证明存在非 `PASS` RouteStop 时，`ROUTE_STOP_OR_TERMINAL` 才会记录 `MOVEMENT_AUTHORITY_PLANNED_STOP_ADVISORY` 并交给 Smart Dispatcher→Signal 产生 approach/CAUTION；不得因制动距离在到站前变成 `STOP` 或写入 0 速度。裸 route 终点、真实 blocker、对向、UNKNOWN、路径/物理证据缺失仍保持 fail-closed STOP。
- Movement Authority 的 `authorityEnd` 会标记来源原因：`HARD_BLOCKER`、`ROUTE_STOP_OR_TERMINAL`、`DWELL_OR_STATION_STOP`、`SINGLE_EXIT_NOT_VERIFIED`、`MAX_AUTHORITY_CAP_REACHED` 属于物理边界；`ARTIFICIAL_WINDOW_LIMIT` 只表示当前 lookahead/授权窗口被截断。人工窗口边界只用于内部扩展与诊断，不得单独把可见信号降级为 PROCEED_WITH_CAUTION/STOP。
- 信号 tick 会为同一列车/routeIndex 构建 canonical `MovementPlanSnapshot`：包含 effective from/to、完整 expanded path、有向边、single conflict 方向、switcher path signature，以及 occupancy/progress 版本。物理 expanded path 可从已验证的中间 `lastPassedGraphNode` 起算；single 方向另用未裁掉 route-leg 起点的 canonical direction context 解析，该上下文不申请资源。Entry lookahead、canEnterPreview、Movement Authority 与最终发布门都必须复用该快照，不能各自重新猜测路径或方向。
- single 方向诊断只在实时窗口与 canonical direction context 完成合并、并过滤到本次实际申请的 `CONFLICT:single:*` 资源之后输出。短窗口暂时无法解析、但完整计划已经恢复方向的中间态不再记录为“方向判定失败”；真正最终 UNKNOWN 的资源仍以 `stage=FINAL`、train、route、purpose、window/plan 状态记录并 fail-closed。
- AutoStation 在 WaitState 期间会向运行时申请 `DepartureGate`（会话锁），信号 tick 会强制维持 STOP；仅在门控放行且会话匹配时释放，避免“停站后被信号 tick 提前发车”。中间站持锁期间不会提前写下一跳 destination，防止 TrainCarts 在静止停站窗口提前重算朝向。
- 出站门控、推进点、destination reissue 与周期信号 tick 统一采用“两层前瞻”：
  - `hardAuthorityWindow` 只覆盖当前 tick 真正要进入的短窗口，只有这里的 live hard blocker 才能直接 STOP 或触发 acquire failure。
  - `advisoryLookaheadWindow` 使用同一个 expanded path 的更远前瞻，只用于 station stop、真实 NODE/EDGE blocker、active opposite single conflict、switcher throat 等风险的 CAUTION / target speed 计算；其资源不得进入 `MOVEMENT_REQUIRED`。
- `BLOCKED_BY_OCCUPANCY`、`WAITING_FOR_SINGLE_ZONE` 与 protective retain 等可恢复 STOP 会保存当时的外部 blocker 快照。最终 Signal 发布除了核验本次 `hardAuthorityWindow` 外，还必须复核这些 blocker 是否仍以 `MOVEMENT_REQUIRED`、`PHYSICAL_FOOTPRINT`、`PROTECTIVE_RETAIN` 或 `HOLD_ONLY` 存在；只要其中任一项仍归外车，短窗口的新 token 不得清除 STOP 或发布行驶信号。只有 claim 已释放、原子转移给本车，或降级为纯 `QUEUE_POSITION` / preview 诊断后，才允许完整授权链恢复信号；快照不可读时按 fail-closed 保持 STOP。
- 排查“绿灯后速度突然归零”时，按同一列车时间线关联 `SMART_STOP_LIFECYCLE`、`SMART_SIGNAL_FINAL` 与 `DIRECT_SIGNAL_UPDATE_SUPPRESSED`。若仍见 `reason=active-occupancy-stop-blocker-still-held`，`hardBarrierReason` 会给出 `资源@claim:owner:role`；应等待真实资源释放事件触发完整重评估，禁止手工释放 claim、清库或用队列位次替代物理清空证明。
- `SMART_ROUTE_ARRIVAL`、`SMART_STOP_LIFECYCLE` 和 `SMART_RESOURCE_LIFECYCLE` 由生产端按实际变化去重后逐次保留，不消耗每分钟普通观察预算。稳定 STOP 与无语义变化的 claim refresh 不重复输出；blocker 按资源、owner 与角色去重排序，枚举顺序变化不算新停因。资源事件记录创建、释放与 owner/角色/方向变化；周期 signal/resource snapshot 仍受预算限制。账本事件不能单独证明现场车尾已清空。
- 周期信号 tick 与推进点的 `hardAuthorityWindow` 会在列车移动时按“当前制动距离 + 跟驰/authority 安全余量”扩展，最多保留 8 条展开图边。这样短咽喉、站前折返与 PWC 前的小段不会因为固定 1-edge 授权而漏看可制动距离内的真实冲突。停着的车同样要求“车头 + 安全余量”（制动距离为 0），见下一条。
- 这段距离从**车头**量起：窗口从当前图节点起算，行进中的列车要补上车头已驶过当前节点的那一段（`TrainPositionResolver` 按车头坐标插值；取不到车头位置时按 0 计，回到从节点起算）。否则车头在一条长边上越走越远，窗口却一直按“节点起算已够长”不往前伸，直到压过下一节点才发现前方拿不到——那时已在制动距离内。实服 2026-09-27：回库 MT 从 `MLU:2:001` 起的 47 格窗口一路“够长”，车头越过 `MLU:2:002` 后穿渡线的原子窗口才被拒，冲出 17 格停在渡线道岔尖轨上。代价：被拒时列车会在离被拒资源约“跟驰停车余量 + authority 注意余量”（实服 16 + 24 = 40 格）处开始停车，长边上比以前停得靠后；短边上本来就是这样。
- **停着时也保留余量**，要求才前后一致：刹车途中车头前进、制动距离缩短，两者之和不变，停稳后仍是“车头 + 余量”。以前停着只要一条边，被挡停下的车下一拍就按一条边放行，起步后按“制动距离 + 余量”又被挡——这就是“突然停下、下一拍又放行”和起步闪烁。实服 2026-09-27 四小时日志：3 秒内解除的停车 458 次，431 次解除时挡车资源仍在。例外（停着时仍用单边窗口）：
  - 停在 `SWITCHER` 节点上的车：道岔出清要能先动，不能因余量里更远的占用原地不动；
  - 余量会盖满整份行车计划时（离 route 终点、或未选站台的 `DYNAMIC` 不到一个余量）：那里本来就是真实停车点，盖满计划会把授权终点变成物理终点，可恢复的保持随之升级为作废授权的硬停车。

  健康恢复的“清理自持单线方向”预览与“重发 destination”也走同一口径：前者预览的就是信号 tick 会发的请求，后者若按单边窗口放行，下一拍信号 tick 按余量又会挡回。
- 停站（dwell）期间信号 tick 直接保持 STOP、不申请前向授权，所以停着的余量不会让停站的车提前多占前方资源。代价只落在“停着准备走”的车上：要等车头前方约 40 格都拿得到才起步，而不是先走一条边再被挡。
- **窗口不越过前方第一个计划停车点**（`capHardAuthorityAtPlannedStop`）：沿有效 route 节点往前找第一个 `RouteStop` 不是 `PASS`（STOP/TERMINATE）的节点，若它落在最小距离之内，最小距离封顶到它，窗口在站台节点收住。列车反正要在那里停，站台之后的资源发车前用不到；不封顶时，离站台不到一个余量的车会被前车留在站台**之后**的尾部保护挡在站外，站台明明空着。实服 2026-09-27：MT 进 SPB:1 前被前车留在 `PTK:SPB:1:002` 的尾部保护挡住，0245/8312/2989/4909 各等 29/26/22/143 秒，4909 那 143 秒把后车堵在汇合岔上，引出了 SPB 汇合岔互等。停稳时车头越过站台节点半个车长，与“停着只要一条边”时挪进站台相同，停车保持随后接管当前边。行进与静止、信号 tick 与推进点都走这一条；通过点（`PASS`）照旧伸过去。
- **被拒时沿已持有授权刹车**（`HeldAuthorityBraking`）：运行中的车在信号 tick 延伸被拒（`BLOCKED_BY_OCCUPANCY`、`PROTECTIVE_RETAIN_HOLD`、`WAITING_FOR_SINGLE_ZONE`）时，不再当拍清零速度——`group.stop()` 是 `vel.setZero()`，就是瞬停——而是沿上一拍已授予、仍由本车以 `MOVEMENT_REQUIRED` 独占的那段授权，刹到“授权终点 − `movement-authority-stop-margin-blocks`”前。停车保持期间这段资源不释放；STOP 带上到停车点的距离落地，走与计划停车同一条制动曲线（`TrainLaunchManager#resolveStopSpeed`，限速不高于当前车速，离终点还远时只保持车速、不借 STOP 加速）。停稳后照旧收缩到当前位置。
  - 终点取有效 token 的 `authorityEndNode`；下一个 route 节点更近时停在它前面——越过 route 节点要由推进点改写 TrainCarts destination，道岔寻路只认 destination，刹车途中不做这件事。
  - fail-closed，任一成立就当拍停车（与旧版相同）：列车静止；没有有效 token 或带 movement inhibitor；token 终点不在本拍行车计划前方；token 里前方任一资源不再以 `MOVEMENT_REQUIRED` 持有；车头到终点的路径不全在 token 内；边长或车头位置读不到（坐标插值被钳到边末端也算读不到）；车头已进停车余量。
  - 为什么安全：路径上的 NODE/EDGE 与冲突资源都由本车 `MOVEMENT_REQUIRED` 独占，别的车拿不到。TrainCarts 的限速是硬切（每个物理步把 maxSpeed 设为限速），每拍（实服 `dispatch-tick-interval-ticks: 20`，即 1 秒）把限速压在 √(2a·(d − `speed-curve-early-brake-blocks`)) 以下，两拍之间最多走一拍的距离，停车点前的剩余距离 d 不会穿过 0：early-brake 12、a ≤ 1 时至少还剩约 11.5 格；early-brake 为 0 时最多越过 a/2 格，由停车余量吸收。拍间隔拉长到 T 秒时下界变成 early-brake − aT²/2。
  - 停因明细：运行中被拒时追加 `:braking=held-authority` 或 `:braking=instant:<原因>`，写在必留的 `SMART_STOP_LIFECYCLE` 行里；停稳后明细不带后缀，算新的停车生命周期，所以一次运行中被拒会有两行 `event=enter`。
  - 不在范围内：推进点在 route 节点处被拒仍是作废授权的硬停车。刹车不越过下一个 route 节点，正常刹车不会走到那里。
- 若首个 `SWITCHER` 或显式咽喉已经进入普通 hard lookahead，`OccupancyRequestBuilder` 会把当前安全边界至冲突点、再到首个正常图边界/出清站点的 NODE、EDGE、CONFLICT 原子提升为硬进路窗口。更远的平交道口只保留在 advisory/完整 Movement Plan 中，不能从数百方块外提前锁闭；一旦联锁已进入硬窗口却找不到可证明出口，请求构造会失败，PERIODIC 与推进点入口都会立即撤销旧 Movement Authority、写入 movement inhibitor 并落地硬 STOP，禁止“入口已放行、列车却沿用上周期 PROCEED 停在道口中间”。
- 前向授权请求中只有下一跳 NODE/EDGE/必要冲突资源标记为 `MOVEMENT_REQUIRED`，当前位置、尾部保护与 hold-only single claim 只作为 `PROTECTIVE_RETAIN` / `HOLD_ONLY` 保留。前向授权不得把 rear guard 或 advisory blocker 混入 fail-closed 请求。
- 发车门控是 admission gate：它保留完整的选定前向路径与必要的原子联锁窗口来判断是否允许出发，但正式 acquire 只写 `MOVEMENT_REQUIRED` 资源，不覆盖 rear guard 或 hold retain claim。
- `canEnter` 的 hard-blocker fail-closed 只作用于 `MOVEMENT_REQUIRED`；保护性资源冲突不会阻止前车继续前进，只会保留本车保护窗口、约束后车，或作为 stale protective claim 的清理候选。
- 出站门控、推进点与周期信号 tick 统一采用“先 `canEnter` / `evaluateProceedDecision`，确认无未标记 hard-blocker bypass 后再 `acquire`，并把 acquire 返回值作为最终放行判定”的顺序；带 `conflictRelease` 标记的场景由占用层 partial acquire，避免死锁释放被误抑制，同时不污染 blocker 的 NODE/EDGE claim。
- 前方列车信号调整会按调度图展开后的 edge 扫描，而不是按 route waypoint 段计数。长单线中 `A -> D` 这种 route-defined segment 会先展开为 `A -> M1 -> M2 -> ... -> D`，再按实际 edge 数切分 hard authority 与 advisory horizon，避免把远端站点或远端列车作为当前 hard movement resource 直接打红灯。
- 前方列车 edge-count 跟驰扫描必须使用完整 advisory / expanded path，而不是 1-edge hard authority window；hard window 只决定当前 tick 是否能进入下一段，不能替代“前方几条 edge 内是否有列车”的黄灯/慢行判断。
- 同向单线的 `CONFLICT:single` claim 只表示走廊方向与入队/授权关系，不代表前车物理位置。跟驰距离必须来自前车真实 `NODE` / `EDGE` claim；否则 conflict entry 会被映射到单线入口距离 0，导致后车即使离前车很远也被误判为 STOP。
- 对向单线准入使用 section 级 `CONFLICT:single:section:bridge:<endA>~<endB>` token：固定命名空间与归一化边界端点使资源身份不受连通分量最小节点漂移影响。section 已被某方向占用/预留时，反向请求在入口前 STOP 并进入队列；同向请求不被 section token 串行化，仍由真实 `NODE` / `EDGE` claim、headway 与速度约束控制。section 方向缺失时按 `UNKNOWN` fail closed，不能绕过真实 hard blocker。
- section token 与旧 per-micro `CONFLICT:single:*` 迁移期并存时，未知方向不得单独制造 phantom STOP；只有同一 single/section 资源上存在外部占用或队列预留，且不能证明同向时才 fail closed。
- section claim 方向会从 committed movement plan 的等价 token 补齐：桥链 section 只接受完整 axis 精确相同且方向唯一的
  micro token；含冒号的 component 不参与字符串分段猜测。证据冲突或 axis 不一致时仍保持 `UNKNOWN` 并按 fail-closed 处理。
- section 索引只给轴线成员边生成 token；道岔分叉处 off-axis 支线不得挂在主线 section 下，保证任一 `sectionInfoForEdge(edge)` 返回的 edge 两端都出现在该 section 的有序轴线节点里。
- 同向 section 内的 `CONFLICT:switcher` 只作为咽喉路径诊断与对向/未知方向保护，不再额外串行化跟驰列车；如果 follower 与 leader 在同一个 `CONFLICT:single:section:*` 上方向一致，switcher claim、switcher queue 和 advisory switcher risk 都不会单独把 follower 压成 STOP/CAUTION。真实 `MOVEMENT_REQUIRED` 的 `NODE` / `EDGE` 仍按 hard blocker 处理。
- 同向跟驰只共享方向性的 single-section token，不共享同一物理 `NODE` / `EDGE`。前车的 `PROTECTIVE_RETAIN` / `HOLD_ONLY` 仍表示该物理窗口尚未释放，会阻止后车取得重叠授权；当前车推进并释放旧窗口后，后车才可在安全间隔证明成立时继续进入。
- 同向前车的 Route/expanded path 只用于定位方向和候选出口。`MovementAuthorizationToken` 分开保存远端 `destinationNode` 与本轮真实 `authorityEndNode/authorizedEdgeCount`：允许后车前进一步之前，出口必须落在真实授权边界内，授权前缀的每一条 NODE/EDGE 都必须属于 token，已提交 destination 必须匹配远端目标，且 token 硬资源仍由同一逻辑列车实时持有。缺 token、边界不完整、destination 不匹配、inhibitor 生效或任一 owner 丢失均 fail-closed。
- 同向前车最终停在死端 Station/Depot 时，不再天然要求后车等待前车完全出清；只要前车路径与本车 single window 重合、方向一致、出口/边界可见且后车有安全 hold point，仍按移动闭塞式跟驰放行。边界单边段、无安全 hold point 或对向/未知占用仍保持 STOP。
- 已经持有同一 single claim 的列车继续前进时，仍必须检查同向外部 leader 是否正停在终端/停站/折返陷阱中；若 leader 不能证明会真正排空本区间，`ALLOW_ALREADY_INSIDE_CONTINUE` 会被收紧为 local-only hold，并输出 `SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER`。该守卫只允许对“可证明在本车前方”的 leader 生效：优先比较同 route index，跨 route 时用本轮展开路径的共同下游锚点剩余图距离定序，只有两者都无法证明时才使用稳定 train key 指定一侧让行；若发现候选 leader 也正在等本车，则跳过守卫，避免同向列车互相把对方焊成 STOP。
- 自持 single continuation 被外部 owner 阻断时，拒绝结果会把外部 same-single claim 一并放入 blockers。这样 health monitor 的 live blocker cycle 可以看到真实 wait-for 边，而不是只看到 self claim 后退化为 snapshot missing / timeout destroy。
- 自持 stale-retain 候选会固化采样请求的完整 hard-authority scope；若当前判定已发现外部 NODE/EDGE/interlocking blocker，则清除旧候选。health 缩减复核仍复用这份 scope，最终 occupancy mutation 前再检查其中是否出现外车 claim，不能因只重建 single resource 而遗忘平交硬阻塞。
- 占用采用事件反射式：推进点会释放窗口外资源；普通列车卸载/移除事件会主动释放占用；实体化回滚列车的 GroupUnload 只转入 TrainCarts offline 精确收容，不能提前释放 claims。信号 tick 仍会对“已不存在列车”的遗留占用做被动清理。
- TrainCarts 的 GroupCreate/GroupLink 会触发一次信号评估，用于覆盖 split/merge 后的状态重建；但 `SpawnableGroup#spawn()` 会在 Depot 事务取得物理 group、写入正式 owner/route/index 与登记 provisional identity 之前同步触发 GroupCreate。因此 GroupCreate 当 tick 只同步收容持久 rollback tombstone、硬停继承 FTA 标签的模板编组，并把完整属性读取/信号评估推迟到下一 tick；不得把半初始化的新 Depot 车判成 late-load。列车改名依赖信号 tick 清理旧缓存。
- spawn/layover 发车成功后，运行时会按本次占用资源收集受影响列车（claim + queue），并请求下一 Bukkit tick 的完整重评估。新车自身的首次 expected-identity 硬停/水合刷新仍留在实体化提交器；其他列车不得在 acquire 或实体化调用栈内同步重入信号计算，避免绕过 Gate Queue 合并与产生嵌套授权。
- Depot 物理实体生成后不会立即完成 SpawnTicket。Spawner 在物理 group 创建后立即返回 `MaterializedSpawn`，调用方取得 identity 后才执行 tags/warm-up 等可失败初始化；owner tag 是第一项写入，确保其余初始化失败时残留实体仍可被启动恢复识别。调度器随后以同一 READY recovery epoch 登记 provisional physical identity，并保持硬停车；周期巡检取得完整实时 rail footprint、原子替换本车现场资源并 promotion 后，TicketAssigner 才提交票据与成功计数。等待超过 4 秒（小于 `SpawnControl` 的 5 秒租约 TTL）、物理 identity 丢失、刷新异常或 epoch 改变时，事务原子进入 `ROLLBACK_REQUIRED`，同时写入 runtime 物理 quarantine；硬停、销毁安排与账务重排完成后转入 `AWAITING_REMOVAL`，继续保留事务并重试销毁，直到 TrainCarts 精确发出 GroupRemove。`group.isValid()==false` 可能只是 GroupUnload，禁止把它合成为移除；卸载前的 quarantine 会通过持久化回滚标签转移到重载后的新物理身份。离线编组则只通过 TrainCarts `OfflineGroupManager.destroyGroupAsync` 的成功结果确认收容，启动恢复会从 `TrainPropertiesStore` 重新发现 tombstone，清理完成前保持 STOP_FIRST。外部 GroupCreate/周期信号入口在这两个阶段都只能保持硬停。收容或账务动作失败会保留记录；同 ticket 只允许一个活动实体化事务，额外 group 独立收容但不重复完成/重排票据；owner 先移除时，逻辑 claims 仍保留到同名额外 identity 全部精确移除。`destroy()` 已安排但精确 `GroupRemove` 尚未到达的窗口仍视为 live rollback identity，同名票据不得再次 spawn 或登记 provisional 身份。达到普通尝试上限或通用 queue age 的实体化失败会继续退避保留票据，不能把水合竞态记作班次完成。重载或停用前先把全部 pending identity 登记 quarantine，再逐一销毁；重载中止后现场恢复任务继续调用旧 assigner 推进收容。成功替换调度器时按 UUID 去重恢复 queue 与 layover pending，并原样迁移服务 `nextDueAt` 与全局 sequence。重试时间下限为 1 tick，避免回滚票据在同一 tick 再次实体化。持久化 `FTA_SPAWN_ORIGIN_PENDING` 仅用于生命周期恢复，不能单独充当 expected-spawn 授权。
- 常规 Depot 发车与 Layover fallback 在物理 group 出现前共用同一 `PreparedDepotSpawn` 预检：固定实际 depot、构造动态 authority 与 gate request、确认 smart-admission/preview、捕获 READY recovery epoch。预检失败必须在 `spawn()` 前撤销动态 authority、释放租约并保留各入口的重试原因；普通 due-ticket 会在预分配阶段记录 depot，超时回库 fallback 则在预检固化实际股道后写入同一 tick 的共享选择账本。待复用 fallback、普通 due-ticket 与其直接 fallback 共用该账本，避免同轮所有实际 Depot 发车连续压入同一短股道，同时避免普通票据在预检时重复计数。
- Depot 出车先把实际选择的 depot 固化为 MovementPlan 起点，并只沿选定路径持有到首个可证明清出点；该原子窗口保留路径内全部 switcher/单线冲突令牌。无向图上的支线 BFS 不属于列车 Movement Authority，分支回库或交叉进路必须通过双方共享的联锁令牌互斥，不能把整片邻域的物理边塞进出库请求。
- 异常清理：`RuntimeSignalMonitor` 会检测所有 TrainCarts 编组的 `TrainStatus.Derailed` 并做安全销毁；`MemberRemoveEvent`（split/脱挂）默认只在源/目标编组携带 FTA runtime tag 时触发异常回收，但若事件编组本身已带 `Derailed` 状态，普通 TrainCarts 列车也会按安全兜底销毁。FTA member-remove 在事件当下先按物理实例硬停车、撤销旧 token 并安装临时 quarantine，原 claim 保持不释放；下一 tick 才分类异常，正常整组移除则由精确 GroupRemove/Unload 解除隔离并清理。
- stale/no-progress 清理只针对“无 progress entry 但仍携带 FTA route/operator 标签”的脱管列车；已有 progress entry 的列车即使 STOP 等待也不会进入该清理计数。
- 异常清理对同一列车启用短窗去重（默认 2 秒）：同一波 `member-remove` 事件风暴只执行一次清理，避免重复日志与重复 destroy。
- 异常销毁会优先按 trainName 获取当前 holder，再兜底销毁事件 group 与 properties holder，并扫描当前在线 group 中携带同一 `FTA_TRAIN_NAME` 或 split 临时别名的残余编组，避免脱轨/逐节删除后只清掉半列车。
- 每次异常清理都会输出 warning 级诊断日志，包含原因、逻辑列车名/raw TrainCarts 名、head/tail 位置、车厢数量，以及可用的 route/progress 信息；split 日志还会附带被拆出车厢的位置与源/目标编组名，便于排查 unexpected split。
- 单线走廊冲突会进入 Gate Queue，信号 tick 会尊重排队顺序与方向锁。
- 中间图节点（未写入 route 的 waypoint/switcher）触发会更新 `lastPassedGraphNode`，信号/占用评估会尽量贴合列车真实位置。
- 运行中当前位置保护只截取当前图节点与下一段实际图边作为资源窗口，但方向、完整 expanded path 与 switcher path signature 继承同一周期的 canonical `MovementPlanSnapshot`。缺少规范计划的兼容路径仍可沿最短路定位物理资源，但 single 方向保持未知，避免短路径反向解释出库授权。
- 当前位置保护与停车保持对当前边上的物理联锁区（`interlocking:*`）按 `PositionZoneEvidence` 取舍：列车停稳且整列足迹完整时只保持车体实际压到的联锁区，行进中或足迹读不到时按当前边整体保持。依据与背景见 `smart-dispatcher.md`「安全状态分层」。
- 成功授权后的 rear guard 与 STOP hold 使用相同的 canonical 计划派生保护资源；计划中的 single 方向缺失或为 `UNKNOWN` 时不按局部路径猜测。若该保护资源已经滑出当前前向计划，占用层只允许非硬请求继承同车既有 claim 中由上一份硬授权提交的已知方向，避免同向列车在平交道口边界被误判为 `UNKNOWN` 对向屏障。没有这两类证据时继续保持 fail-closed。
- Occupancy acquire/release 事件只表达“现场事实已变化”，不能携带或创造 signal / Movement Authority。事件桥只把受影响逻辑列车交给 `RuntimeSignalReevaluationScheduler`；同一 tick 内的大小写变体与 TrainCarts split 临时别名会合并，并在下一 Bukkit tick 进入完整 `handleSignalTick`，不等待默认 50 ticks 的周期巡检。
- 容量等待撤队前，`DynamicCapacityWaitRegistry` 登记实际受阻 DYNAMIC 目标的候选 NODE；已 materialize 时只登记该目标。提前选台的受阻目标索引由分配器通过 `DynamicResolution` 返回，与当前进度索引分开保存，覆盖中间还有 PASS 的场景。同步事件查询仅合并直接队首与该索引中的等待者，不扫描 RouteProgressRegistry 或轨道图、不寻路、不授予 winner。选台成功、推进、交路切换与列车移除会清理通知，改名在 owner 移交提交后迁移接收者。
- 下一 tick 重评估与周期巡检共用同一个 canonical Movement Plan、lookahead/min-clear/rear-guard/switcher-zone、`DispatchPriorityResolver`、Gate Queue、fresh acquire、token 激活和最终发布门。周期巡检仍是丢失事件与瞬时实体不可用的兜底，不再承担正常 release wake-up 的主要延迟。
- Smart wait-for planner 只选择 winner 并请求这条完整重评估链路。planner 记录的 blocker resources 不能构造占用请求或 token；winner 只获得有界 Gate Queue priority intent，人工 `FTA_PRIORITY` 不被覆盖，整数上溢按饱和处理。最终 acquire 仍必须包含规范硬窗口并重新通过方向、现场 owner 与 publication gate。
- 调度器 drain 期间产生的新 occupancy 事件进入下一批，禁止同步递归；同一列车同批最多执行一次，单列车 `RuntimeException`/`LinkageError` 不会中断同批其他列车。插件关闭或重新进入 `STOP_FIRST` 时会关闭调度器并丢弃 pending wake-up。
- `STOP` 是唯一不要求 active Movement Authority 的可见信号；`CAUTION`、`PROCEED_WITH_CAUTION` 与 `PROCEED` 都具有正速度目标，必须由同一轮 active token、已提交 destination 与 fresh hard authority 证明。`DeadlockResolvedEvent` 也只能请求完整重评估，不能再伪造 `STOP -> PROCEED` 事件。
- 最终发布会重新查询占用账本，确认 token 中每个硬资源仍归同一逻辑列车；若此前已经向物理层发布 CAUTION/PROCEED，但 owner 在本 tick 丢失，发布器必须主动写回 STOP，不能只拒绝新的绿灯而保留旧显示。
- Movement token 分为 pending 与 active：acquire 成功后只创建 pending token；只有 destination commit 成功且 claimVersion 匹配时才激活 token 并解除 `movementInhibited`。commit 失败会回滚 token 与本轮前向 claim，并保持 STOP。
- 触发“冲突区放行锁”时，`canEnter.allowed=true` 会同时携带 `conflictRelease=true`；该释放只能跳过抽象 `CONFLICT` 资源，不允许跳过真实 `NODE/EDGE` 硬占用。Depot spawn、station departure、layover reuse 不使用 conflictRelease。
- `withRuntimeConflictClearingEvidence()` 对 single conflict 仍只能附加 `TOPOLOGY_EXIT_HINT` 诊断证据，不改变 `requestPurpose`。普通 routeIndex=0 发车即使持有 single claim，也仍按 `FORWARD_MOVEMENT` 发布。
- switcher 有且只有“实体已在道岔节点内”的清空例外：运行时先给 builder 请求写入当前 occupancy/progress version，再计算证据；`MovementPlanSnapshot.currentNode/effectiveFromNode` 必须精确等于 switcher，路径签名与首条有向边必须证明下一段正在离开该节点，本车必须已持有 switcher NODE，且出口路径不得存在任何外车 NODE/EDGE、物理 footprint 或其他 switcher claim。该证明不要求竞争车已经在同一快照中写入 switcher claim，避免竞争 claim 在 Movement Plan 形成后、最终信号发布前才出现时把实体占用者重新压成零距离 STOP。满足后请求升级为带已验证 release hint 的 `CONFLICT_CLEARING`；Gate Queue 只给该道岔内列车写入 release lock，入口外列车继续被其 NODE/EDGE 硬占用保持 STOP。
- 实体 switcher 的出清路径只把外车 `MOVEMENT_REQUIRED`、`PHYSICAL_FOOTPRINT` 等实体 claim 视为硬 blocker；`UNLOCK_RESERVATION` 只表达尚未获权列车的软预约，不能反向阻止已经位于冲突区内的列车清空。该放宽不适用于 NODE/EDGE 实体占用、敌对 switcher 进路或未知 single 方向。
- switcher 清空请求只有在占用判定实际返回 `conflictRelease=true` 时才成为 drain leader；发布门会用 `MovementPlanSnapshot.switcherPathSignatures` 校验 zone。即使 routeIndex=0，只要列车已经真实经过首段中间 switcher，也按清空处理而非普通首发；普通站台/车库出发仍因缺少实体 switcher NODE 证明而不能冒充 drain。
- `SignalPublicationGate` 不再接收调用方声称的“hard blocker 已清除”布尔值，而是直接消费同轮 `OccupancyDecision`；任何候选 `CAUTION/PROCEED_WITH_CAUTION/PROCEED` 都必须先满足 `decision.allowed=true`，缺失或拒绝的占用裁定一律发布 STOP。`DRAIN_THROUGH` 的分类和放行白名单共用同一个带 drain evidence 的输入类型；不得在白名单中无上下文重算成较宽松的 `CONFLICT_CLEARING`。因此 drain 还必须额外满足 leader、fresh/zone/exit 证明，才可将候选信号发布为可移动 aspect。
- drain leader 的完整 advisory lookahead 只忽略“本次已获 release，或在 hard decision 前已验证实体 occupant 的同 key switcher”抽象 claim/queue risk，覆盖竞争 claim 在 `canEnter` 之后才写入的竞态，避免它在 SignalLookahead 中以 0 距离再次把 Movement Authority 压成 STOP。NODE、EDGE、single 与其他 switcher 风险全部保留；无真实 blocker 的普通通过也不会伪造 `DRAIN_THROUGH` 或触发 `DRAIN_AUTHORITY_INCONSISTENT`。
- 实体 switcher 出清由 `VerifiedSwitcherDrainClaims` 统一生成并消费证明，不按站名、坐标、route code 或 route index 分支。任何站咽喉、车库咽喉、交叉渡线或平交道口，只要规范请求证明 `currentNode == effectiveFromNode == switcher`、本车真实持有该硬 NODE、首条有向边驶向 `effectiveTo`、该边的 `EdgeId` 精确等于 switcher 到出口的规范无向边、请求也把该出口 EDGE 列为 hard authority，且路径上没有外车硬 blocker，即可生成同一类 `VERIFIED_SWITCHER_OCCUPANT`；SPB routeIndex=8 与 PPK routeIndex=0 只是两种端到端回归拓扑。
- live blocker 快照会保留同一进度窗口的完整不可变 `OccupancyRequest`。Health 确认 switcher mutual cycle 后，`SMART_DRAIN_UNLOCK` 可用这份请求更新 occupancy version，并再次经过 `VerifiedSwitcherDrainClaims`、Smart mode 的 `OCCUPANCY_MUTATION` gate 与 `SimpleOccupancyManager.acquire` 锁内复判；成功时只为已经实体占有 switcher 的稳定 winner 取得出口 NODE/EDGE authority，另一列车的抽象 conflict claim 不被删除，随后由正常信号刷新签发可见 PROCEED。请求过期、进度变化、实体 NODE/出口 EDGE 缺失或存在其他外车硬 blocker 时不执行 mutation。
- Smart single/throat admission 必须复用完成版本标记与清空证据计算后的同一份授权请求。咽喉原子检查只忽略 `VerifiedSwitcherDrainClaims` 返回的精确抽象 switcher claim；出口 NODE/EDGE、其他 switcher、single conflict、未知或陈旧证据仍按原规则 fail-closed。这样既避免“占用层允许实体出清，但外层 single admission 又把同一抽象 claim 压回 STOP”的组合门控死锁，也防止一条合法 hint 携带放宽未进入本次计划的其他 switcher。
- 咽喉原子准入不能用 advisory expanded path 替代 hard window。入口至首个 Station/Depot/Interval 清出点的全部 EDGE/NODE/CONFLICT 必须在同一个请求中标记为 `MOVEMENT_REQUIRED`，否则即使快照当前空闲也输出 `throat-hard-authority-incomplete` 并保持入口 STOP。
- 清出点要给整列车留出泊位：清出点之后、累计不到出口泊位距离（车长 + 停车净空，信号 tick 的硬授权按实时车长配置）就又碰上道岔/咽喉，这个清出点作废，原子窗口继续穿过下一组道岔——两组道岔之间的直线段容不下整列车时，停在段内的车必然压着其中一组，它们对这列车就是同一组联锁。道岔后面接长直线时仍取首个清出点；疏通（drain）走同一条路径，因此也不会把车推进这种夹缝。背景：2026-09-27 实服 OFL 车库口，回库 MT 穿过剪刀渡线后停在 `MLU:1:003`（离渡线 17 格、离车库岔口 11 格），车尾压着渡线、车头对着 1 道下行 DS，两车顶牛到关服。实服图上受影响的夹缝只有 `OFL:MLU:1:003`、`HHU:LWN:1:001`（从 `502:74:996` 一侧来时）与 `ZKW:HHU:2:002`（车长加净空超过 32–37 格时）。
- Smart recovery 的 drain/forward unlock 仍默认尊重对向或未知方向 single barrier；只有当当前占用快照证明本车已持有 contested
  section、方向已知、下一跳朝出口前进、出口 edge/node 没有外部 claim，且 hard blocker 只对应同一 section 时，才允许进入最终 signal
  refresh 复判。该分支只输出 `SMART_*_UNLOCK_DRAIN_OUT_ALLOWED` 并触发既有复判，不创建 DRAIN_THROUGH authority、不 force-green、不改
  destination/token。
- `drain-authority-without-leader` / `DRAIN_AUTHORITY_INCONSISTENT` 只对真实 `DRAIN_THROUGH` candidate 生效；对 `FORWARD_MOVEMENT` 或普通 `CONFLICT_CLEARING` 来说只能作为 trace 诊断，不得清 destination、invalid token 或升级为 `authorization_failure` hard STOP。
- 单线走廊的冲突区放行候选仅在请求列车与 blocker 的方向都能判定且互为对向时成立；方向为 `UNKNOWN` 或队列中缺少方向信息时保持 STOP，避免把同向前后车误判为会车死锁。
- 已有 single conflict claim 的方向不会被后续当前位置 retain/hold 覆盖，无论后续请求是无方向还是给出了相反方向；已有 `MOVEMENT_REQUIRED` 角色也不会被保护请求降级。普通请求沿用原 claim 方向，终点折返则由原子 Authority Handoff 一次性替换为反向硬授权。
- 自持 single claim 的 zone 不在本次 `MovementPlanSnapshot` 穿越路径上、且请求对该 zone 不要求 hard authority 时，该 claim 仅视为车尾/区域保护，不否决本车继续移动（trace `SMART_SELF_OWNED_CONTINUATION_ALLOWED reason=tail-protection-zone-not-on-plan`）。claim 与方向原样保留，外部对向/未知方向列车仍由 single-region barrier 拦截；对该 zone 仍要求 hard authority 的请求保持 fail-closed。这样折返后真正的 `self-owned-single-opposite-direction` 拒绝不会被车尾保护 zone 的 `direction-unknown` 拒绝掩盖，健康监控的 `clearSelfOwnedSingleDirectionMismatch` 才能识别并清理同 zone 反向残留；该恢复入口的方向解析与占用层一致（`corridorDirections` 缺失时回退 movement plan 的 `singleConflictDirections`）。
- 发车门控和周期信号 tick 在正式 `canEnter()` 前会清理后方列车留在当前授权资源上的前瞻 queue entry。相同 Route 使用 progress index 与当前段节点顺序；不同 Route 还必须在双方完整 effective canonical path 中唯一证明“后车节点 → 前车节点”，并对当前 soft resource 给出相同的有向证据：EDGE 必须是同一有向边、NODE 必须具有相同前驱/后继、single conflict 必须方向一致、switcher conflict 必须具有 exact `SAME_MOVEMENT` signature。缺 route UUID、动态 materialization、路径、资源级证据或唯一顺序时继续作为真实冲突阻塞。
- 该清理只涉及 `LOOKAHEAD_PREVIEW` 与纯 queue entry；`MOVEMENT_REQUIRED`、`PHYSICAL_FOOTPRINT`、对向、交叉、分歧/汇入签名、terminal throat mutex 与未知方向均保持原有 fail-closed 语义。
- 单线 `CONFLICT:single` 队列只在存在对向/未知方向竞争时串行化；队列中全是同向列车时，跟驰距离交给 NODE/EDGE 硬占用控制，不再由 conflict 队列额外互斥。
- 前向风险的同向跟驰判定会优先使用占用层的 section/queue 证明；当两车前后错开导致 claim 不在同一 section 实例时，运行时会用 `RouteProgressRegistry` 或资源级 canonical path 证明 blocker 位于前方，再交给占用层作为 `knownDirectedPathLeader` 证据。该证据可以补足 single-section 实例错位，但不能替代 switcher 的 exact movement signature；`CONFLICT:switcher` 只有双方都被分类为 `SAME_MOVEMENT` 才能按同向跟驰忽略，`MERGE / DIVERGE / HEAD_ON / CROSSING / UNKNOWN` 均继续交给道岔互斥与 Gate Queue。该证据不放宽物理 `NODE`/`EDGE` blocker；终端站台/Depot 相邻 switcher 仍由咽喉 mutex fail-closed。若任一进度缺失、不同 Route 的资源走向不能确认、blocker 不在前、route 定义无法确认或存在重复 waypoint 折返边界，则保持原有 STOP/CAUTION 行为。
- `SignalConstraintEnvelope` 是信号/速度约束的统一数据模型：同向前车、authority end、single conflict entry、station/depot approach、edge speed limit 等都归并为 constraint points；`SignalAspect` 只作为显示层，实际控车以 envelope 的目标速度与 STOP 模式为准。当前实现保留既有 `SignalLookahead` / movement authority 计算路径，并为后续 cache planner 提供稳定结构。
- 可用 `/fta occupancy stats` 观察自愈与出车重试统计，`/fta occupancy heal` 可手动触发清理。

## tags 与恢复
推进点依赖 TrainProperties tags：
- `FTA_ROUTE_ID`：线路 UUID（由 `/fta depot spawn` 写入）
- `FTA_OPERATOR_CODE`：运营商 code（由 `/fta depot spawn` 写入）
- `FTA_LINE_CODE`：线路 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_CODE`：班次 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_INDEX`：已抵达节点索引（运行时写回）
- `FTA_ROUTE_UPDATED_AT`：可选，调度更新时间（毫秒）
- `FTA_TRAIN_NAME`：上次记录的列车名（用于改名迁移）
- `FTA_DEPOT_ID`：本次新车实际生成的 Depot 股道；仅在 CRET 的 spawn-origin bootstrap 尚未结束时用于恢复首段真实图锚点，不是折返后的持久当前位置
- `FTA_SPAWN_ORIGIN_PENDING`：新车生成时写入 `true`。列车仍在 Depot→首个图节点之间时，即使 launch 已被接受也保持 pending，保证此时重启仍能恢复 DYNAMIC Depot；首次观测到非该 Depot 的真实图节点、推进到后续 index 或进入折返 ticket 后持久写为 `false`

未写入 `FTA_ROUTE_INDEX` 时视为“未激活”，信号 tick 不会构建占用；首次触发推进点后才会写入并进入占用/控车流程。
`TERMINATE` 表示结束载客：若线路在 TERM 后仍有节点（例如回库段/DSTY），继续按线路推进；仅当 TERM 位于线路尾节点时，才进入 Layover 等待调度分配新 ticket/线路。

`RouteProgressRegistry` 对列车名采用不区分大小写的键；即使 TrainCarts 发生大小写改名，也能命中同一进度记录，避免出现“信号/占用看似丢失后被误清理”的问题。

DYNAMIC/同站异台的 effective node 覆盖会同时绑定创建它的 routeId、该索引的声明节点与 RouteStop 定义证据。`/fta reload` 只保留定义未变化的合法 materialization；即使 routeId 相同，只要 waypoint 或 DYNAMIC/CRET 规则已经更新，旧覆盖也会立即丢弃。route handoff 会清空上一交路覆盖；真实节点观测回到 route 声明节点时会写入 declared marker，已结束的旧 `FTA_DEPOT_ID` 不会在下一 tick 重新复活。

线路定义查找顺序：
1) `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`
2) `FTA_ROUTE_ID`（兼容旧标签）

## 手动调试（debug set route）
在不重生列车的情况下，可用命令手动写入 route tags 并同步下一跳 destination：

```
/fta train debug set route <company> <operator> <line> <route> [index|nodeId]
/fta train debug set route <company> <operator> <line> <route> train <train|@train[...]> [index|nodeId]
```

说明：
- `index` 为“已抵达节点索引”（对应 `FTA_ROUTE_INDEX`）。
 - 若提供的是 `nodeId`（非数字），会在该 route 的 `waypoints` 中查找其索引并写入；`nodeId` 必须使用双引号包裹（例如 `"OP:S:PTK:1"`）。
- 若需要显式指定目标列车，可在 route 参数后追加 `train <train|@train[...]>`（注意该关键字必须在可选 `index|nodeId` 之前出现）。
- 命令会通过存储解析 company/operator/line/route 层级，并同步写入 `FTA_ROUTE_ID`（route UUID）与 code 三元组。
- 命令会清理运行时缓存/占用并尝试 `refreshSignal`（若能找到在线列车实例），便于立刻观察控车与闭塞行为。

## 待命与回收 (Layover & Reclaim)
- 列车抵达 `TERM` 站点且线路生命周期为 `REUSE_AT_TERM` 时，只有“当前索引已到线路尾节点”才会进入待命（Layover）状态并注册到 `LayoverRegistry`。
- 若 `TERM` 后仍有定义节点（回库/折返段），运行时会继续推进，不会在 TERM 站台永久拦停。
- Layover 注册时会触发一次即时复用尝试（不必等待下一轮 spawn tick）。
- 即时复用只有在 `readyAt` 已到、TrainCarts 句柄确认列车停稳、Waypoint 居中状态已结束且 `DwellRegistry` 不再有真实停站窗口时才进入折返授权；否则保留 Layover 候选，等待后续 spawn tick 重试。Waypoint TERM 的候选会在触牌时登记，实际 dwell 在居中完成后才启动，因此不能只相信预估的 `readyAt`。
- 下一条交路的 DYNAMIC 站台没有空闲容量时，Layover 不领取 ticket、不执行 authority handoff，也不申请前向进路；候选保持可重试，待站台容量释放后再派发。
- 同一 `ticketId` 在 `LayoverRegistry` 内最多只有一个 dispatch attempt owner。handoff 前被拒绝会释放 attempt，分配器仍可尝试下一辆 READY 候选；一旦 handoff 留下 attempt，当前 tick 与后续 tick 都只重试该 owner，不能把同一票据同时交给第二辆折返车。pending 的超时、fallback 与人工清理也不能删除 active attempt 对应票据。
- Layover 出站不再执行“旧方向普通 acquire 后按旧名字全量 release”。运行时通过原子 Authority Handoff 检查外部 blocker、一次性换向，并把尚未证实清界的旧进站 footprint 以内部 `PHYSICAL_FOOTPRINT` 硬角色留作车尾保护；TrainCarts 改名时同时迁移占用 owner、DYNAMIC effective node 与带稳定 ticket attempt 的 Layover 重试候选。
- handoff 前的完整旧 footprint 由 transient guard 并入通用占用 shrink 与授权回滚的保护集，不使用 TTL；这同时覆盖“旧窗口独有、已转为 `PHYSICAL_FOOTPRINT`”和“与新窗口重叠、已成为反向 `MOVEMENT_REQUIRED`”的资源。释放阈值为“TrainCarts 编组几何得到的保守列车长度 + 从折返节点起至少 `max(1, rear-guard-edges)` 条边的实际方块距离”；只有列车真实图节点事件沿登记的折返后路径逐段连续推进并达到阈值，才解除 sidecar 保护并立即释放仍为 `PHYSICAL_FOOTPRINT` 的旧 claim，重叠 movement claim 随后由正常窗口收缩。重复节点不改变证据，倒退会回退净清界距离；跳点、路径外观测、周期 signal tick、静止重试和 TPS 波动都不能推进证据，列车长度或连续前向路径不足时 fail-retain。
- 同一列车连续折返时，每次 handoff 登记独立 traversal epoch，并封存此前尚未完成的 epoch；后续节点只推进最新方向，避免另一支路重新汇入同名节点后误用旧路径累计里程。完成后仅释放未被其他 epoch 引用的资源，跨路径共享的道口/咽喉仍保持硬保护。新 epoch 的快照不会再次收纳已由旧 guard 负责的 `PHYSICAL_FOOTPRINT`，避免后一次证据缺失反向污染旧资源。
- 周期巡检在 TrainCarts 改名迁移期间同时把当前实体名与 `FTA_TRAIN_NAME` 旧 owner 视为存活；若 owner 原子迁移失败，旧 claim、Layover 候选和 footprint guard 会保持硬停车，不会在同一 tick 被 orphan cleanup 当成幽灵状态释放。
- Waypoint 居中或 `DwellRegistry` 停站窗口仍由旧 owner 持有时，通用 TrainCarts 手动改名会延后运行时 owner 迁移；信号 tick 继续使用旧 owner 保持 STOP，待异步停站状态清理后再完成迁移，避免新名字绕过 dwell 提前出站。
- 只有最终授权、destination、token 已提交，而且 TrainCarts 确认 launch action 已接受、已有待执行 action 或列车已经移动后，才注销候选并联动刷新其它列车。拒绝时撤销 token/destination、设置 movement inhibitor 并硬停，但保留新方向 hard authority 与旧 footprint，供下一轮从同一事实状态重试。
- `ReclaimManager` 定期检查待命列车：
  - 若闲置超时或服务器车辆超限，会为其分配 `RETURN` 票据。
  - `RETURN` 票据优先级较低（-10），礼让正常客运列车。
- 详见 `docs/dev/reclaim-policy.md`。

## 发车票据公平性
- `StorageSpawnManager` 生成 due ticket 时按 SpawnService 轮转，而不是每 tick 从排序后的第一条 service 一直补 backlog。
- 这样在服务器长时间停顿、多个线路/交路组同时 overdue、且 `spawn.max-generate-per-tick` 较小时，前面的线路/组不会长期独占生成预算。
- Layover fallback 的 depot 补发共享本 tick 已选 depot 计数，避免 pending 刷新路径绕过常规 due ticket coordination 后连续压到同一短 depot。
- 不同 depot 的实际出车仍由 `SimpleTicketAssigner` 与 depot 门控决定；若某个 depot 持续出车少，先检查该 depot 是否被占用、是否存在 retry/backoff、以及 route 是否写死了 `CRET <depot>` 而不是使用线路 depot 池。

## 发车门控阻塞策略
- 出站门控在 `shouldYield/blocked` 时会把占用收缩为“停站保护窗口”（当前节点 + rear guard），同时保留前向冲突队列位次，避免列车在红灯等待期间被后车反超队头。
- 出站门控不会只信任 `canEnter()` 的候选结果：候选通过后仍要执行 `acquire()`，并对 acquire 返回值再次走 `evaluateProceedDecision()`；只有 acquire 也允许时才写入 destination/launch，冲突区释放则由占用层只写入未阻塞资源。
- 阻塞日志会输出 blocker 摘要（资源类型/键/持有列车），便于现场定位卡点。

## 控车重算与故障恢复
运行时控车每隔 `runtime.dispatch-tick-interval-ticks` 重新评估占用与信号，并重新下发速度控制。

周期信号重评估只负责当前授权的 idempotent 控制，不做“低速即重发”。旧的 `runtime.failover-stall-speed-bps` 与 `runtime.failover-stall-ticks` 仅为配置兼容保留，不再触发运行时动作；静止故障由健康监控的 `health.stall-threshold-seconds`、`health.recovery-cooldown-seconds` 和分级恢复链处理。不可达判定仍由 `runtime.failover-unreachable-stop` 执行强制停车。

移动授权（Movement Authority）：
- 启用 `runtime.movement-authority-enabled` 后，运行时会用“当前制动距离 + 安全余量”与前方可用距离做实时比对。
- 当授权不足时会把信号降级为更保守等级，并下压目标速度，防止冒进进入未清空区段。
- `PROCEED` 且前方无硬约束（无 blocker/caution）时，不再使用“到下一节点距离”触发授权降级。只有物理 `authorityEndReason` 才能参与可见信号降级；`ARTIFICIAL_WINDOW_LIMIT` 会尝试基于 canonical expanded path 延展授权窗口，不能直接变成黄灯/红灯。
- `/fta train debug` 会显示 `distanceToAuthorityEnd`、`authorityEndResource` 与 `authorizedEdgeCount`；SignalTrace 还会输出 `authorityEndReason`、`authorityWindowDerivedFromExpandedPath`、`authorityExtensionAttempted`、`authorityExtensionSucceeded` 与 `authorityEndIsPhysical`。
- 高频诊断 trace 会按“trace 类型 + 列车 + 去除 tick/requestId/version 后的稳定内容”去重；物理信号已经处于目标 aspect 且 `publishSuppressed=true` 的 no-op SignalTrace 不再输出。若需要确认长期卡住状态，优先看首次 blocker/authority trace 与后续内容变化行，而不是按 per-tick 行数判断。
- Entry lookahead 进入 single zone 且当前窗口看不到出口时，会先在 canonical expanded path 中扩展到 single exit；只有扩展后仍不可见出口，才允许产生 `ENTRY_LOOKAHEAD_BLOCKED`。相关 trace 字段包括 `lookaheadUsesExpandedPath`、`lookaheadWindowNodeCount`、`entryZoneId`、`entryZoneStartIndex`、`exitIndexBeforeExtension`、`extensionAttempted`、`exitIndexAfterExtension`、`exitFeasible` 与 `failureReason`。
- 授权失败或最终 STOP 时，诊断会记录 `destinationPresentWhileBlocked`、`retainedDestination` 与 `blockedReason`。运行时不会主动清空
  TrainCarts destination，避免目的地突然缺失带来不可预期的底层行为。
- 安全余量参数：
  - `runtime.movement-authority-stop-margin-blocks`
  - `runtime.movement-authority-caution-margin-blocks`

速度曲线：
- 若启用 `runtime.speed-curve-enabled`，将根据“剩余距离 + 制动能力”自动计算限速，提前减速而不是过点再减速。
- 当信号处于 PROCEED_WITH_CAUTION/CAUTION/STOP 时，会基于 lookahead 占用中的首个阻塞资源估算距离，提前下压速度；STOP 会额外用 TrainCarts railState 和图节点坐标估算剩余距离，避免固定节点距离导致红灯曲线长期保持非零。
- 当“下一站”为 STOP/TERM waypoint 时，信号 tick 的距离只看前方 blocker（不看下一节点距离/CAUTION 距离），避免提前刹停在牌子前。
- `runtime.speed-curve-type` 控制曲线形态（`physics/linear/quadratic/cubic`）。
- `runtime.speed-curve-factor` 用于调节曲线激进程度（>1 更激进，<1 更保守）。
- `runtime.speed-curve-early-brake-blocks` 用于提前开始减速的缓冲距离。
- `runtime.approach-depot-speed-bps` 用于进库前限速（站点限速仍由 `approach-speed-bps` 控制）。
- approach 正式窗口外 64 blocks 内会先进入 preview 制动区，速度上限从当前目标速度线性收敛到 approaching 限速；进入正式窗口后保持 approaching 限速。若速度曲线启用，还会叠加到停靠目标的物理制动包络并取更低上限。
- approach preview 与最终 speed envelope 的主入口为 `RuntimeTrainController.resolveApproachSpeedEnvelope`；SignalSystem 只提供信号、约束类型和距离。
- 最短路距离会通过缓存复用，并按 `runtime.distance-cache-refresh-seconds` 异步刷新，降低高密度咽喉区的重复计算开销。

重启后从数据库加载 RouteDefinition，再从 tags 恢复当前 index。
若运行时内存中缺少该列车的 RouteProgressEntry，将在首次信号 tick 基于 tags 自动初始化，避免“每 tick 反复发车动作”的异常。
插件启动与 `/fta reload` 都会同步关闭授权门并立即冻结全部受管列车，再延迟 1 tick 扫描 live group。只带 `FTA_TRAIN_NAME`、却没有 route identity 的编组视为实体化初始化未完成或 split 残余，先进入异常物理清理并保持 `STOP_FIRST`，不能作为普通列车静默跳过。每列车从 TrainCarts `TrackedRail` 链取得车头到列尾的实际 RailPath 足迹，栅格化后与停止态逻辑窗口合并，再由占用层一次性原子替换完整现场快照；相同资源上的多个现场车体会全部保留。Route/Graph/index/成员覆盖/实时轨迹/请求证据或提交失败时保持 STOP 并每 20 tick 重试。物理快照成功提交后仍保持 `HYDRATING`，先启动健康、周期/事件信号、自动发车与折返回收组件，再统一切换 `READY` 并逐车重新授权；组件启动失败或 READY 后首次刷新失败都会重新关闭全局门，不会产生 launch 窗口。恢复后新加载或重连的受管列车必须先做保留其他 owner/queue 的单列原子水合；水合 marker 同时校验 canonical owner 与 TrainCarts 编组对象身份，同名的第二个 live group 会直接触发全局 `STOP_FIRST`，不能以 SELF owner 覆盖前一组 footprint。GroupRemove/Unload 也校验该身份，旧 split group 的迟到移除事件不能按名字删除存活组 claim。证据不足同样会关闭全局授权门并触发完整恢复。启动现场保护以及正常运行中的 EDGE/联锁 claim，都只有在本轮实时 `TrackedRail` 证明列尾已经离开资源后才能缩减；任一现场 cell 无法映射时保留本车全部既有 claim，不能仅随 route index 推进清除。

TrainCarts 的 `GroupLinkEvent` 发生在成员搬移与旧组删除之前，事件内两个 group 不是稳定的联挂后快照。运行时因此在事件当下只做全局 `STOP_FIRST`、逐物理实例硬停、撤销旧 Movement Authority 并保留 claims；下一 tick 再从最终 RailTracker 状态执行同一套原子现场重建。周期巡检和事件重评估若遭遇 `RuntimeException` 或 TrainCarts ABI `LinkageError`，也会进入该 fail-closed 恢复边界，并跳过本轮 orphan cleanup，避免异常后错误释放现场资源。

## 健康监控（高频服务）
- 健康检查支持分级修复与冷却控制：
  - `STALL`：`refreshSignal -> forceRelaunch`
  - `PROGRESS_STUCK`：非 STOP 时 `refreshSignal -> reissueDestination -> forceRelaunch`
  - `STOP PROGRESS_STUCK`：自动模式只执行 `refreshSignal -> clearSelfOwnedSingleDirectionMismatch -> reapplyHardStop`，不再 `forceRelaunch`；清理入口仅处理同车 single 旧方向残留，不会重新写 destination 或绕过红灯强制动车。
  - STOP 互卡：自动模式只在双方 STOP、同一 `CONFLICT:single`、方向已知对向、速度低于阈值且不处于 dwell/departure gate/layover/manual hold 时创建 confirmed `DeadlockEpisode`；同一 episode 先 `refresh 双车`，再 `reapplyHardStop 双车`，超过阈值后提交 Smart Dispatcher destroy precheck。
  - 同线级联互卡会优先尝试释放下游链头，而不是让高分的中间 bottleneck 候选反复获得 unlock reservation。候选日志会带 `sameLineCascade=true`，用于确认本轮选择来自级联链头裁决。
  - 当同线级联链头被对向列车顶住时，Smart planner 可产生 `YIELD_TO_HEAD_ON` 候选；执行侧只释放让路车在相关资源上的 `UNLOCK_RESERVATION` / `LOOKAHEAD_PREVIEW` / `QUEUE_POSITION`，不会释放物理占用、不会写 destination、不会签发 movement authority。成功日志为 `SMART_HEAD_ON_YIELD_APPLIED`。
  - 若 Smart planner 或 recovery 只得到 `INSUFFICIENT_DIRECTION_EVIDENCE` / `NEED_DIRECTION_AUDIT` /
    `WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM`，该证据会作为 destroy blocker 传入最终 precheck：普通快速 destroy 被拒绝，并触发一次有界
    direction re-audit（刷新双方信号，优先让 section token 方向补齐链路重新生效）。只有 re-audit 后、等待更长 last-resort
    阈值且仍能证明阻塞活跃交通时，才允许更高门槛的安全移除。
  - 互卡 refresh/hard-stop 只是“恢复动作已执行”，不再作为“已修复”计数；真正的自动兜底由 `SmartDispatcherController -> alias-aware destroyTrainByName -> post cleanup/verification` 完成。
  - 普通长时间停滞 cleanup 与 confirmed deadlock 分开取证且默认关闭。启用后也要求安全恢复至少尝试三轮，并在最后一次 progress/stall recovery mutation 后经过一个后续采样和完整 recovery cooldown；无受控停车/移动/live blocker/active unlock，且最近 blocker 与 Gate Queue 直接成员快照都确认没有正常等待，才进入批次排序。批次内优先空车、最后考虑载客车；执行前会再次读取 live group，且仍需 Smart Dispatcher `ENFORCE` mode gate。
  - WS 的 LWN↔HHU 仅是普通长 corridor 竞争的一种实例：移动中的 owner 保留原子授权，对向列车进入 Gate Queue；真正失去进展的 owner 经过通用恢复与 cleanup 取证后退出，不引入 route/station 特判。
- 每个 signal monitor tick 会先输出 `SMART_DISPATCH_GLOBAL_SNAPSHOT`，随后单车信号评估把 canonical `MovementPlanSnapshot` / `ExpandedPathPlan` 转换为 `ForwardSignalRiskSnapshot`，用于提前 CAUTION 和限速。
- 健康检查由独立定时任务驱动（每秒 tick + `health.check-interval-seconds` 间隔门控），不再依赖信号监控任务触发。
- 这是唯一允许因“持续静止”重新触及发车/目的地执行器的路径；它以实时健康样本和恢复冷却为证据，不能被同 tick 的 occupancy wake-up 或周期信号重评估重复触发。
- `STOP` 信号下的 progress stuck 允许更长宽限（`health.progress-stop-grace-seconds`），避免将正常排队误判为异常。
- 连续修复动作之间受 `health.recovery-cooldown-seconds` 限制，降低高频场景下的抖动与过度修复。
- 详见 `docs/dev/health-monitor.md`。

## 列车速度配置
通过 `/fta train config set|list` 写入列车配置：
- `FTA_TRAIN_TYPE`：车种（EMU/DMU/DIESEL_PUSH_PULL/ELECTRIC_LOCO）
- `FTA_TRAIN_ACCEL_BPS2`：加速度（blocks/second^2）
- `FTA_TRAIN_DECEL_BPS2`：减速度（blocks/second^2）

详见 `docs/dev/train-config.md`。

运行时会把速度换算成 blocks/tick：
- `bps -> bpt`：除以 20
- `bps2 -> bpt2`：除以 20^2

## 进站限速
- `runtime.approach-speed-bps` 控制 approaching 速度上限（进站 + STOP/TERM waypoint handoff）。
- `runtime.approach-window-blocks` / `runtime.approach-window-edges` 控制正式 approach 边界；正式边界外 64 blocks 内会提前进入 preview，避免到边界才突然套低速上限。
- `runtime.approach-target-edges` 控制物理制动包络参考的末尾 edge 范围；最终上限取 preview 线性包络与物理制动包络中的较低值。

## CAUTION 速度来源
- 优先使用“连通分量 caution 覆盖”（`rail_component_cautions`）。
- 未设置覆盖时回退为 `runtime.caution-speed-bps`。
- 详见 `docs/dev/graph-component-caution.md`。

## lookahead 占用
- `runtime.lookahead-edges` 控制每次申请占用的边数量。
- `runtime.min-clear-edges` 用于限制同向跟驰的最小空闲边数（与 lookahead 取最大值）。
- `runtime.rear-guard-edges` 用于保留车尾之后 N 段边（车身按保守车长整段保留），保护长编组尾部避免追尾；算法见 `occupancy-headway.md`。
- 值越大越保守，能降低咽喉/道岔前卡死风险。
- 保守车长（尾部保护、驶出联锁区的泊位距离、折返后旧进路的释放阈值共用）由 `TrainCartsRuntimeHandle#estimatedTrainLengthBlocks` 实测：
  相邻两节车中心的 L1 距离之和，两端各加"半个车体 + 1 格"（按每节车的 TrainCarts 模型 `cartLength`），每个连接处加 0.25 格曲线余量，
  并以"车体长度之和"与"每节 2 格"中较大者托底（`PhysicalRailFootprintPolicy#conservativeTrainLengthBlocks`）。
  任一节读不到位置或模型时按车长未知处理，保留全部后向路径。以前两端合计只补 2 格，模型车会被严重低估：
  实服 MT（TrainCarts 存档 `SUR100_test`，三节 10.0/9.6/9.95 格）实际 30.55 格，被估成约 23 格。
- `runtime.switcher-zone-edges` 控制道岔联合锁闭范围（向前 N 段边）。
- 单线走廊冲突采用方向锁：同向可跟驰，对向需等待走廊清空。
- 道岔、单线与无方向物理联锁冲突都会进入 Gate Queue。priority 提供每分 0.5 秒、最多 2 分钟的有界时间优势，首次等待时间提供 starvation aging；因此短期高优先级先行，持续刷新的老等待者也不会被后来流量永久插队。
- Smart admission 在只有 queue entry、没有真实外部 claim 时，会用只读队列预览选出队头；队头继续进入正式占用判定，输家保持本地 HOLD。关键日志为 `SMART_QUEUE_ARBITRATION_ADMISSION`。
- queue entry 只表达尚未取得进路者的位次，诊断 blocker 必须标记为 `QUEUE_POSITION`，不能冒充 `MOVEMENT_REQUIRED`、物理占用或已授予行车权。列车已持有同一冲突资源的可执行 `MOVEMENT_REQUIRED` claim 时，失败方的纯队列记录不会再作为 advisory 停车点，也不会进入 Smart Dispatcher 的 normal-admission wait-for graph；真实外部 `NODE`、`EDGE`、`CONFLICT` claim 仍独立扫描并保持 fail-closed。该规则适用于任意多入口合流道岔，不依赖站名、坐标或二值走廊方向。
- 多分支道岔的局部 movement 不再套用单线 `CorridorDirection.A_TO_B/B_TO_A`。`SwitcherMovementTopology` 从双方同一 exact switcher key 的 canonical path 提取 `ingress -> switcher -> egress`，统一分类为 `SAME_MOVEMENT / MERGE / DIVERGE / HEAD_ON / CROSSING / UNKNOWN`；成功 proof 只能由 classifier 从 canonical path、完整 directed edge 与匹配的 `EdgeId` 签发，分类结果只提供拓扑事实，不表示 compatible、safe 或 allowed。blocker 快照会保留同一进度窗口的 `MovementPlanSnapshot`，后到的无 request 刷新不会擦掉 rich evidence；进度变化、签名缺失、道岔重复经过、大小写不规范 key 或单边证据都返回 `UNKNOWN` 并 fail-closed。claim/queue 在新请求缺失 exact signature 时会立即删除旧 remembered evidence，不能让旧 movement 继续参与授权。
- blocker snapshot 查询不存在记录时安静返回空结果；从未受阻不是异常。已有快照过期、progress window 移动或异步结果落后时仍记录明确拒绝原因。咽喉整段原子准入必须保留 typed `OccupancyClaim` 并发布同一类 snapshot；`DEPOT_SPAWN` 在列车尚未写入 progress registry 的生命周期前置窗口允许保存 blocker，而运行中 `RUNTIME_MOVE` 缺少或陈旧 progress 仍拒绝写回。
- Dwell 属于受控停车，健康监控在停站期间持续把 last-move/last-progress 观察时钟锚定到当前时刻；停站结束后的 STOP 必须重新满足 `deadlock-min-stop` 才能进入互卡 fallback，不能把 20 秒上下客时间继承成 deadlock 年龄。
- `CROSSING` 表示两条局部 movement 使用不同入口与出口，但它仍是敌对的侧面交叉进路，必须保持互斥；即使双方都提交了局部 EDGE footprint，也不能据此跳过同一个 switcher conflict。`SAME_MOVEMENT` 只作为既有 single-section 同向跟驰的附加证据，不会独立签发道岔 authority。跨不同 switcher ID 的平交/汇流还必须由共享 interlocking-zone 资源表达，不能假设 exact key 不同就安全。
- mutual wait-for 的全部 active edge 都被双方 fresh canonical plan 证明为同一个 exact switcher 的 `MERGE` 投影时，Smart planner 输出 `SMART_DISPATCH_SWITCHER_MERGE_DETECTED`，并把 exact conflict claim 持有者标记为 `currentConflictOwner`、另一方标记为 `otherTrain`；两者都不是下一位 Gate Queue winner 或已确定的 loser。该纯汇流场景不再误报 `NEED_DIRECTION_AUDIT`，但会明确返回 `SWITCHER_MERGE_EXECUTOR_NOT_READY`。若同一 pattern 还包含其他 NODE/EDGE、single 或其他 switcher blocker，则不能被 MERGE 诊断吞掉，继续走正常 fail-closed 审计。在两阶段停车与 admission lease 尚未建立前，planner 不会删除任何一方的 `MOVEMENT_REQUIRED` claim、不会写 destination、不会失效 movement token，也不会自行签发 authority。
- `SMART_MUTUAL_CONFLICT_OWNER_SET` 在赢家选择时记录每个 owner 的实时请求 priority 与来源、当前节点及 `PHYSICAL_FOOTPRINT/HOLD_ONLY` claim、active movement token 的 claimVersion/aspect/resources。证据缺失会写明 `unavailable:no-live-request` 或 `reason=no-token`，不得再用固定 `unknown` 掩盖仲裁输入。
- Gate Queue 优先级默认按 `OPERATION > depot exit/CREATE > RETURN` 分类；`FTA_PRIORITY` 仍可人工覆盖。Depot spawn gate 不再固定加 `100`，其实际 priority 会出现在 `SMART_DEPOT_SPAWN_AUTHORITY_WINDOW`。

## 类体积约束：不要再往 RuntimeDispatchService 里加方法

`RuntimeDispatchService` 约三万行、995 个方法，已经贴着 SpotBugs 的单类分析上限。
越线之后整个类被标记 `SKIPPED_CLASS_TOO_BIG` 并**完全跳过静态分析**——而它恰恰是全项目最需要被覆盖的类。

判据（SpotBugs 4.8.6 `AnalysisContext#isTooBig`）：类文件超过 1,000,000 字节，或方法数超过 1000，任一成立即跳过。
不是行数，是方法数（lambda 编译出的合成方法也算）。数法要把带 `throws` 的方法算进去：

```bash
javap -p <class> | grep -cE '\(.*\)( throws [^;]*)?;$'
```

只数 `'(.*);'` 会漏掉 `throws` 子句结尾的方法——主类恰好没有这种方法，测试类里却很多。

因此往这个类里加能力时，做法是把逻辑放进独立协作者，只在它上面留一个访问器。
`StationStopCoordinator` 与 `RuntimeDispatchService#stationStops()` 是现成范例：
时刻表录制播报 + 计划扣留一共只在这个类上花掉一个方法的余量。

**`RuntimeDispatchServiceTest` 同样受这个上限约束**（现 996 个方法，`spotbugsTest` 会分析它）。
越线的现象不是它自己报错，而是它被整类跳过后，只在它里面赋值的 `FakeTrain` 字段被误报 `UWF_UNWRITTEN_FIELD`。
新用例放进独立测试类（例如 `RuntimeDispatchPositionHoldTest`），不要再往里加。

加方法前先跑一次 `./gradlew spotbugsMain spotbugsTest`，确认没有把余量花光。

## 车站停靠事件与计划扣留

`StationStopCoordinator` 承担两件不属于调度本身的事（它们不读占用、不改授权、不碰信号）：

1. **停靠事实播报**：`StationStopObserver` 单向接收到站、发车、离开管辖三类事件。
   调度层因此不依赖 timetable 包；观察者整体缺席时，调度行为不变一行。
   当前没有已装配的消费者——这组 seam 是给将来做「表定时分 vs 实测时分」对表用的，
   属于 validation/calibration，**不**参与时刻表构建。
   - 到站：`handleStationArrival` 在 `recordArrivalProgress` 之后播报——进度已提交才算到达，
     提前播报会把"看见牌子"录成"到站"。
   - 发车：由 AutoStation 在拿到许可、松开门锁的同一时刻调用 `stationStops().handleDeparture(...)`。
     不从 `checkDeparture` 的返回路径播报，因为那里有多条 `return true` 属于"不做门控"而非"真的发车"。
2. **计划扣留**：`holdsDeparture` 在 `checkDeparture` 里、**申请任何占用之前**被问一次。
   早到的车等到表定时刻再走；扣留期间它对别的车完全透明。
   已到点/已晚点一律放行，扣留时长被 `HOLD_CEILING`（150 秒）硬封顶——必须低于发车门锁自身
   180 秒的回收时限，否则列车会进入"自己不动、也不再为别人排队"的状态。

另有一处与车辆生命周期相关的接入点：`SimpleTicketAssigner#setLayoverReuseGate`。
时刻表层通过它否决"交路额度已用完"的列车继续接运营班次，被否决的车留在 layover 闲置，
由既有的 `ReclaimManager` 在闲置回收窗口内派 RETURN 票送回库。闸默认恒放行，
未装配时 layover 复用行为与之前完全一致；它只否决 OPERATION 票，RETURN 票不受影响——
否则被否决的车反而没有回家的手段。

详见 `docs/dev/timetable.md`。

## 已知限制
- 占用释放采用事件反射式：列车推进后释放窗口外资源；列车卸载/移除事件主动清理，占用快照仍可能在非正常断线时短暂残留。
- 目前默认用 speedLimit/launch 控车；STOP 与 approach 均会按剩余距离计算制动曲线，但仍以 TrainCarts 动作队列执行最终物理运动。
- Smart planner 对真实多分支 merge 的强制 loser claim 释放仍保持关闭；后续只有在 loser 已物理停车、未进入共享 switcher/egress footprint、双方 movement-plan TTL/progress 证据未变，并有 lease 阻止其立即重抢时，才能引入两阶段执行。当前吞吐修复依赖 Gate Queue 正确区分 `QUEUE_POSITION` 与已授予的 hard authority。

## 调度销毁（handleDestroy）与完整清理
- 调度销毁清理范围与 `handleTrainRemoved` 保持一致（进度、停站状态、trigger 状态、信号警告、departure gate、节点历史、动态分配、有效节点覆盖、blocker 快照、routeTrainTracker 位置条目）。Smart Dispatcher 触发的 destroy 还会在数 tick 后执行 verification：确认 runtime group、FTA managed state、occupancy claim、single queue、switcher claim、deadlock graph 与 health episode 不再引用目标列车。
- confirmed deadlock 与普通 stuck cleanup 都只负责发起销毁，不在该调用栈提前释放现场资源。精确 `GroupRemoveEvent` 提交清理后会发布 `OccupancyReleasedEvent`；事件桥唤醒受影响资源上已登记的 Gate Queue 队首及动态容量等待者，并合并到下一 tick 的完整授权重评估。这样既保证后续列车恢复，也不在旧车尚未物理移除时提前放行。
- `train.destroy()` 仍延迟 1 tick 执行物理销毁；post verification 会补跑 runtime cleanup，避免 `GroupRemoveEvent` 缺失时残留 occupancy/queue/retain/blocker snapshot。
- TrainCarts 整列销毁会先逐车厢发出 `MemberRemoveEvent`，最后才发出 `GroupRemoveEvent`/`GroupUnloadEvent`。监听器先同步冻结 FTA 源编组与可见残编的物理实例、撤销旧 Movement Authority，但不释放 claim、不清进度也不销毁；随后按源编组对象身份聚合同 tick 的 member 事件并延迟一 tick 分类。同 tick 收到组移除会取消异常候选并只走正常精确清理，只有下一 tick 仍存活的源组或残编才聚合触发一次 `unexpected-split-*`，避免批量 `/train destroyall` 被误报并递归销毁，同时关闭事件到分类之间的旧授权窗口。
- TrainCarts split 后若把列车临时改成 `main~a/main~b`，运行时会优先使用 `FTA_TRAIN_NAME` 作为逻辑主键，不把这些后缀别名当作真实 rename，避免把进度/占用主键污染成临时名。
- `RuntimeSignalMonitor` 会额外检测“同一逻辑列车名对应多个 live group”的异常场景；但只会把非 split 过渡态的真实重复判为异常，避免 TrainCarts 正常 split/merge 窗口被误杀。
- 异常编组清理（`handleAbnormalGroup`）按来源分级：FTA runtime tag 明确存在的列车会先收集同一逻辑列车的全部相关实体，对它们逐一硬停车并把 FTA 物理身份加入 quarantine，全部隔离完成后才关闭全局授权门、请求现场恢复并延迟销毁整列实体。只要任一隔离实体仍出现在 live group 扫描中，恢复必须保持 `STOP_FIRST`；只有精确匹配该物理身份的 `GroupRemove/Unload` 到达、随后全局现场重建确认实体消失后，才能释放物理资源并重新进入 `READY`，不能在延迟 destroy 前制造空窗。普通 TrainCarts 列车只有在巡检或事件侧看到 `TrainStatus.Derailed` 时才进入安全销毁，并且不会加入 FTA quarantine。无 derailed 状态的 `MemberRemoveEvent` 对普通列车不触发异常清理，避免玩家拆车、其他插件重组或 TrainCarts 内部 split 过渡被误判。
- 状态清理启用 2 秒去重窗口，抑制 TrainCarts split/脱挂事件风暴的重复处理；实体销毁仍会继续尝试覆盖当前事件组，避免 FTA 半编组残留。
- `GroupRemoveEvent` 只会清理“真实离线或真正被销毁”的 FTA 编组；split 临时别名会被识别并跳过，避免把仍在线的 canonical 列车一起清掉。

## 硬占用阻塞检查（Hard Blocker）
- 普通 `allowed=true` 若 blocker 中仍包含其他列车的 NODE/EDGE 硬占用，会强制回退为阻塞信号（STOP），杜绝误放行。
- 带 `conflictRelease=true` 的冲突区释放是例外：它只允许静止/待发列车使用，并由占用层执行 partial acquire，跳过对向列车已持有的 NODE/EDGE 资源。
- CONFLICT 类型资源不视为硬阻塞（由死锁解析器管理）。
- 自身占用（blocker 中 trainName 与当前列车匹配）被跳过。
- blocker 元素为 null 或 resource 为 null 时视为硬阻塞（保守策略）。

## Signal 验证矩阵
- `PROCEED`：前方无占用且无更严格约束时放行。
- `STOP`：前方硬占用、departure gate 持有、或无法定位阻塞位置时停车。
- `CAUTION` / `PROCEED_WITH_CAUTION`：由更远 blocker 或移动授权降级触发。
- `holdStopAtCurrentNode(...)`：统一处理门控等待、dwell、waypoint 停站三类“保持 STOP”场景，并刷新前向队列位次。
- `recentBlockerTrains(...)`：健康监控只读 blocker 快照；过期后会自动失效，避免 stale 阻塞误触发修复。

## 健康修复使用有效节点（DYNAMIC 覆盖）
- `forceRelaunchByName` 和 `reissueDestinationByName` 均使用 `resolveEffectiveNode` 获取 DYNAMIC 站台覆盖后的有效节点，确保修复操作将列车发往正确的站台而非 route 定义中的占位符节点。

## 速度命令限幅
- 速度命令会做“限幅 + 迟滞”处理，减少高频抖动引发的解挂风险。
- 仅对“常规跟速”做上行限幅；发车/放行（`allowLaunch=true`）会跳过上行限幅，避免起步龟速。
- 降低 `speedLimit` 不做降速限幅；`runtime.speed-command-decel-factor` 仅作为兼容配置保留，实际减速平滑交由 TrainCarts acceleration/deceleration。
- 参数：
  - `runtime.speed-command-hysteresis-bps`
  - `runtime.speed-command-accel-factor`
  - `runtime.speed-command-decel-factor`

## Waypoint STOP/TERM 停站
- Waypoint STOP/TERM 的平滑减速由到站前 approach limiter 完成；触发节点后进入停车保持与居中流程。
- 列车停稳后才执行 centerTrain 并开始 dwell/WaitState，避免在未停稳时推进门控/折返。

## 后续预留
- 计划把 SignalAspectPolicy 与速度曲线联动，形成更接近 CBTC 的移动闭塞（远期）。
