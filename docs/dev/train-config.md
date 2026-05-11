# 列车配置（Train Config）

## 目标
- 统一列车低速与加减速曲线配置。
- 巡航速度由调度图默认速度 + 边限速决定，避免双速源冲突。
- CAUTION 信号速度由“连通分量规则 + 配置兜底”决定，不再是列车属性。

## 配置来源
优先读取 TrainProperties tags，缺失时回退为 `config.yml` 默认值。

tags:
- `FTA_TRAIN_TYPE`：车种（EMU/DMU/DIESEL_PUSH_PULL/ELECTRIC_LOCO）
- `FTA_TRAIN_ACCEL_BPS2`：加速度（blocks/second^2）
- `FTA_TRAIN_DECEL_BPS2`：减速度（blocks/second^2）

## 配置模板
`config.yml`:
- `train.default-type`
- `train.types.<type>.accel-bps2`
- `train.types.<type>.decel-bps2`
- `runtime.caution-speed-bps`（默认 CAUTION 速度）

## 命令
`/fta train config set [train|@train[...]] --type <type> --accel <bps2> --decel <bps2>`

`/fta train config list [train|@train[...]]`

未指定列车时，命令会使用 TrainCarts 的“正在编辑”列车：
- 先用 `/train edit` 选中列车（下车后仍可保持选中）
- 或使用 `@train[...]` 选择器一次匹配多列车

## 运行时行为
- PROCEED 信号：使用调度图默认速度作为基准，再叠加边限速。
- CAUTION/PROCEED_WITH_CAUTION 信号：使用连通分量的 caution 速度上限（无覆盖时回退为 `runtime.caution-speed-bps`）。
- STOP 信号：限速 0 并停车。
- 普通 PROCEED 不再把“到下一图节点的距离”当作停车曲线约束。速度曲线只会在真实 blocker/caution、移动授权约束、STOP/TERM waypoint 或前方低限速边存在时下压目标速度。

### `/fta train debug` 速度链

`/fta train debug` 会输出完整限速链，用于解释最终写入 TrainCarts `speedLimit` 的原因：

| 字段 | 说明 |
|------|------|
| `edge_limit_bps` | 当前边 effective speed；PROCEED 的主要巡航基准 |
| `aspect_base_speed_bps` | 信号等级映射后的基础速度；CAUTION 会先落到 caution 速度 |
| `caution_source` | `none`、`config` 或 `component`，说明 CAUTION 速度来源 |
| `approach_limit_bps` | 进站、进库或 STOP/TERM waypoint approach 限速 |
| `movement_authority_limit_bps` | 移动授权根据前方可用距离推导的建议最大速度 |
| `distance_to_authority_end` / `authority_end_resource` / `authorized_edge_count` | 前向授权窗口末端、第一处未授权资源与已授权实际边数；PROCEED 无 blocker 时也会参与 MA 降级/压速 |
| `edge_speed_lookahead_min_bps` | 前方低限速边反推的当前最大速度 |
| `speed_curve_limit_bps` | 执行层速度曲线进一步压低后的速度 |
| `final_target_bps` | 最终写入 TrainCarts 前的目标速度 |
| `final_limiter_source` | 最终 limiter：`edge_limit`、`config_caution`、`component_caution`、`approach_curve`、`approach`、`depot_approach`、`stop_waypoint_approach`、`movement_authority`、`edge_speed_lookahead`、`speed_curve`、`speed_command_rate_limit` 或 `stop` |
| `destination_present_while_blocked` / `retained_destination` / `blocked_reason` | STOP/授权失败时 TrainCarts destination 是否仍存在、保留值与阻塞原因；运行时只诊断，不主动清空 TrainCarts destination |

排查时先看 `final_limiter_source`：

