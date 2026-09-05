# DYNAMIC Route Stops

本文档说明 DYNAMIC route stop 的格式、解析规则与运行时行为。

## 概述

DYNAMIC route stop 允许在运行时根据轨道占用情况动态选择站台/车库，而不是在定义 route 时固定写死。这对于多站台车站或多轨道车库非常有用。

## 格式

### 标准格式

```
DYNAMIC:OP:TYPE:NAME:[FROM:TO]
```

- `OP`：运营商代码（如 `SURN`）
- `TYPE`：节点类型，`S` 表示 Station，`D` 表示 Depot
- `NAME`：站点/车库名称（如 `PPK`、`DEPOT`）
- `[FROM:TO]`：轨道范围（如 `[1:3]` 表示轨道 1、2、3）

### 简写格式

单轨道时可省略范围方括号：

```
DYNAMIC:OP:S:STATION:1       # 等同于 DYNAMIC:OP:S:STATION:[1:1]
DYNAMIC:OP:D:DEPOT:2         # 等同于 DYNAMIC:OP:D:DEPOT:[2:2]
```

### 旧格式兼容

为兼容旧版配置，以下格式仍然支持（默认为 Station 类型）：

```
DYNAMIC:OP:STATION:[1:3]     # 等同于 DYNAMIC:OP:S:STATION:[1:3]
```

## 使用场景

### 1. 独立 DYNAMIC stop

在停靠表中直接使用 DYNAMIC 作为 stop 目标：

```
STOP DYNAMIC:SURN:S:PPK:[1:3]
TERM DYNAMIC:SURN:S:PPK:[1:2]
PASS DYNAMIC:SURN:S:PPK:[1:3]
```

### 2. CRET/DSTY 与 DYNAMIC 组合

#### 显式 NodeId + DYNAMIC 动作

出库/入库时指定固定轨道，同时为下一站添加 DYNAMIC 动作：

```
CRET SURN:D:DEPOT:1 DYNAMIC:SURN:S:PPK:[1:3]
DSTY SURN:D:DEPOT:2 DYNAMIC:SURN:D:DEPOT:[1:3]
```

#### DYNAMIC 简写形式（运行时选择出库/入库轨道）

出库/入库轨道本身也动态选择：

```
CRET DYNAMIC:SURN:D:DEPOT:[1:3]    # 运行时从 DEPOT 轨道 1-3 选择空闲轨道出库
DSTY DYNAMIC:SURN:D:DEPOT:[1:3]    # 运行时从 DEPOT 轨道 1-3 选择空闲轨道入库
```

## 解析规则

### define 阶段

1. 解析 DYNAMIC 指令格式
2. **不**写入固定的 `waypointNodeId`（留空）
3. 将完整 DYNAMIC 指令存入 `notes` 字段
4. 验证范围内的轨道节点

### 验证规则

DYNAMIC stop 在 define 阶段会检查轨道范围内的所有节点：

1. **节点存在性检查**：遍历范围内所有轨道，检查是否存在于调度图
   - 若所有轨道都不存在：报错 `dynamic-no-valid-tracks`
   - 若部分轨道不存在：警告 `dynamic-partial-tracks`（不阻止定义）

2. **可达性检查**：检查相邻 stop 之间是否存在可达路径
   - 对于 DYNAMIC stop，只要有一条轨道可达即通过
   - 若所有轨道都不可达：报错 `edge-unreachable`

示例警告信息：
```
[FTA] 第 2 个 DYNAMIC 停靠点部分轨道不存在 (缺失: SURN:S:PPK:3, 有效: 2)
```

### render 阶段（编辑器回显）

从 `notes` 提取 DYNAMIC 指令，按原格式输出：

```
DYNAMIC SURN:S:PPK:[1:3]           # 独立 DYNAMIC
CRET DYNAMIC:SURN:D:DEPOT:[1:3]    # CRET + DYNAMIC 简写
```

## 运行时选择

### 选择规则

1. **候选筛选**：优先选择空闲且可达的轨道（按轨道号升序）
   - 检查节点是否存在于调度图
   - 检查节点是否被其他列车占用
   - 检查从当前位置是否可达

