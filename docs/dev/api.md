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

FetaruteApi 提供八个子模块：

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
    }

    // 终点站
    detail.terminalName().ifPresent(terminal ->
        System.out.println("开往: " + terminal));
});
```

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

// 停靠类型
RouteApi.PassType: STOP, PASS, TERMINATE, DYNAMIC

// 运营商 / 线路
OperatorApi.OperatorInfo
LineApi.ServiceType: METRO, REGIONAL, COMMUTER, LRT, EXPRESS, UNKNOWN
LineApi.LineStatus: PLANNING, ACTIVE, MAINTENANCE, UNKNOWN

// ETA
EtaApi.Confidence: HIGH, MED, LOW
EtaApi.Reason: NO_VEHICLE, NO_ROUTE, NO_TARGET, NO_PATH, THROAT, SINGLELINE, PLATFORM, DEPOT_GATE, WAIT

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
