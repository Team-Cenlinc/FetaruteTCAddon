# RouteStop 特殊标记与 Sign 约定

本文描述调度 RouteStop 的扩展语法，以及配套的牌子（Sign）命名规则，便于实现动态站台、换线等高级调度能力。

## 1. RouteStop 元数据 DSL
每个 `RouteStop` 除了 `stationId` / `waypointNodeId` 外，还可在 metadata 或 notes 中携带 `action` 字段，推荐使用 `ACTION:PAYLOAD` 的冒号分段格式：
```
ACTION:PAYLOAD[:MORE]
```
例如 `CHANGE:SURN:LT`、`DYNAMIC:SURN:PTK:[1:3]`、`CRET SURN:D:DEPOT:1`。若参数较多，可继续拼接冒号片段。运行时由 `RouteStopActionResolver` 解析为调度意图，再由 `RuntimeDispatchService` 在授权边界内执行。

### 1.1 换线标记（CHANGE）
- 语法：`CHANGE:<OperatorCode>:<LineCode>`（例如 `CHANGE:SURN:LT`）。
- 含义：列车抵达当前 stop 后，调度层把对乘客显示的 operator/line（`FTA_OPERATOR_CODE`/`FTA_LINE_CODE` tags）改为新线路，但**不改变当前 Route 或 routeIndex**，也不改变管理归属。列车继续沿当前 route 运行。
- 典型场景：直通车在枢纽站起按 SURN-LT 线对乘客运营（列车继续按原 FTL 交路行驶、仍归 FTL 管理，HUD/PIDS 显示的线路信息变为 SURN-LT）。
- 行为：
  - 仅写入 `FTA_OPERATOR_CODE` 和 `FTA_LINE_CODE` tags
  - 不修改 `FTA_ROUTE_ID`/`FTA_ROUTE_CODE`/routeIndex
  - 与此前所属线路相同的 CHANGE 不算换线；缺少线路段或有空段的 CHANGE 不执行（运行时记 `CHANGE 解析失败`）
- **起步线路**：定义书**第一行（第一站之前）**写 `CHANGE:<OperatorCode>:<LineCode>` 表示列车**起步即按该线路对乘客运营**，例如交路 `MT-3N_DPExp` 在 NTA 起步、前半段实际在 WS 线上运营，到分界站 HHU 再 `CHANGE:SURC:MT`：
  ```
  CHANGE:SURC:WS
  STOP DYNAMIC:SURC:S:NTA:[1:3]
  ...
  STOP SURC:S:HHU:1
  CHANGE:SURC:MT
  ```
  - 存储上落在首站（sequence 0）的 notes 里，运行时与显示读同一处；解析定义书时，第一站之前的 `CHANGE` 行并入首站 notes（追加在首站已有动作行之后）。第一站之前只允许**一条** CHANGE；出现多条、或与首站下一行的旧写法 CHANGE 并存，视为歧义并报错（`command.route.define.change-ambiguous`）；第一站之前出现 `DYNAMIC`/`ACTION` 仍报 `command.route.define.action-first`。
  - CHANGE 是“抵达该站后”执行的，出车与折返复用没有“抵达”首站，所以**出车与折返复用都直接按它写线路标签**（`RouteLineChanges#entryLine`）：自动出库（`TrainCartsDepotSpawner`）、`/fta depot spawn`、折返复用（`dispatchLayover`）写下的 `FTA_OPERATOR_CODE`/`FTA_LINE_CODE` 就是起步线路，没有有效 CHANGE（未写、与交路同线、格式错误）时仍是交路自身线路，`FTA_ROUTE_CODE`/`FTA_ROUTE_ID` 不变。折返复用从中途下标入路时，入路站及之前的 CHANGE 一并算上，与显示口径一致。
  - 列车随后“抵达”首站时，标签已在目标线路上，运行时不再当成到站换线：不重复改写标签、不记 `CHANGE 移交成功`（标签与目标不一致时——例如没有经过出车入口的列车——仍按原样补写）。
  - 旧写法（CHANGE 写在首站下一行）继续接受，存储结果相同。`/fta route editor give`（按数据库停靠表渲染）与 `define` 后的归档书回显时，首站的 CHANGE 一律渲染成第一行、写在首站那一行之前，一读一写之后旧写法的书归一到新写法；`edit` 只把归档成书转回书与笔、保留原页面，旧归档书在下一次 `define` 后归一；中途站的 CHANGE 仍写在所属站之后。
  - 回显：`/fta route define` 与 `/fta route debug` 的停靠表解析结果里，首站的有效 CHANGE 显示为“起步线路=运营商:线路”，不显示成到站换线；中途站仍显示 `CHANGE:...`。格式错误的首站 CHANGE 运行时不执行，回显原样保留以便发现。
