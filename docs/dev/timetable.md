# 时刻表：从路网生成运营计划 → 按表运行

本文说明 FTCA 的时刻表功能：它怎么被算出来、weight 到底是什么意思、
以及为什么每一辆实体车都保证会回库。

## 三条不变量

整套设计围绕三句话，其余都是它们的实现细节：

```
时刻表是从路网「算」出来的，不是从历史跑车记录「录」出来的。

weight 是目标服务比例，不是每次发车的抽签概率。

每辆实体车都有有限的交路，并且最终必须回库。
```

### 1. 时刻表是造出来的

流程只有两步：

```
route 定义 + 调度图限速 + 运营参数  →  build  →  publish  →  按表运行
```

没有"先去实服录一遍"这一环。`build` 的输入全部是 FTCA 已经掌握的东西：

| 输入 | 来源 |
| --- | --- |
| 站序 | `RouteDefinition.waypoints()` |
| 区段路径 | `RailGraphPathFinder`（与诊断命令、ETA 同一套最短路） |
| 区段限速与加减速 | `DynamicTravelTimeModel`（ETA 模块的权威模型） |
| 停站时长 | `RouteStop.dwellSeconds()`，缺省值来自 `--dwell` |
| 服务比例 | Route metadata 的 `spawn_weight` |
| 首末班 / 间隔 / 交路上限 | `build` 的参数 |

**同一份网络状态 + 同一份配置，永远产出同一张表**，包括 trip 与 duty 的主键——
它们由 `timetableId + code` 名字派生，不用随机 UUID，否则"构建两次结果一致"这条性质
在字段层面成立、在主键层面不成立，而主键会进数据库、会被引用、会出现在导出里。

计算时分时逐段用**该段自己的限速**积分，不用全线平均速度。对一条前半段 20 bps、
后半段 5 bps 的线，平均速度会把全程时分少算三分之一以上，而表定时分一旦偏乐观，
按表运行就会把每一趟车都变成晚点。算不出来的 route（区段不可达、缺限速）会被明确排除
并给出原因，不用任何默认值顶上。

### 2. weight 是目标服务比例

`WS=5, MT=3, DS=2` 的含义是"长期应收敛到 50/30/20"，不是"每次发车各按 50%/30%/20% 抽一次"。

抽签的问题不在期望值，而在方差：它允许连开五班 WS 再连开三班 DS，而乘客感知的是间隔，不是长期期望。
因此这里用 **smooth weighted round-robin**（SWRR）：

```
每一轮：current[i] += weight[i]
        选出 current 最大者 k
        current[k] -= totalWeight
```

确定性、无随机源，同时满足长期比例精确收敛与短窗口不 starvation。对 5:3:2 它给出
`A B A C A B A B C A`，任意连续 10 班里三条线都至少各出现一次，且同一条线不会连开三班。

**暂时排不了的 route 不丢份额**：SWRR 的 `current` 就是天然的 deficit 账本，跳过一次不扣减信用，
约束解除后会连续被选中直到追平。不需要外部再记一套账。

weight 是 objective 而不是硬约束，优先级是：

```
运行约束 > 时刻/路径可行性 > 车辆周转可行性 > weight 目标
```

排不满时 `build` 会同时报出目标份额与实际份额并警告，而不是偷偷生成不合理的班次。

### 3. 每辆车都有有限交路

不要只想成 `Trip → Trip → Trip → …`。运行计划的抽象是**车辆交路（vehicle duty / circulation block）**：

```
Storage
  ↓ 出库
Vehicle Duty
  ├─ Trip 1
  ├─ Trip 2
  ├─ …
  └─ Trip N
  ↓ 回库
Storage
```

同一辆车可以在一个 duty 内连续承担若干 compatible 班次（`WS-01 → WS-08 → 折返 → WS-01 → MT…`
完全可以），但 duty 必须是有限的。

duty 的两端各对应一条**具体的走行线路**，不是估计值：

| 端 | 线路 | 例外 |
| --- | --- | --- |
| 出库 | `CREATE` route（车库 → 首站） | 首站带 `CRET` 指令的运营 route 自己就是出库票 |
| 回库 | `RETURN` route（末站 → 车库） | 以 `DSTY` 收尾的运营 route 跑完即回库 |

两段走行时分同样由 `TimetableTimingCalculator` 从路网算出，计入 duty 的在线时长。
出库票在「首班发车 − 走行 − 就绪时间」发出，否则首班必然晚点；回库票在「末班到达 + 折返」发出。

