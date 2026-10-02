# FetaruteTCAddon 公开 API 开发指南

本文档介绍如何使用 FetaruteTCAddon 提供的公开 API 构建外部插件（如 BlueMap 可视化桥接插件）。

## 快速开始

### 1. 添加依赖

在你的插件 `build.gradle` 中添加：

```groovy
repositories {
    // FetaruteTCAddon 仓库（如果发布到 Maven）
    maven { url 'https://your-maven-repo.example.com' }
}

dependencies {
    compileOnly 'org.fetarute:FetaruteTCAddon:1.0.0'
}
```

在 `plugin.yml` 中声明依赖：

```yaml
depend: [FetaruteTCAddon]
# 或软依赖
softdepend: [FetaruteTCAddon]
```

### 2. 获取 API 实例

```java
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;

public class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 获取 API（可能为 null）
        FetaruteApi api = FetaruteApi.getInstance();
        if (api == null) {
            getLogger().warning("FetaruteTCAddon 未加载");
            return;
        }

        // 或使用 Optional 风格
        FetaruteApi.get().ifPresent(this::setupWithApi);
    }

    private void setupWithApi(FetaruteApi api) {
        getLogger().info("API 版本: " + api.version());
    }
}
```

### 3. 版本兼容检查

```java
// 检查 API 版本兼容性
if (!FetaruteApi.isCompatible(this, "1.0.0")) {
    getLogger().severe("FetaruteTCAddon API 版本不兼容，需要 >= 1.0.0");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

## API 模块

FetaruteApi 提供九个子模块、一个数据版本号 `dataRevision()`（1.6.0，见“车站组与停靠线路”一节），另有一组 Bukkit 事件（见“事件”一节）：

| 模块 | 方法 | 功能 |
|------|------|------|
| `graph()` | `GraphApi` | 调度图：节点、边、路径查询 |
| `trains()` | `TrainApi` | 列车状态：位置、速度、ETA；当前所属线路与回库判定（1.7.0） |
| `routes()` | `RouteApi` | 路线定义：站点、停靠表；直通运转换线站（1.7.0） |
| `occupancy()` | `OccupancyApi` | 占用状态：信号、队列 |
| `stations()` | `StationApi` | 站点信息：位置、名称、关联节点；车站组与停靠线路（1.6.0） |
| `operators()` | `OperatorApi` | 运营商信息：名称、颜色、优先级 |
| `lines()` | `LineApi` | 线路信息：服务类型、颜色、状态 |
| `eta()` | `EtaApi` | ETA：列车/票据/站牌列表（1.9.0 站牌行结构化） |
| `timetables()` | `TimetableApi` | 时刻表：已发布时刻表、车次、站点计划到发、列车当前车次与偏差（1.4.0；1.5.0 统一停靠序号口径；1.8.0 车次取消） |

---

## GraphApi - 调度图

### 获取世界图快照

```java
UUID worldId = player.getWorld().getUID();
api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
    System.out.println("节点数: " + snapshot.nodeCount());
    System.out.println("边数: " + snapshot.edgeCount());
    System.out.println("连通分量数: " + snapshot.componentCount());
    System.out.println("构建时间: " + snapshot.builtAt());
});
```

### 遍历节点

```java
for (GraphApi.ApiNode node : snapshot.nodes()) {
     String id = node.id();
     GraphApi.NodeType type = node.type(); // STATION, DEPOT, WAYPOINT, SWITCHER
     GraphApi.Position pos = node.position();

     // 获取显示名（如果有）
     node.displayName().ifPresent(name -> {
         System.out.println(id + " -> " + name);
     });
}
```

### 遍历边

```java
for (GraphApi.ApiEdge edge : snapshot.edges()) {
    String from = edge.nodeA();
    String to = edge.nodeB();
    int distance = edge.lengthBlocks();
    double speedLimit = edge.speedLimitBps();
    boolean blocked = edge.blocked();
}
```

### 最短路径查询

```java
api.graph().findShortestPath(worldId, "OP:S:StationA:1", "OP:S:StationB:1")
    .ifPresent(path -> {
        System.out.println("路径节点: " + path.nodes());
        System.out.println("总距离: " + path.totalDistanceBlocks() + " 方块");
        System.out.println("预估时间: " + path.estimatedTravelTimeSec() + " 秒");
    });
```

### 连通分量查询

```java
// 检查两点是否在同一连通分量
String keyA = api.graph().getComponentKey(worldId, "OP:S:StationA:1").orElse("");
String keyB = api.graph().getComponentKey(worldId, "OP:S:StationB:1").orElse("");
boolean reachable = keyA.equals(keyB) && !keyA.isEmpty();
```

### 图状态检查

```java
api.graph().getStaleInfo(worldId).ifPresent(info -> {
    if (info.stale()) {
        System.out.println("图已过期: " + info.reason());
    }
});
```

---

## TrainApi - 列车状态

### 列出活跃列车

```java
// 某世界的所有列车
for (TrainApi.TrainSnapshot train : api.trains().listActiveTrains(worldId)) {
    System.out.println("列车: " + train.trainName());
    System.out.println("  路线: " + train.routeId());
    System.out.println("  速度: " + train.speedBps() + " blocks/s");
    System.out.println("  信号: " + train.signal()); // PROCEED, CAUTION, STOP
}

