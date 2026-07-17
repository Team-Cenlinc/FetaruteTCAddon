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
1) 推进点触发：解析当前节点与 RouteStop action → 构建前向 `movementRequiredRequest` 与当前位置 `protectiveRetainRequest` → preview/canEnter
2) 允许进入且通过 hard-blocker 抑制检查：acquire 前向必须资源 → 重新评估 acquire 结果 → 生成 pending movement authorization token → 提交下一跳 destination → 激活 token → 发车/限速；若 acquire 或 destination commit 阶段被同 tick 竞争抢占，会释放本轮前向资源并硬 STOP。
3) 不允许进入：保留当前位置保护资源 → 对 confirmed hard blocker 清空 TrainCarts destination route/destination → speedLimit=0 + hard stop → 清除 movement token；仅非物理 CONFLICT 的 protective-only retain 可进入同向/过期诊断，外车占用同一 EDGE/NODE 时仍按硬 blocker 处理
4) 出站门控（站台/TERM）会额外检查优先级让行：若单线/道岔冲突队列存在更高优先级列车，则保持停站等待；若占用层返回 `allowed=true` 但没有 `conflictRelease` 标记且 blockers 中仍有其他列车的 NODE/EDGE 硬占用，则先回退 STOP，不会写入前向占用窗口。

## 出发授权入口
- `LaunchAuthorizationService` 是闭塞出发授权的统一入口，顺序固定为：构建请求 → preview/canEnter → hard blocker 抑制 → acquire → 写 destination/launch/refresh 或 hold STOP。
- 当前已接入：Station/TERM 出站门控、Layover 复用发车、Depot spawn 前 preview 与 spawn 后 acquire。
- 运行中 progress tick 的普通移动授权已由 `MovementAuthorizationCoordinator` 收敛顺序；signal tick 仍由 `RuntimeDispatchService` 编排请求与诊断，但控车落地委托 `RuntimeTrainController`。
- 后续 Linked-Route 只能在构建 `AuthorizationPlan` 前后挂接 route 切换上下文，不应绕过本服务直接 launch；本轮未实现 Linked-Route 解析或切换。

## Waypoint 停站
- waypoint 节点在 RouteStop 标记为 STOP/TERMINATE 时也会执行停站（PASS 则直接通过）。
- 停站时长优先使用 `dwell=<秒>`，缺失时回退为 20 秒默认值。
- 停站仅在 `GROUP_ENTER` 触发（忽略 `MEMBER_ENTER`），避免过早点刹导致居中不稳。
- 停站期间会保持 STOP 信号；STOP waypoint dwell handoff 属于明确行为例外，可提前写入下一跳 destination，确保发车时直接走寻路方向，但不会在此处放行或发车。
- 停站期间保留“当前节点 + 尾部保护边（`runtime.rear-guard-edges`）”的占用，并同步刷新前方冲突队列位次，避免后车在等待窗口内抢占发车顺序。
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
- AutoStation 的 PASS 不会进入停站/开门流程，因此运行时监听器会在牌子触发时确认当前 RouteStop 为 PASS，并立即执行普通推进，避免列车 destination 卡在被通过的站台。
- `[train]` AutoStation 使用 `GROUP_ENTER` 推进 PASS；`[cart]` AutoStation 仅处理车头 `MEMBER_ENTER`，避免长编组重复推进。

## 发车方向
- 发车方向以 TrainCarts 的寻路结果为准（根据当前 destination 计算下一跳 junction）。
- 调度层不再写入 `FTA_LAUNCH_DIR` 等方向 tag，避免两套方向逻辑相互覆盖。

