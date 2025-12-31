# 调度图构建（/fta graph）

本插件的调度图（RailGraph）用于把世界中的关键节点（站点、区间点、车库、道岔等）抽象成 `Node`，并计算节点之间的区间距离 `Edge.lengthBlocks`，供后续 ETA/间隔/占用等调度逻辑使用。

## 构建命令

- `/fta graph build [--tickBudgetMs <ms>] [--sync] [--all|--here] [--tcc] [--loadChunks] [--maxChunks <n>] [--maxConcurrentLoads <n>]`
- `/fta graph continue [--tickBudgetMs <ms>] [--maxChunks <n>] [--maxConcurrentLoads <n>]`
- `/fta graph status`
- `/fta graph cancel`
- `/fta graph info`
- `/fta graph delete`
- `/fta graph delete here`

### 模式说明

**HERE（玩家默认）**

- 起点优先级：
  1) 若指定 `--tcc`：读取 TCC 编辑器中“当前选中”的轨道方块作为起点
  2) 否则：尝试读取玩家附近的节点牌子（waypoint/autostation/depot）
  3) 再否则：使用玩家脚下附近轨道作为起点
- 发现节点方式：从起点轨道锚点沿轨道连通性扩展，触达区块后扫描 tile entity 中的牌子。

**ALL（控制台默认）**

- 仅扫描当前已加载区块内的 tile entity 牌子，不做“沿轨道扩展”。
- 该模式不会加载区块，也不会生成续跑状态（continue）。

### 时间预算（tickBudgetMs）

`--tickBudgetMs` 表示“每 tick 允许本次构建任务消耗的主线程时间预算（毫秒）”：

- 值越小：对主线程影响更小，但构建耗时更长
- 值越大：构建更快，但更可能造成卡顿

默认值为 `1`。

### 同步模式（--sync）

`--sync` 会在主线程一次性跑完 discovery + explore（可能卡服），仅用于调试/维护。生产环境建议用默认分段模式。

### 可选：沿轨道加载区块（--loadChunks）

默认 build 不会主动加载区块：未加载区块会被视为不可达（见下文“区块加载约束”）。

若希望在 HERE 模式沿轨道“按需扩张并加载相邻区块”，可加：

- `--loadChunks`：启用按需异步加载（仅 HERE 支持）
- `--maxChunks <n>`：本次 build 允许触发加载的区块上限（达到上限会暂停）
- `--maxConcurrentLoads <n>`：并发加载上限（超过会排队）

当触发 `maxChunks` 暂停后，可用 `/fta graph continue` 续跑；续跑同样受 `maxChunks/maxConcurrentLoads` 约束。

## 图构建流水线

构建分为两个阶段（`/fta graph status` 会显示 `phase`）：

1) `discover_nodes`：发现节点
   - 识别并记录：
     - 本插件节点牌子：`waypoint/autostation/depot`
     - TrainCarts 节点牌子：`switcher`（只识别 `[train] switcher`，不会把 `[train] tag` 误认为 switcher）
     - 自动 switcher：轨道访问器返回的邻居数量 ≥ 3 时，会在该轨道方块坐标上生成 `NodeType.SWITCHER`（用于把分叉位置纳入图）
   - 注意：若线网中没有任何 waypoint/autostation/depot 牌子，则该线网不会被视为“本插件接管的信号线路”，build 可能会提示未扫描到节点。

2) `explore_edges`：计算区间距离
   - 使用“多源 Dijkstra”一次遍历整张轨道网络，计算任意两个节点波前相遇时的最短距离，写入 `Edge.lengthBlocks`
   - 最大探索距离默认为 `4096`，用于防止误扫整图或无限环路

## 区块加载约束（重要）

构建过程默认不会主动加载区块：未加载区块会被视为不可达。

- 这意味着：同一条线网如果跨越未加载区域，HERE/ALL 都可能漏扫节点与边
- 运维建议：先预加载线路区域（例如飞行巡检、第三方预加载工具）再执行 `/fta graph build`

若你启用了 `--loadChunks`：

- HERE 模式会沿轨道连通性“按需”异步加载相邻区块，以扩大可达范围（不会无脑全图加载）
- 仍建议设置合理的 `--maxChunks/--maxConcurrentLoads`，避免误加载过多区域
- 若达到 `maxChunks`，build 会暂停并提示 pending chunk 数量；此时可用 `/fta graph continue` 分批续跑

## Switcher 牌子提示

构建结束后会对“发现的分叉/道岔候选位置”做一次诊断：

- 若附近没有扫描到 `switcher` 牌子，会输出 `command.graph.build.missing-switcher.*` 提示（最多显示 10 条坐标）
- 该提示不影响图本身的生成，仅用于提醒运维为关键分叉补齐 TC 行为牌子（或用于封锁/诊断）

## 持久化与预热

成功构建后会写入存储快照：

- `rail_nodes`：节点列表（含 nodeType/坐标/元数据）
- `rail_edges`：区间边与距离
- `rail_graph_snapshots`：快照元信息（built_at/node_count/edge_count/node_signature）

插件启动时若存储就绪，会从快照预热到内存，`/fta graph info` 可直接查看。

## 清理与局部清理

- `/fta graph delete`：删除当前世界的内存快照 + SQL 快照，并清空该世界的续跑缓存（不可恢复）
- `/fta graph delete here`：仅删除玩家附近所在的连通分量（通过“最近节点”定位），适合局部重建/排查

注意：清理前请确保没有正在运行的 build/continue 任务（命令会在检测到运行中时拒绝执行）。

## 图信息（/fta graph info）

`/fta graph info` 会输出当前世界内存快照的概要信息，便于诊断：

- builtAt（快照生成时间）
- 连通分量数量（components）
- 封锁边数量（blockedEdges）
- 孤立节点数量（isolatedNodes）
- 各 NodeType 数量（waypoint/station/depot/destination/switcher）
- switcher 来源拆分（来自 switcher 牌子 vs 自动分叉）
- 最长的 10 条边（按 lengthBlocks 降序）

## 常见问题：SWITCHER 节点异常增多

若你在旧版本中发现大量“莫名其妙的 SWITCHER”：

- 请确认 TrainCarts 的 `[train] tag` 牌子不会被当作 switcher（当前实现只识别 `[train] switcher`）
- 自动 switcher 的判定基于“真实连通邻居数”（`neighbors >= 3`）；若轨道实现返回的邻居异常，也可能导致噪声节点

## 快速写入 Waypoint 牌子

若你已经摆好了牌子方块但不想手动输入长 nodeId，可对准该牌子后执行：

- `/fta graph sign waypoint <nodeId>`

该命令会把牌子前面写成 TrainCarts 格式（`[train]` + `waypoint` + `nodeId`），随后即可用 `/fta graph build` 扫描并建图。
