# 调度系统设计文档

本文档描述 FetaruteTCAddon 的调度系统核心设计，包括 TERM（终点站）处理、Layover（待命复用）机制以及列车生命周期管理。

## 概述

调度系统负责：
1. **Route 推进**：列车依次访问 Route 定义中的节点序列
2. **TERM 处理**：列车到达终点站后的停车与状态转换
3. **Layover 复用**：待命列车的注册、匹配与复用发车
4. **回收管理**：闲置列车的回库与销毁

## 列车生命周期状态

详见 `TrainLifecycleState` 枚举的 JavaDoc，完整状态机图如下：

```
[Depot Spawn]
     │
     ▼
┌─────────────┐    到达 TERM 节点     ┌─────────────────┐
│ IN_SERVICE  │ ─────────────────────▶│   TERMINATING   │
└─────────────┘                       └─────────────────┘
     ▲                                       │
     │                                       │ 完成停站流程
发车成功                                      ▼
     │                                ┌─────────────────┐
┌─────────────┐    门已关/位置合法     │     LAYOVER     │
│ DISPATCHING │ ◀────────────────────│ (等待关门完成)    │
└─────────────┘                       └─────────────────┘
     ▲                                       │
     │                                       │ 关门完成
分配新票据                                    ▼
     │                                ┌─────────────────┐
     └────────────────────────────────│  LAYOVER_READY  │
                                      │ (可接受调度)      │
                                      └─────────────────┘
                                             │
                                 ┌───────────┴───────────┐
                     分配 RETURN 票据│                     │超时/超限回收
                                 ▼                       ▼
                          ┌─────────────┐         ┌─────────────┐
                          │ DEADHEADING │         │  DESTROYED  │
                          │ (回库运行)   │────────▶│ (已销毁)     │
                          └─────────────┘  到达   └─────────────┘
                                          DSTY
```

## TERM 处理流程

### 1. 推进点触发 (`handleProgressTrigger`)

当列车进入节点时，推进点牌子（waypoint/autostation/depot）会触发 `RuntimeDispatchService.handleProgressTrigger()`：

```java
// 检查是否为 TERMINATE 节点且启用复用模式
if (stop.passType() == RouteStopPassType.TERMINATE
    && route.lifecycleMode() == RouteLifecycleMode.REUSE_AT_TERM) {
  // 释放占用、注册 Layover、返回
  occupancyManager.releaseByTrain(trainName);
  handleLayoverRegistrationIfNeeded(trainName, route, currentNode, properties);
  return;
}
```

### 2. 信号周期检查 (`handleSignalTick`)

周期性检查列车状态，确保即使推进点缺失也能正确停车：

```java
// 终点停车（currentIndex >= last）
if (currentIndex >= route.waypoints().size() - 1) {
  properties.setSpeedLimit(0.0);
  train.stop();
  // 补注册 Layover
  if (route.lifecycleMode() == RouteLifecycleMode.REUSE_AT_TERM) {
    handleLayoverRegistrationIfNeeded(trainName, route, terminalNode, properties);
  }
  return;
}
```

### 3. DSTY 销毁判定

DSTY（销毁目标）优先于终点停车：

```java
// DSTY 检查必须在终点停车之前
if (shouldDestroyAt(stop, currentNode)) {
  handleDestroy(train, properties, trainName, "DSTY");
  return;
}
```

## Layover 机制

### terminalKey 匹配规则

使用 `TerminalKeyResolver` 进行统一的 terminalKey 生成与匹配：

| 场景 | layoverKey | targetKey | 结果 |
|------|-----------|-----------|------|
| 精确匹配 | `op:s:central:1` | `op:s:central:1` | ✅ 匹配 |
| 同站不同站台 | `op:s:central:1` | `op:s:central:2` | ✅ 匹配 |
| 不同站点 | `op:s:central:1` | `op:s:downtown:1` | ❌ 不匹配 |
| 同车库不同股道 | `op:d:depot1:1` | `op:d:depot1:2` | ✅ 匹配 |
| Station vs Depot | `op:s:central:1` | `op:d:central:1` | ❌ 不匹配 |

### NodeId 格式

```
Station:    Operator:S:StationName:Track
Depot:      Operator:D:DepotName:Track
Waypoint:   Operator:From:To:Track:Seq
Throat:     Operator:S/D:Name:Track:Seq
```

### 注册流程

```java
// handleLayoverRegistrationIfNeeded
String terminalKey = TerminalKeyResolver.toTerminalKey(location);
layoverRegistry.register(trainName, terminalKey, location, Instant.now(), tags);
```

