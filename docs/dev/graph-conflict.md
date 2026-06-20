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
- 单线 section token：`SingleLineSectionIndex` 在微段之上叠加
  `CONFLICT:single:section:<componentKey>:<endA>~<endB>`，把会让点之间、跨过普通 switcher
  的微段归并成一个方向性准入资源。对向列车必须在进入 section 前竞争同一个 token，不能从两端各自进入不同 micro segment。
- section 边界采用保守判据：线路终端、站/库扇出、同站/同库多 track 站台组可截断；普通 `SWITCHER`
  和无法证明具备会让能力的中间节点不自动成为边界，归并进更长 section。无法形成方向轴的闭环 section 使用
  `CONFLICT:single:section:<componentKey>:cycle:<minNode>`，按严格互斥 fail closed。
- Station/AutoStation 不再作为微段边界；section 也只有在能证明可会让时才以站/库作为边界，因此“单线区间上有多个普通单站台站点”会被同一 section 锁住。
- 道岔联合锁闭范围由 `runtime.switcher-zone-edges` 控制（向前 N 段边）。
- 占用层会对 `CONFLICT:switcher` 与 `CONFLICT:single` 资源启用 Gate Queue。
- `RailGraphCorridorInfo` 提供走廊端点与路径节点列表，方向以端点排序为准。
- `SingleLineSectionInfo` 提供 section 端点、参考路径、覆盖的 micro corridor key 和边界列表；该信息只叠加 section token，不替换
  `RailGraphConflictIndex` 的微段 key。
- single conflict 的方向属于安全上下文：常规 lookahead、运行中当前位置保护、停站前向队列、Depot 出车 lookover 都会尽量携带微段与 section 方向。
  如果后续 hold 请求没有方向，占用管理器会保留已有 claim 的方向，避免长单线内列车被降级为 `UNKNOWN` 后破坏对向识别。
- section claim 写入时会先使用请求中的同 key 方向；若同 key 为 `UNKNOWN`，但同一请求的 committed movement plan
  携带同 component、同 axis 的等价微段 token（例如 `single:<component>:<endA>~<endB>`），占用管理器会把该已知方向写入
  `CONFLICT:single:section:*` claim。component 或 axis 不一致、方向冲突或无法解析时保持 `UNKNOWN`，不得凭空推断。
- 常规进入 `CONFLICT:single` 时若方向缺失或为 `UNKNOWN`，占用层会 fail closed，返回
  `single-conflict-direction-unknown`。已持有同一 conflict 的 hold-only 刷新不受影响，且不会用 UNKNOWN 覆盖已有已知方向。
- 同一 section、同方向的列车不会被 `CONFLICT:switcher` 额外串行化；占用层会用 shared
  `CONFLICT:single:section:*` 方向作为证据，跳过同向 switcher claim/queue/advisory stopper。真实 `NODE` / `EDGE`
  仍保持硬互斥，因此站台、咽喉当前边或前车车体占用不会被 section 方向绕过。
- Depot 出车 lookover 还会为追加的冲突补齐 entryOrder，使 gate queue 能识别“这辆车从 depot 口进入前方冲突区”的队列位置。
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
