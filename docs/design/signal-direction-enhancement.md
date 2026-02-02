# 信号与方向增强设计方案

## 概述

本文档记录信号系统与方向增强的设计方案，供后续实施参考。

---

## 1. 方向概念分层

在调度系统中，"方向"有三种不同含义，需严格区分：

| 层次 | 含义 | 用途 | 数据来源 |
|------|------|------|----------|
| **运营方向** | 上行/下行 | HUD/PIDS 显示 | Line/Route 元数据 |
| **行驶方向** | 列车在 edge 上的 forward/backward | Route 节点顺序决定 | `pathNodes[i] → pathNodes[i+1]` |
| **闭塞方向** | 共享资源此刻允许哪个方向进入 | 调度硬约束 | 占用状态 + 方向锁 |

**核心增强目标**：闭塞方向（第 3 层）。

---

## 2. 现有实现基础

### 2.1 已有的方向支持

```java
// OccupancyClaim 已有方向字段
public record OccupancyClaim(
    OccupancyResource resource,
    String trainName,
    Optional<RouteId> routeId,
    Instant acquiredAt,
    Duration headway,
    Optional<CorridorDirection> corridorDirection  // ← 已存在
) { }

// OccupancyRequest 已有方向 Map
public record OccupancyRequest(
    String trainName,
    Optional<RouteId> routeId,
    Instant now,
    List<OccupancyResource> resources,
    Map<String, CorridorDirection> corridorDirections,  // ← 已存在
    int priority
) { }

// CorridorDirection 枚举
public enum CorridorDirection {
    A_TO_B,
    B_TO_A,
    UNKNOWN;
}
```

### 2.2 已有的冲突组自动识别

`RailGraphConflictIndex` 已实现：
- **单线走廊**：度数=2 的连续链路自动压缩为走廊
- **闭环冲突**：无边界闭环自动归并
- **Switcher 冲突**：道岔附近区间自动附加冲突资源
- **Gate Queue**：占用层对 `CONFLICT:single` 和 `CONFLICT:switcher` 启用 FIFO 队列

```
冲突 Key 格式：
- CONFLICT:single:<componentKey>:<endA>~<endB>  （单线走廊）
- CONFLICT:single:<componentKey>:cycle:<minNode> （闭环）
- CONFLICT:switcher:<nodeId>                     （道岔）
```

### 2.3 走廊信息

```java
// RailGraphCorridorInfo 提供走廊端点与路径
public record RailGraphCorridorInfo(
    String conflictKey,
    NodeId endpointA,      // 走廊左端点
    NodeId endpointB,      // 走廊右端点
    List<NodeId> pathNodes, // 走廊内所有节点（有序）
    boolean isCycle
) { }
```

---

## 3. 增强方案

### Phase 2: Edge 方向从 Route 推导（完全自动）

**目标**：在 `OccupancyRequestBuilder` 构建请求时，自动为每个 edge 计算遍历方向。

**实现思路**：
```java
// OccupancyRequestBuilder 改动
for (int i = 0; i < edges.size(); i++) {
    RailEdge edge = edges.get(i);
    NodeId from = pathNodes.get(i);
    NodeId to = pathNodes.get(i + 1);

    // 计算方向：基于 edge.id() 的端点排序
    CorridorDirection direction = deriveDirection(edge.id(), from, to);
    corridorDirections.put(OccupancyResource.forEdge(edge.id()).key(), direction);
}

private CorridorDirection deriveDirection(EdgeId edgeId, NodeId from, NodeId to) {
    // EdgeId 已归一化为 a < b（字典序）
    // 如果 from == edgeId.a()，则方向为 A_TO_B
    // 如果 from == edgeId.b()，则方向为 B_TO_A
    if (from.equals(edgeId.a())) {
        return CorridorDirection.A_TO_B;
    } else if (from.equals(edgeId.b())) {
        return CorridorDirection.B_TO_A;
    }
    return CorridorDirection.UNKNOWN;
}
```

**关键点**：
- `EdgeId.undirected(a, b)` 已按字典序归一化
- 方向推导无需人工标记，完全从 route 节点顺序自动得出

---

### Phase 3: 冲突判定加入方向检查

**SimpleOccupancyManager 改动**：

```java
// 在 canEnter() 的冲突判定中
for (OccupancyClaim claim : existing) {
    if (claim.trainName().equalsIgnoreCase(request.trainName())) {
        continue; // 同一列车，跳过
    }

    // 获取请求方向与占用方向
    CorridorDirection requestDir = request.corridorDirections()
        .getOrDefault(resource.key(), CorridorDirection.UNKNOWN);
    CorridorDirection claimDir = claim.corridorDirection()
        .orElse(CorridorDirection.UNKNOWN);

    // 单线走廊特殊处理
    if (isSingleCorridorConflict(resource)) {
        if (isSameDirection(requestDir, claimDir)) {
            // 同向：允许跟驰（按 headway 控制间隔）
            continue;
        }
        // 反向：严格互斥
        blockers.add(claim);
        blockedReasons.put(resource, BlockedReason.OPPOSITE_DIRECTION);
        continue;
    }

    // 普通资源：直接冲突
    blockers.add(claim);
}
```

