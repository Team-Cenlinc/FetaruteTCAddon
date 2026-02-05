# 自定义发车（规划稿）

> 本文档为规划记录，不含实现。

## 目标

- 允许玩家或运维“呼叫最近一班空车”，按线路/站点/方向定向发车。
- 与现有自动发车共存：自动发车继续维持基准频率，自定义发车仅作为临时加开/指派。

## 设计草案

- **输入**：线路、起点（站/Depot/区域）、方向（可选）、发车原因（调试/加开/换线）。
- **候选来源**：
  - Layover 池就地复用（优先）。
  - 线路 Depot 池（`spawn_depots`）。
- **选择策略**：
  - 优先“最近/最短 ETA”的候选。
  - 保留均衡权重与线路最大车数上限（`spawn_max_trains`）。
- **安全门控**：
  - 与 Occupancy gate 共享同一判定。
  - 必须保留冲突区放行锁与尾部保护策略。
- **命令草案**：
  - `/fta spawn call <company> <operator> <line> [station] [direction]`
  - `/fta spawn call --nearest`

## 需要补齐的技术点

- 线路级别的“最近/可达”判定（基于 RailGraph 路径或 TrainCarts destination）。
- 候选列车的状态过滤（空载/闲置/非 FTA_BYPASS）。
- 失败回退与诊断输出（避免静默失败）。

## 关联

- `docs/dev/spawn-scheduler.md`（自动发车策略）
- `docs/dev/occupancy-headway.md`（占用/冲突门控）