## 信号变化监测
- 定时任务每 N tick 运行（`runtime.dispatch-tick-interval-ticks`）。
- `RuntimeSignalMonitor` 只负责巡检、异常清理与 ETA 采样，实际信号控制仍由 `RuntimeDispatchService.handleSignalTick(...)` 完成。
- 对运行中列车重新评估 canEnter，信号变化时会触发发车/限速。
- 即便信号未变化，也会刷新限速（用于边限速变化或阻塞解除后的速度恢复）。
- 信号未变化但列车在 proceed-like 信号下物理静止（速度不超过 `failover-stall-speed-bps`）时，仍会补发 launch：上一次 launch 可能被冷却窗口或停车竞态吞掉，不补发就要等 stall failover 或健康监控兜底（实测表现为前车驶离后，后车以 PROCEED 静止数分钟）。停站 dwell、发车门控、movement inhibitor 与 layover 中的静止是计划行为，不在补发范围内；launch 节流仍然生效。
- 发车/加速动作会做节流（`runtime.launch-cooldown-ticks`），避免动作队列膨胀。
- 降低 `speedLimit` 属于安全上限，执行层不会再用速度命令限幅延迟它；列车仍在运动且目标速度下降时，会补发一次 TrainCarts launch 控速动作，让 approach/限速按加减速度平滑收敛。若 `/fta train debug` 显示 `edge_limit`、`edge_speed_lookahead`、`movement_authority` 或 approach limiter，写入的 cap 应立即反映该限制。
- 闭塞 STOP、authorization failure、hard blocker、acquire failure、authority window exceeded、single corridor fail-closed 都属于 hard STOP：运行时会清空 destination route/destination、下发 speedLimit=0、清动作队列、调用 TrainCarts hard stop，并使旧 destination 不再具备运动授权。非物理 CONFLICT 上的 protective-only retain 可在同一 single-corridor 方向已证明一致时降级为跟驰/过期诊断；外车持有同一 EDGE/NODE 时不适用该放宽。
- STOP waypoint dwell handoff 与计划进站 approach 不走 hard STOP；它们可以继续使用 planned-stop 减速曲线，但 movement token 与 hard STOP 抑制状态会隔离闭塞红灯和计划停站语义。
- Movement Authority 的 `authorityEnd` 会标记来源原因：`HARD_BLOCKER`、`ROUTE_STOP_OR_TERMINAL`、`DWELL_OR_STATION_STOP`、`SINGLE_EXIT_NOT_VERIFIED`、`MAX_AUTHORITY_CAP_REACHED` 属于物理边界；`ARTIFICIAL_WINDOW_LIMIT` 只表示当前 lookahead/授权窗口被截断。人工窗口边界只用于内部扩展与诊断，不得单独把可见信号降级为 PROCEED_WITH_CAUTION/STOP。
- 信号 tick 会为同一列车/routeIndex 构建 canonical `MovementPlanSnapshot`：包含 effective from/to、完整 expanded path、有向边、single conflict 方向、switcher path signature，以及 occupancy/progress 版本。物理 expanded path 可从已验证的中间 `lastPassedGraphNode` 起算；single 方向另用未裁掉 route-leg 起点的 canonical direction context 解析，该上下文不申请资源。Entry lookahead、canEnterPreview、Movement Authority 与最终发布门都必须复用该快照，不能各自重新猜测路径或方向。
- AutoStation 在 WaitState 期间会向运行时申请 `DepartureGate`（会话锁），信号 tick 会强制维持 STOP；仅在门控放行且会话匹配时释放，避免“停站后被信号 tick 提前发车”。中间站持锁期间不会提前写下一跳 destination，防止 TrainCarts 在静止停站窗口提前重算朝向。
- 出站门控、推进点、destination reissue 与周期信号 tick 统一采用“两层前瞻”：
  - `hardAuthorityWindow` 只覆盖当前 tick 真正要进入的短窗口，只有这里的 live hard blocker 才能直接 STOP 或触发 acquire failure。
  - `advisoryLookaheadWindow` 使用同一个 expanded path 的更远前瞻，只用于 station stop、真实 NODE/EDGE blocker、active opposite single conflict、switcher throat 等风险的 CAUTION / target speed 计算；其资源不得进入 `MOVEMENT_REQUIRED`。
