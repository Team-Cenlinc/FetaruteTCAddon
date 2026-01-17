# 连通分量 CAUTION 速度

## 目标
- 让 CAUTION 速度不再是列车属性，而是“线路/区域级别”的运维参数。
- 通过连通分量覆盖 + 全局默认值，兼容不同线路的限速策略。

## 组件 key
连通分量的 key 来自调度图：
- 当前策略：取分量内字典序最小的 `NodeId` 作为 `component_key`。
- 该 key 作为运维配置与持久化主键，便于人工识别。

## 存储结构
表：`rail_component_cautions`
- `world_id`：世界 UUID
- `component_key`：连通分量 key
- `caution_speed_bps`：CAUTION 速度上限（blocks/s）
- `updated_at`：更新时间（Instant）

## 命令
设置：
- `/fta graph component set caution "<nodeId>" <speed>`

查询：
- `/fta graph component get caution "<nodeId>"`

清除：
- `/fta graph component clear caution "<nodeId>"`

## 速度格式
与 edge speedlimit 一致：
- `80kmh` / `80km/h` / `0.4bpt` / `8bps`

## 生效顺序
1) 分量覆盖（`rail_component_cautions`）
2) 默认值（`runtime.caution-speed-bps`）

## 运行时行为
当信号为 `CAUTION/PROCEED_WITH_CAUTION`：
- 目标速度取 “分量 caution 覆盖” 或默认值
- 再与边限速取最小值