因此派车有两条硬规则：**只在有出库途径的起点开 duty，只在有回库途径的终点收 duty。**
排不进任何 duty 的班次（起点没有 CREATE 线路又没有接得上的待命车、终点没有 RETURN 线路且后面接不上能回库的班次、
单独一班连同两段走行就超过时长上限）会被从表里取消并逐条报告——宁可少排一班，也不排一班发不出去或回不了库的车。
份额报告按最终保留的班次重算。

**线路级校验**：一条线路没有任何出库途径或没有任何回库途径时，`build` 直接失败并说明缺的是哪一种。
编表时 CREATE/RETURN 的搜索范围是本运营商全部线路（与 `ReclaimManager` 一致），因为出库/回库线路常挂在车库所在的那条线上。

"跑完一趟有多大概率回库"那类机制永远证明不了终止性：只要下一班恰好接得上，它就可以无限续下去。
duty 把边界放进**计划本身**：

```
∀ duty: tripCount ≤ maxTripsPerDuty
     ∧ plannedDuration ≤ maxDutyDuration   （含出库与回库两段走行）
     ∧ endDepotNodeId 存在
     ∧ (returnRouteId 存在 ∨ 末班 route 以 DSTY 收尾)
```

两个上限都没有"不限制"这个取值——写成 0 或负数会被夹回默认值。因此终止性可以**直接在构建产物上断言**，
不需要跑 runtime；`VehicleDutyPlannerTest` 用 2000 趟环线车（终点即起点，任意两班都接得上）
做的就是这件事。

运行期还有第二道保险，两道闸互为镜像：

```
duty 跑完最后一班 → allowsLayoverReuse=false（不准再接运营班）
                 → 表定 RETURN 票到点 → allowsReturn=true → 走 RETURN 线路回库
duty 还没跑完   → allowsReturn=false（回库票带不走它）→ 留在终点等下一班
```

`allowsLayoverReuse` 只作用于 OPERATION 票，`allowsReturn` 只作用于 RETURN 票。
没有第二道闸的话，按表发出的回库票会把正等着跑下一班的车送回车库，那一班就开了天窗。
`ReclaimManager` 的闲置回收仍然保留，作为回库票没能带走车时的兜底。
时刻表层不复制一套车辆所有权，只是把"不准再接班 / 不准带走"这两个事实告诉发车侧。

### 第二层：多车交互（冲突检查）

第一层的计时器模拟的是**一辆孤立的车**，它永远不会遇到单线会让、站台占满、道岔冲突——而这三样正是决定
"baseline 能不能达到"的东西。所以 build 的第四步是 `TimetableConflictChecker`：把全部运行（班次 + 出库/回库走行 +
站台待命）投影到共享资源上，逐资源扫描重叠。它不跑调度器，只做纯数据运算，确定性、毫秒级。

| 资源 | 来源 | 规则 |
| --- | --- | --- |
| 区间（边） | 逐边时分 `SegmentTiming` | 互斥，不分方向；相邻占用之间要留 `--separation`（默认 30 秒） |
| 站台 | 停靠、待命、不停靠经过 | 按容量：具体股道容量 1；站台组 `OP:S:NAME` 容量 = 图里该站的股道数 |
| 单线区段 | `SingleLineSectionIndex`（与运行时同一份桥链索引） | 对向互斥；同向追踪交给边互斥 |
| 道岔 | 路径穿越的 SWITCHER 节点 | 两次通过之间要留 separation |

待命按 **duty** 建模而不是按班次：同一辆车"到站 → 折返 → 再发车"是一段连续的站台占用，拆成两个班次各自的到发区间
会在中间留出一个并不存在的空档。不停靠经过单股道车站的车同样登记一次站台占用——一辆在 A 站待命的车必须能挡住
从它身上碾过去的对向回库车。

**目标 headway 有冲突时**，build 从目标向上以 10 秒为步长搜索**最小可行 headway**（最多放宽到目标的 4 倍），
默认回退到它并在报告里列出目标间隔下的冲突明细；`--strict` 则构建失败。搜索只放宽 headway、不挪动单个班次：
表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成，因此建议值是一个可以直接写回配置的数。

这一层刻意**不**建模授权窗口、制动距离扩展的 lookahead、恢复链。那些属于真调度器；将来的回放校验（阶段 8）如果发现
本模型漏了约束，修的是本模型，不是让 build 去依赖回放。

