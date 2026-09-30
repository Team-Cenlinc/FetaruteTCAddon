# 调度占用与 Headway（闭塞）设计

## 目标
- 用“资源互斥 + headway 规则”抽象闭塞，支持边/节点/道岔冲突区等多粒度占用。
- 调度层只给出“最早进入时间 + 信号许可”，运行时负责具体动作。

## 核心概念
- Resource：占用对象（EDGE/NODE/CONFLICT）。
- Claim：占用记录，仅描述当前占用者与 headway 配置（无 releaseAt）。
- Request：列车申请占用的上下文（不再包含 travelTime，包含单线走廊方向信息）。
- Decision：是否可进入、信号许可与阻塞信息（earliest 仅作提示）。
- Queue：冲突资源的 FIFO 排队快照，用于运维诊断与顺序控制。
- `touchQueues()`：只刷新队列位次，不会写入 claim；用于门控等待和停站等待期间保住前车队头。

## ResourceIntent 与 ClaimRole
- 每个请求资源都会携带 `ResourceIntent`：`MOVEMENT_REQUIRED`、`PROTECTIVE_RETAIN`、`QUEUE_POSITION`、`HOLD_ONLY`、`LOOKAHEAD_PREVIEW`。
- `LOOKAHEAD_PREVIEW` 只属于风险预览。即使调用方误把含 preview 的完整请求传入通用 `acquire`，占用管理器也会先剥离 preview 资源及其 metadata；它不能成为 claim、queue、版本写入或 Movement Authority。
- `MOVEMENT_REQUIRED` 表示本轮前进授权必须取得的资源；`canEnter` 只对这类资源 fail-closed。
- `PROTECTIVE_RETAIN` 与 `HOLD_ONLY` 用于当前位置、尾部保护和 STOP 保留。它们不会阻止前车的 forward movement；如果与其他列车冲突，运行时应保持本车保护、约束后车或触发 stale claim 清理。
- `QUEUE_POSITION` 只表示冲突队列位次，适用于门控等待和停站等待期间保住排序；它不应被当作 NODE/EDGE 硬占用。
- `ClaimRole` 是 claim 写入占用账本后的角色镜像，防止 retain/hold claim 被误当成本车新的前向授权。外车的 `PROTECTIVE_RETAIN` / `HOLD_ONLY` 如果落在本车申请的同一物理 EDGE/NODE 上，仍是硬 blocker；只有不代表同一物理窗口的保护性 CONFLICT 才可进入 stale/release candidate 诊断。`PHYSICAL_FOOTPRINT` 只由 Authority Handoff 产生，不对应新的 `ResourceIntent`；它表示尚未取得列尾清空证明的真实车体足迹，对 EDGE/NODE/CONFLICT 都保持硬闭锁。
- 同一列车刷新同一资源时遵循授权单调性：已有 `MOVEMENT_REQUIRED` 或 `PHYSICAL_FOOTPRINT` 不会被 `PROTECTIVE_RETAIN` / `HOLD_ONLY` 等非硬请求降级；新的 `MOVEMENT_REQUIRED` 可以接管与其重叠的旧 footprint。非硬请求不能自行建立方向或覆盖已有方向。合法换向必须通过 `AuthorityHandoffSupport` 原子结束旧授权并建立新授权，普通 `acquire` 继续拒绝反向覆盖。
- 当前位置、尾部保护与 STOP hold 优先继承 canonical `MovementPlanSnapshot` 的方向。资源已经滑出当前前向计划时，非硬请求可继承同车在该资源上由上一份硬授权提交的已知方向；若两者都没有证据则保持 `UNKNOWN`，绝不回退到保护窗口的局部路径推导。
- Authority Handoff 预检只暂时隐藏自身 claim/queue，外部 blocker 与 Gate Queue 仍按原规则判定；拒绝完整恢复且不发布 release。成功时重叠资源写入反向硬 claim，旧窗口独有且代表车体足迹的 EDGE/NODE/CONFLICT 在没有 rear-clear 证据前转为 `PHYSICAL_FOOTPRINT`。运行时 sidecar 从 handoff 前快照保护完整旧 footprint，不让周期 shrink 或授权回滚提前删除重叠的反向 movement claim；只有同一 traversal epoch 的真实相邻图节点沿登记有向路径连续推进，累计距离达到“保守列车长度 + `rear-guard-edges` 对应边长”后才解除保护并按角色安全收缩。跳点、路径外观测、连续折返换向或长度/路径证据缺失时 fail-retain，倒退则回退净清界进度。fail-retain 不是永久保留：登记时带路线证据（路线生效节点序列 + 折返所在下标）的 guard 还有后备释放——运行时路线路径点按序到达，下标与节点名都与登记一致，且沿登记路径的累计前进距离达到“保守列车长度 + `rear-guard-edges` 对应边长 + 32 格余量”时，无论连续节点链是否断过（含已封存的 epoch）都解除保护，并沿用同一套按角色收缩；车长未知或没有路线证据的 guard 仍 fail-retain。登记无计划、封存、后备释放各有一行诊断（`TURNBACK_FOOTPRINT_GUARD_NO_PLAN` / `_SEALED` / `_FAR_CLEAR`，均在 diagnostic 豁免表内），正常运行接近 0。起因：2026-09-29 实服 NTA 咽喉道岔被一条漏事件后封存的 guard 占住整段发车窗口，此前没有任何路径能释放它。列车改名通过 owner migration 同步迁移 claim、queue、switcher signature 与 deadlock lock，最终 destination commit 前还会复核全部 `MOVEMENT_REQUIRED` 资源仍由新名字持有。
- claim、方向、角色、headway、route 与道岔路径签名均未变化的 refresh 是 no-op，不推进 occupancy version，也不发布 `OccupancyAcquiredEvent`。
- 冲突队列 refresh 仍会更新 `lastSeen` 防止活跃列车过期，但方向、优先级、稳定 entry order 与道岔路径签名都未变化时不推进 occupancy version；只有排队语义变化才触发版本更新。