**新增 BlockedReason 枚举**：
```java
public enum BlockedReason {
    NONE,
    SAME_DIRECTION_HEADWAY,  // 同向但间隔不足
    OPPOSITE_DIRECTION,       // 反向冲突
    QUEUE_WAITING,            // 在冲突队列中等待
    CONFLICT_ZONE,            // 冲突区被占用
    UNKNOWN
}
```

---

### Phase 4: 信号显示增强

**OccupancyDecision 扩展**：
```java
public record OccupancyDecision(
    boolean allowed,
    Instant earliestTime,
    SignalAspect signal,
    List<OccupancyClaim> blockers,
    BlockedReason primaryReason,      // 新增：主要阻塞原因
    Optional<Integer> queuePosition   // 新增：队列位置（如果在排队）
) { }
```

**HUD 显示**：
```yaml
# 可选：显示阻塞原因
IDLE_1: <{signal_color_tag}>⬤</{signal_color_tag}> <white>{blocked_reason_zh}</white>
IDLE_2: <{signal_color_tag}>⬤</{signal_color_tag}> <white>{blocked_reason_en}</white>
```

**新增占位符**：
- `{blocked_reason}` - 阻塞原因文本
- `{blocked_reason_zh}` / `{blocked_reason_en}` - 本地化版本
- `{queue_position}` - 队列位置（如 "#2"）

---

## 4. 单线走廊方向状态机（可选进阶）

如果需要更严格的"方向令牌"控制（类似真实铁路的闭塞系统）：

```java
class CorridorDirectionState {
    CorridorDirection currentDirection = UNKNOWN; // 当前锁定方向
    Set<String> holders = new HashSet<>();        // 占用该方向的列车

    boolean canEnter(String trainName, CorridorDirection requestDir) {
        if (holders.isEmpty()) {
            // 无人占用：可以进入并锁定方向
            return true;
        }
        if (holders.contains(trainName)) {
            // 自己已在走廊内
            return true;
        }
        if (currentDirection == UNKNOWN || currentDirection == requestDir) {
            // 同向：允许跟驰
            return true;
        }
        // 反向：必须等待 holders 清空
        return false;
    }

    void acquire(String trainName, CorridorDirection direction) {
        if (holders.isEmpty()) {
            currentDirection = direction;
        }
        holders.add(trainName);
    }

    void release(String trainName) {
        holders.remove(trainName);
        if (holders.isEmpty()) {
            currentDirection = UNKNOWN; // 重置方向
        }
    }
}
```

**触发时机**：
- `acquire`：列车进入走廊（GROUP_ENTER 第一个走廊节点）
- `release`：列车离开走廊（GROUP_LEAVE 最后一个走廊节点）

---

## 5. 诊断命令扩展

```bash
# 查看占用详情（含方向）
/fta occupancy list
# 输出示例：
# EDGE:StationA~StationB  train=EXP-01  dir=A_TO_B  acquired=12s ago
# CONFLICT:single:...:StationA~StationC  train=EXP-02  dir=B_TO_A  queue=#1

# 查看走廊状态
/fta graph corridor <edgeA> <edgeB>
# 输出示例：
# 走廊: single:world1:StationA~StationC
# 端点: StationA ↔ StationC
# 当前方向: A→C (locked)
# 占用列车: EXP-01, EXP-02
# 等待队列: LOCAL-03 (C→A)

# 查看冲突组
/fta graph conflict path <from> <to>
```

---

## 6. 实施路线图

| 阶段 | 内容 | 自动化程度 | 优先级 |
|------|------|-----------|--------|
| **Phase 2** | Edge 方向从 route 推导 | ✅ 完全自动 | 高 |
| **Phase 3** | 冲突判定加入方向检查 | ✅ 完全自动 | 高 |
| **Phase 4** | 信号显示增强 | ✅ 完全自动 | 中 |
| **Phase 5** | 走廊方向状态机（可选） | ✅ 完全自动 | 低 |

**无需人工标记**：所有方向信息从 route 节点顺序 + 图结构自动推导。

---

## 7. 相关文件

- `OccupancyRequestBuilder.java` - 构建占用请求
- `SimpleOccupancyManager.java` - 占用判定与队列
- `RailGraphConflictIndex.java` - 冲突组自动识别
- `RailGraphCorridorInfo.java` - 走廊信息
- `CorridorDirection.java` - 方向枚举
- `SignalAspect.java` - 信号等级
- `TrainHudContextResolver.java` - HUD 占位符
