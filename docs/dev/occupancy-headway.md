# 调度占用与 Headway（闭塞）设计

## 目标
- 用“资源互斥 + headway 规则”抽象闭塞，支持边/节点/道岔冲突区等多粒度占用。
- 调度层只给出“最早进入时间 + 信号许可”，运行时负责具体动作。

## 核心概念
- Resource：占用对象（EDGE/NODE/CONFLICT）。
- Claim：占用记录，仅描述当前占用者与 headway 配置（无 releaseAt）。
- Request：列车申请占用的上下文（不再包含 travelTime）。
- Decision：是否可进入、信号许可与阻塞信息（earliest 仅作提示）。

## 信号许可（SignalAspect）
- `PROCEED`：可进入。
- `PROCEED_WITH_CAUTION`：短暂等待后可进入。
- `CAUTION`：需等待，建议限速/提示。
- `STOP`：禁止进入。

## MVP 资源解析规则
- edge 必占用自身资源：`EDGE:<from~to>`。
- edge 若连接 `SWITCHER` 节点，额外占用冲突资源：`CONFLICT:switcher:<nodeId>`。
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
- 默认会同时占用路径上的 NODE 资源（当前节点 + lookahead 节点），用于阻止前车未离开时后车进入同一节点。
- `OccupancyRequestBuilder` 负责从 `TrainRuntimeState + RouteDefinition + RailGraph` 构建请求。

## 运行时接入
- 推进点（waypoint/autostation/depot/switcher）会构建占用请求并下发下一跳 destination。
- 运行中通过定时 tick 重新评估 signal，信号降级时限速或停车。
- 相关说明见 `docs/dev/runtime-dispatch.md`。

## 观测与运维
- `/fta occupancy dump [limit]`：查看占用快照。
- `/fta occupancy release <train>`：按列车清理占用。
- `/fta occupancy release-resource <EDGE|NODE|CONFLICT> <key>`：按资源清理占用。
- `/fta occupancy debug acquire edge "<from>" "<to>"`：对单条边执行占用。
- `/fta occupancy debug can edge "<from>" "<to>"`：对单条边执行判定（不占用）。
- `/fta occupancy debug acquire path "<from>" "<to>"`：对最短路路径执行占用。
- `/fta occupancy debug can path "<from>" "<to>"`：对最短路路径执行判定（不占用）。

## 预留 API
- `OccupancyManager#getClaim/snapshotClaims` 提供只读查询。
