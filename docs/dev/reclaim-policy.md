# 车辆回收策略 (Reclaim Policy)

## 背景
为实现“有限实体列车的循环复用”，列车在抵达终点站（TERM）后会进入待命（Layover）状态。但随着运营时间推移，如果待命列车过多或长期闲置，会占用服务器资源。
回收策略旨在自动清理这些闲置列车，将其调度回车库（Depot）或销毁点（DSTY）。

## 核心逻辑
由 `ReclaimManager` 定期扫描 `LayoverRegistry`：
1. **闲置超时**：列车待命超过 `reclaim.max-idle-seconds`。
2. **总量超限**：Minecraft 世界中已加载且有效的 TrainCarts group 数超过 `reclaim.max-active-trains`，优先回收待命最久的列车；离线持久化的 `TrainProperties` 不计入活跃车。
3. **生命周期到期**：若列车标签 `FTA_OP_TRIPS >= FTA_OP_MAX`，即使未超时也优先回收。

以上任一条件成立后，还要过**回库闸**（`ReclaimManager#setReturnGate`）：按表运行时装的是 `TimetableService#allowsReturn`，
与表定回库票同一个判据——交路还有班次要跑的车不收（日志 `回收跳过: 交路还有班次要跑`），也不计入下面的滞留计时；
交路已经断了（剩下的班次都过了 `timetable.assign-tolerance-seconds`，也没有一张票还在等它）的车照常回收。
没有这道闸时，闲置超时、总量超限、方向供给过剩都能把正等着下一班的车送回车库，那一班就开了天窗。

**等自己交路的带客回库班**（`ReclaimManager#setOwnReturnWait`，装的是 `TimetableService#awaitsOwnReturnAt`）：交路跑完了的车按表还有一班带客回库。
车停在这一班的起点站、它还没开也没过发车容差时，回收不收（日志 `回收跳过: 等本交路的带客回库班`，状态变了才再记），让回库班的票把车带走；
这样的车也不算本方向的闲置供给，不会把同方向别的车推成“过剩”收走。车停在别的站（票接不到它）或回库班过了容差还没开成时不等，照常回收——
等待只认时刻，不认“票还在等”，必有尽头。
终点站常有开往不同车库的几条 RETURN 线路（例如 PPK 的 MT-1O_ShortD 去绿洲农场、MT-2O_ShortD 去壑湖），回收原先挑第一条从本站出发的，
抢在回库班前面把车带走时车会开去别的车库，回库班的票则一直空等（`折返票据等待过久`），站牌照交路写的终点也成了错的。

回收派票时**先试列车所绑交路的回库线路**（`ReclaimManager#setPreferredReturnRoute`，装的是 `TimetableService#returnRouteOf`），试不成再按原顺序试其它线路。
派车成功后通知时刻表结清交路（`ReclaimManager#setReclaimListener` → `TimetableService#reclaimed`）：沿交路自己的回库线路走的，算跑了带客回库班
（绑上那一班，`TIMETABLE_RECLAIM_AS_RETURN_LEG`）；走别的线路的，车离开交路（交路还有班次时转空缺交给替补，跑完的那一趟照旧算跑完）。
两种情况交路的回库票都不再等它，按时刻到期撤下，不再在站牌上挂着。

**正线折返点立即回收**：待命位置是正线区间路径点（`RouteTerminals#isMainlineTurnback`：`WaypointKind.INTERVAL`，例如 MT-1O_ShortR 终到的 `OFL:MLU:2:004`；
车站、咽喉、车库、道岔都不算；编表的往返对锚定用的是同一个判定）的车停在正线上，会挡同一股道的后车，不能等闲置上限。闲置满
`ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS`（15 秒，实际约为下一次扫描）且过了**正线回送闸**
（`ReclaimManager#setMainlineReturnGate`，参数是列车名与它刚跑完的 `FTA_ROUTE_ID`）就立即回收，日志 `回收触发: 正线折返点无后续班次`：
有从这个路径点出发的 RETURN 交路（人工定义，首站写这个节点 id）就照它开走；只有查过全部 RETURN 交路、**确实没有**一条从这里出发时才**原地销毁**
（`RECLAIM_MAINLINE_DESTROY`，失败记 `RECLAIM_MAINLINE_DESTROY_FAILED`）。有这样的交路只是这一拍没派出去（闭塞、被拒、交接进行中）、
存储不可用、或运营商回溯不到时都不销毁，照常进下面的滞留计时。已不在待命池、有乘客、或有进行中的折返事务时不销毁
（`RECLAIM_MAINLINE_SKIP`，与滞留销毁同一组闸，按待命池的当前记录判，不用扫描开头的快照）；
`reclaim.stranded-destroy-seconds: 0`（回收不销毁车）时也不销毁、只记滞留。
**单股道车站同一条规则**（2026-09-30）：待命位置是只有一股道的车站（`RouteTerminals#isSingleTrackStation`：图里同一运营商、同一站码的车站节点只有它自己，
如 CHT 只有 `SURC:S:CHT:3`；与编表的站台组容量同一口径；`ReclaimManager#setSingleTrackStation`）时，车占着全站唯一的股道，后车只能在站外等——
车进去没多久就得出来，不能在里面等后面的车次。触发条件、闸与处置和正线折返点完全相同，日志 `回收触发: 单股道车站无后续班次`。
背景：实服 WS 一辆车晚点到 CHT，下一班 2C 已过容差作废，交路里剩下的 2N 从 NTA 发车、它根本赶不过去；回库闸因"交路还有班次"一直不放，
它在唯一的股道上等到 2N 也过期（约 20 分钟），后车全部等待放行、严重晚点 20 分钟。按表 WS 在 CHT 的每次停留都是 24 秒，问题只在运行时。