// 全服列车
Collection<TrainApi.TrainSnapshot> all = api.trains().listAllActiveTrains();
```

### 获取单个列车

```java
api.trains().getTrainSnapshot("train-1").ifPresent(train -> {
    // 当前节点
    train.currentNode().ifPresent(node ->
        System.out.println("当前节点: " + node));

    // 下一目标
    train.nextNode().ifPresent(next ->
        System.out.println("下一站: " + next));

    // ETA 信息
    train.eta().ifPresent(eta -> {
        System.out.println("预计到达: " + eta.etaMinutes() + " 分钟");
        System.out.println("即将到站: " + eta.arriving()); // 距离 <= 2 条边
        System.out.println("延误: " + eta.delayed());
    });
});
```

### 当前线路与回库（1.7.0）

```java
api.trains().getTrainSnapshot("SURC-WS-LC-1037").ifPresent(train -> {
    // 直通运转换线后是新线路；routeId 里的线路仍是交路本身的（管理归属）
    train.lineCode().ifPresent(line ->
        System.out.println("当前线路: " + train.operatorCode().orElse("?") + ":" + line));
    if (train.outOfService()) {
        System.out.println("回库 / Not in Service"); // 不显示「开往」、下一站与到站时间
    }
});
```

| 字段 | 口径 |
|------|------|
| `routeId` / `routeCode` | 交路代码 `运营商:线路:交路`（两者同值，`routeCode` 在交路找得到时有值）。其中的运营商、线路是交路本身的归属（管理归属），出车后不变 |
| `operatorCode` / `lineCode` | 列车**当前**对乘客显示的线路：取自列车的线路标签（出车与直通运转 CHANGE 写入），没有标签时为交路本身的线路；线路存在时代码按主数据的写法给出。两者同时有值，标签与交路都不明时为空 |
| `outOfService` | 回库交路越过运营终点（EOP）之后，或整趟没有载客车站的回库交路为 true；与 HUD、站牌的「回库 / Not in Service」同一判定（`RouteTerminals.outOfService`）。停在运营终点时仍为 false；出库、运营交路恒为 false |
| `nextNode` | 下一个**途经节点**（可能是区间点或咽喉），不一定是下一个停车站 |

用 1.6.0 签名构造 `TrainSnapshot` 的代码仍可编译运行：当前线路为空、`outOfService` 为 false。
列车进度下标与交路 UUID 仍按原方式取：`EtaApi#getRuntimeSnapshot` 的 `routeIndex`，`RouteApi#findByCode` 拆 `routeId`。

### 统计数量

```java
int total = api.trains().activeTrainCount();
int inWorld = api.trains().activeTrainCount(worldId);
```

---

## RouteApi - 路线定义

### 列出所有路线

```java
for (RouteApi.RouteInfo route : api.routes().listRoutes()) {
    System.out.println("路线: " + route.code() + " (" + route.id() + ")");
    System.out.println("  显示名: " + route.displayName());
    System.out.println("  运营类型: " + route.operationType()); // LOCAL, RAPID, EXPRESS
    System.out.println("  交路阶段: " + route.stage());         // CREATE, OPERATION, RETURN
}
```

### 路线 ID、运营类型与交路阶段（1.6.0）

| 字段 | 口径 |
|------|------|
| `RouteInfo#id` | 路线 UUID。`listRoutes()`、`getRoute()`、`findByCode()` 对同一条路线返回同一个值（1.6.0 之前恒为 `null`） |
| `RouteInfo#operationType` | 运营类型，来自路线的停站模式 `pattern_type`：`LOCAL → LOCAL`、`RAPID`/`NEO_RAPID → RAPID`、`EXPRESS`/`LIMITED_EXPRESS → EXPRESS`（1.6.0 之前恒为 `NORMAL`）。`NORMAL` 仅在路线实体不可用时出现 |
| `RouteInfo#stage` | 交路阶段，来自路线的 `operation_type`：`CREATE` 出库、`OPERATION` 运营、`RETURN` 回库，未知为 `UNKNOWN` |

运营类型与交路阶段相互独立：一条各停路线可以是出库、运营或回库交路。需要只看载客运营时按 `stage() == RouteStage.OPERATION` 过滤。

### 获取路线详情

```java
api.routes().getRoute(routeUuid).ifPresent(detail -> {
    System.out.println("途经点: " + detail.waypoints());

    // 停靠表：与 waypoints 等长、下标一一对应；sequence 就是下标（0 起），展示时自行 +1
    for (RouteApi.StopInfo stop : detail.stops()) {
        System.out.println((stop.sequence() + 1) + ". " + stop.nodeId());
        stop.stationName().ifPresent(name ->
            System.out.println("   站名: " + name));
        stop.stationId().ifPresent(id ->
            System.out.println("   车站: " + stop.stationCode().orElse("?") + " (" + id + ")"));
        System.out.println("   停车: " + stop.dwellSeconds() + "s");
        System.out.println("   类型: " + stop.passType()); // STOP, PASS, TERMINATE
        System.out.println("   动态站台: " + stop.dynamic()); // true = 运行时选择站台
    }

    // 终点信息（EOR/EOP）
    RouteApi.TerminalInfo terminal = detail.terminal();
    if (!terminal.isEmpty()) {
        System.out.println("路线终点 (EOR): " + terminal.endOfRouteNodeId());
        terminal.endOfRouteName().ifPresent(name ->
            System.out.println("  EOR 站名: " + name));
        System.out.println("运营终点 (EOP): " + terminal.endOfOperationNodeId());
        terminal.endOfOperationName().ifPresent(name ->
            System.out.println("  开往: " + name));
    }
});
```

### 停靠序号口径（1.5.0 统一）

公开 API 里所有“第几站”都是**交路节点序列的 0 起下标**，同一个数在各处指同一站：

| 字段 | 含义 |
|------|------|
| `RouteApi.StopInfo#sequence` | 本条在 `RouteDetail.stops()` / `waypoints()` 中的下标（1.5.0 之前为 1 起重新编号） |
| `TimetableApi.StopTime#stopSequence`、`Departure#stopSequence` | 同上 |
| `TimetableApi.TrainAssignment#lastStopSequence` / `nextStopSequence` | 同上 |
| `TrainArriveStationEvent` / `TrainDepartStationEvent#getStopIndex` | 同上 |
| `EtaApi.RuntimeSnapshot#routeIndex` | 列车最近到达的节点下标（同一口径） |

所以拿到序号 `n` 直接 `route.stops().get(n)` 即可。`stops()` 包含 PASS 节点（区间点、咽喉、通过站），序号不是“第几个停车站”；
是否停车看 `passType`（STOP/TERMINATE 停，PASS 不停），**不要**用 `dwellSeconds == 0` 判断——停站 0 秒的 STOP 站同样停车。

### 停靠点站名与车站身份（1.6.0）

停靠点通常只写节点（实服数据里 `route_stops.station_id` 全部为空），DYNAMIC 停靠只写在 `notes` 里。1.6.0 起按节点解析车站：

| 节点 | 例 | `stationId` / `stationCode` | `stationName` |
|------|----|------|------|
| 站台 `OP:S:CODE:TRACK` | `SURC:S:PPK:1` | 站码 `CODE` 对应的车站 | 车站记录的站名 |
| 咽喉 `OP:S:CODE:TRACK:SEQ` | `SURC:S:PPK:1:001` | 同上 | 同上 |
| DYNAMIC 占位 `OP:S:CODE:fromTrack` | `SURC:S:WYB:1` | 同上 | 同上 |
| 车库 `OP:D:CODE:TRACK`、DYNAMIC 车库 | `SURC:D:LWN:1` | 空 | 空 |
| 区间点、道岔等 | `SURC:PPK:HHU:1:001` | 空 | 空 |

