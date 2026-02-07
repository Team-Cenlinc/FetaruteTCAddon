# 运行时调度接入说明

## 目标
- 让占用/闭塞成为“真正会挡车、会放行”的运行时硬约束。
- 推进点（waypoint/autostation/depot/switcher）负责下发下一跳 destination。
- 信号变化实时控车（限速/停车/发车）。

## 运行时流程
1) 推进点触发：解析当前节点 → 构建 OccupancyRequest → canEnter
2) 允许进入：acquire → 写入下一跳 destination → 发车/限速
3) 不允许进入：基于 lookahead 阻塞位置细分信号（PROCEED_WITH_CAUTION/CAUTION/STOP）→ 限速或停车
4) 出站门控（站台/TERM）会额外检查优先级让行：若单线/道岔冲突队列存在更高优先级列车，则保持停站等待。

## Waypoint 停站
- waypoint 节点在 RouteStop 标记为 STOP/TERMINATE 时也会执行停站（PASS 则直接通过）。
- 停站时长优先使用 `dwell=<秒>`，缺失时回退为 20 秒默认值。
- 停站仅在 `GROUP_ENTER` 触发（忽略 `MEMBER_ENTER`），避免过早点刹导致居中不稳。
- 停站期间会保持 STOP 信号，且提前写入下一跳 destination，确保发车时直接走寻路方向。
- 停站期间保留“当前节点 + 尾部保护边（`runtime.rear-guard-edges`）”的占用，避免后车过早释放后互卡。
- 对 STOP/TERM waypoint 的进站控车采用 handoff：信号 tick 不强制 STOP，而是把目标速度上限压到 `runtime.approach-speed-bps`（approaching）。
- 仅当存在前方 blocker（红灯/占用阻塞）时，才使用“到 blocker 的距离”触发进一步减速/停车；不使用到下一节点距离，避免提前刹停在牌子前。
- 停稳判定：连续 `1` tick 未移动即视为停稳；若超过 `400` ticks 未停稳则进入超时兜底。
- 仅在停稳后才会执行居中、启动 dwell，并通过 `addActionWaitState()` 真正 hold 住列车。

## 发车方向
- 发车方向以 TrainCarts 的寻路结果为准（根据当前 destination 计算下一跳 junction）。
- 调度层不再写入 `FTA_LAUNCH_DIR` 等方向 tag，避免两套方向逻辑相互覆盖。

## 信号变化监测
- 定时任务每 N tick 运行（`runtime.dispatch-tick-interval-ticks`）。
- 对运行中列车重新评估 canEnter，信号变化时会触发发车/限速。
- 即便信号未变化，也会刷新限速（用于边限速变化或阻塞解除后的速度恢复）。
- 发车/加速动作会做节流（`runtime.launch-cooldown-ticks`），避免动作队列膨胀。
- 占用采用事件反射式：推进点会释放窗口外资源；列车卸载/移除事件会主动释放占用；信号 tick 仍会对“已不存在列车”的遗留占用做被动清理。
- TrainCarts 的 GroupCreate/GroupLink 会触发一次信号评估，用于覆盖 split/merge 后的状态重建；列车改名依赖信号 tick 清理旧缓存。
- 单线走廊冲突会进入 Gate Queue，信号 tick 会尊重排队顺序与方向锁。
- 中间 waypoint（未写入 route）触发会更新 `lastPassedGraphNode`，信号/占用评估会尽量贴合列车真实位置。
- 当触发“冲突区放行锁”时，信号 tick 会跳过前车扫描/前瞻限速，保证放行列车在锁定期持续获得可发车信号。
- 可用 `/fta occupancy stats` 观察自愈与出车重试统计，`/fta occupancy heal` 可手动触发清理。

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
`TERMINATE` 表示结束载客：若线路在 TERM 后仍有节点（例如回库段/DSTY），继续按线路推进；仅当 TERM 位于线路尾节点时，才进入 Layover 等待调度分配新 ticket/线路。