- 周期信号 tick 与推进点的 `hardAuthorityWindow` 会在列车移动时按“当前制动距离 + 跟驰/authority 安全余量”扩展，最多保留 8 条展开图边。这样短咽喉、站前折返与 PWC 前的小段不会因为固定 1-edge 授权而漏看可制动距离内的真实冲突；静止和健康恢复重发路径仍保持单边硬窗口，避免把远端 advisory blocker 提前升级为物理 STOP。
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
- 同向前车最终停在死端 Station/Depot 时，不再天然要求后车等待前车完全出清；只要前车路径与本车 single window 重合、方向一致、出口/边界可见且后车有安全 hold point，仍按移动闭塞式跟驰放行。边界单边段、无安全 hold point 或对向/未知占用仍保持 STOP。
- 已经持有同一 single claim 的列车继续前进时，仍必须检查同向外部 leader 是否正停在终端/停站/折返陷阱中；若 leader 不能证明会真正排空本区间，`ALLOW_ALREADY_INSIDE_CONTINUE` 会被收紧为 local-only hold，并输出 `SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER`。该守卫只允许对“可证明在本车前方”的 leader 生效：优先比较同 route index，跨 route 时用本轮展开路径的共同下游锚点剩余图距离定序，只有两者都无法证明时才使用稳定 train key 指定一侧让行；若发现候选 leader 也正在等本车，则跳过守卫，避免同向列车互相把对方焊成 STOP。
- 自持 single continuation 被外部 owner 阻断时，拒绝结果会把外部 same-single claim 一并放入 blockers。这样 health monitor 的 live blocker cycle 可以看到真实 wait-for 边，而不是只看到 self claim 后退化为 snapshot missing / timeout destroy。
- 占用采用事件反射式：推进点会释放窗口外资源；列车卸载/移除事件会主动释放占用；信号 tick 仍会对“已不存在列车”的遗留占用做被动清理。
- TrainCarts 的 GroupCreate/GroupLink 会触发一次信号评估，用于覆盖 split/merge 后的状态重建；列车改名依赖信号 tick 清理旧缓存。
- spawn/layover 发车成功后，运行时会按本次占用资源主动刷新受影响列车（claim + queue），降低“新车占用已生效但他车未及时红灯”的风险。
- Depot 出车先把实际选择的 depot 固化为 MovementPlan 起点，并只沿选定路径持有到首个可证明清出点；该原子窗口保留路径内全部 switcher/单线冲突令牌。无向图上的支线 BFS 不属于列车 Movement Authority，分支回库或交叉进路必须通过双方共享的联锁令牌互斥，不能把整片邻域的物理边塞进出库请求。
- 异常清理：`RuntimeSignalMonitor` 会检测所有 TrainCarts 编组的 `TrainStatus.Derailed` 并做安全销毁；`MemberRemoveEvent`（split/脱挂）默认只在源/目标编组携带 FTA runtime tag 时触发异常回收，但若事件编组本身已带 `Derailed` 状态，普通 TrainCarts 列车也会按安全兜底销毁。
- stale/no-progress 清理只针对“无 progress entry 但仍携带 FTA route/operator 标签”的脱管列车；已有 progress entry 的列车即使 STOP 等待也不会进入该清理计数。
- 异常清理对同一列车启用短窗去重（默认 2 秒）：同一波 `member-remove` 事件风暴只执行一次清理，避免重复日志与重复 destroy。
- 异常销毁会优先按 trainName 获取当前 holder，再兜底销毁事件 group 与 properties holder，并扫描当前在线 group 中携带同一 `FTA_TRAIN_NAME` 或 split 临时别名的残余编组，避免脱轨/逐节删除后只清掉半列车。
- 每次异常清理都会输出 warning 级诊断日志，包含原因、逻辑列车名/raw TrainCarts 名、head/tail 位置、车厢数量，以及可用的 route/progress 信息；split 日志还会附带被拆出车厢的位置与源/目标编组名，便于排查 unexpected split。
- 单线走廊冲突会进入 Gate Queue，信号 tick 会尊重排队顺序与方向锁。
- 中间图节点（未写入 route 的 waypoint/switcher）触发会更新 `lastPassedGraphNode`，信号/占用评估会尽量贴合列车真实位置。
- 运行中当前位置保护只截取当前图节点与下一段实际图边作为资源窗口，但方向、完整 expanded path 与 switcher path signature 继承同一周期的 canonical `MovementPlanSnapshot`。缺少规范计划的兼容路径仍可沿最短路定位物理资源，但 single 方向保持未知，避免短路径反向解释出库授权。
- 成功授权后的 rear guard 与 STOP hold 使用相同的 canonical 计划派生保护资源；计划中的 single 方向缺失或为 `UNKNOWN` 时不按局部路径猜测。若该保护资源已经滑出当前前向计划，占用层只允许非硬请求继承同车既有 claim 中由上一份硬授权提交的已知方向，避免同向列车在平交道口边界被误判为 `UNKNOWN` 对向屏障。没有这两类证据时继续保持 fail-closed。
- 事件驱动信号链路只作为桥接：默认启用 `runtime.signal-event-coalesce`，Occupancy acquire/release 事件只标记受影响列车 dirty，周期 tick 统一做授权提交；更宽松信号不会通过事件链路放行。STOP 可作为高优先级刷新来源，但不会在 `OccupancyManager.acquire()` 的同步调用栈内重入同一列车 hard STOP。
- 当当前信号未知（`currentSignal=null`）时，事件链路仅允许 `STOP` 立即生效，不接受 `CAUTION/PROCEED` 的初始化放行。
- 事件重评估请求与周期 tick 共用同一组窗口参数（lookahead / min-clear / rear-guard / switcher-zone）、同一 `DispatchPriorityResolver`、同一实时 effective waypoint 与同一 canonical direction context，确保 release 后的 preview 与周期路径同时保持物理窗口、方向和资源计数一致。
- STOP 事件即使与当前信号相同，只要列车仍在运动、destination 仍存在，或存在有效 movement token / hard-stop inhibitor，也会重新下发 hard STOP。
- Movement token 分为 pending 与 active：acquire 成功后只创建 pending token；只有 destination commit 成功且 claimVersion 匹配时才激活 token 并解除 `movementInhibited`。commit 失败会回滚 token 与本轮前向 claim，并保持 STOP。
- 触发“冲突区放行锁”时，`canEnter.allowed=true` 会同时携带 `conflictRelease=true`；该释放只能跳过抽象 `CONFLICT` 资源，不允许跳过真实 `NODE/EDGE` 硬占用。Depot spawn、station departure、layover reuse 不使用 conflictRelease。
- `withRuntimeConflictClearingEvidence()` 只能附加 `TOPOLOGY_EXIT_HINT` 诊断证据，不改变 `requestPurpose`，也不能让普通 `RUNTIME_MOVE` 被发布层分类为 `DRAIN_THROUGH`。`DRAIN_THROUGH` 只允许在真实 drain authority 新鲜、zone 与 `MovementPlanSnapshot.singleConflictDirections` 匹配、列车位于对应 single conflict 内且正在清空出口时成立；普通 routeIndex=0 发车即使持有 single claim，也仍按 `FORWARD_MOVEMENT` 发布。
- Smart recovery 的 drain/forward unlock 仍默认尊重对向或未知方向 single barrier；只有当当前占用快照证明本车已持有 contested
  section、方向已知、下一跳朝出口前进、出口 edge/node 没有外部 claim，且 hard blocker 只对应同一 section 时，才允许进入最终 signal
  refresh 复判。该分支只输出 `SMART_*_UNLOCK_DRAIN_OUT_ALLOWED` 并触发既有复判，不创建 DRAIN_THROUGH authority、不 force-green、不改
  destination/token。
