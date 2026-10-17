# Route 运营属性与停靠标记

本文描述 Route 的运营属性（Create/Operation/Return）以及 CRET/TERM/DSTY 的停靠标记约定，供调度与运维参考。

## Route 运营属性
- `CREATE`：补车班次（通常用于从 Depot 注入运力）。
- `OPERATION`：运营班次（客运/正线运行）。
- `RETURN`：回库/回送班次（非运营）。

命令示例：
- 创建补车路由：`/fta route create ... --operation CREATE`
- 创建运营路由：`/fta route create ... --operation OPERATION`
- 更新为回库路由：`/fta route set ... --operation RETURN`

> 当前调度会基于运营属性做实际分流：`CREATE` 优先用于补车、`OPERATION` 用于在线运行、`RETURN` 用于回库/摘车。

补充说明（交路组）：
- `spawn_weight` 仅对 `OPERATION` 生效；`CREATE/RETURN` 固定按权重 `1` 参与。
- 交路组可配置 `maxOperationTrips`，列车达到圈数后会优先进入 `RETURN` 回库流程。

## 站台屏经由
站台屏在终点下面轮换显示备注，其中“经由”可以按交路配置：

- 配置：`/fta route set <company> <operator> <line> <route> --via "HHU,SPB"`（逗号分隔，按显示顺序；多个站要加双引号，客户端不认不带引号的逗号；只收本交路停车的站码，写错会列出可选的站）
- 清除：`--via-clear`；`/fta route info` 显示当前配置
- 未配置时站台屏自行推断一个：本站之后、终点之前停车的站里换乘线路最多的，一样多时站台多的，再一样时直通换线站、离本站近的优先；只停一条线、站台不到 4 个又不是直通站的不算
- 配置的站列车已经过就不写，也不改用推断；站台屏放不下时只留第一站

## 停靠标记（action）
RouteStop 的 notes 支持 action 行（`ACTION:PAYLOAD`），常用标记：

### CRET（生成）
- 语法：`CRET <NodeId>` 或 `CRET DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`
- 约束：整条路线仅允许一个 `CRET`，且必须为首个 stop（CRET 自身占一行 stop）。
- 说明：`CREATE` route 需要首 stop 为 `CRET` 才能进入 depot 出车计划。

### TERM（终到）
- 使用 stop 前缀 `TERM`/`TERMINATE`（即 RouteStopPassType.TERMINATE）。
- 语义：标记“运营终到站”，后续调度可在此等待下一班次或回库。

### DSTY（销毁）
- 语法：`DSTY <NodeId>` 或 `DSTY DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`
- 约束：整条路线仅允许一个 `DSTY`，且必须为最后 stop（DSTY 自身占一行 stop）。

### DYNAMIC（动态站台/车库）
- 语法：`DYNAMIC:<OperatorCode>:<StationCode>` 或 `DYNAMIC:<OperatorCode>:<DepotCode>`
- 可附带区间：如 `DYNAMIC:SURN:PTK:[1:3]`

### POWER（多模车换模）
- 语法：`POWER <ptg5|ptg6|shoe|diesel|supercap>`，写在所属停靠点（含 PASS 路径点）之后，与 CHANGE 同为动作行。
- 语义：多模车从这一点起换成这种受电方式；单模车、车上没有这种方式的车照旧跑。电对电经过即换，涉及内燃推迟到停车点并多停 25 秒。见 `multi-mode-power.md`。

### DECP / COPL（解编与联挂）
- 语法：`DECP FRONT|REAR:<节数>:GOTO:<交路>` / `DECP …:IDLE` / `DECP …:RETURN`；`COPL FROM:<交路>[:WAIT:<秒>]`。写在所属停靠点之后的动作行，在该站停车时执行。
- 解编：按运行方向从车头（FRONT）或车尾（REAR）切下这么多节；没被指定的部分继续本交路。GOTO 的交路须与本交路同一条线、同一交路组（`spawn_group`），首站是本站；IDLE 就地成待命车；RETURN 回库。
- 联挂：与同组里终到本站的 FROM 交路的车挂成一列，沿用写 COPL 一方的身份。先到的车在站台等（WAIT 不写取 `coupler.couple-wait-seconds`），后到的车开进同一条股道挂上；等不到各走各的。
- 那一站多停 `coupler.decouple-seconds` / `couple-seconds`（缺省 30 秒），编表、到站预测与现场停站同一口径。运行时见 `coupler.md`，编表见 `timetable.md`。
- 编组写法可写组合 `A+B`：各段交给 TrainCarts 解析后按顺序拼成一列出车，每段车厢打上各自的来源编组（`FTA_CAR_CONSIST`），解编只在来源编组分界上切。

## define 校验（当前）
`/fta route define` 会做基础一致性校验：
- CRET 只能出现一次，且必须是首个 stop。
- DSTY 只能出现一次，且必须是最后 stop。
- 若调度图快照已加载，会校验相邻 stop 的 NodeId 是否存在可达边；图未加载时仅提示并跳过校验。
- POWER 的受电方式认不出时报错。
- DECP / COPL 写法认不出、写在首站末站或不停车的站上、一站写了多条、`FRONT:n:IDLE`（解下的车头会挡住本交路继续前行）时报错（`RouteCouplingDirectives#validateLocal`）。

`/fta route debug <company> <operator> <line> <route> [page]` 可直接查看已保存线路的 stop 回显（无需先 define）。
`/fta route debug <company> <operator> <line> <route> [page]` 会进行调试展示：
- CRET/DSTY 会以指令行形式展示（不显示 PASS 前缀）。
- 若相邻 stop 不直接相连，会展开最短路径中的中间节点并标记为 PASS（仅回显，不写入路线）。

## 手动校验命令
`/fta route validate <company> <operator> <line> [route]`
- 触发与 define 相同的基础校验逻辑，并汇总输出问题路线。
- 调度图快照未加载时仅做结构校验，跳过可达性检查。
- 额外校验自动发车配置：不存在的 `spawn_group`、缺失 line/group baseline、同组多条 `OPERATION` route 缺少 `spawn_weight`、`CREATE` 首站缺少 `CRET`。
- 对 `DYNAMIC` stop：显式范围（如 `[1:3]`）会逐轨道检查；未显式范围时会在已加载图快照中扫描实际匹配的 Station/Depot 节点，避免只检查 `:1`。
- POWER 涉及内燃却写在不停车的路径点上时提示推迟到其后第一个停车点；写了 POWER 而首站没写时提示出发模式取决于列车（只提示，define 不拦）。
- DECP / COPL 指向的交路：须存在、不是本交路、同一交路组；GOTO 的交路首站是解编站，FROM 的交路末站是联挂站（`RouteCouplingDirectives#validateTargets`）。

> 调度层的实际行为（终到等待/回库/销毁）将在后续运行时调度阶段接入。