- 停靠点绑定了车站记录（`station_id`）时以绑定为准。
- 站码查不到车站记录时，`stationCode` 与 `stationName` 都是站码，`stationId` 为空。
- 节点无法按上表解析、但被某个车站绑定为图节点（`graph_node_id`）时，归到该车站。
- 运营商代码不区分大小写，跨公司重名时先到先得；解析交路停靠点时优先取交路自身的运营商。HUD 按站码查车站使用同一个索引，二者不会给出不同的车站；同一站码在不同运营商下不会串站。
- `TerminalInfo#endOfOperationName`、`endOfRouteName` 使用同一套规则；线路终点为车库时 `endOfRouteName` 仍为 `LWN Depot`（停靠表里车库停靠点的 `stationName` 为空）。
- 解析结果在路线缓存或车站数据变化时预先算好，`getRoute()` 不访问存储。

1.6.0 之前只有绑定了车站记录的停靠点给出站名，DYNAMIC 停靠给出的是站码（如 `WYB`），普通停靠点为空。

### 直通运转（1.7.0）

停靠点备注里的 `CHANGE:<运营商>:<线路>` 是一条**通知**：列车到达该站起改按另一条线运营（对乘客显示新线路），**不换交路、不改进度下标，也不改变管理归属**——
交路、交路组、时刻表、调度仍归交路自身的线路（`RouteInfo#operatorCode`/`lineCode`）。一趟直通车对乘客而言横跨两条线，`StopInfo#lineChange` 标出换线站：

```java
RouteApi.LineRef line = new RouteApi.LineRef(detail.info().operatorCode(), detail.info().lineCode());
for (RouteApi.StopInfo stop : detail.stops()) {
    if (stop.lineChange().isPresent()) {
        line = stop.lineChange().get();
        System.out.println(stop.stationName().orElse(stop.nodeId()) + " 起直通 " + line.lineCode());
    }
    // 本站及之后属于 line；换乘时排除的也是它（换线站本身两条都排除）
}
```

- 每站所属线路 = 该站及之前最后一次换线的目标，没有时为交路自身线路。**换线站本身算新线路**：列车以原线路到达、以新线路发车。
- **起点的当前线路**：定义书第一站之前的 `CHANGE`（起步线路）存在首站备注里，`StopInfo#lineChange` 在首站给出目标线路；出车与折返复用直接按它写线路标签，所以列车刚出车、还没到首站时，`TrainSnapshot#operatorCode`/`lineCode` 就是这条起步线路（`routeId` 与管理归属仍是交路自身的线路）。
- 只有真正换线才有值：目标与此前所属线路相同的 CHANGE、缺线路段或有空段的 CHANGE（运行时也不执行）都不算。
- `LineRef` 的代码在线路存在时按主数据的写法给出（指令可能写成小写），不存在时原样给出；比较请不区分大小写。
- 列车当前属于哪条线看 `TrainSnapshot#operatorCode`/`lineCode`。
- 面向乘客的查询同一口径：停靠线路（`StationApi#linesServing`，“这里能坐哪条线”）换线之后的车站算新线路、换线站两条都算；
  站牌行 `EtaApi.BoardRow#lineName` 与线路过滤按列车到该站时所属的线路。
- 管理归属不随换线变化：`RouteInfo`、`TrainSnapshot#routeId`、时刻表（`TimetableApi`，含 `Departure#lineId`）、调度与回收都是交路自身的线路。

### EOR 与 EOP 区别

- **EOR (End of Route)**: 线路终点，即交路的最后一个节点（常为车库或折返线）；车库时 `endOfRouteName` 为 `LWN Depot`
- **EOP (End of Operation)**: 退出营运前的最后一个车站，即最后停靠的车站；回库途中只通过的车站、折返线上的 TERM 都不算

两者与 HUD `dest_eor` / `dest_eop`、站牌同一口径（`RouteTerminals`）。以实服 `WS-1C_ShortD`（回库交路）为例：
EOP 是 `SURC:S:HHU:3`，EOR 是车库 `SURC:D:LWN:1`。

方向牌/信息屏通常显示 **EOP**。

### 按代码查找

```java
// operator:line:route 格式
api.routes().findByCode("METRO", "Line1", "EXP-01")
    .ifPresent(detail -> {
        // ...
    });
```

---

## OccupancyApi - 占用状态

### 检查节点/边占用

```java
// 检查节点是否被占用
boolean nodeOccupied = api.occupancy().isNodeOccupied(worldId, "OP:S:StationA:1");

// 检查边是否被占用（格式：nodeA<->nodeB）
boolean edgeOccupied = api.occupancy().isEdgeOccupied(worldId, "OP:S:A:1<->OP:S:B:1");
```

### 获取占用者

```java
api.occupancy().getNodeOccupant(worldId, "OP:S:StationA:1")
    .ifPresent(claim -> {
        System.out.println("占用列车: " + claim.trainName());
        System.out.println("资源类型: " + claim.resourceType()); // NODE, EDGE, CONFLICT
        System.out.println("信号: " + claim.signal());
    });
```

### 列出所有占用

```java
for (OccupancyApi.OccupancyClaim claim : api.occupancy().listAllClaims()) {
    System.out.println(claim.trainName() + " 占用 " + claim.resourceId());
}
```

### 查看队列

```java
for (OccupancyApi.QueueSnapshot queue : api.occupancy().listQueues(worldId)) {
    System.out.println("资源: " + queue.resourceId());
    for (OccupancyApi.QueueEntry entry : queue.entries()) {
        System.out.println("  #" + entry.position() + " " + entry.trainName()
            + " (等待 " + entry.waitingSeconds() + "s)");
    }
}
```

---

## StationApi - 站点信息

### 列出所有站点

```java
// 列出所有站点
for (StationApi.StationInfo station : api.stations().listAllStations()) {
    System.out.println(station.code() + ": " + station.name());
    station.secondaryName().ifPresent(en -> System.out.println("  英文: " + en));
}

// 按运营商列出
for (StationApi.StationInfo station : api.stations().listByOperator(operatorId)) {
    System.out.println(station.name());
}

// 按线路列出
for (StationApi.StationInfo station : api.stations().listByLine(lineId)) {
    System.out.println(station.name());
}
```

