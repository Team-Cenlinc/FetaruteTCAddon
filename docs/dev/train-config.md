# 列车配置（Train Config）

## 目标
- 统一列车低速与加减速曲线配置。
- 巡航速度由调度图默认速度 + 边限速决定，避免双速源冲突。
- CAUTION 信号速度由“连通分量规则 + 配置兜底”决定，不再是列车属性。

## 配置来源
优先读取 TrainProperties tags，缺失时回退为 `config.yml` 默认值。

tags:
- `FTA_TRAIN_TYPE`：车种（METRO/EMU/DMU/DIESEL_PUSH_PULL/ELECTRIC_LOCO）
- `FTA_TRAIN_ACCEL_BPS2`：加速度（blocks/second^2）
- `FTA_TRAIN_DECEL_BPS2`：减速度（blocks/second^2）

## 配置模板
`config.yml`:
- `train.default-type`（默认 `metro`）
- `train.types.<type>.accel-bps2`
- `train.types.<type>.decel-bps2`
- `runtime.caution-speed-bps`（默认 CAUTION 速度）

## 车种预设
预设在 `TrainType` 枚举中，配置里缺失的车种或字段按预设补齐。1 格按 1 米计，取低速段平均起动加速度与常用制动减速度（不是紧急制动）：

| 车种 | 说明 | 加速度 | 减速度 |
|------|------|--------|--------|
| `metro` | 地铁/轻轨型电动车组（含 tram-train），站距短、起停频繁 | 1.1 | 1.2 |
| `emu` | 通勤/市域电动车组 | 0.9 | 1.0 |
| `dmu` | 内燃动车组 | 0.6 | 0.9 |
| `electric_loco` | 电力机车牵引 | 0.5 | 0.8 |
| `diesel_push_pull` | 内燃机车推拉 | 0.35 | 0.7 |

未打车种标签的列车按 `train.default-type` 取值；编表与 ETA 的运行曲线也按默认车种计算，改动车种预设或默认车种后需重新 build 并发布时刻表，否则表定时分与实际运行不一致。
注意 `ConfigUpdater` 只补缺失键、不改已有值：老服务器升级后 `train.default-type: emu` 与旧的 emu 数值会原样保留，需要手工改为 `metro` 与新预设。
未发车票据的 ETA 由 `SpawnTrainConfigResolver` 按 CRET 车库牌子的列车模板名推断车种：含 `metro`/`tram`/`light_rail` 的归 `metro`（优先判定），其余按 emu/dmu 等关键字匹配，推断不出来用默认车种。已发车列车只认 `FTA_TRAIN_TYPE` 标签与默认车种。

## 命令
`/fta train config set [train|@train[...]] --type <type> --accel <bps2> --decel <bps2>`

`/fta train config list [train|@train[...]]`

未指定列车时，命令会使用 TrainCarts 的“正在编辑”列车：
- 先用 `/train edit` 选中列车（下车后仍可保持选中）
- 或使用 `@train[...]` 选择器一次匹配多列车

## 运行时行为
- PROCEED 信号：使用调度图默认速度作为基准，再叠加边限速。当前节点与下一路径点相邻时就是那条边的限速；不相邻（线路只写车站，站间还有若干图节点）时取本段最短路的**限速包络**——列车所在区间的限速，以及刹得住前方每条更低限速边的最高速度 `√(v²+2·a·d)`（`a` 为列车减速度，`d` 从车头量起）。关闭 `runtime.speed-curve-enabled` 时退回整段最小限速。
  - 不能取整段最小值：2026-09-27 实服 WS LWN:2→SWN:2 共 865 格，只有道岔后一条 48 格边是默认 8 格/秒，列车全段 8 格/秒、每趟比表慢 51 秒；编表与 ETA 都按逐边限速算。
