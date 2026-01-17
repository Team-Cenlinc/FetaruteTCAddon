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
