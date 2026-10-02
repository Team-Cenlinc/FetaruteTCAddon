# AutoStation 行为说明

AutoStation 牌子是“站点行为节点”，用于执行停站/开关门/发车，不负责调度寻路。

## 触发条件
- 仅在列车具备 `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`（或 `FTA_ROUTE_ID`）tag 且 RouteStop 标记为 `STOP/TERMINATE` 时停站。
- 若 RouteStop 未填写 `dwellSeconds`，默认停站 20 秒；若填写 `<= 0` 则视为不停车。
- 未匹配到 RouteStop 或无 route tag 的列车视为 waypoint，直接通过。
- RouteStop 匹配顺序：先按站点 ID（同运营商 + 站点 code）匹配，失败后再用 waypoint nodeId 匹配。
- 兼容 `[train]` 与 `[cart]`：`[cart]` 只会触发 `MEMBER_ENTER`，因此仅处理车头触发一次，避免长编组重复停站。

## 牌子格式
```
[train] / [cart]
autostation
<Operator:S:Station:Track>
<门方向: N/E/S/W/NE/NW/SE/SW/BOTH/NONE>
```

说明：
- 第 3 行为站点节点 ID（4 段 `S` 格式）。
- 第 4 行为开门方向：N/E/S/W/NE/NW/SE/SW 为世界方向；BOTH 双侧开门；NONE 或空行不开门。

方向说明：
- N/E/S/W/NE/NW/SE/SW 是牌子文本写出的世界绝对方向，不是牌子方块朝向、轨道方向、列车行进方向或相对于列车的方向。
- 门侧判定只比较 `doorL` 与 `doorR` 附件目标在该世界方向上的投影；投影更大的那一侧更靠近牌子写出的世界方向。
- 斜向站台可直接写 NE/NW/SE/SW，例如 SE 表示世界东南（X+、Z+），NW 表示世界西北（X-、Z-）。
- 若无法同时取得 `doorL` / `doorR` 的可靠世界位置，或两侧投影无法区分，将不执行开门，避免误开对侧。

## 开关门动画
- 标准动画：`doorL` / `doorR`（相对列车行进方向的左/右门）。
- 旧车兼容：`doorL10` / `doorR10` 会按时长切分为开门/关门两段；其他 legacy 动画会截取开门段并在关门时反放。
  - 对 `doorL10/doorR10`：默认取前 5 秒作为开门段，剩余部分作为关门段；若总时长不足 10 秒则按一半切分。
  - 其他 legacy 动画：开门段优先按 “scene marker 含 open” 的节点截取，其次按“位姿变化量最大”的节点；若无明显变化再用“最长持续时间”兜底。
  - 若无法解析动画节点，将回退直接播放 `doorL10/doorR10`。
- AutoStation 开/关门动画同时置 `reset` 与 `queue`。TrainCarts 的 `Attachment#startAnimation` 先判 `reset`：直接顶掉附件上当前的动画并清空队列，`queue` 分支走不到——即门动画**总会打断**同一附件上正在播放的其它模型动画（升弓、受电弓复位等），并不排队。要真正排队必须去掉 `reset`，那样门动画不再从头开始，行为会变，需单独评估。
- 出库与首站**不做门动画预热**。曾经的预热以 `reset + speed=0` 播放门动画把门摆回起点，但速度为零则动画永远播不完，TrainCarts 只在当前动画播完后才推进附件动画队列，此后显式带 `queue` 且不带 `reset` 的动画（TC 牌子 `animate` 写了 `queue`、命令 `--queue`）会一直卡在它后面；用 chest 生成的车没有这一步，动画从第一次起就能动。
- N/E/S/W/NE/NW/SE/SW 的门侧判定必须由 `doorL/doorR` 附件世界位置决定，不使用列车 facing/travel vector 猜测动画名。附件局部探针仅用于取得更可靠的世界位置，不改变“世界方向投影更大者胜出”的规则。
- 关门提示音：关门时会在门位置播放提示音，仅当附近 12 格内存在玩家时触发。
  - 门位置优先取含 `doorL/doorR/doorL10/doorR10` 动画的附件坐标；找不到则回退到车体位置。
  - 提示音会连续播放 3 次，每次间隔 10 tick。

## 自定义关门/开门提示音（Sequencer 标记）
可在模型附件上添加 `sequencer` 名称，用作提示音触发点：

- `doorOpenChime`：开门提示音（仅当定义了自定义声音时触发）
- `doorCloseChime`：关门提示音（优先触发自定义，否则使用默认关门声音）
- `doorChime`：通用提示音（同时作用于开门与关门）

提示音配置建议（附件配置示例，键名可在此处自定义）：
```
sequencer: doorCloseChime
sound: BLOCK_NOTE_BLOCK_BELL
volume: 1.0
pitch: 1.2
```

若未定义 `sound`，关门会回退使用全局默认关门声音；开门无默认音。

默认关门提示音由 `config.yml` 控制：
- `autostation.door-close-sound`
- `autostation.door-close-sound-volume`
- `autostation.door-close-sound-pitch`

## 停站流程
1. 居中对齐并停稳（与 `[train] station` 相同的 center 逻辑）。
2. 停稳后延迟 10 tick 开门。
3. 从开门开始计时，等待 dwell 时间（默认 20s）。
4. 停站门控持有期间只推进 routeIndex 与动态站台选择，不提前写下一跳 TrainCarts destination，避免静止停站时被 TrainCarts 按出口方向反向。
5. 关门动画结束且出站门控放行后，由下一次 signal tick 提交 destination 并发车。
6. dwell 结束后停站任务每秒问一次出站门控。问之前先确认这次停站没被别的流程接手：列车改了名，或改派到了另一条交路（终点待命复用就是这样——改名、换新交路、自行发车），就只收尾（释放本次停站的门控与 dwell 记录、恢复出口偏移、结束 WaitState）后退出，不再替它判"从本站发车"，也不记本站离站。
   - 否则已开走的车会一直以"从本站发车"被判门控，每次判定都刷新它在本站单线区段上的排队位。2026-09-27 实服 NTA：9675 复用为 1673 开走后，对向进站的 4936 因此被挡半小时以上，1673 远在 HHU 仍被"本站发车"扣着。
   - 读不到当前名字或交路不算接手，照旧运行。