### 服务规划与车辆周转是两件事

```
服务规划                          车辆周转
RouteTrip A ─┐                    Storage → [ Trip A → Trip C → Trip A ] → Storage
RouteTrip B ─┼─ weight/timing               └────────── 有限 duty ──────────┘
RouteTrip C ─┘   决定"跑什么"                        决定"谁来跑"
```

顺序不可交换：先定班次，再派车。绝不允许"某终点恰好停着一辆车，于是多发这条线"——
那会让车辆周转反过来扭曲服务比例，而这种扭曲在运营上是看不见的。

## 数据模型

时刻表以**线路**为单位，一条线下的多条 route 通过 weight 分配比例。三层各自回答一个问题：

| 层 | 回答 | 变更原因 |
| --- | --- | --- |
| `routePlans` | 每条 route 跑一趟要多久 | 改限速、改站序 |
| `trips` | 今天几点各发哪条线的车 | 改运营比例、改间隔 |
| `duties` | 每一班由哪辆车跑、什么时候回库 | 改车辆周转策略 |

三层分开，是因为任何一层调整不必重算其余两层。

存储：

| 表 | 内容 |
| --- | --- |
| `fta_timetables` | 表头 + `route_plans`（JSON，整体读写；每条带 `kind`＝OPERATION/CREATE/RETURN） |
| `fta_timetable_trips` | 发车表，一趟一行，`(timetable_id, trip_code)` 唯一；只有 OPERATION |
| `fta_timetable_duties` | 车辆交路：`end_depot_node_id` NOT NULL，`create_route_id`/`return_route_id` 可空（两端 route 自带 CRET/DSTY 时），`return_second` 是回库票发出时刻 |

`end_depot_node_id` 是 NOT NULL 的：没有回库端点的 duty 是一条没有出口的链，不允许落库。
duty 的 `planned_start_second` 可以是负数（出库早于服务日零点），`planned_end_second` 可以超过一天（跨零点）。
本项目没有 schema 迁移机制：如果开发库里已经建过旧形状的 `fta_timetable_duties`，需要手工删表重建。

## 命令

```
/fta timetable build <company> <operator> <line> <code>
        [--headway <sec>] [--start <HH:mm>] [--end <HH:mm>] [--dwell <sec>]
        [--max-trips <n>] [--max-duty-minutes <n>] [--turnaround <sec>]
        [--separation <sec>] [--strict]
        [--name "<name>"] [--prefix <p>] [--zone <zoneId>]
/fta timetable list <company> <operator> <line>
/fta timetable info <company> <operator> <line> <code> [page]
/fta timetable duties <company> <operator> <line> <code> [page]
/fta timetable publish|unpublish <company> <operator> <line> <code>
/fta timetable delete <company> <operator> <line> <code> --confirm
/fta timetable export <company> <operator> <line> <code> [limit]
/fta timetable status
```

`--headway` 不填时从线路的 baseline 频率出发：线路级 `spawnFreqBaselineSec` 优先，其次线路 metadata 里的交路组 baseline
（只有一个组就用它；多个组按频率合并 `1 / Σ(1/b_i)`，因为时刻表以整条线路为单位排班、再按 route weight 切分份额），
都没有才用 300 秒。baseline 是目标不是硬约束：排出来有冲突时回退到最小可行间隔（见上文第二层）。报告里有一行"间隔来源"。

`build` 的输出不是一句"成功"，而是一份可解释的报告：班次数、运营/出库/回库 route 数、计划窗口（含实际使用的间隔，
目标间隔有冲突被放宽时会标出）、冲突检查结果（无冲突，或目标间隔下的冲突数与明细）、
交路数、**全天出库次数与峰值同时在线车数**、**目标服务比例 vs 实际服务比例**、最长一趟车、单交路最多班次与最长在线、
"所有交路都以回库收尾"这一行，以及**被取消的班次**（按 route 与原因归组，列出时刻）。

峰值同时在线车数是将来与 operator 车数上限比较的量；duty 总数不是——一天 40 个 duty 可能只需要 6 辆车。

`RouteOperationType.OPERATION` 进发车表；CREATE/RETURN 提供车辆交路两端的走行，不进发车表，但同样落在 `route_plans` 里。
`duties` 子命令会显示每个交路经哪条线路出库、哪条线路几点回库。