- `edge_limit`：edge effective speed 生效，未被其它运行时约束压低。
- `config_caution` / `component_caution`：当前处于 CAUTION/PROCEED_WITH_CAUTION，目标速度来自配置或连通分量 caution，并非 edge speed 失效。
- `movement_authority`：前方 blocker/caution 距离不足，移动授权主动压速。
- `edge_speed_lookahead`：前方存在更低 effective speed 的边，系统提前减速。
- `approach_curve`：已进入下一处停靠点的 approach 窗口，系统正按 expanded route 的末尾 edge 数逐步收敛到进站/进库限速。
- `speed_curve`：执行层按真实约束距离做制动曲线；若没有 blocker/caution/STOP waypoint，应检查诊断中的距离字段。

### approach 触发规则

`approach` 不再按“下一节点编码像 Station/Depot”直接触发。运行时会从当前 route index 向前找到下一处 `STOP/TERMINATE` stop，把当前节点到该 stop 的 route 片段按调度图最短路展开，再按窗口判断是否已经 approaching：

- `runtime.approach-window-blocks`：到 approach 触发点的展开路径距离小于等于该值时触发；默认 `96.0` blocks，`0` 表示不按距离触发。
- `runtime.approach-window-edges`：到 approach 触发点的展开路径边数小于等于该值时触发；默认 `0`，即禁用边数触发，避免一条很长 edge 过早压速。
- `runtime.approach-target-edges`：到 approach 触发点剩余边数小于等于该值时必须已经降到 approaching 限速；默认 `1`，也就是进入最后 1 条调度图 edge 时应已完成降速。
- `Station` stop：Station 本体、同 operator/station/track 的 station throat，以及与这些节点立即相邻的 switcher 都按 station approach 处理，目标限速使用 `runtime.approach-speed-bps`。
- `Depot` stop：Depot 本体、同 operator/depot/track 的 depot throat，以及与这些节点立即相邻的 switcher 都按 depot approach 处理，目标限速使用 `runtime.approach-depot-speed-bps`。
- 普通 `Waypoint` stop：只在该 STOP/TERMINATE waypoint 自身进入窗口时使用 `runtime.approach-speed-bps`。
- `PASS` 站、普通区间点、未 materialize 的动态占位符不会触发 approach 限速。

进入 approach 窗口后不会直接硬切低速。运行时会基于 expanded route 计算到 station/depot/throat/switcher 的总距离，并扣除最后 `approach-target-edges` 条 edge 的长度，按 `sqrt(v_limit^2 + 2 * decel * distance)` 生成速度包络。包络阶段的 `final_limiter_source=approach_curve`；真正进入目标 edge 区间后 limiter 会显示为 `approach`、`depot_approach` 或 `stop_waypoint_approach`。

`/fta train debug` 会额外输出 `approach_node`、`approach_kind`、`approach_reason`、`distance_to_approach` 与 `approach_limit_bps`，用于判断 32 bps 之类的低速到底来自真实停靠、Depot approach、STOP waypoint，还是其它 limiter。

占用相关诊断会显示 `signal_blocker_resources`、`request_resources` 与 `current_claims_for_train` 的摘要。若正常出站被红灯卡住，先确认 blocker owner 是否为自身、后车前瞻、尾部保护、Depot spawn 预占或真正前车。

## 控车节流与补能
- 每次调度 tick 都会刷新 `speedLimit`，加减速曲线由 TrainCarts 的 WaitAcceleration 接管。
- “运动中补能”的 launch/accelerate 只在信号变化、强制刷新或低速 failover 时下发，避免周期性加速打断停靠或在道岔处反向弹回。
- 速度命令新增“限幅 + 迟滞”保护：
  - `runtime.speed-command-hysteresis-bps`
  - `runtime.speed-command-accel-factor`
  - `runtime.speed-command-decel-factor`
- 发车/放行时会跳过“上行限幅”，由 TrainCarts launch + WaitAcceleration 接管起步斜率，避免发车目标速度被压到极低。
- 降低 `speedLimit` 不做降速限幅，也不受迟滞保护。边限速、前方低限速、移动授权、approach 与 STOP 这类安全上限必须立即写入；实际车辆不会瞬间减速，平滑制动由 WaitAcceleration 负责。
- 授权距离不足时会触发移动授权降级（Movement Authority），与速度限幅协同防止冒进与急剧速度跳变。