- `drain-authority-without-leader` / `DRAIN_AUTHORITY_INCONSISTENT` 只对真实 `DRAIN_THROUGH` candidate 生效；对 `FORWARD_MOVEMENT` 或普通 `CONFLICT_CLEARING` 来说只能作为 trace 诊断，不得清 destination、invalid token 或升级为 `authorization_failure` hard STOP。
- 单线走廊的冲突区放行候选仅在请求列车与 blocker 的方向都能判定且互为对向时成立；方向为 `UNKNOWN` 或队列中缺少方向信息时保持 STOP，避免把同向前后车误判为会车死锁。
- 已有 single conflict claim 的方向不会被后续当前位置 retain/hold 覆盖，无论后续请求是无方向还是给出了相反方向；已有 `MOVEMENT_REQUIRED` 角色也不会被保护请求降级。普通请求沿用原 claim 方向，终点折返则由原子 Authority Handoff 一次性替换为反向硬授权。
- 自持 single claim 的 zone 不在本次 `MovementPlanSnapshot` 穿越路径上、且请求对该 zone 不要求 hard authority 时，该 claim 仅视为车尾/区域保护，不否决本车继续移动（trace `SMART_SELF_OWNED_CONTINUATION_ALLOWED reason=tail-protection-zone-not-on-plan`）。claim 与方向原样保留，外部对向/未知方向列车仍由 single-region barrier 拦截；对该 zone 仍要求 hard authority 的请求保持 fail-closed。这样折返后真正的 `self-owned-single-opposite-direction` 拒绝不会被车尾保护 zone 的 `direction-unknown` 拒绝掩盖，健康监控的 `clearSelfOwnedSingleDirectionMismatch` 才能识别并清理同 zone 反向残留；该恢复入口的方向解析与占用层一致（`corridorDirections` 缺失时回退 movement plan 的 `singleConflictDirections`）。
- 发车门控和周期信号 tick 在正式 `canEnter()` 前会清理同 Route 后方列车留在当前授权资源上的前瞻 queue entry；该步骤不释放 claim，且不同 route、未知进度、索引不在后方的列车仍会作为真实冲突阻塞。
- 同 Route 同 index 的跟驰列车会继续比较 `lastPassedGraphNode` 在当前 route 段最短路上的顺序；当前车已经通过更靠前的中间节点时，可清理后车留在当前授权窗口里的前瞻 claim/queue，避免同向追驰互相红灯。
- 单线 `CONFLICT:single` 队列只在存在对向/未知方向竞争时串行化；队列中全是同向列车时，跟驰距离交给 NODE/EDGE 硬占用控制，不再由 conflict 队列额外互斥。
- 前向风险的同向跟驰判定会优先使用占用层的 section/queue 证明；当两车前后错开导致 claim 不在同一 section 实例时，运行时会用 `RouteProgressRegistry` 额外证明“同 route 且 blocker 索引在前”，再交给占用层作为 `knownSameRouteLeader` 证据。该证据只影响抽象 `CONFLICT:single` / `CONFLICT:switcher` 的 advisory 风险分类，不放宽物理 `NODE`/`EDGE` blocker；终端站台/Depot 相邻 switcher 仍由咽喉 mutex fail-closed。若任一进度缺失、route 不同、blocker 不在前、route 定义无法确认或存在重复 waypoint 折返边界，则保持原有 STOP/CAUTION 行为。
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