**没有回库线路的车站同一条规则**（2026-10-02）：待命位置所在终点没有任何 RETURN 交路出发（查遍全部运营商，首站匹配与下文"RETURN 搜索范围"相同；
结果按终点缓存 `ReclaimManager.RETURN_ROUTE_RECHECK_SECONDS` = 300 秒，避免每轮扫描都在主线程扫库），典型是原地折返的车站（NTA）。
车在这样的站上只能接本交路的下一班，接不上就再也走不了，等闲置上限只会占着站台；两股道被两辆这样的车占满，在这里折返的线路全都进不来。
只看**绑着交路**的车（`ReclaimManager#setDutyBound`，按表运行时装 `TimetableService#dutyBindingOf`）：没绑交路的车（例如重启后账本丢了）还可能接一张从这里始发的首班票，照旧等闲置上限。
触发条件与闸同正线折返点（闲置满 15 秒、过正线回送闸），日志 `回收触发: 无回库线路的车站无后续班次`，处置是原地销毁（`RECLAIM_NO_ROUTE_DESTROY`，
跳过记 `RECLAIM_NO_ROUTE_SKIP`，失败记 `RECLAIM_NO_ROUTE_DESTROY_FAILED`；乘客、交接、`stranded-destroy-seconds: 0` 三道闸相同）。
背景：零点冷启动时两辆晚点约 4 分钟的 MT-3 到 NTA 时下一班已作废，先后困在 NTA 两股道上各 30 分钟（当时只有滞留兜底），
SURC 没有越行线，WS-2 与 MT-3 全线停摆。续班票现在等本交路的车（见 `timetable.md`），这条规则是它之外的兜底。

**交路已换车的车**（2026-09-30）：严重晚点、被从交路上换下来的车（见 `timetable.md` 交路换车）再也没有班可跑，
闲置满 `MAINLINE_TURNBACK_MIN_IDLE_SECONDS` 就回收（`ReclaimManager#setRetiredVehicle`，按表运行时装 `TimetableService#retiredFromDuty`），
日志 `回收触发: 交路已换车`，不占着站台等闲置上限。它没有交路，回库闸本来就放行。

**按表没有后续任务的车**：时刻表确知这辆待命车再也没有班可跑（`ReclaimManager#setIdleForGood`，按表运行时装 `TimetableService#idleForGoodAt`，
参数是列车名、停的节点与它刚跑完的 `FTA_ROUTE_ID`），闲置满 15 秒就回收，日志 `回收触发: 按表没有后续任务`。只在按表出票（`spawn-enabled: true`）时判，查不清一律当作还有活：

- 被换下来的车；
- 绑着交路：还没跑的班次里没有一班从这里（站台组）发车、又还赶得上（没过发车容差，或它的票还在等这辆车）；交路跑完了，也没有从这里出发、没过容差的带客回库班；
- 没绑交路、刚跑完的是由时刻表出票的交路：10 分钟之内（含过了计划还没过容差的）没有一个交路的首班从这里发车——没绑交路的车只能接首班。

叫来的车（`FTA_CALL`）不在这里判。它不过回库闸与"等回库班"（时刻表已经放行），没有回库线路时原地销毁（`RECLAIM_NO_ROUTE_DESTROY`），
回库线路只是这一拍派不出去时进滞留计时。**`reclaim.enabled: false` 时也照做**（只做这一项）：时刻表确知它没有活，留着只会占住站台。

这道闸代替上面的回库闸，按表运行时装的是 `TimetableService#allowsReturnFromMainlineTurnback`，只管**由时刻表出票**的交路：