### 获取站点详情

```java
api.stations().getStation(stationId).ifPresent(station -> {
    System.out.println("站点代码: " + station.code());
    System.out.println("站点名称: " + station.name());
    station.secondaryName().ifPresent(name ->
        System.out.println("副站名: " + name));

    // 位置信息
    station.location().ifPresent(pos ->
        System.out.println("位置: " + pos.x() + ", " + pos.y() + ", " + pos.z()));

    // 关联的调度图节点
    station.graphNodeId().ifPresent(nodeId ->
        System.out.println("图节点: " + nodeId));
});
```

### 按代码查找

```java
api.stations().findByCode(operatorId, "AAA").ifPresent(station -> {
    System.out.println("找到站点: " + station.name());
});
```

### 统计数量

```java
int total = api.stations().stationCount();
System.out.println("共 " + total + " 个站点");
```

### 车站组与停靠线路（1.6.0）

**车站组**是乘客视角的一座换乘站，成员是车站记录，可以跨运营商、跨公司（例如 FTA 的 SL 线车站与 SURC 的车站位于同一处）。
同一运营商、同一站码的不同股道本来就是同一站，不需要建组。一个车站最多属于一个组。每个成员标注换乘方式
`TransferType`（`SAME_PLATFORM` 同台换乘、`IN_STATION` 站内换乘、`OUT_OF_STATION` 出站换乘）与可选的步行秒数。车站组由
`/fta station group` 命令维护（见 [站点主数据管理](station-management.md)）。

**停靠线路**由 FTCA 计算，调用方不必再遍历路线推导换乘：

```java
StationApi stations = api.stations();

// 列车浮层：下一站可换乘的线路（节点 ID 可以是站台、咽喉或 DYNAMIC 占位）
for (StationApi.ServingLine line : stations.linesServingNode("SURC:S:PPK:1")) {
    if (line.lineId().equals(currentLineId)) {
        continue; // 本线
    }
    String color = line.color().orElse("#888888");
    String how = line.transferType()
        .map(type -> type + line.walkSeconds().stream().mapToObj(s -> " " + s + "s").findFirst().orElse(""))
        .orElse("本站");
    System.out.println(line.operatorCode() + " " + line.lineCode() + " " + line.lineName()
        + " @" + line.stationCode() + " [" + how + "] " + color);
}

// 车站所属的车站组
stations.findGroupOfNode("FTA:S:PPK:1").ifPresent(group -> {
    System.out.println(group.code() + " " + group.name());
    for (StationApi.StationGroupMember m : group.members()) {
        System.out.println("  " + m.operatorCode() + "/" + m.stationCode() + " " + m.stationName()
            + " " + m.transferType());
    }
});
```

`linesServing(stationId)` / `linesServingNode(nodeId)` 的口径：

- 返回停靠查询站**及同组各站**的全部线路。查询站自身的线路 `transferType`、`walkSeconds` 为空；同组其他车站的线路带上该成员的换乘方式与步行秒数，`stationId`/`stationCode` 是实际停靠的那座车站。
- 只统计 `STOP` 与 `TERMINATE`，`PASS` 不算；DYNAMIC 停靠归到它所在的车站。
- 出库、回库、运营各阶段的路线都统计；需要区分时按 `RouteApi.RouteInfo#stage` 自行过滤。
- 直通运转（1.7.0）：按列车在该站所属的线路统计——换线之后的车站算新线路，换线站本身两条都算（以原线路到达、以新线路发车）；换线目标线路不存在时其后的车站不计。1.7.0 之前一律算交路本身的线路，直通车换线后的车站会被当成原线路停靠。
- 同一条线路既停靠查询站又停靠同组其他车站时只列一次，保留查询站那一条。
- 排序：成员 `sortOrder` → 运营商代码 → 线路代码。
- `color` 为线路色，缺失时回退运营商主题色。
- 节点不是车站节点（区间点、车库）或没有对应的车站记录时返回空列表。

**性能与刷新**：车站组与停靠线路读内存快照。路线、车站、线路、运营商、车站组变化时重建，返回不可变列表；
每次查询只做查表、不访问存储，可在任意线程调用（按列车每 0.5 秒刷新一次没有问题）。

**变更通知**：

- `FetaruteApi#dataRevision()`：路线、车站、线路、车站组任一变化时递增，插件重载后不回退。外部插件可以缓存查询结果，发现版本变化后立即刷新。
- `StationGroupChangedEvent`：车站组变化（建组、成员增删改、删组）后的下一 tick 发出，`getDataRevision()` 为变化生效后的版本。

```java
long seen = -1;

void refreshIfChanged(FetaruteApi api) {
    long revision = api.dataRevision();
    if (revision != seen) {
        seen = revision;
        rebuildOverlayCache(api); // 重新拉取路线与停靠线路
    }
}
```

---

## OperatorApi - 运营商信息

### 列出运营商

```java
// 列出所有运营商
for (OperatorApi.OperatorInfo op : api.operators().listAllOperators()) {
    System.out.println(op.code() + ": " + op.name());
}

// 按公司列出
for (OperatorApi.OperatorInfo op : api.operators().listByCompany(companyId)) {
    System.out.println(op.name());
}
```

### 获取运营商详情

```java
api.operators().getOperator(operatorId).ifPresent(op -> {
    System.out.println("运营商: " + op.name());
    op.colorTheme().ifPresent(color -> System.out.println("主题色: " + color));
});
```

### 按代码查找

```java
api.operators().findByCode(companyId, "MTR").ifPresent(op -> {
    System.out.println("找到运营商: " + op.name());
});
```

---

## LineApi - 线路信息

### 列出线路

```java
// 列出所有线路
for (LineApi.LineInfo line : api.lines().listAllLines()) {
    System.out.println(line.code() + ": " + line.name());
}

// 按运营商列出
for (LineApi.LineInfo line : api.lines().listByOperator(operatorId)) {
    System.out.println(line.name() + " (" + line.serviceType() + ")");
}
```

### 获取线路详情

```java
api.lines().getLine(lineId).ifPresent(line -> {
    System.out.println("线路: " + line.name());
    line.color().ifPresent(color -> System.out.println("颜色: " + color));
});
```

### 按代码查找

