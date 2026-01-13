# 调度占用与 Headway（闭塞）设计

## 目标
- 用“资源互斥 + headway 规则”抽象闭塞，支持边/节点/道岔冲突区等多粒度占用。
- 调度层只给出“最早进入时间 + 信号许可”，运行时负责具体动作。

## 核心概念
- Resource：占用对象（EDGE/NODE/CONFLICT）。
- Claim：占用记录，包含 `releaseAt` 与 `headway`。
- Request：列车申请占用的上下文（含 travelTime）。
- Decision：是否可进入、最早进入时间与信号许可。

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
- headway = 释放后仍需等待的时间。
- 可按 route/资源类型动态返回。
- 若 releaseAt + headway 未到，则判定阻塞。

## 释放策略
- MVP 使用“超时释放”：`releaseAt + headway` 到期后清理。
- 后续可接入事件驱动（到达 waypoint/站台/区间端点）做精确释放。

## Lookahead 占用
- 运行时可按“当前节点 + N 段边”申请占用，降低咽喉/道岔前的卡死。
- `OccupancyRequestBuilder` 负责从 `TrainRuntimeState + RouteDefinition + RailGraph` 构建请求。

## 观测与运维
- `/fta occupancy dump [limit]`：查看占用快照。
- `/fta occupancy release <train>`：按列车清理占用。
- `/fta occupancy release-resource <EDGE|NODE|CONFLICT> <key>`：按资源清理占用。
- `/fta occupancy cleanup`：清理超时占用。
- `/fta occupancy debug acquire edge "<from>" "<to>" [seconds]`：对单条边执行占用。
- `/fta occupancy debug can edge "<from>" "<to>" [seconds]`：对单条边执行判定（不占用）。
- `/fta occupancy debug acquire path "<from>" "<to>" [seconds]`：对最短路路径执行占用。
- `/fta occupancy debug can path "<from>" "<to>" [seconds]`：对最短路路径执行判定（不占用）。

### 调试参数说明
- `seconds` 表示 travelTime，用于计算 releaseAt；不等同 headway。

## 预留 API
- `OccupancyManager#getClaim/snapshotClaims` 提供只读查询。
- `updateReleaseAt/updateReleaseAtByTrain` 用于接入事件驱动的精确释放。
