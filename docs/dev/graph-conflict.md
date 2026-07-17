# 联合闭锁冲突组（单线走廊）

## 目标
- 为单线区间与道岔咽喉提供“互斥资源”，避免对向会车与进路冲突。
- 由图快照自动计算，避免人工维护。

## 冲突组规则（当前实现）
- Switcher 冲突：每条连接 `SWITCHER` 的区间附加 `CONFLICT:switcher:<nodeId>`。
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
- 平交道口、咽喉和折返端点仍采用进路原子准入：请求必须一次取得从进入安全停车边界到冲突区出口的选定 EDGE、NODE、switcher
  与桥链 section。无法证明出口或进路重叠时 fail-closed，不会为了并行而允许列车停进冲突区。
- section 边界采用桥森林的结构判据：桥度数不等于 2 的端点/分支、完整图中的分支 `SWITCHER`、同站/同库多 track 安全会让点都会截断链。桥度数为 2 的普通 switcher 保持透明，线性单线仍归并成一个方向 section；普通单站台站点不会凭节点类型自动切断单线。
- Station/AutoStation 不再作为微段边界；section 也只有在能证明可会让时才以站/库作为边界，因此“单线区间上有多个普通单站台站点”会被同一 section 锁住。
- 道岔联合锁闭范围由 `runtime.switcher-zone-edges` 控制（向前 N 段边）。
- 占用层会对 `CONFLICT:switcher` 与 `CONFLICT:single` 资源启用 Gate Queue。队列只负责竞争次序，不代表物理占用；完整请求的
  preview/acquire 决定队头是否已经取得全部进路资源。
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
- 若同一长走廊的多条 edge 共享同一个 `CONFLICT:single` key，`entryOrder` 记录的是首次进入该冲突组的展开 edge 序号，而不是每条 edge 各自覆盖一次。
- `conflictRelease` 仅允许 `AuthorizationPurpose.CONFLICT_CLEARING` 请求触发，并且必须携带 inside/exit 证据。Depot spawn、Station departure、Layover reuse 与普通 runtime move
  都不能直接释放冲突队列。释放最多跳过 `CONFLICT` blocker；遇到真实 `NODE`/`EDGE` blocker 时返回
  `conflict-release-hard-blocker:<resource>`，防止未进入冲突区的列车借 deadlock release 冒进长单线。

## 运维命令
- `/fta graph conflict list [--node "<nodeId>"] [page]`：列出冲突组与覆盖区间数量。
- `/fta graph conflict edge "<nodeA>" "<nodeB>"`：查询指定区间的冲突组 key。
- `/fta graph conflict path "<from>" "<to>" [page]`：汇总最短路路径上的冲突组。

## 后续预留
- 可扩展为“人工排除/手动归类”：
  - 在 `rail_edge_overrides` 增加字段；
  - 或新增独立表保存冲突组覆盖。
