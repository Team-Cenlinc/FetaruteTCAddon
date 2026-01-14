# AutoStation 行为说明

AutoStation 牌子是“站点行为节点”，用于执行停站/开关门/发车，不负责调度寻路。

## 触发条件
- 仅在列车具备 `FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE`（或 `FTA_ROUTE_ID`）tag 且 RouteStop 标记为 `STOP/TERMINATE` 时停站。
- 若 RouteStop 未填写 `dwellSeconds`，默认停站 20 秒；若填写 `<= 0` 则视为不停车。
- 未匹配到 RouteStop 或无 route tag 的列车视为 waypoint，直接通过。
- RouteStop 匹配顺序：先按站点 ID（同运营商 + 站点 code）匹配，失败后再用 waypoint nodeId 匹配。

## 牌子格式
```
[train] / [cart]
autostation
<Operator:S:Station:Track>
<门方向: N/E/S/W/BOTH/NONE>
```

说明：
- 第 3 行为站点节点 ID（4 段 `S` 格式）。
- 第 4 行为开门方向：N/E/S/W 为世界方向；BOTH 双侧开门；NONE 或空行不开门。

方向说明：
- N/E/S/W 需要结合列车朝向（车头面向方向）推导左右门，避免倒车时方向翻转。
- 若无法判定列车朝向，将不执行开关门，避免误开门。

## 开关门动画
- 标准动画：`doorL` / `doorR`（相对列车行进方向的左/右门）。
- 旧车兼容：`doorL10` / `doorR10` 会按时长切分为开门/关门两段；其他 legacy 动画会截取开门段并在关门时反放。
  - 对 `doorL10/doorR10`：默认取前 5 秒作为开门段，剩余部分作为关门段；若总时长不足 10 秒则按一半切分。
  - 其他 legacy 动画：开门段优先按 “scene marker 含 open” 的节点截取，其次按“位姿变化量最大”的节点；若无明显变化再用“最长持续时间”兜底。
  - 若无法解析动画节点，将回退直接播放 `doorL10/doorR10`。
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
4. 关门动画并放行列车。