2. **安全背压**：已经声明 DYNAMIC、但当前无法安全 materialize 时返回 `BLOCKED`
   - 典型原因包括没有空闲且可达的轨道、DYNAMIC 定义无效、图快照缺失、当前位置缺失或占用服务不可用
   - 不回退到 Route 中仅用于声明的占位 NodeId
   - 不把已占用站台当成可选目标
   - 信号 tick / 推进点保持停车，并撤回本车当前全部纯排队位次
   - Layover 在换向和领取 ticket 前停止本轮派发，等待站台容量恢复

对于尽头站，空闲站台仍然具有最高停靠优先级；站台全满时，进站列车撤回当前全部纯
queue entry，让已经停靠的列车能够先取得出站进路。已取得的 NODE、EDGE、
CONFLICT claim 以及列车实际占用的轨道不会因此释放或绕过。

若站台空闲且可达，只是咽喉、单线或道岔暂时繁忙，选择器仍会 materialize 该站台，
随后由普通授权链进入 FIFO queue；此时不得误报为容量耗尽并反复撤队。授权请求只能到达
首个已 materialize 的 DYNAMIC 目标，不能借由 lookahead 或原子联锁越过后续声明占位节点。
Depot spawn gate 同样遵守此边界：若紧邻出库点的是 DYNAMIC，必须先选出实际站台再生成列车；
若无法选台则在 preview、spawn 与可写 acquire 之前重试，不得用 `fromTrack` 占位节点申请进路或写入
TrainCarts destination。

### Materialization 粘性

同一列车、route 与 stop index 一旦选出合法 effective node，该选择在本段运行中保持稳定。后续中间 waypoint 或周期 signal tick 只能复用已经 materialize 的站台；即使另一站台此刻更空闲，也不能覆盖原选择。原站台暂时繁忙时应保持 `BLOCKED` 并等待普通占用/Gate Queue 放行，不能通过重新选台制造 destination 与已申请进路分叉。

只有 materialization 的 route/声明节点/RouteStop 定义证据失效、交路 handoff 清理旧状态，或列车真实完成该进度窗口后，运行时才允许建立新的选择。相关回归应同时验证“首次选择成功”和“后续推进点不会从已选股道跳回较小股道”。

### Waypoint 事件边界

列车车头进入已经声明在 Route 中的普通 transit/PASS Waypoint 时，运行时会在 `MEMBER_ENTER` 立即推进并 materialize 后续 DYNAMIC 目标。这样即使 TrainCarts/TCCoasters 组合没有再送达 `GROUP_ENTER`，也不会出现 `lastPassedGraphNode` 已到咽喉、route index 与 destination 却仍停在上一段的状态。

Waypoint STOP/TERMINATE 仍等待 `GROUP_ENTER`，保留整组到齐、居中与停站语义。若随后又收到同节点的 `GROUP_ENTER`，同节点/同索引去重窗会阻止重复推进。

### 占用检查

- 使用 `OccupancyManager` 检查 NODE 资源占用
- 同一列车对自身占用的节点不计为"占用"

### 可达性检查

- 使用 `RailGraphPathFinder` 计算最短路径
- 节点必须同时存在于 SignNodeRegistry 和 RailGraph

## 示例

### 完整 Route 示例

```
# 出库（动态选择车库轨道）
CRET DYNAMIC:SURN:D:DEPOT:[1:3]

# 中间站点（动态选择站台）
STOP DYNAMIC:SURN:S:PPK:[1:2]

# 终点站（动态选择站台）
TERM DYNAMIC:SURN:S:END:[1:3]

# 入库（动态选择车库轨道）
DSTY DYNAMIC:SURN:D:DEPOT:[1:3]
```

### 混合使用

```
# 固定出库轨道，下一站动态选择
CRET SURN:D:DEPOT:1 DYNAMIC:SURN:S:PPK:[1:3]

# 固定站台
STOP SURN:S:MID:1

# 动态选择终点站台
TERM DYNAMIC:SURN:S:END:[1:2]
```

## Debug