权限：`fetarute.timetable`（只读）、`fetarute.timetable.manage`（编表/发布/删除）。

## 运行时改变了什么

投入运行后只有两处行为变化，都由 `timetable.enabled` 总开关控制，默认关闭：

### 出站门控里的计划扣留

`RuntimeDispatchService#checkDeparture` 在**申请任何占用之前**问一次
`StationStopCoordinator#holdsDeparture`：早到的车等到表定时刻再走。扣留期间它对别的车完全透明——
没有先占资源再等。

三道安全边界：

- 已到点或已晚点一律放行；时刻表不负责让晚点的车更晚，也不试图用无限扣留"追回时刻表"。
- 扣留时长被 `StationStopCoordinator.HOLD_CEILING`（150 秒）硬封顶，低于发车门锁自身的 180 秒回收时限。
  否则会进入"自己不动、也不再为别人排队"的状态，那是最难归因的一类停滞。
- 早到幅度超出上限判为匹配错误，放行并留痕。

### 发车出票

`timetable.spawn-enabled` 打开后，`TimetableSpawnManager` 装饰在 `StorageSpawnManager` 外层：
有 PUBLISHED 时刻表的 route（含该表的 CREATE/RETURN 线路），headway 票会被拦下（并向 delegate 报完成，避免它的 backlog 涨满后静默停发），
改由时刻表出票；其余 route 原样透传。

时刻表出三种票，一个 duty 的完整生命周期就是：

```
出库票（CREATE，duty.plannedStart）→ 运营票 × N（OPERATION，各 trip 的发车时刻）→ 回库票（RETURN，duty.returnSecond）
```

两端 route 自带 CRET/DSTY 的 duty 没有对应的走行票。拦下 RETURN 的 headway 票是必要的：
那种周期性回库票会把交路还没跑完的车抓走；现在回库只由表定回库票加 `allowsReturn` 闸驱动。

前提：这些 route 本来就得是可发车服务（配了 depot 与 spawn 开关）。时刻表只提供"几点发车"，
不提供"从哪发"。

### 票据认识 duty

每张表定票都带着交路意图（哪份表、哪个 duty、哪一天、哪种票、第几班），通过票据分配器上的三个钩子落地：

| 钩子 | 规则 |
| --- | --- |
| 候选过滤 `acceptsVehicle` | 绑在某交路上的车只接同一交路的票；没绑交路的车能接首班和回库票，**不能接续班** |
| 到期 `expiryOf` | 计划时刻 + `assign-tolerance-seconds` 还没车就作废（`TIMETABLE_SPAWN_SKIP reason=abandoned`），不走全局 max-age |
| 派发回调 `onDispatched` | 出库票实体化的车、接了首班的车，立刻绑到交路上（`TIMETABLE_DUTY_BOUND`） |

于是"接班没车"的语义是**等**：续班票在 pending 里等本交路那辆车到站（晚点就晚点跑），不抓别的交路的车，也不新出库；
超过容差才作废。门控上首次绑定到带 duty 的车次时同样会建立交路绑定，所以自由运行的车一旦绑上表定车次，之后也只认自己的交路。
绑定只在内存，重启后回到自由运行。

### 车次绑定

列车与表定车次的绑定发生在**第一次问门控**时：取该 route 所有已发布时刻表里，在当前停靠点
计划发车时刻与"现在"最接近、偏差在 `assign-tolerance-seconds` 内、尚未被别的车占用的那一趟。
没有合适的就不绑定，该车自由运行。

回到起点（`stopIndex == 0`）会重新匹配——那是新的一趟车。不重新匹配的话，同一条 route 上
连续接班的列车会一直用第一趟的时刻，交路进度也永远停在第一班。

绑定只存在于内存：重启后所有车回到自由运行。**宁可少绑，不可错绑**——错误的绑定会让车等一个
不属于它的时刻，而丢失绑定只会退回现状。

## 配置

见 `config.yml` 的 `timetable:` 段，所有开关默认关闭。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 按表运行总开关 |
| `spawn-enabled` | `false` | 是否由时刻表接管发车出票 |
| `hold-max-seconds` | `120` | 早到扣留上限，运行时再被 150 秒硬上限封顶 |
| `assign-tolerance-seconds` | `300` | 车次绑定允许的最大偏差 |
| `max-catch-up-seconds` | `300` | 发车侧单次轮询的回补窗口上限 |
| `reload-interval-seconds` | `60` | publish/unpublish 后最多多久生效 |
| `zone` | `""` | 时刻表默认时区，留空用服务器默认 |