- 自由运行的交路、只扣车不出票（`timetable.spawn-enabled: false`）、不知道刚跑完哪条交路（缺 `FTA_ROUTE_ID`）、未启用按表运行：
  不放行，照旧走上面三条规则——接哪一班由间隔出票决定，没有交路上的对应关系可判；
- 本交路的**下一班**已过了 `timetable.assign-tolerance-seconds`、它的票也不在了放行（`TIMETABLE_DUTY_NEXT_TRIP_MISSED`；续班票等本交路的车时不到期，见 `timetable.md`）：
  再下一次回到这个折返点发车要等一整个往返，停在正线上等那么久会一直挡着后车；站台上的车则照旧等到末班也作废；
- 下一班还接得上时落到 `allowsReturn`：没绑交路（例如重启后账本丢了）、交路已跑完、剩下的班次全都作废时放行，否则不放行（`TIMETABLE_RETURN_DENIED`）。

正线回送闸不放行（下一班还接得上）时，闲置超时等规则触发的回收仍要过回库闸，不会因为停在正线上就绕过去。

为什么是销毁而不是自动找车库开回去：这条规则只在网络已经晚点时触发（正常情况下编表把往返对锚在正线端，车到了约 20 秒就接下一班），
而正线折返点往往没有顺向的近车库——实服 `OFL:MLU:2:004` 除折返本身外不再倒车能到的车库是 D:HHU（约 2129 格）与 D:LWN（约 2408 格），
都要沿干线跑一整趟表外车；D:OFL 就在旁边，却要再倒一次车。车库发车按需生成，销毁与回库在车辆资源上等价。

## 回收动作
- **生成 RETURN 票据**：为待回收列车分配一张 `RETURN` 类型的 `ServiceTicket`。
- **低优先级调度**：回收票据的优先级设为 `-10`（普通客运为 `0`，VIP/Depot发车可能更高），确保回收列车不会抢占正常客运列车的线路资源（Fairness）。
- **强制发车**：通过 `TicketAssigner.forceAssign` 绕过常规发车计划，直接尝试申请占用。
- **标签复位**：RETURN 或 CREATE 再次发车后，`FTA_OP_TRIPS` 会重置为 `0`，`FTA_OP_MAX` 继续沿用 route/group 的配置结果，供下一轮运营判断。
- **RETURN 搜索范围**：先在本运营商的线路里找，找不到再扩到全部运营商；候选 RETURN 的首站按站点 code、裸节点 id
  与 DYNAMIC 规范三种写法匹配。直通车停在外方终点时，靠这一步才找得到能带它回去的线路。
  站点 code 只和终点所在车站/车库的名字段比（`SURC:S:PPK:1` 的 `PPK`）：区间路径点 `PPK:RVS:1:001` 里的起讫站段不表示车停在 PPK。
- **没有回库线路就当场处理**：时刻表已经放行的立即回收（正线折返点、单股道车站、无回库线路的车站）、交路已换车的车或按表没有后续任务的车，
  查过全部 RETURN 交路、确实没有一条从这个终点出发时，不等滞留计时，原地销毁（正线折返点与单股道车站记 `RECLAIM_MAINLINE_DESTROY`，
  其余记 `RECLAIM_NO_ROUTE_DESTROY`）：它不会再有班可跑。闲置超时、车辆超限、方向供需这些泛用回收不知道车还有没有班（自由运行、间隔发车），
  没有回库线路时照常进下面的滞留计时。乘客、交接两道闸与 `stranded-destroy-seconds: 0`（只记滞留、不销毁）照旧。
  "终点有没有回库线路"的查询遇到存储异常时按"有"处理、不缓存（`回收: 查询回库线路失败`），不会掐断整轮回收。
- **滞留销毁兜底**：有回库交路却仍然派不出 RETURN 票（被拒、闭塞、运营商回溯不到、存储不可用）的待命车，从第一次判定该回收起计时，超过 `reclaim.stranded-destroy-seconds`
  （默认 300，`0` 关闭；没有越行线的线路上一辆车占着终点股道就挡住全部后车，等不起半小时。**升级注意**：已有服务器的 `config.yml`
  不会被改写，旧值 1800 仍然生效，需要手动改）走 `destroyTrainByName` 销毁，日志 `RECLAIM_STRANDED_DESTROY`（失败记 `RECLAIM_STRANDED_DESTROY_FAILED`）。
  **有乘客**（`reason=has-passengers`）或**有进行中的折返事务**（`reason=dispatch-attempt-in-progress`）的车不碰，记 `RECLAIM_STRANDED_SKIP`。
  一旦这辆车成功派到 RETURN 票，计时清零。