开启 debug 模式后，DYNAMIC 选择过程会输出日志：

```yaml
# config.yml
debug:
  enabled: true
```

日志示例：

```
DYNAMIC 分配失败: 无可用站台 (train=Train-001, spec=SURN:S:END:[1:3])
DYNAMIC 容量等待: train=Train-001 route=<routeId> index=2 reason=no-available-platform withdrawnQueueEntries=3
```

## DYNAMIC 节点匹配

### RouteIndexResolver DYNAMIC 匹配

当列车抵达某节点时，`RouteIndexResolver.resolveCurrentIndexWithDynamic()` 会检查该节点是否匹配 route 中的任何 DYNAMIC stop：

1. **普通匹配**：首先尝试精确匹配 `waypointNodeId`
2. **同站容错**：若精确匹配失败，尝试同站不同轨道匹配（Station/Depot key）
3. **DYNAMIC 匹配**：若以上都失败，遍历所有 stop 检查 DYNAMIC 规范匹配

DYNAMIC 匹配规则（`DynamicStopMatcher.matches()`）：
- `operatorCode` 必须匹配（忽略大小写）
- `nodeType` 必须匹配（S/D）
- `nodeName` 必须匹配（忽略大小写）
- `track` 必须在 `[fromTrack, toTrack]` 范围内

示例：
```
DYNAMIC:SURC:D:OFL:[1:2]  匹配 SURC:D:OFL:1 ✓
DYNAMIC:SURC:D:OFL:[1:2]  匹配 SURC:D:OFL:2 ✓
DYNAMIC:SURC:D:OFL:[1:2]  匹配 SURC:D:OFL:3 ✗（超出范围）
DYNAMIC:SURC:D:OFL        匹配 SURC:D:OFL:99 ✓（无范围限制）
```

### HUD 终点显示（EOP）

HUD 显示的 "End of Operation"（EOP）是 route 中**最后一个 AutoStation 类型的 stop**：

1. 从后往前遍历 stops
2. 跳过 `passType == PASS` 的 stop
3. 只考虑有 `stationId` 或 DYNAMIC Station 类型的 stop
4. 纯 Waypoint 类型的 STOP/TERM 不计入 EOP

对于 DYNAMIC stop，会从规范中提取站点信息用于显示。

## API 支持

### RouteApi.StopInfo

DYNAMIC stop 在 API 中的表示：

```java
record StopInfo(
    int sequence,
    String nodeId,              // placeholder nodeId (OP:S/D:NAME:fromTrack)
    Optional<String> stationName, // 从 DynamicSpec.nodeName() 提取
    int dwellSeconds,
    PassType passType,          // 行为：STOP/PASS/TERMINATE
    boolean dynamic             // true = DYNAMIC 站台
)
```

- `nodeId`：DYNAMIC stop 使用 placeholder（`spec.toPlaceholderNodeId()`，取 `fromTrack`）
- `stationName`：从 DYNAMIC 规范的 `nodeName` 提取
- `dynamic`：标识是否为动态站台选择

### RouteApi.TerminalInfo

终点信息区分 EOR 和 EOP：

```java
record TerminalInfo(
    String endOfRouteNodeId,        // EOR: waypoints 末尾
    Optional<String> endOfRouteName,
    String endOfOperationNodeId,    // EOP: 最后一个 Station stop
    Optional<String> endOfOperationName
)
```

DYNAMIC 终点站会正确解析并填入 EOP 字段。

## 相关文件

- `DynamicStopMatcher.java`：DYNAMIC 规范解析与匹配工具
- `RouteIndexResolver.java`：Route 索引解析（支持 DYNAMIC 匹配）
- `FtaRouteCommand.java`：Route 定义解析
- `RuntimeDispatchService.java`：运行时 DYNAMIC 选择
- `TrainHudContextResolver.java`：HUD 终点（EOP）解析
- `RouteProgressRegistry.java`：Route 进度跟踪
- `RouteApiImpl.java`：API 层 DYNAMIC 解析与 EOR/EOP 计算
- `EtaService.java`：ETA 计算中的 DYNAMIC 终点解析
