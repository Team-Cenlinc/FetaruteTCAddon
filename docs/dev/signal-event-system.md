# 信号事件重评估系统

本文档描述 Occupancy 事实变化如何唤醒完整运行时授权计算。核心不变量是：事件只能请求重新计算，不能携带或创造可行车信号；任何非 `STOP` 的物理发布仍必须来自 fresh acquire、active Movement Authority token、已提交 destination 与最终发布门。

## 调用流

```text
OccupancyManager release / recorded queue-head eligibility change
  -> SignalEventBus 同步发布事实事件
  -> SignalEvaluator 只解析受影响逻辑列车名
  -> RuntimeSignalReevaluationScheduler 合并 wake-up
  -> 下一 Bukkit tick drain
  -> RuntimeDispatchService.reevaluateSignalByName
  -> 完整 handleSignalTick
  -> canonical Movement Plan
  -> Gate Queue canEnter + acquire
  -> pending token + destination commit + active token
  -> SignalPublicationGate
  -> RuntimeTrainController 物理控制
```

事件总线仍是同步的，但订阅者不会在 `OccupancyManager.acquire()` / `release()` 的调用栈内读取 TrainCarts 实体、修改 occupancy、签发 token 或控车。由下一 tick 调度器形成明确的异步边界，避免同步重入和锁内回调。

Depot 实体化与 Layover 复用成功后也遵循同一边界：`RuntimeDispatchService#requestSignalReevaluationForResources(...)` 只把同资源 claim/queue 中的其他列车交给调度器。新实体自身的 expected-identity 首次硬停/水合刷新是独立的物理收容步骤，不能被延后或借此让其他列车同步重入授权。

启动或重载时，事件桥和调度器不会立即启动。运行时先进入 `STOP_FIRST`，冻结全部受管列车并原子重建现场 `PHYSICAL_FOOTPRINT`；只有现场恢复完成、监督组件全部启动并进入 `READY` 后，事件 wake-up 才能触发正常授权计算。再次进入恢复事务时会先取消订阅并关闭调度器，已经排入 Bukkit 的 Runnable 执行时也不会控车。

先停期间的到站与推进点照样提交**到达事实**（`commitArrivalDuringStartupFreeze`）：到达是物理事实，先停只拦授权。命中交路站点时推进索引与最近经过节点
（含 DYNAMIC 实际股道），交路外的区间点只更新最近经过节点；不改占用、不发授权、不销毁、不进待命，比当前进度靠后的到达不回写。只给已确认的物理主人补记——
关授权门会清空已水合主人表，所以关门前先把它合并进 `freezeOwnerIdentities`，重建提交重新登记后清掉；异常隔离车、重复逻辑身份、已销毁车的滞后事件不补记。
补记时输出必留诊断 `SMART_STARTUP_FREEZE_ARRIVAL_COMMITTED`。以前整条事件直接丢掉，重建再按旧进度摆放逻辑占用：2026-09-28 实服 3291 在别的车断车触发重建的同一刻
到达 PTK:1，到站被丢，重建把它的占用摆回 SPB:1；空着的 SPB:1 被后车合法拿走，两车从此永久互卡 5 小时以上。

## 核心组件

### SignalEventBus

`SignalEventBus` 是同步、类型安全的事实事件总线。当前授权唤醒只依赖：

- `OccupancyReleasedEvent`：携带原 owner 与已释放资源；只有它可能让该资源的 Gate Queue 队首从等待变为可重新判定。
- `OccupancyQueueChangedEvent`：只有其 `eligibleTrainNames` 显式记录“新队首取得仲裁资格”时才会唤醒；普通队列维护的该列表为空。
- `DeadlockResolvedEvent`：只表示锁关系变化；若使用 `DeadlockResolver`，必须把 released train 交给同一完整重评估 requester。

`OccupancyAcquiredEvent` 仍可供死锁诊断或审计订阅，但不是完整授权的 wake-up：acquire 只会缩小可用资源。`OccupancyQueueChangedEvent` 不能由订阅者重读整条队列后猜测 wake-up；发布者必须先证明队首资格确实变化，再把精确名称放入事件。

总线会隔离普通运行时异常与依赖 ABI 引发的 `LinkageError`；诊断日志自身失败也不能中断后续订阅者。它本身不是调度线程，也不是 Movement Authority 来源。

### SignalEvaluator

`SignalEvaluator` 保留历史类名，当前职责已经收窄为“占用事实到 wake-up 的事件桥”：

- 订阅 released 事件，并通过 `RuntimeDispatchRequestProvider#trainsWaitingFor` 查询每个已释放资源上已登记的 Gate Queue 队首；未登记的前向候选由显式生命周期事件或健康恢复处理。
- 对 queue-changed 事件，只消费事件已证明的 `eligibleTrainNames`；它不重新查询队列，也不把一般 priority/TTL/心跳更新升级成完整授权。
- 永远排除 source。source 已在当前完整授权调用栈中完成本次决定；让它因自己的 release 或自身 refresh 再次排队会在静态 STOP 中制造反馈环。
- 忽略空名称；不构建 preview signal、不调用 `canEnter/acquire`、不发布 `SignalChangedEvent`。