```java
api.lines().findByCode(operatorId, "L1").ifPresent(line -> {
    System.out.println("找到线路: " + line.name());
});
```

---

## EtaApi - ETA 与站牌列表

### 列车 ETA

```java
EtaApi.EtaResult result = api.eta().getForTrain("train-1", EtaApi.Target.nextStop());
System.out.println("ETA: " + result.etaMinutes() + " 分钟");
System.out.println("状态: " + result.statusText());
```

### 票据 ETA

```java
EtaApi.EtaResult ticketEta = api.eta().getForTicket("ticket-001");
System.out.println("发车 ETA: " + ticketEta.etaMinutes() + " 分钟");
```

### 站牌列表

```java
EtaApi.BoardResult board = api.eta().getBoard("OP", "AAA", null, Duration.ofMinutes(10));
for (EtaApi.BoardRow row : board.rows()) {
    System.out.println(row.lineName() + " -> " + row.destination() + " (" + row.statusText() + ")");
}
```

站牌行的 `lineName`（线路代码）与 `getBoard` 的线路过滤按列车到达本站时所属的线路（1.7.0，直通运转换线后为新线路，换线站本身即新线路）。

#### 结构化字段（1.9.0）

显示方不必再解析 `statusText`：

```java
for (EtaApi.BoardRow row : board.rows()) {
    String when = switch (row.phase()) {
        case AT_STATION -> "停车中";
        case ARRIVING -> row.passing() ? "即将通过" : "即将进站";
        default -> row.eta().map(t -> t.toString()).orElse("-");
    };
    String delay = row.delaySeconds().isPresent() && row.delaySeconds().getAsLong() >= 60
        ? "晚点 " + row.delaySeconds().getAsLong() / 60 + " 分" : "";
    System.out.println(row.lineName() + " " + (row.terminating() ? "本站终到" : row.destination())
        + " " + when + " " + delay);
}
```

| 字段 | 含义 |
|---|---|
| `etaEpochMillis` / `eta()` | 预计到达或通过本站；已在站为查询时刻 |
| `phase` | `FORECAST` 未出票预测、`PENDING` 已出票未发车、`EN_ROUTE` 运行中、`ARRIVING` 即将到达或通过、`AT_STATION` 停在本站 |
| `stopSequence` | 本站停靠序号（0 起下标，与 RouteApi、TimetableApi 同一口径） |
| `passing` / `terminating` / `outOfService` | 本站通过不停 / 本站是运营终点 / 列车在回库途中 |
| `trainName` | 运行中列车的列车名；票据与预测为空 |
| `delaySeconds` | 按表运行时相对计划的偏差（正数为晚点）：运行中为到达，已在站为发车，未发车为起点发车；不按表运行时为空 |
| `platformPending` / `platformCandidates` | 站台待定：本站是动态站台（DYNAMIC）停靠，列车还没有选台，时刻表也没有计划站台；此时 `platform` 为 `-`，候选为图上存在的股道号（升序，候选未知时为空）。只有一条候选时站台已经确定，不算待定 |
| `cars` / `vacantSeats()` | 运行中列车各节车的座位与在座乘客（`EtaApi.CarLoad`，车头在前）与全车空位数；票据、预测与读不到车辆模型时为空。座位数取车辆模型里的座位个数，在座取坐在这节车上的玩家 |
| `platformPlanned` | `platform` 是计划站台：时刻表排定的股道，或没有时刻表计划时列车在下一个停车站上先定的暂定股道。列车还没有选台，进站前选台时这条股道被占会改停别的站台，届时发 `TrainPlatformAssignedEvent`（原因 `CHANGED_FROM_PLAN`） |

**站台什么时候确定**：运行中的列车在到达本站的前一个节点（多为站咽喉）时选台，站台不够时更晚；停在站内的列车按它实际停的股道。按表运行的线路，编表时给动态站台排了计划股道（`/fta timetable build` 之后），选台前站牌就写计划站台（`platformPlanned`），选台时计划股道空闲就停它。
没有时刻表计划的车（间隔发车，或旧表），调度在列车驶向下一个停车站时先定一条暂定股道（同样标 `platformPlanned`），选台时空闲就兑现；
更远的站与未发车的票据仍是待定。显示方遇到待定行，应在全站各屏都列出（或按候选站台过滤），不要把它当成 1 站台。

**行为变更（1.9.0）**：停在本站的列车（`AT_STATION`）也会列出，此前列车一到站就从本站站牌上消失；时刻表预测不再包含已取消的车次，取消信息从 `TimetableApi.Departure#cancelled` 取；动态站台尚未选台时 `platform` 为 `-`，此前给的是 DYNAMIC 范围里的第一条股道（占位股道），列车未必去那里；按交路运行时，开往交路终点、到站后接着开下一趟的车（折返站）列的是下一趟（终点、计划发车、站台），不再是“本站终到”，`routeId` 也是下一趟的交路。

### 运行时快照（调试）

```java
api.eta().getRuntimeSnapshot("train-1").ifPresent(snap -> {
    System.out.println("Route: " + snap.routeId() + " idx=" + snap.routeIndex());
});
```

---

## TimetableApi - 时刻表（1.4.0，1.5.0 修订，1.8.0 车次取消）

只读，数据来自内存中已发布时刻表的快照，查询不访问数据库。返回的 `Instant` 已按时刻表自身时区与服务日换算好。

### 站点计划发车

```java
TimetableApi tt = api.timetables();
for (TimetableApi.Departure d :
    tt.departuresAt(operatorId, "HHU", Instant.now(), Duration.ofMinutes(15), 8)) {
    System.out.println(d.tripCode() + " " + d.plannedDeparture() + (d.terminating() ? " 终到" : ""));
}
```

只列在该站停车（`StopTime#stops()`：STOP 与 TERMINATE，停站 0 秒也算）的车次，通过站不列；多张已发布时刻表合并后按计划发车时刻排序；
窗口上限 24 小时。`terminating` 为 true 表示本站是该车次终点（TERMINATE 站，或其后只剩回库/折返等通过点）。

`Departure#lineId` 与 `operatorId` 过滤都是时刻表所属的线路与运营商，即交路组的管理归属。直通运转换线不改变它（换线只是通知列车改按另一条线运营）；
乘客在本站看到的线路用 `routeId` + `stopSequence` 对照 `RouteApi.StopInfo#lineChange`。

### 车次取消（1.8.0）

