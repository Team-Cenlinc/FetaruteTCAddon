# ADR 0002：Node-first 与稀疏物理联锁目录

## 状态

已接受（2026-07-30）。

## 背景

Smart Dispatcher 的规范运行模型已经由 `Node`、`Edge`、Route 与 `MovementPlanSnapshot` 表达。Switcher、单线 section、进路窗口、方向和信号授权都能在这个模型上完成。图上不相邻、但在世界中同高相交的轨道（例如十字平交）无法仅靠拓扑 Edge 发现，因此仍需要一层物理证据。

曾评估把每条 Edge 的全部三维轨道方块写入 `rail_edges.footprint_json`，并在运行时保留全世界 `cell -> edge` 反向索引。该方案的数据库、常驻内存和启动预热成本都会随线路总长度增长；同时会让普通轨道坐标反查逐渐变成第二套行车模型，削弱 Node/Movement Plan 的所有权边界。

## 决策

采用 **Node-first + Sparse Interlocking Catalog**：

1. `RailEdge` 与 `rail_edges` 只保存节点间拓扑、距离和运营属性，不保存轨道方块列表。
2. 完整 graph build 可以临时采样每条 Edge 的实际 TrainCarts/TCCoasters `RailPath`，但只用于发现跨 Edge 的物理重合；编译完成后丢弃普通轨道方块。
3. 持久化和运行时只保留真实物理联锁 Zone：
   - `edge -> zone key`；
   - Zone 的参与 Edge；
   - Zone 附近用于列尾清出判断的局部重合方块；
   - coverage、格式版本和完整 Edge universe 签名。
4. 普通轨道上的实时车体方块未命中任何 Zone，是“完整且当前不在物理联锁区”的正常结果，不是解析失败。
5. Route/Node 推导的普通保护资源在启动恢复中写为 `HOLD_ONLY`；只有实时车体实际命中的 sparse Zone 写为 `PHYSICAL_FOOTPRINT`。
6. 物理观测只能建立或保留保护，不能签发方向或扩大 Movement Authority。方向与前进授权继续由规范 `MovementPlanSnapshot` 独占。
7. Switcher 继续使用 `switcher:<nodeId>`，不会因为物理层存在而复制一套 Zone。
8. sparse snapshot 缺失、损坏、格式不支持、Edge 签名不匹配或 coverage 不完整时，系统使用世界级 fail-closed sentinel 并保持启动授权门关闭；不得把“没有 Zone 记录”解释成“没有平交”。
9. 不能证明完整 Edge universe 的 refresh/partial merge 不发布完整目录。恢复正常吞吐必须执行一次覆盖完整的默认 Node-to-Node graph build。

## 性能契约

- 数据库与常驻内存：`O(Node + Edge + Zone + Zone 局部方块)`，不随普通轨道方块总数线性增长。
- 普通进路资源投影：`O(1 + 当前 Edge 的 Zone 数)`。
- 实时车体检查：`O(本列车本轮车体方块数 + 命中 Zone 数)`，不扫描全图。
- 完整 build 仍需一次性遍历已纳入构建的轨道来证明隐藏平交，但采样结果是构建期临时数据，不写入 JSON、不进入启动常驻状态。
- 轨道发现与 Edge 探索继续服从 tick budget。当前 Zone 编译、图合并与 SQL 提交仍属于低频收尾工作，完整大图 build 必须在低峰执行；把这段收尾改为分阶段 materialize/commit 是后续性能工作，不能通过恢复全轨道常驻索引来规避。

## TCCoasters

TCCoasters 继续受支持。虚拟牌子仍通过 TrainCarts `TrackedSign` 发现，曲线仍通过 TrainCarts `RailPath.Segment` 取得真实几何。区别是完整曲线只在构建期用于 Zone 发现，或在运行时按单列车局部读取车体；不会把整条 TCC 路径永久写入数据库。

## 迁移与运维

- 新版本停止读写 `rail_edges.footprint_json`。旧数据库残留该列时安全忽略，不执行破坏性 DROP。
- 新版本首次读取没有 `rail_interlocking_snapshots` 的旧图时按不完整处理，列车保持 STOP。
- 安全停车后执行完整默认 graph build；确认 `/fta graph info` 显示 catalog 完整、Edge coverage 匹配后，才能恢复自动授权。
- `--bfs`、快速 refresh 或未覆盖完整连通分量的 partial build 不能签发完整 sparse catalog。

## 结果

这一决策保留 Node 模型的低成本与可解释性，同时让 JBS 一类隐藏平交继续拥有硬互斥、稳定排队、真实列尾释放和 fail-closed 启动恢复。代价是完整 build 仍需要临时读取全线几何；后续若大图构建峰值仍高，应优化构建流水线和临时 spool，而不是把全轨道方块重新引入运行时模型。
