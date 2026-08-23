# 联合闭锁冲突组（单线走廊）

## 目标
- 为单线区间与道岔咽喉提供“互斥资源”，避免对向会车与进路冲突。
- 由图快照自动计算，避免人工维护。

## 冲突组规则（当前实现）
- Switcher 冲突：每条连接 `SWITCHER` 的区间附加 `CONFLICT:switcher:<nodeId>`。
- 物理足迹联锁：两个图上不相邻的区间只要共享同一个三维轨道方块，就生成稳定的
  `CONFLICT:interlocking:<sha256>` pair-zone；共享端点的区间若离开端点后又形成独立重合簇，也按同一规则建区。key 由世界与归一化 Edge 对确定，与站名、线路名和 switcher 牌子无关，因此同一机制覆盖平交、渡线和其他图拓扑未表达的实体轨迹交叉；不同 Y 高度不会冲突。
- 物理联锁资源是硬互斥资源：不能被同向证明、drain ignore、陈旧 retain、release-lock 或 Smart Dispatcher 推测性 unlock 绕过。列车只有在前车真正释放该资源后才会被资源释放事件唤醒并重新申请。
- `EDGE / NODE / SWITCHER / single-section` 仍是主联锁模型。三维轨迹不会替代 Switcher 牌子：共享端点且只形成一个连续重合簇的相邻边继续由既有 Switcher/section 资源保护，不重复生成物理联锁区；若两条共享端点的复杂曲线在远处再次形成第二个不连通重合簇，仍会建立 pair-zone，不能因“相邻”整对跳过。物理层主要补足图上互不相邻但现实中同高交叉的轨道，例如没有分岔连接的十字平交。由于隐藏交叉可能发生在任意一条边的中部，完整 coverage 仍需采集全部边，不能仅采集带 Switcher 牌子的节点附近。
- 同一不可变联锁快照只保存 `edge -> interlocking zone` 与 `zone cell -> zone key`。普通轨道方块不进入 SQL 或常驻反向索引；启动/迟加载现场恢复按列车实际 cell 数只查询局部 Zone，不为每个车厢扫描全图。`/fta graph info` 的 `zone_cells/multi_zone_cells` 用于核对常驻空间索引规模。TCCoasters 轨道通过 TrainCarts 的 `RailType/RailPath` Adapter 在构建期临时采样，虚拟 `TrackNodeSign` 通过 `TrackedSign` 接口解析，核心联锁模块不直接依赖 TCC 专有类型。
- 联锁 coverage 以当前世界完整 Edge universe 为边界。旧数据库、损坏足迹、partial build、缺失轨道 anchor 或任何未完整捕获的区间，都会让预期区间共享
  `CONFLICT:interlocking:incomplete:<worldId>`，以串行化代替漏放；`/fta graph info` 会显示完整度、captured/expected 与精确联锁区数量。
- 图快照切换若会改变联锁资源投影，而占用层仍存在 active claims，会拒绝激活新快照并继续使用旧图。运维需先安全停车并清空占用，再重试 build/refresh，避免旧 claim key 与新请求 key 瞬时并存。
- 单线微段冲突：将度数=2 的连续链路压缩为一个走廊，所有区间共享
  `CONFLICT:single:<componentKey>:<endA>~<endB>`；用于“对向互斥”，同向跟驰不再互斥。
- 闭环冲突：若连通分量内无边界节点，则归并为
  `CONFLICT:single:<componentKey>:cycle:<minNode>`。
  闭环仍为严格互斥，不区分方向。
- 微段边界判定保持为：度数≠2 或节点类型为 `SWITCHER`。这是 `RailGraphConflictIndex`
  的既有诊断/兼容粒度，普通 switcher 仍会拆出多个 micro corridor key。
- 单线 section token：`SingleLineSectionIndex` 在完整无向图上用迭代 Tarjan 找桥，只把桥森林中的连续链归并为
  `CONFLICT:single:section:bridge:<endA>~<endB>`。固定 `bridge` 命名空间与归一化边界端点共同构成稳定身份，不再引用连通分量最小节点；因此热快照新增无关旁支节点不会让在途 claim 与新请求换 key。桥是“移除后会切断连通分量”的边，因此可以证明不存在替代路径；对向列车必须在进入同一桥链前竞争同一个方向 token，不能从两端分别进入不同 micro segment。
