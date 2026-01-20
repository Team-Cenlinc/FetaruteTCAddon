# Route 运营属性与停靠标记

本文描述 Route 的运营属性（Operation/Return）以及 CRET/TERM/DSTY 的停靠标记约定，供调度与运维参考。

## Route 运营属性
- `OPERATION`：运营班次（客运/正线运行）。
- `RETURN`：回库/回送班次（非运营）。

命令示例：
- 创建：`/fta route create ... --operation OPERATION`
- 更新：`/fta route set ... --operation RETURN`

> 路线属性用于后续调度决策与班次筛选，当前仅作为元数据保存。

## 停靠标记（action）
RouteStop 的 notes 支持 action 行（`ACTION:PAYLOAD`），常用标记：

### CRET（生成）
- 语法：`CRET <NodeId>` 或 `CRET DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`
- 约束：整条路线仅允许一个 `CRET`，且必须为首个 stop（CRET 自身占一行 stop）。

### TERM（终到）
- 使用 stop 前缀 `TERM`/`TERMINATE`（即 RouteStopPassType.TERMINATE）。
- 语义：标记“运营终到站”，后续调度可在此等待下一班次或回库。

### DSTY（销毁）
- 语法：`DSTY <NodeId>` 或 `DSTY DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`
- 约束：整条路线仅允许一个 `DSTY`，且必须为最后 stop（DSTY 自身占一行 stop）。

### DYNAMIC（动态站台/车库）
- 语法：`DYNAMIC:<OperatorCode>:<StationCode>` 或 `DYNAMIC:<OperatorCode>:<DepotCode>`
- 可附带区间：如 `DYNAMIC:SURN:PTK:[1:3]`

## define 校验（当前）
`/fta route define` 会做基础一致性校验：
- CRET 只能出现一次，且必须是首个 stop。
- DSTY 只能出现一次，且必须是最后 stop。
- 若调度图快照已加载，会校验相邻 stop 的 NodeId 是否存在可达边；图未加载时仅提示并跳过校验。

`/fta route debug <company> <operator> <line> <route> [page]` 可直接查看已保存线路的 stop 回显（无需先 define）。
`/fta route debug <company> <operator> <line> <route> [page]` 会进行调试展示：
- CRET/DSTY 会以指令行形式展示（不显示 PASS 前缀）。
- 若相邻 stop 不直接相连，会展开最短路径中的中间节点并标记为 PASS（仅回显，不写入路线）。

## 手动校验命令
`/fta route validate <company> <operator> <line> [route]`
- 触发与 define 相同的基础校验逻辑，并汇总输出问题路线。
- 调度图快照未加载时仅做结构校验，跳过可达性检查。

> 调度层的实际行为（终到等待/回库/销毁）将在后续运行时调度阶段接入。