- CAUTION/PROCEED_WITH_CAUTION 信号：使用连通分量的 caution 速度上限（无覆盖时回退为 `runtime.caution-speed-bps`）。
- STOP 信号：限速 0 并停车。只有当前 route index 后已记录非 `PASS` RouteStop 的计划停靠接近，才仅通过 approach 或 CAUTION 限速而不因制动距离单独变成 STOP；裸终点、真实物理 blocker、授权失败和证据缺失仍会 fail-closed 到 STOP。
- 普通 PROCEED 不再把“到下一图节点的距离”当作停车曲线约束。速度曲线只会在真实 blocker/caution、移动授权约束、STOP/TERM waypoint 或前方低限速边存在时下压目标速度。
- 前方低限速边：前瞻窗口（`runtime.lookahead-edges`）之外，还会并入到下一停车点整段展开路径上的各边限速，按同一条 `√(v²+2·a·d)` 提前制动。线路逐点写通过点时，当前节点与下一路径点相邻，只看窗口内几条边会在远处慢速边前刹晚、到边口被截速；编表运行曲线沿整段路径反推，控车须看得一样远。

### `/fta train debug` 速度链

`/fta train debug` 会输出完整限速链，用于解释最终写入 TrainCarts `speedLimit` 的原因：

| 字段 | 说明 |
|------|------|
| `edge_limit_bps` | PROCEED 的主要巡航基准：相邻时是当前边 effective speed，不相邻时是本段路径的限速包络（见上） |
| `aspect_base_speed_bps` | 信号等级映射后的基础速度；CAUTION 会先落到 caution 速度 |
| `caution_source` | `none`、`config` 或 `component`，说明 CAUTION 速度来源 |
| `approach_limit_bps` | 进站、进库或 STOP/TERM waypoint approach 限速 |
| `movement_authority_limit_bps` | 移动授权根据前方可用距离推导的建议最大速度 |
| `distance_to_authority_end` / `authority_end_resource` / `authorized_edge_count` | 前向授权窗口末端、第一处未授权资源与已授权实际边数；只有物理 `authorityEndReason` 可参与 MA 可见信号降级 |
| `edge_speed_lookahead_min_bps` | 前方低限速边反推的当前最大速度 |
| `speed_curve_limit_bps` | 执行层速度曲线进一步压低后的速度 |
| `final_target_bps` | 最终写入 TrainCarts 前的目标速度 |
| `final_limiter_source` | 最终 limiter：`edge_limit`、`config_caution`、`component_caution`、`approach_curve`、`approach`、`depot_approach`、`stop_waypoint_approach`、`movement_authority`、`edge_speed_lookahead`、`speed_curve`、`speed_command_rate_limit` 或 `stop` |
| `destination_present_while_blocked` / `retained_destination` / `blocked_reason` | STOP/授权失败时 TrainCarts destination 是否仍存在、保留值与阻塞原因；运行时只诊断，不主动清空 TrainCarts destination |

排查时先看 `final_limiter_source`：

- `edge_limit`：edge effective speed（或本段限速包络）生效，未被其它运行时约束压低。
- `config_caution` / `component_caution`：当前处于 CAUTION/PROCEED_WITH_CAUTION，目标速度来自配置或连通分量 caution，并非 edge speed 失效。
- `movement_authority`：前方 blocker/caution 距离不足，移动授权主动压速。
- `edge_speed_lookahead`：前方存在更低 effective speed 的边，系统提前减速。
- `approach_curve`：前方有进站限速区，列车正按车型减速度刹向区界（区外制动段）。
- `speed_curve`：执行层按真实约束距离做制动曲线；若没有 blocker/caution/STOP waypoint，应检查诊断中的距离字段。

### approach 触发规则

`approach` 不按“下一节点编码像 Station/Depot”直接触发。运行时会从当前 route index 向前找到下一处 `STOP/TERMINATE` stop，把当前节点到该 stop 的 route 片段按调度图最短路展开，再在展开路径上划出**进站限速区**。

触发点：

- `Station` stop：Station 本体、同 operator/station/track 的 station throat，以及与这些节点立即相邻的 switcher，限速使用 `runtime.approach-speed-bps`。
- `Depot` stop：Depot 本体、同 operator/depot/track 的 depot throat，以及与这些节点立即相邻的 switcher，限速使用 `runtime.approach-depot-speed-bps`。
- 普通 `Waypoint` stop：只有该 STOP/TERMINATE waypoint 自身，限速使用 `runtime.approach-speed-bps`。
- `PASS` 站、普通区间点、未 materialize 的动态占位符不是触发点。

