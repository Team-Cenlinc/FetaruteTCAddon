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
- 占用采用事件反射式：推进点会释放窗口外资源；列车卸载/移除事件会主动释放占用；信号 tick 仍会对“已不存在列车”的遗留占用做被动清理。
- TrainCarts 的 GroupCreate/GroupLink 会触发一次信号评估，用于覆盖 split/merge 后的状态重建；列车改名依赖信号 tick 清理旧缓存。
- 单线走廊冲突会进入 Gate Queue，信号 tick 会尊重排队顺序与方向锁。

## tags 与恢复
推进点依赖 TrainProperties tags：
- `FTA_ROUTE_ID`：线路 UUID（由 `/fta depot spawn` 写入）
- `FTA_OPERATOR_CODE`：运营商 code（由 `/fta depot spawn` 写入）
- `FTA_LINE_CODE`：线路 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_CODE`：班次 code（由 `/fta depot spawn` 写入）
- `FTA_ROUTE_INDEX`：已抵达节点索引（运行时写回）
- `FTA_ROUTE_UPDATED_AT`：可选，调度更新时间（毫秒）
- `FTA_TRAIN_NAME`：上次记录的列车名（用于改名迁移）

未写入 `FTA_ROUTE_INDEX` 时视为“未激活”，信号 tick 不会构建占用；首次触发推进点后才会写入并进入占用/控车流程。
`TERMINATE` 表示结束载客：若线路在 TERM 后仍有节点（例如回库段/DSTY），继续按线路推进；若已无后续节点，则等待调度分配新 ticket/线路。

线路定义查找顺序：
1) `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`
2) `FTA_ROUTE_ID`（兼容旧标签）

## 控车重算与 failover
运行时控车每隔 `runtime.dispatch-tick-interval-ticks` 重新评估占用与信号，并重新下发速度控制。

新增 failover 判定：
- 低速判定：速度低于 `runtime.failover-stall-speed-bps` 持续 `runtime.failover-stall-ticks` 时，会重下发 destination。
- 不可达判定：若当前节点到下一节点在调度图中不可达，则执行强制停车（`runtime.failover-unreachable-stop`）。

速度曲线：
- 若启用 `runtime.speed-curve-enabled`，将根据“剩余距离 + 制动能力”自动计算限速，提前减速而不是过点再减速。
- 当信号处于 CAUTION/STOP 时，会基于 lookahead 占用中的首个阻塞资源估算距离，提前下压速度。
- `runtime.speed-curve-type` 控制曲线形态（`physics/linear/quadratic/cubic`）。
- `runtime.speed-curve-factor` 用于调节曲线激进程度（>1 更激进，<1 更保守）。
- `runtime.speed-curve-early-brake-blocks` 用于提前开始减速的缓冲距离。
- `runtime.approach-depot-speed-bps` 用于进库前限速（站点限速仍由 `approach-speed-bps` 控制）。

重启后从数据库加载 RouteDefinition，再从 tags 恢复当前 index。
若运行时内存中缺少该列车的 RouteProgressEntry，将在首次信号 tick 基于 tags 自动初始化，避免“每 tick 反复发车动作”的异常。
插件启动后会延迟 1 tick 扫描现存列车并重建占用快照（基于 tags 初始化进度并重新评估信号）。

## 列车速度配置
通过 `/fta train config set|list` 写入列车配置：
- `FTA_TRAIN_TYPE`：车种（EMU/DMU/DIESEL_PUSH_PULL/ELECTRIC_LOCO）
- `FTA_TRAIN_ACCEL_BPS2`：加速度（blocks/second^2）
- `FTA_TRAIN_DECEL_BPS2`：减速度（blocks/second^2）

详见 `docs/dev/train-config.md`。

运行时会把速度换算成 blocks/tick：
- `bps -> bpt`：除以 20
- `bps2 -> bpt2`：除以 20^2

## 进站限速
- `runtime.approach-speed-bps` 控制进站限速上限。
- 当下一节点为站点（AutoStation）时，限速会取 `min(默认速度, approach)`，再与边限速取最小值。

## CAUTION 速度来源
- 优先使用“连通分量 caution 覆盖”（`rail_component_cautions`）。
- 未设置覆盖时回退为 `runtime.caution-speed-bps`。
- 详见 `docs/dev/graph-component-caution.md`。

## lookahead 占用
- `runtime.lookahead-edges` 控制每次申请占用的边数量。
- `runtime.min-clear-edges` 用于限制同向跟驰的最小空闲边数（与 lookahead 取最大值）。
- 值越大越保守，能降低咽喉/道岔前卡死风险。
- `runtime.switcher-zone-edges` 控制道岔联合锁闭范围（向前 N 段边）。
- 单线走廊冲突采用方向锁：同向可跟驰，对向需等待走廊清空。
- 道岔/单线冲突会进入 FIFO Gate Queue，避免抢占导致的顺序漂移。

## 已知限制
- 占用释放采用事件反射式：列车推进后释放窗口外资源；列车卸载/移除事件主动清理，占用快照仍可能在非正常断线时短暂残留。
- 目前默认用 speedLimit/launch 控车，未实现更精细的制动曲线。

## 后续预留
- 计划把 SignalAspectPolicy 与速度曲线联动，形成更接近 CBTC 的移动闭塞（远期）。
