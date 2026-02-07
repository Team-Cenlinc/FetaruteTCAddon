# 健康监控与自愈

## 目标
- 在高密度发车场景下，尽早发现“看起来在运行但实际卡住”的列车。
- 避免误报：把正常停站、正常红灯排队与异常停滞区分开。
- 自动修复采用分级恢复，避免一次性激进动作导致解挂或抖动。

## 检测项
- `STALL`：信号为 `PROCEED`，且速度持续低于阈值（默认 30 秒）。
- `PROGRESS_STUCK`：route index 长时间不变（默认 60 秒）。
- 停站排除：`DwellRegistry` 中存在剩余停站时间时，跳过上述检测。

## 分级恢复
### STALL
1. `refreshSignalByName(train)`：先重新评估信号与控车指令。
2. `forceRelaunchByName(train)`：若仍持续异常，再升级强制重发。

### PROGRESS_STUCK
1. `refreshSignalByName(train)`：先做轻量恢复。
2. `reissueDestinationByName(train)`：重下发下一跳 destination，修复“目标丢失/被覆盖”。
3. `forceRelaunchByName(train)`：最后兜底重发。

## STOP 宽限
- 当信号为 `STOP` 时，`progress stuck` 在宽限窗口内不判定异常（默认 180 秒）。
- 用于支持咽喉区排队等待，减少“红灯等待被误判为故障”的噪声告警。

## 恢复冷却
- 每列车每类恢复动作受冷却时间限制（默认 10 秒）。
- 目的：避免每次健康检查都重复修复，造成动作队列抖动。

## 配置项（`config.yml`）
- `health.check-interval-seconds`
- `health.auto-fix-enabled`
- `health.stall-threshold-seconds`
- `health.progress-stuck-threshold-seconds`
- `health.progress-stop-grace-seconds`
- `health.recovery-cooldown-seconds`
- `health.occupancy-timeout-minutes`
- `health.orphan-cleanup-enabled`
- `health.timeout-cleanup-enabled`

## 与调度占用的配合
- 信号 tick 在 `canEnter=false` 时会收缩占用到“停站保护窗口”（当前节点 + rear guard）。
- 目的：减少红灯等待期间对前方资源的长期占用，降低同向互卡概率。