## 遥测（未来的 calibration，不是构建输入）

`StationStopObserver` / `StationStopEvent` / `StationStopCoordinator` 这组停靠事件 seam 保留着，
但**当前没有时刻表方向的消费者**，也不参与 `build`。

它的用途是将来做对表：

```
planned segment duration   vs   actual segment duration
```

从而看出限速模型是否过于乐观、哪些区段常受拥堵影响、dwell 是否需要调整、哪些 route 的计划时分需要裕量。
那是 **validation / calibration**，不是**时刻表的来源**——这条边界不能被越过，否则又会退回
"时刻表是录出来的"那个模型。

## 排查

调试日志（`debug.enabled: true`）里的关键行：

| 前缀 | 含义 |
| --- | --- |
| `TIMETABLE_ASSIGN` / `TIMETABLE_RELEASE` | 车次绑定与解绑 |
| `TIMETABLE_DUTY_CLOSED` | 某辆车交路额度用完，复用被否决 |
| `TIMETABLE_RETURN_DENIED` | 某辆车交路还没跑完，回库票被否决、车留在终点 |
| `TIMETABLE_DUTY_BOUND` / `TIMETABLE_DUTY_BIND_CONFLICT` | 车绑到交路上 / 已绑别的交路（错派的车暴露在这里） |
| `TIMETABLE_CANDIDATE_REJECT` | 某张票拒绝了某辆待命车：`other-duty` 或 `unbound-cannot-continue-duty` |
| `TIMETABLE_SPAWN_DISPATCHED` | 表定票派给了哪辆车 |
| `TIMETABLE_DUTY_RELEASED` | 交路进度随列车下线释放 |
| `TIMETABLE_RELOAD` | 已发布时刻表缓存刷新 |
| `TIMETABLE_SPAWN_TICKET` / `TIMETABLE_SPAWN_SKIP` | 表定出票（`kind=CREATE/OPERATION/RETURN`，带 duty）与跳过/作废原因（`no-spawn-service`、`abandoned`） |
| `SCHEDULED_DEPARTURE_HOLD` | 某辆车正因等待表定时刻被扣留 |
| `SCHEDULED_DEPARTURE_HOLD_SKIPPED` | 早到幅度超上限，已放行（多半绑错了车次） |
| `SCHEDULED_DEPARTURE_PLAN_FAILED` | 计划源抛异常，已按现状放行 |

现场看到"一辆车停在站里不动"时，先看有没有 `SCHEDULED_DEPARTURE_HOLD`：
有就是正常等点，没有就与时刻表无关。

看到"某辆车一直不回库"时，看 `TIMETABLE_DUTY_CLOSED`：
应当在它跑满交路额度那一刻出现；没出现说明它根本没绑到表定车次（自由运行），
那属于 `assign-tolerance-seconds` 或时刻表覆盖范围的问题。出现了但车还在，
再看有没有 `TIMETABLE_SPAWN_TICKET kind=RETURN duty=…`：没有就是 RETURN 线路不是可发车服务（`TIMETABLE_SPAWN_SKIP reason=no-spawn-service`）。

看到"首班总是晚点"时，看出库票 `kind=CREATE` 的 `plannedDeparture` 是否早于首班发车"走行 + 折返"——
它应当是；不是的话多半是 CREATE 线路的时分没算对。

## 已知边界

- 冲突模型的边界：站台组容量取图里的物理股道数，不看各 route 的 DYNAMIC 范围（range 更窄时会少报）；单线区段只识别桥链，
  环内的会让、平交由边互斥兜底；车库容量视为无限；不建模授权窗口与制动距离。逐边时分按模型的逐边估算等比分摊到区段总时分上。
- 车次绑定与交路进度不持久化，重启后回到自由运行。
- 仅由 code 定义（无 UUID）的交路不参与按表运行：绑定挂不回 Route。
- 一条线路的多份已发布时刻表之间不做撞车仲裁，运营侧自查。
- `build` 目前用 `DynamicTravelTimeModel` 的默认加减速参数（1.0 / 1.2 bps²），
  尚未按列车类型区分；接 `TrainConfigResolver` 是后续工作。
- 进站 approaching 限速尚未接入 `build`（ETA 运行时已启用），因此表定时分会比实际略乐观一点点。