`RouteProgressRegistry` 对列车名采用不区分大小写的键；即使 TrainCarts 发生大小写改名，也能命中同一进度记录，避免出现“信号/占用看似丢失后被误清理”的问题。

线路定义查找顺序：
1) `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`
2) `FTA_ROUTE_ID`（兼容旧标签）

## 手动调试（debug set route）
在不重生列车的情况下，可用命令手动写入 route tags 并同步下一跳 destination：

```
/fta train debug set route <company> <operator> <line> <route> [index|nodeId]
/fta train debug set route <company> <operator> <line> <route> train <train|@train[...]> [index|nodeId]
```

说明：
- `index` 为“已抵达节点索引”（对应 `FTA_ROUTE_INDEX`）。
 - 若提供的是 `nodeId`（非数字），会在该 route 的 `waypoints` 中查找其索引并写入；`nodeId` 必须使用双引号包裹（例如 `"OP:S:PTK:1"`）。
- 若需要显式指定目标列车，可在 route 参数后追加 `train <train|@train[...]>`（注意该关键字必须在可选 `index|nodeId` 之前出现）。
- 命令会通过存储解析 company/operator/line/route 层级，并同步写入 `FTA_ROUTE_ID`（route UUID）与 code 三元组。
- 命令会清理运行时缓存/占用并尝试 `refreshSignal`（若能找到在线列车实例），便于立刻观察控车与闭塞行为。

## 待命与回收 (Layover & Reclaim)
- 列车抵达 `TERM` 站点且线路生命周期为 `REUSE_AT_TERM` 时，只有“当前索引已到线路尾节点”才会进入待命（Layover）状态并注册到 `LayoverRegistry`。
- 若 `TERM` 后仍有定义节点（回库/折返段），运行时会继续推进，不会在 TERM 站台永久拦停。
- Layover 注册时会触发一次即时复用尝试（不必等待下一轮 spawn tick）。
- `ReclaimManager` 定期检查待命列车：
  - 若闲置超时或服务器车辆超限，会为其分配 `RETURN` 票据。
  - `RETURN` 票据优先级较低（-10），礼让正常客运列车。
- 详见 `docs/dev/reclaim-policy.md`。

## 发车门控阻塞策略
- 出站门控在 `shouldYield/blocked` 时会把占用收缩为“停站保护窗口”（当前节点 + rear guard），避免列车在红灯等待期间长时间持有前方资源导致同向互卡。
- 阻塞日志会输出 blocker 摘要（资源类型/键/持有列车），便于现场定位卡点。

## 控车重算与 failover
运行时控车每隔 `runtime.dispatch-tick-interval-ticks` 重新评估占用与信号，并重新下发速度控制。

新增 failover 判定：
- 低速判定：速度低于 `runtime.failover-stall-speed-bps` 持续 `runtime.failover-stall-ticks` 时，会重下发 destination。
- 不可达判定：若当前节点到下一节点在调度图中不可达，则执行强制停车（`runtime.failover-unreachable-stop`）。

移动授权（Movement Authority）：
- 启用 `runtime.movement-authority-enabled` 后，运行时会用“当前制动距离 + 安全余量”与前方可用距离做实时比对。
- 当授权不足时会把信号降级为更保守等级，并下压目标速度，防止冒进进入未清空区段。
- 安全余量参数：
  - `runtime.movement-authority-stop-margin-blocks`
  - `runtime.movement-authority-caution-margin-blocks`