## 信号许可（SignalAspect）
- `PROCEED`：可进入。
- `PROCEED_WITH_CAUTION`：前方两段区间内存在 stop，用于提前减速提示。
- `CAUTION`：下一个区间会遇到 stop，准备停车。
- `STOP`：禁止进入或无法定位阻塞位置（例如仅 CONFLICT 阻塞）。

## 最小可用版本资源解析规则
- edge 必占用自身资源：`EDGE:<from~to>`。
- edge 若连接 `SWITCHER` 节点，额外占用冲突资源：`CONFLICT:switcher:<nodeId>`。
- edge 若属于无替代路径的桥链，会叠加 `CONFLICT:single:section:*` 方向资源及其兼容 micro key（对向互斥，同向可跟驰）。
- 非桥网格不再叠加整片网格冲突资源；规范 `MovementPlan` 中实际选定的 EDGE、NODE 与 switcher 共同定义进路边界。两条进路完全不相交时可以并行，实际共享任一硬资源时仍 fail-closed。
- 无任何安全边界的纯闭环继续附加 `CONFLICT:single:*:cycle:*` 严格互斥资源；这类拓扑不能用“进路暂不相交”证明两列车最终可以会让。
- node 占用使用 `NODE:<nodeId>`（switcher 同样补冲突资源）。

## Headway 规则
- headway 用于刻画“线路安全冗余”，当前以配置形式保留。
- 事件驱动释放后立即重新判定，不再依赖 releaseAt 计算。

## 释放策略
- 当前采用事件驱动：列车推进/离开资源时立即释放。
- 列车卸载/移除事件会主动释放该列车的所有占用。
- 自愈清理按 claim 粒度释放：当同一冲突资源允许多列车合法共占（如同向单线跟驰）时，只移除离线/异常列车自己的 claim，不会整段误清。
- 具体释放时优先携带列车名，避免共享冲突资源上误删其他合法 claim。
- 不再依赖超时清理。

## Lookahead 占用
- 运行时可按“当前节点 + N 段边”申请占用，降低咽喉/道岔前的卡死。
- 对实际选定路径，只有首个 `SWITCHER`、显式咽喉或无方向的精确 `CONFLICT:interlocking:*` 已经进入普通 hard lookahead 时，构建器才会把从当前安全边界、经过该冲突点、直到首个正常图边界/出清站点的 NODE、EDGE 与 CONFLICT 一次性提升为 `MOVEMENT_REQUIRED`。方向性 `CONFLICT:single:*` 不做整段出口提升，继续由局部硬窗口、方向锁、headway 和前车 token 控制同向跟驰。远端联锁仍只存在于完整 Movement Plan/advisory 中，不能提前扩大当前硬授权；一旦精确物理联锁开始提升却无法证明冲突外第一条清出边，请求为空并 fail-closed，避免列车只拿到道口入口就停在冲突区内。
- **重要**：lookahead 边数基于 **RailGraph 展开后的实际边**，而非 Route 定义中的节点跨度。
  - Route 节点 A→B 之间如果在 RailGraph 中有多个中间 Waypoint，会先展开再按边数截断。
  - 这确保了 `lookahead-edges=2` 始终代表 2 条实际轨道边（约 60-100 blocks），而非 2 个站间区间。
