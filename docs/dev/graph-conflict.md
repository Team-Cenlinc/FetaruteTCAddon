# 联合闭锁冲突组（单线走廊）

## 目标
- 为单线区间与道岔咽喉提供“互斥资源”，避免对向会车与进路冲突。
- 由图快照自动计算，避免人工维护。

## 冲突组规则（当前实现）
- Switcher 冲突：每条连接 `SWITCHER` 的区间附加 `CONFLICT:switcher:<nodeId>`。
- 单线走廊冲突：将度数=2 的连续链路压缩为一个走廊，所有区间共享
  `CONFLICT:single:<componentKey>:<endA>~<endB>`。
- 边界判定：度数≠2 或节点类型为 `SWITCHER`。
- 不把 Station/AutoStation 作为边界，因此“单线区间上有多个站点”会被同一走廊锁住。

## 运维命令
- `/fta graph conflict list [--node "<nodeId>"] [page]`：列出冲突组与覆盖区间数量。
- `/fta graph conflict edge "<nodeA>" "<nodeB>"`：查询指定区间的冲突组 key。
- `/fta graph conflict path "<from>" "<to>" [page]`：汇总最短路路径上的冲突组。

## 后续预留
- 可扩展为“人工排除/手动归类”：
  - 在 `rail_edge_overrides` 增加字段；
  - 或新增独立表保存冲突组覆盖。
