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
- 含义：列车抵达当前 stop 后，调度层更新列车的所属 operator/line 标识（`FTA_OPERATOR_CODE`/`FTA_LINE_CODE` tags），但**不改变当前 Route 或 routeIndex**。列车继续沿当前 route 运行，仅逻辑归属变更。
- 典型场景：直通车在枢纽站由 FTL 线移交给 SURN-LT 线运营（列车继续按原 route 行驶，但 HUD/PIDS 显示的线路信息变为新线路）。
- 行为：
  - 仅写入 `FTA_OPERATOR_CODE` 和 `FTA_LINE_CODE` tags
  - 不修改 `FTA_ROUTE_ID`/`FTA_ROUTE_CODE`/routeIndex
  - HUD placeholder（如 `{line}`/`{line_color}`/`{operator}`）会在下次 tick 时感知变化

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