`Departure#cancelled` 为 true 表示这趟车在本站不再停，站牌照常列出、由调用方决定怎么显示（如「取消 / Cancelled」）。两种情况：

- **整趟没开出**：到点没有派出车（票过了发车容差作废，或服务器卡顿超过追补上限被跳过），各站都标为取消。
- **开出后车没了**：执行这趟车的列车半途离开运行时（卡死清理、互卡销毁等），从第一个还没发车的停车站起到车次终点都标为取消；
  已经发车的站不变。车到达终点站就是跑完了，之后被销毁、回收都不算。

取消同时发出 `TimetableTripCancelledEvent`（见“事件”一节），每趟车（按服务日区分）只发一次：

```java
@EventHandler
public void onCancelled(TimetableTripCancelledEvent event) {
    // 站牌行用 时刻表 ID + 车次号 + 服务日 对上；covers(n) 判断某一站是否不再停
    board.markCancelled(event.getTimetableId(), event.getTripCode(), event.getServiceDate(),
        event.getFirstCancelledStopSequence());
}
```

| 字段 | 口径 |
|------|------|
| `getScope()` | `FULL`：起点就没有发车；`PARTIAL`：开出过，从 `getFirstCancelledStopSequence()` 那一站起不再停 |
| `getReason()` | `NOT_DISPATCHED`：没派出车；`VEHICLE_REMOVED`：执行的列车离开了运行时——半途离开时本趟 `PARTIAL`，交路上后面替补赶不上的班次同时以 `FULL` 发出（`getTrainName()` 是离开的那列车） |
| `getFirstCancelledStopSequence()` | 与 `Departure#stopSequence` 同一口径；整趟取消时为首个停车站 |
| `getServiceDate()` | 与 `Departure#serviceDate` 同一口径（起点发车所在日期） |
| `getTrainName()` | 执行的列车；没派出车时为空 |

取消之后又有车接上这趟车（例如重启后留在线上的车在门控上绑了它）时，`departuresAt` 恢复正常显示，但**不另发事件**；
以 `departuresAt` 为准的站牌不受影响，只靠事件缓存状态的调用方需要定期用 `departuresAt` 校正。取消只存内存，重启后清空。
用 1.7.0 签名构造 `Departure` 的代码仍可编译运行：`cancelled` 为 false。

### 列车当前车次与偏差

```java
tt.getAssignment("SURC-WS-LC-1037").ifPresent(a -> {
    System.out.println("车次 " + a.tripCode() + " 交路 " + a.dutyCode().orElse("-"));
    a.currentDelaySeconds().ifPresent(d -> System.out.println("上一站晚点 " + d + " 秒"));
    // 预计晚点说的是哪一站：nextStop* 三个字段，站码最稳
    a.projectedDelaySeconds().ifPresent(d -> System.out.println(
        "预计到 " + a.nextStationCode().orElse("?") + " 晚点 " + d + " 秒"));
});
```

| 字段 | 口径 |
|------|------|
| `currentDelaySeconds` | 本车次最近一次**实际**到站或发车（同交路上一趟车的记录不算）与该站计划到达/发车相减，正数为晚点 |
| `lastStopSequence` / `lastStopNodeId` / `lastStationCode` | 上面那次到发的停靠序号、实际节点（DYNAMIC 为实际股道）、站码（1.5.0 增加后两项） |
| `projectedDelaySeconds` | 按 ETA **预计**到达 `nextStop*` 那一站与计划到达相减。列车在区间被扣停时随之增长，`currentDelaySeconds` 要到下一次到发才更新 |
| `nextStopSequence` / `nextStopNodeId` / `nextStationCode` | 下一个计划停车点（按交路 `passType`，与 RouteApi 停靠表、HUD 的“下一站”是同一站）。DYNAMIC 已选台时 `nextStopNodeId` 是实际股道，未选台时是占位股道 `OP:S:CODE:fromTrack`（与 RouteApi 一致）；站码不随选台变化 |
| `initialDeviationSeconds` | 绑定车次时的偏差 |

- DYNAMIC 停靠：已选台按实际股道估算；未选台按车站级估算（到该站在图上存在的任一候选股道，取最早），占位股道不可达也算得出来。
- 性能：`getAssignment` / `listAssignments` 按 tick 缓存，同一 tick 内对同一列车重复查询只算一次 ETA；可放心按帧轮询。
- 按表运行未启用（`enabled() == false`）时已发布时刻表仍可查询，但不会有车次绑定。

### 时刻表内容

`listPublished()` / `listByLine(lineId)` 返回概要，`getTimetable(id)` 返回交路时分（各站相对起点发车的到发偏移与停车方式 `passType`）、
按发车时刻排序的车次、车辆交路（出库→依次运行的车次→回库）。`StopTime#stationCode` 只有车站本体节点才有，区间点、咽喉、车库为空。
1.5.0 之前发布的时刻表没有记录停车方式，读出时按“首末站或停站大于 0 秒”回推，重新构建并发布后即按交路定义。

---

## 事件（1.4.0）

以下均为同步 Bukkit 事件，在事实发生后的**下一个 tick** 由主线程统一发出：调度路径里只入队，不直接调用外部代码，
监听器抛异常或耗时都不影响调度。事件只读、不可取消。某类事件没有监听器时连事件对象都不创建。

| 事件 | 时机 |
|------|------|
| `TrainArriveStationEvent` | 进度推进到本站（可能仍在制动/对标，停稳后才开门） |
| `TrainDepartStationEvent` | 拿到发车许可、松开门锁离站 |
| `TrainHoldEvent` | 被扣停（信号、占用、授权、尾保等，或停站/门控超时；正常停站、按表等点、折返待命、终点作业不算），含原因与阻塞者；与 ETA 顺延同一判定 |
| `TrainHoldReleasedEvent` | 扣停解除，含持续时长 |
| `TrainSignalChangeEvent` | 信号显示变化 |
| `TrainReleasedEvent` | 离开运行时管辖（销毁、回库、改派、异常清理） |
| `TrainHealthAlertEvent` | 健康监控告警（沿用告警总线的一分钟限流） |
| `TimetableTripAssignedEvent` | 绑定到时刻表车次 |
| `TimetableTripCancelledEvent` | 时刻表车次取消（1.8.0）：整趟没派出车，或执行的列车半途离开、剩下的站不再停；含范围、起始停靠序号与原因 |
| `StationGroupChangedEvent` | 车站组变化（1.6.0）：建组、成员增删改、删组；含变化后的数据版本 |
| `TrainPlatformAssignedEvent` | 列车在某一站的站台定下来或变了（1.9.0）：动态站台进站前选台、被挡改选，固定站台的车停到别的股道也算（原因 `CHANGED_FROM_PLAN`，计划站台为声明的站台）；含停靠序号、现在与上一次的站台、计划站台、原因（`ASSIGNED` / `CHANGED_FROM_PLAN` / `CHANGED`）。同值不发 |