- 同向跟驰最小空闲边数由 `runtime.min-clear-edges` 控制（与 lookahead 取最大值）。
- 尾部保护 = 整列车身 + 车尾之后 `runtime.rear-guard-edges` 段边：从车头最近到达的 route 节点往回逐边累计到覆盖保守车长
  （车尾落在哪条边上，那条边算车身；车长算法见 `runtime-dispatch.md`），再多留 N 段边；车长未知时保留全部可证明的后向路径。
  确保长列车尾部在完全离开前不被后车侵入。
  余量按"车尾之后的边数"算，不拿车头身后那条边的长度当距离——那个量与车尾身后无关，站台边一长再取整到整边，保护会多退一两段。
  车身从节点起量，而停站时列车以站牌为中心、车头越过站台节点约半个车长，保护因此比实际车尾多出约半个车长。终点折返待命车另按停稳后的
  实测车身收窄（见 `runtime-dispatch.md` 的"待命与回收"）；途中站停车仍按这条规则。
  折返交接的旧 footprint 释放阈值（见 `runtime-dispatch.md`）是另一套机制，不受这条影响。
- 默认会同时占用路径上的 NODE 资源（当前节点 + lookahead 节点），用于阻止前车未离开时后车进入同一节点。
- `OccupancyRequestBuilder` 负责从 `TrainRuntimeState + RouteDefinition + RailGraph` 构建请求。
- Occupancy 请求使用的是 expanded path：Route 相邻节点会先通过 `RailGraph.shortestPath` 展开成真实 graph nodes/edges，再按 lookahead edge count 截断。若现场看到“两个车很远却红灯”，优先检查远端资源是否误进入了 `MOVEMENT_REQUIRED`，而不是假设占用用了未展开的 route span。
- 运行时信号 tick 会把 expanded path 拆成两层：`hardAuthorityWindow` 只进 `MOVEMENT_REQUIRED`；`advisoryLookaheadWindow` 只给 `SignalLookahead` / Smart Dispatcher 计算 yellow aspect 与目标速度。
- `LOOKAHEAD_PREVIEW` 的 advisory 扫描会忽略同向 `CONFLICT:single` claim / queue entry：同向单线是否需要慢行由前方列车的真实 NODE/EDGE claim 距离决定，不由 single conflict 入口距离决定。对向或方向未知的 single claim / queue 仍然是风险，并保持 fail-closed。
- self-owned `CONFLICT:single` claim 只有在请求方向与既有方向连续、且队列内没有对向/未知方向竞争时才允许 continuation；方向相反或缺失时仍 fail-closed。
- `OccupancyManager#clearSelfOwnedSingleDirectionMismatches` 只供健康恢复在 STOP progress stuck 后调用：它仅清理同一逻辑列车、同一 single conflict、旧方向与当前请求方向明确相反的 claim/queue。普通 `canEnter/acquire` 不会自动释放该残留，方向未知或其他列车 blocker 仍按 STOP 处理。

## 运行时接入
- 推进点（waypoint/autostation/depot/switcher）会构建占用请求并下发下一跳 destination。
- 运行中通过定时 tick 重新评估 signal，信号降级时限速或停车。
- 相关说明见 `docs/dev/runtime-dispatch.md`。

## 事件驱动信号系统
- 占用变化时 `SimpleOccupancyManager` 会发布 `OccupancyAcquiredEvent` / `OccupancyReleasedEvent`；纯 Gate Queue 撤队、TTL 过期、release lock 失效，或既有条目的方向/优先级/路径签名变化则发布 `OccupancyQueueChangedEvent`，不伪称物理资源已经释放。首次入队仍由当前完整授权链处理，不额外自唤醒。
- `SignalEvaluator` 订阅这些事件，即时重新评估受影响列车的信号状态。
- 信号变化时发布 `SignalChangedEvent`，由运行时桥接层默认 coalesce 为 dirty train；周期 tick 再统一做前向授权、destination commit 与控车落地。
- STOP 可作为高优先级刷新来源，但不在 `OccupancyManager.acquire()` 同步调用栈内重入同一列车 hard STOP，避免 acquire → event → STOP → 下一 tick PROCEED 的抖动。
- 此机制降低信号响应延迟，同时避免事件链路绕过前向授权原子提交。
- 详见 `docs/dev/signal-event-system.md`。

## Gate Queue（排队控制）
- `CONFLICT:switcher:*` 与 `CONFLICT:single:*` 使用优先级队列控制放行顺序。
- **优先级 (Priority)**：
  - 高优先级列车优先放行（例如客运 > 回收）。
  - 优先级相同时，按“首见时间”先到先得（FIFO）。
  - 当前默认优先级：客运=0，回收（Reclaim）=-10。