- 环内非桥 edge 不生成 `single:section:*`。平交网格、渡线、会让环与站场多进路不能仅凭环秩证明“单线方向”，因此不生成
  任何 `single:section:*:cycle:*` 方向资源。
- 非桥网格不生成整片网格 token。列车的规范 `MovementPlan` 会把已选定的有序进路展开为真实 EDGE、NODE 与
  `CONFLICT:switcher:*` 资源；只有双方实际共享这些资源时才互斥。同处一个平交、渡线或会让网格，但选定进路完全不相交的列车可以并行。
- 无任何边界的纯闭环继续保留 `CONFLICT:single:*:cycle:*` 严格互斥 token。闭环内没有可证明的停车会让边界，不能把当前窗口暂不重叠当作最终可清界证据。
- `RailGraph` 仍是无向物理拓扑，不需要增加新的线路配置或持久化模型。有向性只存在于单周期提交的
  `MovementPlan` 与其 `Movement Authority` 中；TrainCarts 改选路径后必须先生成并重新申请新的完整授权，不能沿无向邻域猜测分支。
- 平交道口、咽喉和折返端点仍采用进路原子准入：请求必须一次取得从进入安全停车边界到冲突区出口的选定 EDGE、NODE 与 switcher/精确物理联锁资源。精确 `interlocking:*` 一旦进入 hard window，出口窗口就从最后一条冲突边之后累计，至少证明“实时保守车长 + 配置停车净空”的无该冲突距离；完整 route 不能证明足够泊位、车长缺失或路径中再次进入同一冲突时，builder 直接 fail-closed。方向性 `single:*` 不扩张为整段物理独占，仍由局部硬窗口、方向 section、headway 与 leader token 支持同向跟驰。无法证明出口或进路重叠时不会为了并行而允许列车停进冲突区。
- section 边界采用桥森林的结构判据：桥度数不等于 2 的端点/分支、完整图中的分支 `SWITCHER`、同站/同库多 track 安全会让点都会截断链。桥度数为 2 的普通 switcher 保持透明，线性单线仍归并成一个方向 section；普通单站台站点不会凭节点类型自动切断单线。
- Station/AutoStation 不再作为微段边界；section 也只有在能证明可会让时才以站/库作为边界，因此“单线区间上有多个普通单站台站点”会被同一 section 锁住。
- 道岔联合锁闭范围由 `runtime.switcher-zone-edges` 控制（向前 N 段边）。
- 占用层会对 `CONFLICT:switcher`、`CONFLICT:single` 与 `CONFLICT:interlocking` 资源启用 Gate Queue。物理联锁使用无方向全局队头，不能借用单线同向共享；队列只负责竞争次序，不代表物理占用，完整请求的 preview/acquire
  决定队头是否已经取得全部进路资源。
- Gate Queue 把 priority 换算为有上限的时间优势（每分 0.5 秒、最多 2 分钟），再与首次等待时间形成固定排序键。这样短期仍遵循 `OPERATION > depot exit/CREATE > RETURN`，持续刷新的老等待者最终会排在后来高优先级流量之前；固定键不会随 tick 来回翻转。相同键再按 priority、冲突入口距离、首次等待时间与规范列车名确定唯一 winner。
- `RailGraphCorridorInfo` 提供走廊端点与路径节点列表，方向以端点排序为准。
- `SingleLineSectionInfo` 提供 section 端点、参考路径、覆盖的 micro corridor key 和边界列表。对于实现
  `RailGraphSectionSupport` 的运行时图，资源解析器只在 edge 确属桥链 section 时附加相应 micro key；非桥网格上的 micro key 保留为诊断信息，不再进入 Movement Authority。
- single conflict 的方向属于安全上下文：常规 lookahead、运行中当前位置保护、停站前向队列与 Depot 选定出库路径都会尽量携带微段与 section 方向。
  如果后续 hold 请求没有方向，占用管理器会保留已有 claim 的方向，避免长单线内列车被降级为 `UNKNOWN` 后破坏对向识别。