```java
SignalEvaluator evaluator =
    new SignalEvaluator(
        eventBus,
        requestProvider,
        signalReevaluationScheduler::request,
        runtimeDispatchService::failClosedAfterSignalReevaluationFailure,
        debugLogger);
evaluator.start();
```

### RuntimeSignalReevaluationScheduler

调度器隐藏合并与防重入细节，外部只有：

```java
signalReevaluationScheduler.request(trainName);
```

行为约束：

- 使用 `TrainNameNormalizer` 合并大小写变体和 TrainCarts `~a/~b` 临时 split 后缀。
- 同一逻辑列车在一个批次内最多执行一次，保留该批最先收到的名称供 canonical/alias 解析。
- 当前事件栈退出后的下一 Bukkit tick 才执行完整重评估。
- drain 期间新产生的请求进入下一批，不递归执行。
- 单次 drain 只在小的主线程工作预算内开始重评估；未开始的 FIFO 候选完整保留到下一 tick。这是避免有限批次独占服务器的保护，不会抑制或掩盖重复事实事件。
- 下一 tick 排队、等待查询或单列车重评估发生 `RuntimeException` / `LinkageError` 时，都会先关闭全局授权门并请求 `STOP_FIRST` 恢复；日志故障也不能中断同批其他列车。
- `close()` 清除 pending 状态；已安排任务会在执行时看到关闭状态并直接退出。

### RuntimeDispatchRequestProvider

事件桥只使用其只读等待查询：

- 从 `OccupancyQueueSupport` 收集每个已释放资源的当前队首，而不是整条等待队列。
- 只读取已登记的 Gate Queue 条目；同步事件发布期间绝不扫描 route progress、遍历全图或展开最短路。尚未入队的前向候选由周期巡检进入同一完整授权入口，不能以占用事务内的全局路径搜索交换更低延迟。
- 事件桥不构建诊断请求；候选列车只在下一 tick 由 `RuntimeDispatchService` 的完整授权入口重读现场状态、取得资源并决定信号。

### RuntimeDispatchService

`reevaluateSignalByName()` 使用与 health/runtime 相同的 alias-aware 解析取得当前 `TrainProperties`/holder，再进入 `handleSignalTick(..., false)`。它不接受外部 aspect，因此 release、已证明的 queue-head eligibility 与 deadlock-resolved 三类 wake-up 最终都必须重新经过 startup gate、canonical plan、Gate Queue、fresh acquire、token 与发布门。

`STOP` 是唯一不要求 active Movement Authority 的可见信号。`CAUTION`、`PROCEED_WITH_CAUTION` 与 `PROCEED` 都具有正速度目标，统一要求 ACTIVE token；缺少 token 的 preview 或兼容事件不能驱动车辆。

## 与周期巡检的关系

- 事件 wake-up：正常资源释放或已证明的 queue-head eligibility change 后的下一 tick 读取最终现场状态，目标延迟约 1 tick。
- `RuntimeSignalMonitor`：每 tick 只运行轻量 heartbeat；默认每 `runtime.dispatch-tick-interval-ticks`（当前模板为 50 ticks）开始新的候选快照，未完成的快照按预算在下一 tick 续跑。首次观测、物理运动变化与运动中列车进入完整授权；稳定静止列车只做存活/ETA 采样，等待真实 release、生命周期事件或健康恢复。
- 两条路径进入同一个 `handleSignalTick`，不会维护两套授权或 signal 状态机。

不要通过降低周期间隔来替代事件 wake-up；也不要让事件桥直接发布更宽松 aspect。吞吐由及时完整重算改善，安全由单一授权入口保证。

## 测试重点

- 同一 tick 的重复 release 与 split alias 只产生一次完整重评估。
- occupancy 同步回调返回前，运行时重评估次数仍为零。
- drain 中再次 request 同列车时，调用深度保持一并在下一 tick 处理第二代请求。
- 单列车 ABI 异常不阻断同批其他列车。
- release 只重评估该资源的队首且永远排除 source；静态 recoverable STOP 本身不得自行反复排入下一 tick。
- acquire 与一般 queue refresh 不得触发完整授权。撤队、TTL 过期、release lock 失效以及既有队首降权/换向，只有真的让另一列车成为可仲裁队首时才在事件中写入该新队首；同一队首或无队首时不得 wake-up。
- `DeadlockResolvedEvent` 不产生 `STOP -> PROCEED` 事件，只请求完整重评估。
- 无 ACTIVE token 的 `CAUTION`、`PROCEED_WITH_CAUTION`、`PROCEED` 全部被最终发布门拒绝。
- 关闭或进入恢复事务后，pending Runnable 不再调用运行时。

## 调试

关键日志：

- `占用事件下一 tick 完整信号重评估组件已启动`
- `信号完整重评估排队失败`
- `信号完整重评估失败`
- `SMART_STARTUP_OCCUPANCY_RECONSTRUCTION state=STOP_FIRST|HYDRATING|READY`
- `SIGNAL_FINAL_AUTHORIZATION_REJECTED` / publication gate reason

资源释放后应在下一 tick 看到同一列车的新 PERIODIC/full-authority trace；不能再以 `SignalChangedEvent STOP -> PROCEED` 作为放行证据。

## 参见

- [运行时调度](runtime-dispatch.md)
- [Smart Dispatcher](smart-dispatcher.md)
- [冲突组规则](graph-conflict.md)
