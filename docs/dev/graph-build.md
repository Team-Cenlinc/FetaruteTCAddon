# 调度图构建（/fta graph）

本插件的调度图（RailGraph）用于把世界中的关键节点（站点、区间点、车库、道岔等）抽象成 `Node`，并计算节点之间的区间距离 `Edge.lengthBlocks`，供后续 ETA/间隔/占用等调度逻辑使用。

## 构建命令

- `/fta graph build [--tickBudgetMs <ms>] [--sync] [--all|--here]`
- `/fta graph status`
- `/fta graph cancel`
- `/fta graph info`

### 模式说明

**HERE（玩家默认）**

- 起点优先级：
  1) 若安装了 TCCoasters 且玩家处于编辑状态：优先读取“当前编辑/选中”的 coaster 节点/轨道作为起点
  2) 否则：尝试读取玩家附近的节点牌子（waypoint/autostation/depot）
  3) 再否则：使用玩家脚下附近轨道作为起点
- 发现节点方式：从起点轨道锚点沿轨道连通性扩展，触达区块后扫描 tile entity 中的牌子。

**ALL（控制台默认）**

- 仅扫描当前已加载区块内的 tile entity 牌子，不做“沿轨道扩展”。

### 时间预算（tickBudgetMs）

`--tickBudgetMs` 表示“每 tick 允许本次构建任务消耗的主线程时间预算（毫秒）”：

- 值越小：对主线程影响更小，但构建耗时更长
- 值越大：构建更快，但更可能造成卡顿

默认值为 `1`。

### 同步模式（--sync）

`--sync` 会在主线程一次性跑完 discovery + explore（可能卡服），仅用于调试/维护。生产环境建议用默认分段模式。

## 图构建流水线

构建分为两个阶段（`/fta graph status` 会显示 `phase`）：

1) `discover_nodes`：发现节点
   - 识别并记录：
     - 本插件节点牌子：`waypoint/autostation/depot`
     - TrainCarts 节点牌子：`switcher/tag`
     - 自动 switcher：轨道访问器返回的邻居数量 ≥ 3 时，会在该轨道方块坐标上生成 `NodeType.SWITCHER`（用于把分叉位置纳入图）
   - 若 HERE 模式注入了 TCC 预置节点：会先把 coaster 的 TrackNode 列表注入为 Node（NodeId 形如 `TCCNODE:<world>:<x>:<y>:<z>`）

2) `explore_edges`：计算区间距离
   - 使用“多源 Dijkstra”一次遍历整张轨道网络，计算任意两个节点波前相遇时的最短距离，写入 `Edge.lengthBlocks`
   - 最大探索距离默认为 `4096`，用于防止误扫整图或无限环路

## 区块加载约束（重要）

构建过程不会主动加载区块：未加载区块会被视为不可达。

- 这意味着：同一条线网如果跨越未加载区域，HERE/ALL 都可能漏扫节点与边
- 运维建议：先预加载线路区域（例如飞行巡检、第三方预加载工具）再执行 `/fta graph build`

## Switcher 牌子提示

构建结束后会对“发现的分叉/道岔候选位置”做一次诊断：

- 若附近没有扫描到 `switcher/tag` 牌子，会输出 `command.graph.build.missing-switcher.*` 提示（最多显示 10 条坐标）
- 该提示不影响图本身的生成，仅用于提醒运维为关键分叉补齐 TC 行为牌子（或用于封锁/诊断）

## 持久化与预热

成功构建后会写入存储快照：

- `rail_nodes`：节点列表（含 nodeType/坐标/元数据）
- `rail_edges`：区间边与距离
- `rail_graph_snapshots`：快照元信息（built_at/node_count/edge_count/node_signature）

插件启动时若存储就绪，会从快照预热到内存，`/fta graph info` 可直接查看。