- section claim 写入时会先使用请求中的同 key 方向；若桥链 section 为 `UNKNOWN`，但同一请求的 committed movement plan
  携带完整 axis 精确相同的 micro token，占用管理器会把该唯一已知方向写入 `CONFLICT:single:section:*` claim。micro component
  可以是含冒号的 NodeId，因此匹配完整 axis 后缀而不是按冒号猜分段；axis 不一致、多个证据方向冲突或无法解析时保持
  `UNKNOWN`，不得凭空推断。
- 常规进入 `CONFLICT:single` 时若方向缺失或为 `UNKNOWN`，占用层会 fail closed，返回
  `single-conflict-direction-unknown`。已持有同一 conflict 的 hold-only 刷新不受影响，且不会用 UNKNOWN 覆盖已有已知方向。
- 同一 section、同方向的列车不会被 `CONFLICT:switcher` 额外串行化；占用层会用 shared
  `CONFLICT:single:section:*` 方向作为证据，跳过同向 switcher claim/queue/advisory stopper。真实 `NODE` / `EDGE`
  仍保持硬互斥，因此站台、咽喉当前边或前车车体占用不会被 section 方向绕过。
- Depot 出车只从实际选定路径解析冲突与 entryOrder，使 gate queue 能识别“这辆车从 depot 口进入所选冲突区”的队列位置；不得从无向邻域分支推导本车方向或硬授权。
- route waypoint 不是冲突方向的最小单位。构建 OccupancyRequest 和运行时前方列车扫描时，必须先把 route-defined segment 展开成实际图 edge，再解析 corridor direction 与 entryOrder；否则长单线中间 edge 会丢失方向上下文，表现为同向车被压成 CAUTION/STOP。
- 同一份已展开的 movement path 会先建立一次只读语义方向索引，再供该请求内全部 directional conflict edge 查询；不得为每条 edge 重新扫描整条 path 的 interval 锚点或重建站间轴。该索引只缓存路径不变的语义事实，不缓存 occupancy、queue、signal 或授权决定；每次请求仍会基于当前占用状态重新执行 fail-closed 准入。
- 若同一长走廊的多条 edge 共享同一个 `CONFLICT:single` key，`entryOrder` 记录的是首次进入该冲突组的展开 edge 序号，而不是每条 edge 各自覆盖一次。
- `conflictRelease` 仅允许 `AuthorizationPurpose.CONFLICT_CLEARING` 请求触发，并且必须携带 inside/exit 证据。Depot spawn、Station departure、Layover reuse 与普通 runtime move
  都不能直接释放冲突队列。释放最多跳过 `CONFLICT` blocker；遇到真实 `NODE`/`EDGE` blocker 时返回
  `conflict-release-hard-blocker:<resource>`，防止未进入冲突区的列车借 deadlock release 冒进长单线。

## 运维命令
- `/fta graph conflict list [--node "<nodeId>"] [page]`：列出冲突组与覆盖区间数量。
- `/fta graph conflict edge "<nodeA>" "<nodeB>"`：查询指定区间的冲突组 key。
- `/fta graph conflict path "<from>" "<to>" [page]`：汇总最短路路径上的冲突组。
- `/fta graph conflict interlocking list [page]`：列出精确物理联锁区，并显示每个稳定 hash 对应的两条 Edge 与重叠方块数量。
- `/fta graph conflict interlocking get "<interlocking:key>" [page]`：把运行时 trace 中的联锁 hash 反查为两条 Edge 与具体重叠坐标。

物理联锁 claim 的 acquire、blocker-read 与 release 会输出 `SMART_INTERLOCKING_CLAIM_LIFECYCLE` trace。现场可从 trace 复制
`resource=CONFLICT:interlocking:<sha256>` 中的 key，再用 `interlocking get` 还原到图边和物理坐标，从而确认两辆车是否竞争同一平交资源以及资源是否已实际释放。

## 后续预留
- 可扩展为“人工排除/手动归类”：
  - 在 `rail_edge_overrides` 增加字段；
  - 或新增独立表保存冲突组覆盖。