扣停、信号、车次绑定三类没有现成的变化回调，每 tick 对比一次采样快照，同一 tick 内的来回变化会被合并。
同一次扣停期间换原因会再发一次 `TrainHoldEvent`（`getSince()` 不变），解除事件的时长覆盖整次扣停。每 tick 最多发出 256 个事件，
积压超过 4096 个时丢弃新事件。

```java
public class BoardListener implements Listener {
    @EventHandler
    public void onArrive(TrainArriveStationEvent event) {
        getLogger().info(event.getTrainName() + " 到达 " + event.getNodeId());
    }

    @EventHandler
    public void onHold(TrainHoldEvent event) {
        getLogger().info(event.getTrainName() + " 被扣停: " + event.getReasonCode());
    }
}
```

请监听具体事件类；抽象基类 `TrainStationEvent` 不能直接监听。

---

## 线程安全

**所有 API 返回的数据都是不可变快照**，可安全在任意线程使用。车站组与停靠线路查询（1.6.0）本身即读内存快照，可直接在任意线程高频调用：

```java
// 可在异步线程中处理
CompletableFuture.runAsync(() -> {
    api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
        // 处理数据...
    });
});
```

但建议在主线程调用 API 方法以获取最新数据，然后在异步线程处理：

```java
// 推荐模式
Bukkit.getScheduler().runTask(plugin, () -> {
    api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            processSnapshot(snapshot); // 异步处理
        });
    });
});
```

---

## 数据模型

### 枚举类型

```java
// 节点类型
GraphApi.NodeType: STATION, DEPOT, WAYPOINT, SWITCHER, UNKNOWN

// 信号
TrainApi.Signal / OccupancyApi.Signal: PROCEED, PROCEED_WITH_CAUTION, CAUTION, STOP, UNKNOWN

// 运营类型（1.6.0 起来自 pattern_type）
RouteApi.OperationType: NORMAL, RAPID, EXPRESS, LOCAL, OTHER

// 交路阶段（1.6.0，来自 operation_type）
RouteApi.RouteStage: CREATE, RETURN, OPERATION, UNKNOWN

// 换乘方式（1.6.0）
StationApi.TransferType: SAME_PLATFORM, IN_STATION, OUT_OF_STATION

// 停靠类型（行为）
RouteApi.PassType: STOP, PASS, TERMINATE

// 动态站台标识
RouteApi.StopInfo.dynamic(): true = 运行时根据占用选择站台

// 运营商 / 线路
OperatorApi.OperatorInfo
LineApi.ServiceType: METRO, REGIONAL, COMMUTER, LRT, EXPRESS, UNKNOWN
LineApi.LineStatus: PLANNING, ACTIVE, MAINTENANCE, UNKNOWN

// ETA
EtaApi.Confidence: HIGH, MED, LOW
EtaApi.Reason: NO_VEHICLE, NO_ROUTE, NO_TARGET, NO_PATH, THROAT, SINGLELINE, PLATFORM, DEPOT_GATE, WAIT,
               HOLD（被扣停，ETA 已按扣停时长顺延）, OVERDUE（班次已过计划发车仍未发出）
EtaApi.BoardPhase: FORECAST, PENDING, EN_ROUTE, ARRIVING, AT_STATION（1.9.0）

// 资源类型
OccupancyApi.ResourceType: NODE, EDGE, CONFLICT
```

### 位置

```java
GraphApi.Position(double x, double y, double z)

// 用于 BlueMap 等可视化
double x = position.x();
double y = position.y();
double z = position.z();
```

---

## 示例：BlueMap 桥接

以下是一个简化的 BlueMap 集成示例：

```java
public class BlueMapBridge extends JavaPlugin {

    private FetaruteApi api;

    @Override
    public void onEnable() {
        api = FetaruteApi.getInstance();
        if (api == null) {
            getLogger().severe("FetaruteTCAddon 未加载");
            return;
        }

        // 注册 BlueMap 标记
        BlueMapAPI.onEnable(blueMapApi -> {
            for (World world : getServer().getWorlds()) {
                UUID worldId = world.getUID();
                blueMapApi.getWorld(worldId).ifPresent(bmWorld -> {
                    bmWorld.getMaps().forEach(map -> {
                        addRailwayMarkers(map, worldId);
                    });
                });
            }
        });
    }

    private void addRailwayMarkers(BlueMapMap map, UUID worldId) {
        api.graph().getSnapshot(worldId).ifPresent(snapshot -> {
            MarkerSet markerSet = MarkerSet.builder()
                .label("铁路网络")
                .build();

            // 添加站点标记
            for (GraphApi.ApiNode node : snapshot.nodes()) {
                if (node.type() == GraphApi.NodeType.STATION) {
                    GraphApi.Position pos = node.position();
                    POIMarker marker = POIMarker.builder()
                        .label(node.displayName().orElse(node.id()))
                        .position(pos.x(), pos.y(), pos.z())
                        .build();
            markerSet.put(node.id(), marker);
                }
            }

            // 添加轨道线
            for (GraphApi.ApiEdge edge : snapshot.edges()) {
                // ... 绘制线条
            }

            map.getMarkerSets().put("railway", markerSet);
        });
    }
}
```

---

## 版本历史

