# 运行时调度接入说明

## 目标
- 让占用/闭塞成为“真正会挡车、会放行”的运行时硬约束。
- 推进点（waypoint/autostation/depot/switcher）负责下发下一跳 destination。
- 信号变化实时控车（限速/停车/发车）。

## 运行时流程
1) 推进点触发：解析当前节点 → 构建 OccupancyRequest → canEnter
2) 允许进入：acquire → 写入下一跳 destination → 发车/限速
3) 不允许进入：更新信号为 CAUTION/STOP → 限速或停车

## 信号变化监测
- 定时任务每 N tick 运行（`runtime.dispatch-tick-interval-ticks`）。
- 对运行中列车重新评估 canEnter，信号变化时会触发发车/限速。
- 即便信号未变化，也会刷新限速（用于边限速变化或阻塞解除后的速度恢复）。
- 当列车仍在占用区间时，信号 tick 会续租占用，避免超时释放导致后车误判可进入。

## tags 与恢复
推进点依赖 TrainProperties tags：
- `FTA_ROUTE_ID`：线路 UUID（由 `/fta depot spawn` 写入）
- `FTA_OPERATOR_CODE`：运营商 code（由 `/fta depot spawn` 写入）
- `FTA_LINE_CODE`：线路 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_CODE`：班次 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_INDEX`：已抵达节点索引（运行时写回）
- `FTA_ROUTE_UPDATED_AT`：可选，调度更新时间（毫秒）

线路定义查找顺序：
1) `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`
2) `FTA_ROUTE_ID`（兼容旧标签）

重启后从数据库加载 RouteDefinition，再从 tags 恢复当前 index。

## 列车速度配置
通过 `/fta train config set|list` 写入列车配置：
- `FTA_TRAIN_TYPE`：车种（EMU/DMU/DIESEL_PUSH_PULL/ELECTRIC_LOCO）
- `FTA_TRAIN_CRUISE_BPS`：巡航速度（blocks/second）
- `FTA_TRAIN_CAUTION_BPS`：警示速度（blocks/second）
- `FTA_TRAIN_ACCEL_BPS2`：加速度（blocks/second^2）
- `FTA_TRAIN_DECEL_BPS2`：减速度（blocks/second^2）

运行时会把速度换算成 blocks/tick：
- `bps -> bpt`：除以 20
- `bps2 -> bpt2`：除以 20^2

## 进站限速
- `runtime.approach-speed-bps` 控制进站限速上限。
- 当下一节点为站点（AutoStation）时，限速会取 `min(cruise, approach)`。

## lookahead 占用
- `runtime.lookahead-edges` 控制每次申请占用的边数量。
- 值越大越保守，能降低咽喉/道岔前卡死风险。

## 已知限制
- 释放策略仍为超时释放（releaseAt + headway）；事件驱动释放待接入。
- 目前默认用 speedLimit/launch 控车，未实现更精细的制动曲线。
