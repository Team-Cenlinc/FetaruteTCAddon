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
1) 推进点触发：解析当前节点与 RouteStop action → 构建 OccupancyRequest → preview/canEnter
2) 允许进入且通过 hard-blocker 抑制检查：acquire → 重新评估 acquire 结果 → 写入下一跳 destination → 发车/限速；若 acquire 阶段被同 tick 竞争抢占，会立即回退 STOP，不写 destination/launch。
3) 不允许进入：基于 lookahead 阻塞位置细分信号（PROCEED_WITH_CAUTION/CAUTION/STOP）→ 限速或停车
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
- 发车/加速动作会做节流（`runtime.launch-cooldown-ticks`），避免动作队列膨胀。
- 降低 `speedLimit` 属于安全上限，执行层不会再用速度命令限幅延迟它；列车仍在运动且目标速度下降时，会补发一次 TrainCarts launch 控速动作，让 approach/限速按加减速度平滑收敛。若 `/fta train debug` 显示 `edge_limit`、`edge_speed_lookahead`、`movement_authority` 或 approach limiter，写入的 cap 应立即反映该限制。
- `STOP` 是闭塞硬约束，但不是瞬时清零：运行时会用列车当前位置到下一节点/阻塞点的剩余距离计算制动曲线，持续下压到 0，确保在下一节点前停下；距离缺失或已到停车点时才直接停车。
- AutoStation 在 WaitState 期间会向运行时申请 `DepartureGate`（会话锁），信号 tick 会强制维持 STOP；仅在门控放行且会话匹配时释放，避免“停站后被信号 tick 提前发车”。
- 出站门控、推进点与周期信号 tick 统一采用“先 `canEnter` / `evaluateProceedDecision`，确认无未标记 hard-blocker bypass 后再 `acquire`，并把 acquire 返回值作为最终放行判定”的顺序；带 `conflictRelease` 标记的场景由占用层 partial acquire，避免死锁释放被误抑制，同时不污染 blocker 的 NODE/EDGE claim。
- 前方列车信号调整会按调度图展开后的 edge 扫描，而不是按 route waypoint 段计数。长单线中 `A -> D` 这种 route-defined segment 会先展开为 `A -> M1 -> M2 -> ... -> D`，再按实际 edge 数决定 STOP/CAUTION/PROCEED_WITH_CAUTION，避免把远端站点误当成一格前方。
- 占用采用事件反射式：推进点会释放窗口外资源；列车卸载/移除事件会主动释放占用；信号 tick 仍会对“已不存在列车”的遗留占用做被动清理。
- TrainCarts 的 GroupCreate/GroupLink 会触发一次信号评估，用于覆盖 split/merge 后的状态重建；列车改名依赖信号 tick 清理旧缓存。
- spawn/layover 发车成功后，运行时会按本次占用资源主动刷新受影响列车（claim + queue），降低“新车占用已生效但他车未及时红灯”的风险。
- Depot 出车 lookover 会以实际 depot 节点向外做有方向的 BFS，追加前方 edge/CONFLICT 时同时携带走廊方向与 entryOrder，避免出库门控在长单线入口把同向/对向上下文丢掉。
- 异常清理：`RuntimeSignalMonitor` 会检测所有 TrainCarts 编组的 `TrainStatus.Derailed` 并做安全销毁；`MemberRemoveEvent`（split/脱挂）默认只在源/目标编组携带 FTA runtime tag 时触发异常回收，但若事件编组本身已带 `Derailed` 状态，普通 TrainCarts 列车也会按安全兜底销毁。
- stale/no-progress 清理只针对“无 progress entry 但仍携带 FTA route/operator 标签”的脱管列车；已有 progress entry 的列车即使 STOP 等待也不会进入该清理计数。
- 异常清理对同一列车启用短窗去重（默认 2 秒）：同一波 `member-remove` 事件风暴只执行一次清理，避免重复日志与重复 destroy。
- 异常销毁会优先按 trainName 获取当前 holder，再兜底销毁事件 group 与 properties holder，并扫描当前在线 group 中携带同一 `FTA_TRAIN_NAME` 或 split 临时别名的残余编组，避免脱轨/逐节删除后只清掉半列车。
- 每次异常清理都会输出 warning 级诊断日志，包含原因、逻辑列车名/raw TrainCarts 名、head/tail 位置、车厢数量，以及可用的 route/progress 信息；split 日志还会附带被拆出车厢的位置与源/目标编组名，便于排查 unexpected split。
- 单线走廊冲突会进入 Gate Queue，信号 tick 会尊重排队顺序与方向锁。
- 中间图节点（未写入 route 的 waypoint/switcher）触发会更新 `lastPassedGraphNode`，信号/占用评估会尽量贴合列车真实位置。
- 运行中当前位置保护会从当前图节点沿最短路取“下一段图边”并携带 `CONFLICT:single` 方向；不会再只尝试当前节点直连下一个 route waypoint，避免长单线中段丢失走廊方向。
- 事件驱动信号链路只作为桥接：仅接受“更严格”信号（如 STOP）立即生效；更宽松信号统一交由周期 tick 决策，避免事件链路误放行。
- 当当前信号未知（`currentSignal=null`）时，事件链路仅允许 `STOP` 立即生效，不接受 `CAUTION/PROCEED` 的初始化放行。
- 触发“冲突区放行锁”时，`canEnter.allowed=true` 会同时携带 `conflictRelease=true`；运行时允许该释放继续，实际 `acquire` 会跳过 blocker 已持有的 `NODE/EDGE` 资源。移动中的列车不使用该释放特权，会回退为阻塞信号。
- 单线走廊的冲突区放行候选仅在请求列车与 blocker 的方向都能判定且互为对向时成立；方向为 `UNKNOWN` 或队列中缺少方向信息时保持 STOP，避免把同向前后车误判为会车死锁。
- 已有 single conflict claim 的方向不会被后续“无方向的当前位置 hold”覆盖；如果 hold 请求只用于保留当前位置，管理器会沿用原 claim 方向，保证互卡恢复仍能识别对向列车。
- 发车门控和周期信号 tick 在正式 `canEnter()` 前会清理同 Route 后方列车留在当前授权资源上的前瞻 queue entry；该步骤不释放 claim，且不同 route、未知进度、索引不在后方的列车仍会作为真实冲突阻塞。
- 同 Route 同 index 的跟驰列车会继续比较 `lastPassedGraphNode` 在当前 route 段最短路上的顺序；当前车已经通过更靠前的中间节点时，可清理后车留在当前授权窗口里的前瞻 claim/queue，避免同向追驰互相红灯。
- 单线 `CONFLICT:single` 队列只在存在对向/未知方向竞争时串行化；队列中全是同向列车时，跟驰距离交给 NODE/EDGE 硬占用控制，不再由 conflict 队列额外互斥。
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