- CHANGE 是**通知**，不是移交管理：交路、交路组、时刻表、调度（优先级、回收、折返）仍归交路自身的线路；换线只影响对乘客显示的线路。
- 显示口径（`RouteLineChanges` 是唯一定义，执行与显示共用同一套解析）：
  - 每站所属线路 = 该站及之前最后一个有效 CHANGE 的目标，没有时为交路自身线路；**换线站本身算新线路**（以原线路到达、以新线路发车，到站即改写标签）。首站的 CHANGE 是起步线路：首站就属于目标线路，车站目录的停靠线路只算目标线路（列车从来不是以交路自身线路到达首站的）。
  - 列车当前线路以标签为准，标签不全时为交路本身的线路（运行时执行 CHANGE 必然写标签，标签就是“通知是否发生”的事实）。
  - HUD：`{line}`/`{line_color}`/`{operator}`/`{company}` 与线路绑定的模板跟当前线路走；`{through_*}` 给出前方下一次换线的车站与线路；LCD 前方停靠列表里换线之后的各站按新线路着色。
  - 公开 API（1.7.0）：`RouteApi.StopInfo#lineChange` 标出换线站，`TrainApi.TrainSnapshot#operatorCode`/`lineCode` 为当前线路；
    停靠线路（`StationApi#linesServing`）换线之后的车站算新线路、换线站两条都算；站牌行的线路按列车到该站时所属的线路。时刻表仍是交路自身线路的。

### 1.2 动态站台标记（DYNAMIC）
- 语法：`DYNAMIC:<OperatorCode>:<S|D>:<NodeCode>[:Range]`，例如 `DYNAMIC:SURN:S:PTK:[1:3]` 表示 PTK 站 1-3 号站台任选其一。
  - 旧格式 `DYNAMIC:<OperatorCode>:<StationCode>[:Range]` 仍按 Station 解析。
  - Station 候选 NodeId 固定为 `Operator:S:Station:Track`；Depot 候选 NodeId 固定为 `Operator:D:Depot:Track`。
  - Range 省略时默认使用受限候选范围，避免“全站扫描”带来的不可控与卡顿风险；多站台建议显式给范围。
- 行为（运行时选择顺序，满足“先空闲后可达”语义）：
  - 先筛选“空闲站台”：对应 NODE 资源未被其他列车占用（占用系统快照）。
  - 再做只读可达性检查；选择器不申请占用、不写 destination。
  - 已声明 DYNAMIC、但当前无法安全 materialize 时返回明确的 `BLOCKED`；容量耗尽、定义无效以及图/当前位置/占用证据缺失均属于该状态。禁止回退到 Route 占位节点或已占用站台。
  - 信号 tick / 推进点收到 `BLOCKED` 后保持停车，并撤回本车当前全部纯 queue entry；Layover 则在领取 ticket、换向与进路申请前终止本轮派发。
  - 站台全满时不会释放或绕过 NODE、EDGE、CONFLICT 等真实 claim；尽头站由已停靠列车优先出站，容量释放后进站列车再重新选择。
  - 若存在空闲且可达站台、只是咽喉或道岔暂时繁忙，仍先 materialize 站台，再由普通授权链保留 FIFO queue；不得把进路繁忙误判为容量耗尽。
  - 可写授权窗口与 Depot spawn gate 都到首个已 materialize 的 DYNAMIC 目标为止；更远处未解析的 DYNAMIC 在占位节点前截断，紧邻目标未解析时 fail-closed，原子联锁不得越界扩展，首个 TrainCarts destination 也不得使用 `fromTrack` 占位节点。
  - 选定后只覆盖该 stop 的“有效 NodeId”；普通 TrainCarts destination 必须等 Dispatcher 完成 `canEnter/acquire` 且无 hard blocker 后才写入。
  - DYNAMIC resolver / allocator 不控车、不 launch、不 stop、不 acquire。
  - TODO：将选定站台写回 MetaTag/事件（供 HUD/PIDS 精确显示）。

