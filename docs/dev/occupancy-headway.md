# 调度占用与 Headway（闭塞）设计

## 目标
- 用“资源互斥 + headway 规则”抽象闭塞，支持边/节点/道岔冲突区等多粒度占用。
- 调度层只给出“最早进入时间 + 信号许可”，运行时负责具体动作。

## 核心概念
- Resource：占用对象（EDGE/NODE/CONFLICT）。
- Claim：占用记录，仅描述当前占用者与 headway 配置（无 releaseAt）。
- Request：列车申请占用的上下文（不再包含 travelTime，包含单线走廊方向信息）。
- Decision：是否可进入、信号许可与阻塞信息（earliest 仅作提示）。
- Queue：冲突资源的 FIFO 排队快照，用于运维诊断与顺序控制。

## 信号许可（SignalAspect）
- `PROCEED`：可进入。
- `PROCEED_WITH_CAUTION`：前方两段区间内存在 stop，用于提前减速提示。
- `CAUTION`：下一个区间会遇到 stop，准备停车。
- `STOP`：禁止进入或无法定位阻塞位置（例如仅 CONFLICT 阻塞）。

## 最小可用版本资源解析规则
- edge 必占用自身资源：`EDGE:<from~to>`。
- edge 若连接 `SWITCHER` 节点，额外占用冲突资源：`CONFLICT:switcher:<nodeId>`。
- edge 若处于单线走廊，会关联冲突资源：`CONFLICT:single:<component>:<endA>~<endB>`（对向互斥，同向可跟驰）。
- node 占用使用 `NODE:<nodeId>`（switcher 同样补冲突资源）。

## Headway 规则
- headway 用于刻画“线路安全冗余”，当前以配置形式保留。
- 事件驱动释放后立即重新判定，不再依赖 releaseAt 计算。

## 释放策略
- 当前采用事件驱动：列车推进/离开资源时立即释放。
- 列车卸载/移除事件会主动释放该列车的所有占用。
- 不再依赖超时清理。

## Lookahead 占用
- 运行时可按“当前节点 + N 段边”申请占用，降低咽喉/道岔前的卡死。
- 同向跟驰最小空闲边数由 `runtime.min-clear-edges` 控制（与 lookahead 取最大值）。
- `runtime.rear-guard-edges` 会保留当前节点向后 N 段边，确保长列车尾部在完全离开前不被后车侵入。
- 默认会同时占用路径上的 NODE 资源（当前节点 + lookahead 节点），用于阻止前车未离开时后车进入同一节点。
- `OccupancyRequestBuilder` 负责从 `TrainRuntimeState + RouteDefinition + RailGraph` 构建请求。

## 运行时接入
- 推进点（waypoint/autostation/depot/switcher）会构建占用请求并下发下一跳 destination。
- 运行中通过定时 tick 重新评估 signal，信号降级时限速或停车。
- 相关说明见 `docs/dev/runtime-dispatch.md`。

## 事件驱动信号系统
- 占用变化时 `SimpleOccupancyManager` 会发布 `OccupancyAcquiredEvent` / `OccupancyReleasedEvent`。
- `SignalEvaluator` 订阅这些事件，即时重新评估受影响列车的信号状态。
- 信号变化时发布 `SignalChangedEvent`，由 `TrainController` 订阅并立即下发控车指令。
- 此机制大幅降低信号响应延迟（从周期 tick 改为事件触发，延迟 < 1 tick）。
- 详见 `docs/dev/signal-event-system.md`。

## Gate Queue（排队控制）
- `CONFLICT:switcher:*` 与 `CONFLICT:single:*` 使用优先级队列控制放行顺序。
- **优先级 (Priority)**：
  - 高优先级列车优先放行（例如客运 > 回收）。
  - 优先级相同时，按“首见时间”先到先得（FIFO）。
  - 当前默认优先级：客运=0，回收（Reclaim）=-10。
- 走廊为空时会比较两侧队列头部的优先级与首见时间，优先放行更优的一侧。
- 同向跟驰仍可并行进入走廊，但进入顺序受队列约束。
- 冲突区放行：当两侧列车互相占用节点而卡死时，会基于 lookahead 的 entryOrder 优先放行更接近冲突入口的一侧。
- entryOrder 来自占用请求的“首次进入冲突区的边序号”，可避免折返段场景误放行离入口更远的列车。
- 冲突区放行锁：放行后会锁定同一列车一段时间，避免信号乒乓；锁定期间仍会校验阻塞列车在同一冲突队列内。
- entryOrder 在队列内取“更小者优先”并保持稳定，避免路径抖动导致队头来回翻转。
- 出站门控会查询单线/道岔冲突队列的更高优先级列车，必要时让行并保持停站等待（仅站台/TERM）。
- 队列条目若超过 30 秒未刷新会自动清理（避免遗留阻塞）。
- `/fta occupancy queue` 通过 `OccupancyQueueSupport` 输出队列快照（含方向、优先级与首见时间）。
- 队列条目包含 `priority` 与 `entryOrder`，用于诊断冲突区放行与“更近者优先”的排序。

## 观测与运维
- `/fta occupancy dump [limit]`：查看占用快照。
- `/fta occupancy queue [limit]`：查看排队快照。
- `/fta occupancy release <train>`：按列车清理占用。
- `/fta occupancy release-resource <EDGE|NODE|CONFLICT> <key>`：按资源清理占用。
- `/fta occupancy stats`：查看自愈/出车重试等运行统计。
- `/fta occupancy heal`：手动触发“孤儿占用/进度/待命”自愈清理。
- `/fta occupancy debug acquire edge "<from>" "<to>"`：对单条边执行占用。
- `/fta occupancy debug can edge "<from>" "<to>"`：对单条边执行判定（不占用）。
- `/fta occupancy debug acquire path "<from>" "<to>"`：对最短路路径执行占用。
- `/fta occupancy debug can path "<from>" "<to>"`：对最短路路径执行判定（不占用）。

## 预留 API
- `OccupancyManager#getClaim/snapshotClaims` 提供只读查询。