## 控车重算与 failover
运行时控车每隔 `runtime.dispatch-tick-interval-ticks` 重新评估占用与信号，并重新下发速度控制。

新增 failover 判定：
- 低速判定：速度低于 `runtime.failover-stall-speed-bps` 持续 `runtime.failover-stall-ticks` 时，会重下发 destination。
- 不可达判定：若当前节点到下一节点在调度图中不可达，则执行强制停车（`runtime.failover-unreachable-stop`）。

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
插件启动后会延迟 1 tick 扫描现存列车并重建占用快照（基于 tags 初始化进度并重新评估信号）。

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
- 每个 signal monitor tick 会先输出 `SMART_DISPATCH_GLOBAL_SNAPSHOT`，随后单车信号评估把 canonical `MovementPlanSnapshot` / `ExpandedPathPlan` 转换为 `ForwardSignalRiskSnapshot`，用于提前 CAUTION 和限速。
- 健康检查由独立定时任务驱动（每秒 tick + `health.check-interval-seconds` 间隔门控），不再依赖信号监控任务触发。
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
- `runtime.rear-guard-edges` 用于保留当前节点向后 N 段边，保护长编组尾部避免追尾。
- 值越大越保守，能降低咽喉/道岔前卡死风险。
- `runtime.switcher-zone-edges` 控制道岔联合锁闭范围（向前 N 段边）。
- 单线走廊冲突采用方向锁：同向可跟驰，对向需等待走廊清空。
- 道岔/单线冲突会进入 FIFO Gate Queue，避免抢占导致的顺序漂移。
- Smart admission 在只有 queue entry、没有真实外部 claim 时，会用只读队列预览选出队头；队头继续进入正式占用判定，输家保持本地 HOLD。关键日志为 `SMART_QUEUE_ARBITRATION_ADMISSION`。
- Gate Queue 优先级默认按 `OPERATION > depot exit/CREATE > RETURN` 分类；`FTA_PRIORITY` 仍可人工覆盖。Depot spawn gate 不再固定加 `100`，其实际 priority 会出现在 `SMART_DEPOT_SPAWN_AUTHORITY_WINDOW`。

