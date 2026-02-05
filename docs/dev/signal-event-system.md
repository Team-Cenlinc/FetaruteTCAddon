# 信号事件驱动系统

本文档描述 FetaruteTCAddon 的信号事件驱动架构，该架构将占用变化与信号响应解耦，实现即时信号更新。

## 概述

传统周期性 tick 模式下，信号状态每隔固定时间（如 5 tick）才会刷新，导致列车在资源释放后仍需等待下一个 tick 才能响应。事件驱动模式通过发布-订阅机制，在资源状态变化时立即触发信号重评估，大幅降低响应延迟。

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     OccupancyManager                            │
│  acquire() / release()                                          │
│        │                                                        │
│        ▼                                                        │
│  publish(OccupancyAcquiredEvent / OccupancyReleasedEvent)       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      SignalEventBus                             │
│  同步发布-订阅机制，类型安全                                       │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│     SignalEvaluator      │    │     DeadlockResolver     │
│  订阅占用变化事件          │    │  管理死锁释放锁           │
│  重新评估受影响列车        │    │  8秒TTL                  │
│  发布 SignalChangedEvent │    │                          │
└──────────────────────────┘    └──────────────────────────┘
              │
              ▼
┌──────────────────────────┐
│     TrainController      │
│  订阅 SignalChangedEvent │
│  即时下发控车指令          │
│  调用 RuntimeDispatch    │
└──────────────────────────┘
```

## 核心组件

### SignalEventBus

信号事件总线，提供同步的发布-订阅机制。

```java
SignalEventBus bus = new SignalEventBus(debugLogger);

// 订阅特定事件类型
Subscription sub = bus.subscribe(SignalChangedEvent.class, event -> {
    // 处理信号变化
});

// 发布事件
bus.publish(new SignalChangedEvent(now, trainName, oldSignal, newSignal));

// 取消订阅
sub.unsubscribe();
```

特性：
- 类型安全的事件订阅
- 同步执行（避免并发问题）
- 订阅者异常隔离（一个订阅者异常不影响其他订阅者）
- 支持全局订阅（接收所有事件）

### SignalEvaluator

信号评估器，订阅占用变化事件，在资源状态变化时重新评估受影响列车的信号。

```java
SignalEvaluator evaluator = new SignalEvaluator(
    eventBus,
    occupancyManager,
    requestProvider,
    debugLogger
);
evaluator.start();
```

职责：
- 订阅 `OccupancyAcquiredEvent` 和 `OccupancyReleasedEvent`
- 根据事件中的受影响列车列表，重新评估信号状态
- 若信号变化，发布 `SignalChangedEvent`
- 维护列车信号缓存，检测变化

### TrainController

控车订阅器，接收信号变化事件并立即下发控车指令。

```java
TrainController controller = new TrainController(
    eventBus,
    (trainName, signal) -> {
        // 实际控车逻辑
        runtimeDispatchService.applySignalFromEvent(trainName, signal);
    },
    debugLogger
);
controller.start();
```

### RuntimeDispatchRequestProvider

为 `SignalEvaluator` 提供构建占用请求的能力，实现 `TrainRequestProvider` 接口。

职责：
- 根据列车名获取 TrainCarts 属性与线路定义
- 从 `RouteProgressRegistry` 读取推进点状态
- 使用 `OccupancyRequestBuilder` 构建占用请求
- 通过 `OccupancyQueueSupport` 查询等待特定资源的列车

### DeadlockResolver

死锁解决器，管理冲突区放行锁。

```java
DeadlockResolver resolver = new DeadlockResolver(eventBus, debugLogger);

// 尝试授予锁
if (resolver.grantLock(trainName, conflictKey, now)) {
    // 放行该列车
}

// 检查是否持有锁
boolean holds = resolver.holdsLock(trainName, conflictKey);

// 清理过期锁
resolver.purgeExpiredLocks(now);
```

特性：
- 锁 TTL 8秒，避免长期阻塞
- 支持按列车批量释放锁

## 事件类型

### OccupancyAcquiredEvent

资源被占用时发布。

```java
record OccupancyAcquiredEvent(
    Instant timestamp,
    String trainName,
    List<OccupancyResource> acquiredResources,
    List<String> affectedTrains  // 被新占用影响的列车
) implements SignalEvent
```

### OccupancyReleasedEvent

资源被释放时发布。

```java
record OccupancyReleasedEvent(
    Instant timestamp,
    String trainName,
    List<OccupancyResource> releasedResources
) implements SignalEvent
```

### SignalChangedEvent

列车信号变化时发布。

```java
record SignalChangedEvent(
    Instant timestamp,
    String trainName,
    SignalAspect previousSignal,  // 可能为 null（首次评估）
    SignalAspect newSignal
) implements SignalEvent
```

## 初始化流程

在 `FetaruteTCAddon.onEnable()` 中：

1. `initOccupancyManager()` 创建 `SignalEventBus` 并注入 `SimpleOccupancyManager`
2. `initRuntimeDispatch()` 初始化运行时调度服务
3. `initSignalEventDrivenComponents()` 创建并启动：
   - `RuntimeDispatchRequestProvider`
   - `SignalEvaluator`
   - `TrainController`

## 与周期性 tick 的关系

事件驱动系统作为主要信号响应机制，`RuntimeSignalMonitor` 的周期性 tick 保留作为兜底：
- 事件驱动：资源变化时立即触发，延迟 < 1 tick
- 周期性 tick：每 N tick 执行一次，处理遗漏或边缘情况

建议：生产环境可适当降低周期性 tick 频率（如从 5 tick 改为 20 tick），减少 CPU 开销。

## 调试

启用 `debug.enabled=true` 后，事件发布与处理会输出详细日志：

```
[DEBUG] SignalEvent: type=OccupancyReleasedEvent at 2026-02-05T17:00:00Z
[DEBUG] 信号变化(事件): train=SURC-MT-L北-0261 STOP -> PROCEED
[DEBUG] 控车下发: train=SURC-MT-L北-0261 signal=PROCEED unblocked=true
[DEBUG] 事件信号下发: train=SURC-MT-L北-0261 signal=PROCEED
```

## 参见

- [占用与间隔控制](occupancy-headway.md)
- [运行时调度](runtime-dispatch.md)
- [冲突组规则](graph-conflict.md)