划区规则（`StopApproach.zones`，编表与 ETA 的运行曲线 `RunCurveModel` 用的是同一个函数）：沿展开路径逐节点看，节点到它之后第一个触发点的距离不超过 `runtime.approach-window-blocks`（默认 `96.0`），或边数不超过 `runtime.approach-window-edges`（默认 `0` 即禁用），就从该节点到下一节点整段按进站限速运行；相邻的段合并成一个区。停车点本身也算一个区（运行曲线以进站限速为到站速度）。

控车限速：

- 车头在区内：限进站速度，`final_limiter_source` 显示为 `approach`、`depot_approach` 或 `stop_waypoint_approach`。
- 车头在区外：按列车减速度制动至前方每个区的起点，取 `sqrt(v_app^2 + 2 * decel * d)` 的最小值（`d` 为车头到区起点的距离），`final_limiter_source=approach_curve`。关闭 `runtime.speed-curve-enabled` 时不做区外制动，只在区内限速。
- 两次调度之间由逐 tick 斜坡按同一组限速区继续下调（见下节）。

这与运行曲线的反向推算（常减速度制动至限速区起点）是同一条曲线，控车不会比表定时分更早或更晚减速；改 `approach-window-*`、进站限速或车种加减速后都要重新 build 时刻表。区外制动与前方限速边都按车型减速度原值计算，不乘 `runtime.speed-curve-factor`（编表同样不乘）；该系数只作用于阻塞/授权终点的停车曲线。

**计划停车由进站控制接管**：移动授权终点正是下一处计划停车点（`ROUTE_STOP_OR_TERMINAL` 且有 RouteStop 证明）、且进站限速已就绪时，这个终点不再作为“刹到 0”的约束参与移动授权、速度曲线与 Smart 前瞻，停车点按上面的进站规则到站（车站由 AutoStation 居中刹停）。此前它从上一图节点量距，末段边短于约 54 格时整段被压到进站限速以下（2026-09-27 实服末段 45 格时进站只有 8.1 格/秒），并常被降为 PROCEED_WITH_CAUTION。前方真实阻塞仍按硬约束处理；STOP 信号、没有进站限速（`approach-speed-bps` 为 0）或授权终点不是该停车点时，行为不变。

仍保留的差异：

- 区间停车点（STOP waypoint）：运行曲线按到点速度 0 算（从进站限速刹停约多出 4 秒），控车按进站限速到点、由调度层停车后再居中。多出的几秒大致抵消停车与居中耗时（车站把这段记在 `timetable.station-stop-overhead-seconds`，区间停车点没有），在有实测数据前不改。
- 关闭 `runtime.speed-curve-enabled` 时控车不做区外制动，编表照常按减速度反推。

`/fta train debug` 会额外输出 `approach_node`、`approach_kind`、`approach_reason`、`distance_to_approach` 与 `approach_limit_bps`。`approach_reason` 含 `distance`（车头到第一个触发点）、`node_distance`（当前图节点到第一个触发点）、`zone_start`（车头到最近限速区起点）与 `engaged`（车头是否已在区内）；区外制动已在限速时也会显示，此时 `engaged=false`。

占用相关诊断会显示 `signal_blocker_resources`、`request_resources` 与 `current_claims_for_train` 的摘要。若正常出站被红灯卡住，先确认 blocker owner 是否为自身、后车前瞻、尾部保护、Depot spawn 预占或真正前车。

## 控车节流与补能
- 每次调度 tick 都会刷新 `speedLimit`。注意 TrainCarts 的 `speedLimit` 是硬上限：每个物理步都把实体最大速度设成它，改低后下一 tick 就截速，
  不经过任何减速过程；`WaitAcceleration` 只作用于跟车（waitDistance）、互斥区与阻挡牌，**不平滑 speedLimit**。