- **挂起交接只告警**：进行中的折返事务（`LayoverRegistry.DispatchAttempt`）认领之后可能已经改动了占用（改名迁移 owner 等），只能由同一张票重试完成，
  不能按时间自动释放。可它又会让上面的滞留销毁永远跳过这辆车，所以认领满 120 秒（`ReclaimManager.STALE_DISPATCH_ATTEMPT_SECONDS`；
  交接在每个发车 tick 重试一次，成功就注销候选、被拒就释放认领，挂满两分钟等于连续二十多次既没成功也没被拒）时告警：
  debug 行 `RECLAIM_DISPATCH_ATTEMPT_STALE` 加一条不依赖 debug 开关的 WARN。同一次认领只报一次，释放后重新认领算新的一次；
  回收扫描间隔决定实际报出时刻在 120 秒到 120 秒 + `check-interval-seconds` 之间。认领时刻由调度层时钟传入。
  `RECLAIM_STRANDED_SKIP reason=dispatch-attempt-in-progress` 也带上 `attemptAgeSeconds`。

## 配置项
在 `config.yml` 的 `reclaim` 段落：
```yaml
reclaim:
  enabled: false                 # 是否启用回收策略（模板与代码默认都是关）
  max-idle-seconds: 300          # 待命超过多少秒触发回收
  max-active-trains: 50          # 压力模式阈值：全服活跃列车数超过它就每轮回收一辆闲置车
  check-interval-seconds: 60     # 检查周期
  stranded-destroy-seconds: 300  # 兜底：有回库交路却一直派不出 RETURN 票的待命车，滞留超过它就销毁；0 关闭（连同没有回库线路时的原地销毁）
```

`reclaim.max-active-trains` 是**出口侧**的压力阈值，与 `spawn.max-active-trains` 的入口侧准入不是一回事，两者互不替代。

## 优先级与公平性 (Fairness)
调度占用请求现已支持优先级（Priority）：
- **Depot Spawn**：`0` (普通) 或更高 (视配置)
- **Operation (客运)**：`0` (默认)
- **Reclaim (回收)**：`-10` (低优先级)

当多列车竞争同一资源（如单线区间、道岔）时，`OccupancyManager` 的排队队列（Queue）会优先放行高优先级列车；优先级相同时按“先到先得”（FIFO）处理。

## 状态边界
- `ReclaimManager` 只处理 `LayoverRegistry` 里的待命列车，不会碰正在运行的列车，也不会直接改写 signal。
  **但它会销毁待命列车**：上面的滞留兜底是回收侧唯一一处销毁动作，触发条件是"该回收 + 长期派不出 RETURN 票 + 无乘客 + 无进行中折返事务"。
- Reclaim 只按不可变 ticketId 重试自己创建且 routeId 一致的 RETURN attempt；Layover 的位置、readyAt 或 TrainCarts 名称刷新不会改变事务身份。若候选已被普通运营折返等其他 dispatch attempt 认领，本轮跳过，不能复用其 ticketId 改派 RETURN；同站同 tick 的两列车也各自持有独立票据。
- 若某条匹配 RETURN route 在建立 attempt 前即被预检拒绝，会清理该临时 ticket 并继续尝试下一条匹配 route；一旦 handoff attempt 已建立，则固定在原 route 上稳定重试，避免双事务。
- 管理运营商取候选的 `FTA_ROUTE_ID -> Line -> Operator`（交路本身的运营商），不看 `FTA_OPERATOR_CODE`：直通运转 `CHANGE` 会把它改写成对乘客显示的运营商，跨运营商换线后必然与交路不一致。有交路 ID 却回溯不到（交路已删除）时拒绝回收，由滞留销毁兜底；只有没有交路 ID 的旧数据才按运营商标签找，且全库恰好一个同 code Operator 才允许回退。不同公司使用相同 Operator code 时不会再按遍历顺序误选 RETURN route。
- 如果某列车在 rename / split 过渡态中尚未稳定为单一逻辑列车，通常会先被运行时巡检或 layover 清理链路处理；回收侧只在候选稳定可见时才会分配 RETURN 票据。
- 当一次回收成功后，本轮会立即扣减同方向供给计数，避免同方向候选被连续过回收。

## 常见问题
- **列车为何不回收？**
  - 检查 `enabled` 是否为 `true`。
  - 检查列车所在位置是否有可用的 `RETURN` 线路（即该运营商在当前站点是否有去往 DSTY 的路线）。
  - 检查线路资源是否被高优先级列车长期占用。
- **为什么 maxTrips 到了还没立刻回库？**
  - 回收扫描只在下一个回收 tick 触发。
  - 需要列车已进入 `LayoverRegistry`，才会分配 RETURN 票据。