速度曲线：
- 若启用 `runtime.speed-curve-enabled`，将根据“剩余距离 + 制动能力”自动计算限速，提前减速而不是过点再减速。
- 当信号处于 PROCEED_WITH_CAUTION/CAUTION/STOP 时，会基于 lookahead 占用中的首个阻塞资源估算距离，提前下压速度。
- 当“下一站”为 STOP/TERM waypoint 时，信号 tick 的距离只看前方 blocker（不看下一节点距离/CAUTION 距离），避免提前刹停在牌子前。
- `runtime.speed-curve-type` 控制曲线形态（`physics/linear/quadratic/cubic`）。
- `runtime.speed-curve-factor` 用于调节曲线激进程度（>1 更激进，<1 更保守）。
- `runtime.speed-curve-early-brake-blocks` 用于提前开始减速的缓冲距离。
- `runtime.approach-depot-speed-bps` 用于进库前限速（站点限速仍由 `approach-speed-bps` 控制）。
- 最短路距离会通过缓存复用，并按 `runtime.distance-cache-refresh-seconds` 异步刷新，降低高密度咽喉区的重复计算开销。

重启后从数据库加载 RouteDefinition，再从 tags 恢复当前 index。
若运行时内存中缺少该列车的 RouteProgressEntry，将在首次信号 tick 基于 tags 自动初始化，避免“每 tick 反复发车动作”的异常。
插件启动后会延迟 1 tick 扫描现存列车并重建占用快照（基于 tags 初始化进度并重新评估信号）。

## 健康监控（高频服务）
- 健康检查支持分级修复与冷却控制：
  - `STALL`：`refreshSignal -> forceRelaunch`
  - `PROGRESS_STUCK`：`refreshSignal -> reissueDestination -> forceRelaunch`
- 健康检查由独立定时任务驱动（每秒 tick + `health.check-interval-seconds` 间隔门控），不再依赖信号监控任务触发。
- `STOP` 信号下的 progress stuck 允许更长宽限（`health.progress-stop-grace-seconds`），避免将正常排队误判为异常。
- 连续修复动作之间受 `health.recovery-cooldown-seconds` 限制，降低高频场景下的抖动与过度修复。
- 详见 `docs/dev/health-monitor.md`。

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
- `runtime.approach-speed-bps` 控制 approaching 速度上限（进站 + STOP/TERM waypoint handoff）。
- 当下一节点为站点（AutoStation）时，限速会取 `min(默认速度, approach)`，再与边限速取最小值。

## CAUTION 速度来源
- 优先使用“连通分量 caution 覆盖”（`rail_component_cautions`）。
- 未设置覆盖时回退为 `runtime.caution-speed-bps`。
- 详见 `docs/dev/graph-component-caution.md`。

## lookahead 占用
- `runtime.lookahead-edges` 控制每次申请占用的边数量。
- `runtime.min-clear-edges` 用于限制同向跟驰的最小空闲边数（与 lookahead 取最大值）。
- `runtime.rear-guard-edges` 用于保留当前节点向后 N 段边，保护长编组尾部避免追尾。
- 值越大越保守，能降低咽喉/道岔前卡死风险。
- `runtime.switcher-zone-edges` 控制道岔联合锁闭范围（向前 N 段边）。
- 单线走廊冲突采用方向锁：同向可跟驰，对向需等待走廊清空。
- 道岔/单线冲突会进入 FIFO Gate Queue，避免抢占导致的顺序漂移。

## 已知限制
- 占用释放采用事件反射式：列车推进后释放窗口外资源；列车卸载/移除事件主动清理，占用快照仍可能在非正常断线时短暂残留。
- 目前默认用 speedLimit/launch 控车，未实现更精细的制动曲线。

## 速度命令限幅
- 速度命令会做“限幅 + 迟滞”处理，减少高频抖动引发的解挂风险。
- 参数：
  - `runtime.speed-command-hysteresis-bps`
  - `runtime.speed-command-accel-factor`
  - `runtime.speed-command-decel-factor`

## Waypoint STOP/TERM 停站
- Waypoint STOP/TERM 触发时仅写入 stopState，运行时信号 tick 负责软刹减速。
- 列车停稳后才执行 centerTrain 并开始 dwell/WaitState，避免“触发即硬停”的观感。

## 后续预留
- 计划把 SignalAspectPolicy 与速度曲线联动，形成更接近 CBTC 的移动闭塞（远期）。
