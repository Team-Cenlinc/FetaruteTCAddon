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

FetaruteApi 提供九个子模块，另有一组 Bukkit 事件（见“事件”一节）：

| 模块 | 方法 | 功能 |
|------|------|------|
| `graph()` | `GraphApi` | 调度图：节点、边、路径查询 |
| `trains()` | `TrainApi` | 列车状态：位置、速度、ETA |
| `routes()` | `RouteApi` | 路线定义：站点、停靠表 |
| `occupancy()` | `OccupancyApi` | 占用状态：信号、队列 |
| `stations()` | `StationApi` | 站点信息：位置、名称、关联节点 |
| `operators()` | `OperatorApi` | 运营商信息：名称、颜色、优先级 |
| `lines()` | `LineApi` | 线路信息：服务类型、颜色、状态 |
| `eta()` | `EtaApi` | ETA：列车/票据/站牌列表 |
| `timetables()` | `TimetableApi` | 时刻表：已发布时刻表、车次、站点计划到发、列车当前车次与偏差（1.4.0） |

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
    System.out.println("路线: " + route.code());
    System.out.println("  显示名: " + route.displayName());
    System.out.println("  类型: " + route.operationType()); // NORMAL, EXPRESS, etc.
}
```

### 获取路线详情

```java
api.routes().getRoute(routeUuid).ifPresent(detail -> {
    System.out.println("途经点: " + detail.waypoints());

    // 停靠表
    for (RouteApi.StopInfo stop : detail.stops()) {
        System.out.println(stop.sequence() + ". " + stop.nodeId());
        stop.stationName().ifPresent(name ->
            System.out.println("   站名: " + name));
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

### 运行时快照（调试）

```java
api.eta().getRuntimeSnapshot("train-1").ifPresent(snap -> {
    System.out.println("Route: " + snap.routeId() + " idx=" + snap.routeIndex());
});
```

---

## TimetableApi - 时刻表（1.4.0）

只读，数据来自内存中已发布时刻表的快照，查询不访问数据库。返回的 `Instant` 已按时刻表自身时区与服务日换算好。

### 站点计划发车

```java
TimetableApi tt = api.timetables();
for (TimetableApi.Departure d :
    tt.departuresAt(operatorId, "HHU", Instant.now(), Duration.ofMinutes(15), 8)) {
    System.out.println(d.tripCode() + " " + d.plannedDeparture() + (d.terminating() ? " 终到" : ""));
}
```

只列在该站停车的车次（通过站不列），多张已发布时刻表合并后按计划发车时刻排序；窗口上限 24 小时。

### 列车当前车次与偏差

```java
tt.getAssignment("SURC-WS-LC-1037").ifPresent(a -> {
    System.out.println("车次 " + a.tripCode() + " 交路 " + a.dutyCode().orElse("-"));
    a.currentDelaySeconds().ifPresent(d -> System.out.println("晚点 " + d + " 秒"));
});
```

`currentDelaySeconds` 取本车次最近一次实际到站或发车（同交路上一趟车的记录不算），与该站计划到达/发车相减（正数为晚点）；
`projectedDelaySeconds` 按 ETA 预计到达下一个停车点、与计划到达相减——列车在区间被扣停时它会随之增长，
而 `currentDelaySeconds` 要到下一次到发才更新；`initialDeviationSeconds` 是绑定车次时的偏差。
按表运行未启用（`enabled() == false`）时已发布时刻表仍可查询，但不会有车次绑定。

### 时刻表内容

`listPublished()` / `listByLine(lineId)` 返回概要，`getTimetable(id)` 返回交路时分（各站相对起点发车的到发偏移）、
按发车时刻排序的车次、车辆交路（出库→依次运行的车次→回库）。

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

**所有 API 返回的数据都是不可变快照**，可安全在任意线程使用：

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

// 运营类型
RouteApi.OperationType: NORMAL, RAPID, EXPRESS, LOCAL, OTHER

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