未写入 `FTA_ROUTE_INDEX` 时视为“未激活”，信号 tick 不会构建占用；首次触发推进点后才会写入并进入占用/控车流程。
`TERMINATE` 表示结束载客：若线路在 TERM 后仍有节点（例如回库段/DSTY），继续按线路推进；仅当 TERM 位于线路尾节点时，才进入 Layover 等待调度分配新 ticket/线路。

`RouteProgressRegistry` 对列车名采用不区分大小写的键；即使 TrainCarts 发生大小写改名，也能命中同一进度记录，避免出现“信号/占用看似丢失后被误清理”的问题。

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
- `ReclaimManager` 定期检查待命列车：
  - 若闲置超时或服务器车辆超限，会为其分配 `RETURN` 票据。
  - `RETURN` 票据优先级较低（-10），礼让正常客运列车。
- 详见 `docs/dev/reclaim-policy.md`。

## 发车票据公平性
- `StorageSpawnManager` 生成 due ticket 时按 SpawnService 轮转，而不是每 tick 从排序后的第一条 service 一直补 backlog。
- 这样在服务器长时间停顿、多个线路/交路组同时 overdue、且 `spawn.max-generate-per-tick` 较小时，前面的线路/组不会长期独占生成预算。
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
- `PROCEED` 且前方无硬约束（无 blocker/caution）时，不再使用“到下一节点距离”触发授权降级；改用前向授权窗口末端
  `distanceToAuthorityEnd`。只要制动距离 + 余量超过该授权末端，仍会降级或下压 MA speed cap，避免长单线 lookahead 之外的未授权资源被当成无限通行。
- `/fta train debug` 会显示 `distanceToAuthorityEnd`、`authorityEndResource` 与 `authorizedEdgeCount`，用于确认 MA 是被 blocker/caution 触发，还是被“第一处未授权资源边界”触发。
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
  - `STOP PROGRESS_STUCK`：自动模式只执行 `refreshSignal -> reissueDestination`，不再 `forceRelaunch`，避免健康监控绕过红灯强制动车；需要强制解锁时使用手动入口。
  - STOP 互卡：自动模式只在双方 STOP、同一 `CONFLICT:single`、方向已知对向、速度低于阈值且不处于 dwell/departure gate/layover/manual hold 时创建 `DeadlockEpisode`；同一 episode 先 `refresh 双车`，再 `reissue stableLeader`，超过阈值后销毁一个稳定 leader。
  - 互卡 refresh/reissue 只是“恢复动作已执行”，不再作为“已修复”计数；真正的自动兜底由 `destroyTrainByName` 完成，并等待 `GroupRemoveEvent -> handleTrainRemoved` 释放占用后刷新 survivor。
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

## 已知限制
- 占用释放采用事件反射式：列车推进后释放窗口外资源；列车卸载/移除事件主动清理，占用快照仍可能在非正常断线时短暂残留。
- 目前默认用 speedLimit/launch 控车；STOP 与 approach 均会按剩余距离计算制动曲线，但仍以 TrainCarts 动作队列执行最终物理运动。

## 调度销毁（handleDestroy）与完整清理
- 调度销毁清理范围与 `handleTrainRemoved` 保持一致（进度、stall 状态、停站状态、trigger 状态、信号警告、departure gate、节点历史、动态分配、有效节点覆盖、blocker 快照、routeTrainTracker 位置条目），唯一区别是不在此处释放占用——`train.destroy()` 延迟 1 tick 执行物理销毁，占用由 `GroupRemoveEvent → handleTrainRemoved` 在实体实际消亡后释放，避免 SpawnMonitor 在物理销毁前 acquire 导致撞车。
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
