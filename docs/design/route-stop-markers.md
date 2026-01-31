# RouteStop 特殊标记与 Sign 约定

本文描述调度 RouteStop 的扩展语法，以及配套的牌子（Sign）命名规则，便于实现动态站台、换线等高级调度能力。

## 1. RouteStop 元数据 DSL
每个 `RouteStop` 除了 `stationId` / `waypointNodeId` 外，还可在 metadata 或 notes 中携带 `action` 字段，推荐使用 `ACTION:PAYLOAD` 的冒号分段格式：
```
ACTION:PAYLOAD[:MORE]
```
例如 `CHANGE:SURN:LT`、`DYNAMIC:SURN:PTK:[1:3]`、`CRET SURN:D:DEPOT:1`。若参数较多，可继续拼接冒号片段。运行时解析目前由 `RuntimeDispatchService` 直接处理（后续可再抽出专用解释器）。

### 1.1 换线标记（CHANGE）
- 语法：`CHANGE:<OperatorCode>:<LineCode>`（例如 `CHANGE:SURN:LT`）。
- 含义：列车抵达当前 stop 后，调度层更新列车的所属 operator/line 标识（`FTA_OPERATOR_CODE`/`FTA_LINE_CODE` tags），但**不改变当前 Route 或 routeIndex**。列车继续沿当前 route 运行，仅逻辑归属变更。
- 典型场景：直通车在枢纽站由 FTL 线移交给 SURN-LT 线运营（列车继续按原 route 行驶，但 HUD/PIDS 显示的线路信息变为新线路）。
- 行为：
  - 仅写入 `FTA_OPERATOR_CODE` 和 `FTA_LINE_CODE` tags
  - 不修改 `FTA_ROUTE_ID`/`FTA_ROUTE_CODE`/routeIndex
  - HUD placeholder（如 `{line}`/`{line_color}`/`{operator}`）会在下次 tick 时感知变化

### 1.2 动态站台标记（DYNAMIC）
- 语法：`DYNAMIC:<OperatorCode>:<StationCode>[:Range]`，例如 `DYNAMIC:SURN:PTK:[1:3]` 表示 PTK 站 1-3 号站台任选其一。
  - 当前实现仅支持 Station 节点（候选 NodeId 固定为 `Operator:S:Station:Track`）。
  - Range 省略时默认视为 `[1:1]`（即固定 track=1），避免“全站扫描”带来的不可控与卡顿风险；如需多站台必须显式给范围。
- 行为（运行时选择顺序，满足“先空闲后可达”语义）：
  - 先筛选“空闲站台”：对应 NODE 资源未被其他列车占用（占用系统快照）。
  - 再检查可达性 + 占用许可：对每个候选站台构建一次 lookahead 请求并调用 `canEnter()` 验证（含走廊方向/冲突组），选出第一个可进入者。
  - 若无任何“空闲且可进入”候选，会回退为“可进入的第一个站台”（并输出 debug 日志），用于避免全占用时完全无法选站台。
  - 选定后会覆盖该 stop 的“有效 NodeId”，并写入 TrainCarts destination 触发自动寻路。
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
- RouteStop 标记只影响我们向 TrainCarts 写“下一 destination”时的逻辑。在执行 `CHANGE`/`DYNAMIC` 等动作后，仍通过 `train.getProperties().setDestination(nodeId)` 交给 TC 寻路
- PIDS/Scoreboard 读取 `RouteProgress` + RouteStop 列表即可展示未来停靠。执行 `DYNAMIC` 时，选定的站台节点应写入列车 MetaTag（如 `fta:current-platform=PTK2`）并广播事件，便于 UI 更新。
- `RouteProgress` 中需缓存 `routeId`、`sequence`、`nextStop` 等字段，跨服或列车重载时从 MetaTag 恢复，再继续应用 RouteStop 标记。

> 以上约定为第一版草案，后续可以逐步扩展 action 前缀或在 metadata 中使用结构化 JSON，以便解析器保持稳定。