- 走廊为空时会比较两侧队列头部的优先级与首见时间，优先放行更优的一侧。
- 同向跟驰仍可并行进入走廊，但进入顺序受队列约束。
- 冲突区放行：当两侧列车互相占用节点而卡死时，会基于 lookahead 的 entryOrder 从近到远扫描候选 conflict，选择更接近冲突入口且 blocker 同属该队列的一侧作为放行候选。
- 冲突区放行会在 `OccupancyDecision` 上标记 `conflictRelease=true`；运行时允许该释放继续，占用层在 `acquire()` 时只写入未被 blocker 持有的资源，避免把对向列车的 NODE/EDGE claim 抢成自己的占用。
- 普通 single 冲突区放行仅用于**对向会车死锁**。若请求侧前方仍存在同向阻塞列车（即使同时存在对向阻塞），也不会触发放行，避免把后车提前送入咽喉造成二次互卡。
- 单线走廊要求请求列车与 blocker 都有明确方向且互为对向；任一方方向为 `UNKNOWN` 或队列中缺少方向时保持 STOP。这样会牺牲部分方向缺失场景的自动解锁，但能避免把同向前后车误判为对向死锁。
- 复杂平交道口不使用 single 的 A/B 方向猜测。若一列车的实体 NODE 已在 switcher 内、规范路径明确驶向出口，而入口外列车只持有同一 switcher 的未来抽象 claim，则优先让道口内列车清空（GO），入口列车继续由道口内列车的 NODE/EDGE 保持 HOLD。出口存在任意外车 NODE/EDGE/物理 footprint、路径签名缺失、当前位置仍在入口外时一律不触发。
- switcher owner 在成功 acquire 后会离开等待队列；冲突释放校验只把“同一 switcher、仍保有路径签名的实际 claim”视作同队列 blocker。无签名旧 claim、其他 switcher claim 与所有无方向 single claim 仍按安全侧拒绝。
- entryOrder 来自占用请求的“首次进入冲突区的边序号”，可避免折返段场景误放行离入口更远的列车。
- 冲突区放行锁：放行后会锁定同一列车一段时间，避免信号乒乓；锁创建与续用都要求所有 blocker 精确属于同一个 conflict key。partial acquire 也只跳过“已验证 hint + 本车有效 release lock + blocker”三者同 key 的抽象资源，前一道岔的锁不能顺带绕过后来出现的另一道岔。
- 放行锁只对持锁列车生效，不会跨列车共享；预览路径只读，不会写入锁或队列状态。
- entryOrder 在队列内取“更小者优先”并保持稳定，避免路径抖动导致队头来回翻转。
- 出站门控会查询单线/道岔冲突队列的更高优先级列车，必要时让行并保持停站等待（仅站台/TERM）。
- 停站等待期间会持续刷新自身在前向冲突队列中的位次，即使尚未真正占用前方区段，也不会让后车在等待窗口内抢到队头。
- 运行时在前车重新申请同一前向授权窗口时，会清理“同 Route 且索引更小”的后车前瞻队列条目；该清理只移除 queue，不释放 claim，也不会作用于不同 route、对向或交叉冲突列车。
- 队列条目若超过 `runtime.stale-queue-entry-ttl-seconds`（默认 30 秒）未刷新会自动清理，避免遗留 UNKNOWN/opposite entry 长期串行化已知同向跟驰。
- 队列条目在方向判定更新时会从旧方向桶迁移到新方向桶；TTL 清理后也会同步回收旧 priority/entryOrder 元数据，避免重复排队或继承过期排序状态。
- `/fta occupancy queue` 通过 `OccupancyQueueSupport` 输出队列快照（含方向、优先级与首见时间）。
- 队列条目包含 `priority` 与 `entryOrder`，用于诊断冲突区放行与“更近者优先”的排序。

## 观测与运维
- `/fta occupancy dump [limit]`：查看占用快照。
- `/fta occupancy queue [limit]`：查看排队快照。
- `/fta occupancy release <train>`：按列车清理占用。
- `/fta occupancy release-resource <EDGE|NODE|CONFLICT> <key>`：按资源清理占用。
- `/fta occupancy stats`：查看自愈/出车重试等运行统计。
- `/fta occupancy heal`：手动触发“孤儿占用/进度/待命”自愈清理。
- `/fta occupancy debug acquire edge "<from>" "<to>"`：对单条边执行占用。
- `/fta occupancy debug can edge "<from>" "<to>"`：对单条边执行判定（不占用）。
- `/fta occupancy debug acquire path "<from>" "<to>"`：对最短路路径执行占用。
- `/fta occupancy debug can path "<from>" "<to>"`：对最短路路径执行判定（不占用）。

## 预留 API
- `OccupancyManager#getClaim/snapshotClaims` 提供只读查询。