### 复用发车流程

```java
// dispatchLayover
String startTerminalKey = TerminalKeyResolver.toTerminalKey(startNode);
String routeFirstTerminalKey = TerminalKeyResolver.toTerminalKey(routeFirstNode);

if (!TerminalKeyResolver.matches(startTerminalKey, routeFirstTerminalKey)) {
  return false; // 位置与首站不匹配
}

// 同站不同站台：从索引 0 开始
int startIndex = RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.empty(), startNode);
if (startIndex < 0) {
  startIndex = 0; // fallback
}
```

## RouteLifecycleMode

| 模式 | 说明 | TERM 后行为 |
|------|------|-----------|
| `REUSE_AT_TERM` | 终到后进入待命复用 | 注册 Layover，等待下一张票据 |
| `DESTROY_AFTER_TERM` | 终到后销毁 | 继续执行 DSTY 路径并销毁 |

模式推导规则（`RouteDefinition.resolveMode`）：
- 若 TERM 后存在 DSTY 指令 → `DESTROY_AFTER_TERM`
- 否则 → `REUSE_AT_TERM`

## 回收管理 (ReclaimManager)

定期检查 `LayoverRegistry`，回收闲置列车：

1. **闲置超时**：`idle > maxIdleSeconds` 触发回收
2. **车辆超限**：`activeTrains > maxTrains` 触发回收

回收方式：分配 RETURN 类型票据，使列车驶向 DSTY 销毁。

## 关键类

| 类 | 职责 |
|----|------|
| `RuntimeDispatchService` | 运行时调度控制核心 |
| `LayoverRegistry` | Layover 候选列车池 |
| `TerminalKeyResolver` | terminalKey 生成与匹配 |
| `RouteProgressRegistry` | 列车进度追踪 |
| `SimpleTicketAssigner` | 票据分配与发车 |
| `ReclaimManager` | 闲置列车回收 |
| `TrainLaunchManager` | 控车执行器 |

## 调试建议

### 常见问题排查

1. **Layover 列车无法被复用**
   - 检查 terminalKey 匹配：`/fta layover list` 查看候选列车
   - 确认 Route 首站与 Layover 位置的 Station code 一致

2. **列车穿站**
   - 检查推进点牌子是否正确放置
   - 查看 `handleSignalTick` 的终点停车逻辑是否触发

3. **列车卡在 Layover**
   - 检查是否有匹配的 OPERATION/RETURN Route
   - 查看 `SpawnManager` 是否生成了票据

### Debug 日志

启用 `debug.enabled=true` 后，关键日志：
- `Layover 注册: train=... terminalKey=... station=...`
- `Layover 发车成功: train=... route=...`
- `Layover 发车失败: ...` （含具体原因）

## ETA 动态速度模型

从 v0.x.x 起，ETA 计算使用动态速度模型，综合考虑边限速与列车加减速参数。

### 核心组件

| 类 | 职责 |
|----|------|
| `DynamicTravelTimeModel` | 基于边限速与运动学公式计算旅行时间 |
| `SpawnTrainConfigResolver` | 从 CRET depot 推断未发车列车的加减速配置 |
| `TrainRuntimeSnapshot` | 运行时快照，包含速度/距离字段 |

### 计算逻辑

1. **边限速**：优先使用 `RailEdge.baseSpeedLimit`，无配置时使用 fallback 速度（6 bps）
2. **加减速**：
   - 运行中列车：从 tags 读取 `FTA_TRAIN_ACCEL_BPS2` / `FTA_TRAIN_DECEL_BPS2`
   - 未发车列车：从 Route CRET depot 的 spawn pattern 推断 TrainType，再获取配置模板
3. **运动学公式**：`computeTravelTimeWithSpeeds(distance, v0, vMax, vEnd)` 支持：
   - 纯匀速：v0 = vMax = vEnd
   - 加速+匀速+减速：标准梯形速度曲线
   - 三角形曲线：距离不足以完成完整加减速

### 配置推断优先级（未发车）

1. Route metadata 中的 `spawn_train_pattern` → 推断 TrainType
2. CRET depot 牌子第四行的 spawn pattern → 推断 TrainType
3. Fallback 到 `config.yml` 的 `train-config.default-type`

### 扩展点

- `TrainRuntimeSnapshot` 已预留 `currentSpeedBps` / `distanceToNextBlocks` 字段
- 后续可通过 RailPath position 计算精确边内进度，进一步提升 ETA 精度