在 `route define` 书本语法中，DYNAMIC 可以：

- 作为独立 action 行附着到上一条 stop（推荐，语义最清晰）
- 或写在 stop 行末尾（便于编辑），例如：`STOP PTK DYNAMIC:SURN:PTK:[1:3]`
- 或作为 stop 目标的简写（语义：本站=PTK，站台动态选择），例如：`STOP DYNAMIC:SURN:PTK:[1:3]`

### 1.3 生成标记（CRET）
- 语法：`CRET <NodeId>` 或 `CRET DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`。
- 含义：明确列车在何处生成；调度层会在该 stop 触发生成/出库逻辑。
- 约束：整条路线仅允许一个 `CRET`，且必须为首个 stop（CRET 自身占一行 stop）。

### 1.4 销毁标记（DSTY）
- 语法：`DSTY <NodeId>` 或 `DSTY DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`。
- 含义：列车抵达该 stop 后执行销毁/回收（例如进库后销毁实体）。
- 约束：整条路线仅允许一个 `DSTY`，且必须为最后 stop（DSTY 自身占一行 stop）。

## 2. Sign 牌子命名扩展
在 `dispatcher/sign` 体系内，保留以下扩展规则，方便与 RouteStop 标记联动：

| Sign 类型 | 格式 | 说明 |
| --- | --- | --- |
| Waypoint | `Operator:From:To:Track:Seq` | 已在 AGENTS.md 描述，可在 `Track` 字段附加 `#TAG` 说明，如 `LT1#DYNAMIC` 表示该轨可参与动态站台分配 |
| Station | `Operator:S:Station:Track` | 站点/站台类节点（用于停站/开关门等行为）；`Track` 可编号 1..n，对应 `DYNAMIC[1:n]` 的站台索引 |
| Station throat | `Operator:S:Station:Track:Seq` | 站咽喉（图节点/Waypoint；同站点下的多个咽喉用 Seq 区分） |
| Depot | `Operator:D:Depot:Track` | 车库节点（用于发车/回库等行为；同时用于与同名站点区分）；`Track` 可编号 1..n |
| Depot throat | `Operator:D:Depot:Track:Seq` | 车库咽喉（图节点/Waypoint；同车库下的多个咽喉用 Seq 区分） |

## 3. 与 Runtime 的关系
- RouteStop 标记先被解析为调度意图，不直接操作 TrainCarts、routeIndex 或 occupancy。
- 普通下一跳 destination 只在 Dispatcher 完成授权后写入；AutoStation/waypoint dwell、debug setup、spawn execution 等行为流程例外必须在代码里带明确 reason。
- 执行 `CHANGE` 只更新逻辑归属 tag；执行 `DYNAMIC` 只 materialize effective node，后续仍由授权路径决定是否写 `setDestination(...)` 交给 TrainCarts 寻路。
- PIDS/Scoreboard 读取 `RouteProgress` + RouteStop 列表即可展示未来停靠。执行 `DYNAMIC` 时，选定的站台节点应写入列车 MetaTag（如 `fta:current-platform=PTK2`）并广播事件，便于 UI 更新。
- `RouteProgress` 中需缓存 `routeId`、`sequence`、`nextStop` 等字段，跨服或列车重载时从 MetaTag 恢复，再继续应用 RouteStop 标记。

> 以上约定为第一版草案，后续可以逐步扩展 action 前缀或在 metadata 中使用结构化 JSON，以便解析器保持稳定。
