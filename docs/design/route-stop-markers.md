# RouteStop 特殊标记与 Sign 约定

本文描述调度 RouteStop 的扩展语法，以及配套的牌子（Sign）命名规则，便于实现动态站台、换线等高级调度能力。

## 1. RouteStop 元数据 DSL
每个 `RouteStop` 除了 `stationId` / `waypointNodeId` 外，还可在 metadata 或 notes 中携带 `action` 字段，推荐使用 `ACTION:PAYLOAD` 的冒号分段格式：
```
ACTION:PAYLOAD[:MORE]
```
例如 `CHANGE:SURN:LT`、`DYNAMIC:SURN:PTK:[1:3]`。若参数较多，可继续拼接冒号片段，或用 `ACTION[参数]` 的数组式写法。运行时解析逻辑由 `RouteStopInterpreter` 统一处理。

### 1.1 换线标记（CHANGE）
- 语法：`CHANGE:<OperatorCode>:<LineCode>`（例如 `CHANGE:SURN:LT`）。
- 含义：列车抵达当前 stop 后，调度层将 `RouteProgress` 切换到目标运营商/线路下的默认 Route 或指定 Route，并重新计算后续停靠表。
- 典型场景：直通车在枢纽站由 FTL 线切换到 SURN-LT 线继续行驶。

### 1.2 动态站台标记（DYNAMIC）
- 语法：`DYNAMIC:<OperatorCode>:<StationCode>`，可附带区间，如 `DYNAMIC:SURN:PTK:[1:3]` 表示 PTK 站 1-3 号站台任选其一。
- 行为：调度层根据实时占用情况在指定站台集合中挑选一个空闲 nodeId，将其写入下一目的地，并把选择结果写回列车 MetaTag，供 PIDS/日志使用。
- 扩展：若只写 `DYNAMIC` 而不带范围，默认使用该站所有站台；如需特殊策略，可在 `[]` 内写 JSON 片段，例如 `[prefer=2,3]`。

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