| 版本 | 变更 |
|------|------|
| 1.9.0 | 站牌行结构化。**记录新增字段**：`EtaApi.BoardRow` 增加 `etaEpochMillis`（附 `eta()`）、`phase`（新枚举 `EtaApi.BoardPhase`）、`stopSequence`、`passing`、`terminating`、`outOfService`、`trainName`、`delaySeconds`、`platformPending`、`platformCandidates`；保留 1.8.0 的全参构造器作为次级构造器（时刻为 0、阶段为 `EN_ROUTE`、序号为 -1、`outOfService` 按主目的地 ID 是否为 `OUT_OF_SERVICE` 推断），按旧签名 `new` 的代码源码与二进制均兼容；使用记录模式解构的代码需补上新增分量；`Optional` 分量传 `null` 时规整为空。`EtaApi.BoardRow` 另增 `platformPlanned` 与 `cars`（新记录 `EtaApi.CarLoad`，附 `vacantSeats()`）；`TimetableApi.Departure` 增加 `plannedNodeId`（动态站台的计划股道），保留 1.8.0 与 1.7.0 的构造器。**新事件**：`TrainPlatformAssignedEvent`（站台定下来或变了）。**行为变更**：`getBoard` 列出停在本站的列车（`AT_STATION`）；时刻表预测排除已取消的车次；动态站台尚未选台时 `platform` 为计划站台或 `-`（此前为占位股道）；折返站上已知下一趟的来车按下一趟列出 |
| 1.8.0 | 车次取消：`TimetableApi.Departure` 增加 `cancelled`（保留 1.7.0 构造器，取消为 false），新增 `TimetableTripCancelledEvent` |
| 1.7.0 | 直通运转与回库。**记录新增字段**：`RouteApi.StopInfo` 增加 `lineChange`（新记录 `RouteApi.LineRef`：运营商代码 + 线路代码），`TrainApi.TrainSnapshot` 增加 `operatorCode`、`lineCode`（`Optional<String>`，对乘客显示的当前线路）与 `outOfService`；两者均保留 1.6.0 的全参构造器作为次级构造器（`lineChange` 与当前线路为空、`outOfService` 为 false），按旧签名 `new` 的代码源码与二进制均兼容；使用记录模式解构的代码需补上新增分量。两个记录的 `Optional` 分量传 `null` 时规整为空。**行为变更**（均为直通运转换线后面向乘客的口径修正，没有 CHANGE 的交路结果不变；交路、时刻表等管理归属不变）：`StationApi#linesServing`/`linesServingNode` 换线之后的车站算新线路、换线站两条都算（此前一律算交路本身的线路）；`EtaApi.BoardRow#lineName` 与 `getBoard` 的线路过滤按列车到该站时所属的线路。文档修正：`TrainSnapshot#routeCode` 实际与 `routeId` 同为 `运营商:线路:交路`（此前文档写成 `L1-R1`，实现未变） |
| 1.6.0 | 新增车站组与停靠线路：`StationApi#listStationGroups`/`findGroupOfStation`/`findGroupOfNode`/`linesServing`/`linesServingNode`，记录 `StationGroupInfo`、`StationGroupMember`、`ServingLine` 与枚举 `TransferType`；新增 `FetaruteApi#dataRevision()` 与 `StationGroupChangedEvent`。**记录新增字段**：`RouteApi.RouteInfo` 增加 `stage`（新枚举 `RouteStage`），`RouteApi.StopInfo` 增加 `stationId`、`stationCode`；两者均保留旧的全参构造器作为次级构造器（`stage` 取 `UNKNOWN`，`stationId`/`stationCode` 为空），按旧签名 `new` 的代码源码与二进制均兼容；对这两个记录使用记录模式（record pattern）解构的代码需补上新增分量。**行为变更**：`RouteInfo#id` 不再为 `null`；`RouteInfo#operationType` 按 `pattern_type` 映射（此前恒为 `NORMAL`）；`StopInfo#stationName` 与 `TerminalInfo` 的站名改为车站记录的真实站名（此前普通停靠点为空、DYNAMIC 停靠为站码），查不到记录时退回站码；DYNAMIC 车库停靠点的 `stationName` 改为空（此前为车库代码），与普通车库节点一致 |
| 1.5.0 | **行为变更**：停靠序号统一为交路节点的 0 起下标——`RouteApi.StopInfo#sequence` 由 1 起改为 0 起，与 `TimetableApi` 的 `stopSequence`、车站到发事件的 `getStopIndex()` 同一口径（此前三者可能差 1）；`TimetableApi.TrainAssignment` 增加 `lastStopNodeId`/`lastStationCode`/`nextStopNodeId`/`nextStationCode`；`TimetableApi.StopTime` 增加 `passType` 与 `stops()`，停车判定改按交路 `passType`（此前按“停站 > 0 秒”猜，停站 0 秒的 STOP 站被当成通过、预计晚点落到后面的站）；`StopTime#stationCode` 只给车站本体节点；DYNAMIC 停靠的预计晚点按实际股道（未选台按车站级）估算；`getAssignment`/`listAssignments` 按 tick 缓存；ETA 中途站未配停站按运行时默认 20 秒计（此前按 0 秒）、TERMINATE 站计入停站、中途停车按起停拆段、到站后停站计时注册前的空档计入本站停站 |
| 1.4.0 | 新增 `TimetableApi` 与 `api.event` 事件；`EtaApi.Reason` 增加 `HOLD`、`OVERDUE`，ETA 随扣停与票据超时顺延；`TerminalInfo` 的 EOP 与 HUD/站牌同一口径，没有载客车站时为空（不再回退为 EOR）；EOR 仍为交路最后一个节点，车库时名称为 `LWN Depot`、折返线时为它之前最近的车站；
`EtaApi.BoardRow.destination`（站牌主目的地）改取运营终点，回库车越过运营终点后为“回库”；`EtaApi.Reason.WAIT` 收窄为可预知的等待（按表等点、票据未到点），占用/信号等待改报 `HOLD`；修正版本常量（此前代码停留在 1.2.0，`isCompatible(..., "1.3.0")` 会误判为不兼容） |
| 1.3.0 | RouteApi: StopInfo 增加 `dynamic` 字段；RouteDetail 增加 `TerminalInfo`（EOR/EOP）；移除 `PassType.DYNAMIC` |
| 1.2.0 | 新增 OperatorApi / LineApi / EtaApi |
| 1.1.0 | 新增 StationApi：站点信息查询；新增 API 单元测试 |
| 1.0.0 | 初始版本：GraphApi, TrainApi, RouteApi, OccupancyApi |

---

## 注意事项

1. **不要缓存 API 实例**：每次使用前通过 `FetaruteApi.getInstance()` 获取
2. **处理 null/empty**：API 可能返回空 Optional 或空集合
3. **版本检查**：使用 `isCompatible()` 确保兼容性
4. **internal 包禁用**：不要直接使用 `api.internal` 包中的类

## 问题反馈

如有 API 问题或功能建议，请在 GitHub Issues 中提交。