## 已知限制
- 占用释放采用事件反射式：列车推进后释放窗口外资源；列车卸载/移除事件主动清理，占用快照仍可能在非正常断线时短暂残留。
- 目前默认用 speedLimit/launch 控车；STOP 与 approach 均会按剩余距离计算制动曲线，但仍以 TrainCarts 动作队列执行最终物理运动。

## 调度销毁（handleDestroy）与完整清理
- 调度销毁清理范围与 `handleTrainRemoved` 保持一致（进度、stall 状态、停站状态、trigger 状态、信号警告、departure gate、节点历史、动态分配、有效节点覆盖、blocker 快照、routeTrainTracker 位置条目）。Smart Dispatcher 触发的 destroy 还会在数 tick 后执行 verification：确认 runtime group、FTA managed state、occupancy claim、single queue、switcher claim、deadlock graph 与 health episode 不再引用目标列车。
- `train.destroy()` 仍延迟 1 tick 执行物理销毁；post verification 会补跑 runtime cleanup，避免 `GroupRemoveEvent` 缺失时残留 occupancy/queue/retain/blocker snapshot。
- TrainCarts split 后若把列车临时改成 `main~a/main~b`，运行时会优先使用 `FTA_TRAIN_NAME` 作为逻辑主键，不把这些后缀别名当作真实 rename，避免把进度/占用主键污染成临时名。
- `RuntimeSignalMonitor` 会额外检测“同一逻辑列车名对应多个 live group”的异常场景；但只会把非 split 过渡态的真实重复判为异常，避免 TrainCarts 正常 split/merge 窗口被误杀。
- 异常编组清理（`handleAbnormalGroup`）按来源分级：FTA runtime tag 明确存在的列车会清理 progress/occupancy 并销毁整列实体；普通 TrainCarts 列车只有在巡检或事件侧看到 `TrainStatus.Derailed` 时才进入安全销毁。无 derailed 状态的 `MemberRemoveEvent` 对普通列车不触发异常清理，避免玩家拆车、其他插件重组或 TrainCarts 内部 split 过渡被误判。
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