- 两次调度之间由逐 tick 限速斜坡（`SpeedLimitRamp`）沿本周期的速度包络继续下调：包络只收“距离从车头量起”的曲线——进站包络与前方限速边；
  每 tick 按实际走过的距离重算，一次最多下调约 0.1 bps。斜坡只降不升，发现限速被别处改写（STOP、居中、重发等）即退出，连续三个调度周期未刷新也退出。
  包络比实际车速高出 2 bps 以上时暂不写入：TrainCarts 进行中的 launch 每遇限速变化就从当前速度重新规划，重算那一 tick 不加速，起步途中若每一两 tick 写一次，实际加速度只剩设计值的一半左右。限速高于车速时本来就不起作用，车速追近包络时再写即可。
- 进站包络与前方限速边的距离从车头量起（`TrainPositionResolver` 插值车头在当前边上的位置）；阻塞/授权终点的停车曲线仍从当前图节点量起——
  车站的授权终点也会进入那条“刹到 0”的曲线，改成车头起算会让车停在站牌前。
- 过节点时的推进放行（没有前瞻与进站上下文的 PROCEED）不得越过斜坡登记的进站限速，否则进站途中每过一个节点就会先把限速抬回线路速度、
  下一拍再砍回去。只挡进站限速：前方限速边越过即失效，周期命令值里又有上调限幅的滞后，拿它们封顶会扣住减速解除后推进放行的补牵引。
  封顶值取进站保持约束本身（制动至限速区的物理上界），不与斜坡已写入的限速取小：已写入值里可能含边限速与上调限幅的滞后，拿它封顶会把出站加速途中的推进放行压回旧限速；远处的保持约束高于线路速度，不会压低推进放行。
- TrainCarts 截速只截本步位移，实体速度向量本身不缩短：限速被压低后向量里仍留着旧速度，限速一抬就会瞬间弹回。
  运动中目标下降时下发的那次 launch 起的正是“把向量重置为目标值”的作用，不是平滑。
- “运动中补能”的 launch/accelerate 在信号变化、强制刷新或低速 failover 时下发；此外目标速度比当前车速高出 5% 以上（驶过慢速边、授权延伸）且列车身上没有别的 TrainCarts 动作时也补一次牵引——TrainCarts 列车不会因为 speedLimit 调高就自己加速。停站等待、停稳居中等动作还在队列里时不补（launch 会排在它们后面执行），报告不了动作队列的实现也不补。已在执行的本插件 launch 不会被重复下发。
- 速度命令新增“限幅 + 迟滞”保护：
  - `runtime.speed-command-hysteresis-bps`
  - `runtime.speed-command-accel-factor`
  - `runtime.speed-command-decel-factor`（兼容旧配置，已不生效）
- 发车/放行/运行中补牵引时会跳过“上行限幅”，由 TrainCarts launch 按车型加速度接管起步与提速斜率，避免目标速度被压到极低。
- 按加速度下发的 launch 一律使用**线性**曲线（`TrainCartsRuntimeHandle#accelerationLaunchConfig`），不用服务器默认的 `launchFunction`（通常是 bezier）。
  TrainCarts 的 launch 在 `speedLimit` 变化时会从当前速度重新规划一段曲线，调度周期每秒写一次限速，起步期间至少每秒重算一次；
  bezier 每段起点加速度为 0，反复重算后几乎不提速（2026-09-27 实服 OFL→HAS 头 50 格用了 26 秒，正常约 11 秒）。线性曲线重算后仍按原加速度提速，
  与运行曲线的常加速度模型一致。TrainCarts 自己按加速度发车时也用线性曲线。
- 降低 `speedLimit` 不做降速限幅，也不受迟滞保护。边限速、前方低限速、移动授权、approach 与 STOP 这类安全上限必须立即写入。
- 授权距离不足时会触发移动授权降级（Movement Authority），与速度限幅协同防止冒进与急剧速度跳变；`ARTIFICIAL_WINDOW_LIMIT` 属于人工窗口截断，只能触发内部扩展和诊断，不能单独发布黄灯/红灯。
