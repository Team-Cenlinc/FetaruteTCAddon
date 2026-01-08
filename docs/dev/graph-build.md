# 调度图构建（/fta graph）

本插件的调度图（RailGraph）用于把世界中的关键节点（站点、区间点、车库、道岔等）抽象成 `Node`，并计算节点之间的区间距离 `Edge.lengthBlocks`，供后续 ETA/间隔/占用等调度逻辑使用。

## 构建命令

- `/fta graph build [--tickBudgetMs <ms>] [--sync] [--all|--here] [--tcc] [--loadChunks] [--maxChunks <n>] [--maxConcurrentLoads <n>]`
- `/fta graph continue [--tickBudgetMs <ms>] [--maxChunks <n>] [--maxConcurrentLoads <n>]`
- `/fta graph status`
- `/fta graph cancel`
- `/fta graph info`
- `/fta graph edge speed set "<a>" "<b>" <speed>`
- `/fta graph edge speed clear "<a>" "<b>"`
- `/fta graph edge speed temp "<a>" "<b>" <speed> <ttl>`
- `/fta graph edge speed temp-clear "<a>" "<b>"`
- `/fta graph edge speed path "<from>" "<to>" <speed> [--confirm]`
- `/fta graph edge speed path temp "<from>" "<to>" <speed> <ttl> [--confirm]`
- `/fta graph edge speed list [--node "<nodeId>"] [page]`
- `/fta graph edge restrict set "<a>" "<b>" <speed> <ttl>`
- `/fta graph edge restrict clear "<a>" "<b>"`
- `/fta graph edge restrict path "<from>" "<to>" <speed> <ttl> [--confirm]`
- `/fta graph edge restrict list [--node "<nodeId>"] [page]`
- `/fta graph edge block set "<a>" "<b>"`
- `/fta graph edge block clear "<a>" "<b>"`
- `/fta graph edge block temp "<a>" "<b>" <ttl>`
- `/fta graph edge block temp-clear "<a>" "<b>"`
- `/fta graph edge block path "<from>" "<to>" [--confirm]`
- `/fta graph edge block path temp "<from>" "<to>" <ttl> [--confirm]`
- `/fta graph edge block list [--node "<nodeId>"] [page]`
- `/fta graph edge get "<a>" "<b>"`
- `/fta graph edge get adjacent "<nodeId>" [page]`
- `/fta graph edge get path "<from>" "<to>" [page]`
- `/fta graph edge overrides orphan list [--node "<nodeId>"] [page]`
- `/fta graph edge overrides orphan cleanup [--node "<nodeId>"] [--confirm]`
- `/fta graph query "<from>" "<to>"`
- `/fta graph path "<from>" "<to>" [page]`
- `/fta graph component "<nodeId>"`
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
- `rail_edge_overrides`：区间运维覆盖（限速/临时限速/封锁等），不会被 build 覆盖
- `rail_graph_snapshots`：快照元信息（built_at/node_count/edge_count/node_signature）

插件启动时若存储就绪，会从快照预热到内存，`/fta graph info` 可直接查看。

节点牌子的注册表、冲突检测、拆牌清理与 `rail_nodes` 增量同步细节，见：`docs/dev/node-sign-registry.md`。

## 快照失效判断（node_signature）

为了避免“世界内节点牌子已变更，但仍在使用旧图”的问题，图快照引入了基于 `node_signature` 的失效判断：

- `/fta graph build` 时会把节点集合签名写入 `rail_graph_snapshots.node_signature`
- 建牌/拆牌时插件会对 `rail_nodes` 做增量同步（仅 waypoint/autostation/depot），并据此计算“当前节点签名”
- 若当前签名与快照签名不一致：旧图会被标记为失效，启动预热会跳过加载，`/fta graph info` 会提示需要重建

当图处于 stale 状态时（内存快照被清空），如果你在 HERE 模式只重建某一个联通分量，命令会在需要时从 SQL 加载旧图作为 merge base，避免误删其他联通分量的数据。

注意：该机制只覆盖“节点牌子集合变化”。轨道拓扑变化、未被增量同步的节点来源（例如 switcher/自动分叉/TCC 虚拟牌子）仍需运维自行决定何时重建。

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

## 图查询（/fta graph query/path/component）

图查询命令用于“运维排障/线路验证”，依赖内存快照，因此请先完成一次 `/fta graph build`（若图处于 stale 状态，`/fta graph info` 会提示重建）。

补充：命令侧已适配交互与补全，便于运维快速定位问题。

- `/fta graph` 二级帮助为可点击条目（run/suggest），并按权限与 sender 类型过滤可用命令。
- `query/path/component` 的 nodeId 参数支持 Tab 补全（仅基于内存快照，最多返回 20 条候选；无快照时仅返回占位符）。
- 玩家端由于 Minecraft 命令解析限制，nodeId（含 `:`）建议使用引号包裹（Tab 补全也会返回带引号的候选）。

### 两点可达性（query）

- `/fta graph query "<from>" "<to>"`
  - 输出：hops（跳数）、distance_blocks（最短距离，blocks）、eta（估算）
  - ETA 计算：沿最短路逐段累计（按边限速优先，否则回退默认速度）
  - 默认寻径会绕开被封锁的区间（见下文 edge block）

默认速度来自 `config.yml`：

```yml
graph:
  default-speed-blocks-per-second: 8.0
```

该值用于“诊断估算”，建议按服务器实际列车运行速度调参。

### 区间限速/临时限速（edge speed）

区间限速属于“运维覆盖层”（`rail_edge_overrides`），不会被 `/fta graph build` 覆盖；命令支持直接边与“最短路批量”两种形态：

- 直接边（两节点必须相邻）：
  - `/fta graph edge speed set "<a>" "<b>" <speed>`
  - `/fta graph edge speed clear "<a>" "<b>"`
  - `/fta graph edge speed temp "<a>" "<b>" <speed> <ttl>`
  - `/fta graph edge speed temp-clear "<a>" "<b>"`
- 最短路批量：
  - `/fta graph edge speed path "<from>" "<to>" <speed> [--confirm]`
  - `/fta graph edge speed path temp "<from>" "<to>" <speed> <ttl> [--confirm]`
- 查看列表（管理）：
  - `/fta graph edge speed list [--node "<nodeId>"] [page]`

#### 速度单位（命令输入/展示）

约定 `1 block≈1m`，`20 tick = 1s`。内部与存储统一使用 blocks/s（bps），命令支持多单位输入：

- `bps`（blocks/s）：调度层内部单位
- `bpt`（blocks/tick）：TrainCarts 常用语境
- `kmh`/`km/h`/`kph`：人类直观单位

换算：

- `1 bps = 3.6 km/h`
- `1 bpt = 20 bps = 72 km/h`
- `km/h → bps = kmh / 3.6`
- `km/h → bpt = kmh / 72`
- `bps → km/h = bps * 3.6`
- `bpt → km/h = bpt * 72`

输入示例：`80kmh`、`80km/h`、`0.4bpt`、`8bps`（省略单位时默认视为 `kmh`）。

#### TTL 格式

TTL 支持 `s/m/h/d`，并支持组合（例如 `1h30m`）：`90s`、`1m`、`2h`、`1h30m`、`1d`。

#### ETA 与限速的关系

每条边的有效速度（blocks/s）按以下规则计算：

- `base = edge.baseSpeedLimit > 0 ? edge.baseSpeedLimit : graph.default-speed-blocks-per-second`
- `normal = override.speed_limit_bps ? override.speed_limit_bps : base`
- `effective = min(normal, override.temp_speed_limit_bps (若 TTL 仍有效))`

随后按边累计：`edgeSeconds = edge.lengthBlocks / effective`，整段 ETA 为各边之和。

### 临时限速别名（edge restrict）

`restrict` 是 `speed temp` 的语义化别名：用于表达“可通行但临时降速”的限制（TTL 到期自动失效）。

- 直接边：
  - `/fta graph edge restrict set "<a>" "<b>" <speed> <ttl>`
  - `/fta graph edge restrict clear "<a>" "<b>"`
- 最短路批量：
  - `/fta graph edge restrict path "<from>" "<to>" <speed> <ttl> [--confirm]`
- 查看列表（管理）：
  - `/fta graph edge restrict list [--node "<nodeId>"] [page]`

### 区间封锁（edge block）

封锁属于“不可通行”的运维控制（maintenance/事故/临时管制等）：被封锁的 edge 默认不允许寻径穿越。

- 直接边：
  - `/fta graph edge block set "<a>" "<b>"`（手动封锁，必须 clear）
  - `/fta graph edge block clear "<a>" "<b>"`（清除 manual + TTL）
  - `/fta graph edge block temp "<a>" "<b>" <ttl>`（TTL 封锁，到期自动失效）
  - `/fta graph edge block temp-clear "<a>" "<b>"`（仅清除 TTL）
- 最短路批量：
  - `/fta graph edge block path "<from>" "<to>" [--confirm]`
  - `/fta graph edge block path temp "<from>" "<to>" <ttl> [--confirm]`
- 查看列表（管理）：
  - `/fta graph edge block list [--node "<nodeId>"] [page]`

封锁生效规则：`effectiveBlocked = blocked_manual || (blocked_until != null && now < blocked_until)`。

### 快速查看区间状态（edge get）

用于运维快速查看某条区间或一条最短路上的“当前生效速度/限速/临时限速/封锁”等信息：

- 直接区间：`/fta graph edge get "<a>" "<b>"`（两节点必须相邻）
- 相邻区间：`/fta graph edge get adjacent "<nodeId>" [page]`
- 最短路区间详情：`/fta graph edge get path "<from>" "<to>" [page]`

输出中的 flags：`S`=存在长期限速（speed set），`R`=存在生效的临时限速（restrict/temp），`B`=区间被封锁（block）。

### 孤儿 overrides（orphan overrides）

当线路改造/节点改名后，可能出现“库里有 rail_edge_overrides，但当前图快照已不存在该边”的孤儿记录，可用以下命令排查与清理：

- `/fta graph edge overrides orphan list [--node "<nodeId>"] [page]`
- `/fta graph edge overrides orphan cleanup [--node "<nodeId>"] [--confirm]`

### 最短路径（path）

- `/fta graph path "<from>" "<to>" [page]`
  - 分页输出节点序列（每页 10 条）
  - 玩家端会把节点坐标做成可点击 `/tp x y z`，便于定位牌子/咽喉/道岔附近的轨道区域

注意：这里的坐标来自图快照记录的节点坐标（通常是牌子方块坐标），不保证一定落在轨道方块上，仅用于诊断定位。

### 连通分量统计（component）

- `/fta graph component "<nodeId>"`
  - 输出：seed 所在连通分量的 nodes/edges 数量，以及其中 blocked_edges 数量
  - 常用于排查“为什么两点不可达”：先确认是否在同一连通分量，再回到 build 的区块加载/漏扫问题

### 权限

- `fetarute.graph.query`：访问 query/path/component
- `fetarute.graph.edge`：访问 edge speed/restrict/block

## 常见问题：SWITCHER 节点异常增多

若你在旧版本中发现大量“莫名其妙的 SWITCHER”：

- 请确认 TrainCarts 的 `[train] tag` 牌子不会被当作 switcher（当前实现只识别 `[train] switcher`）
- 自动 switcher 的判定基于“真实连通邻居数”（`neighbors >= 3`）；若轨道实现返回的邻居异常，也可能导致噪声节点

## 常见问题：拆牌后仍提示节点 ID 冲突

若你确认原牌子已经不存在，但重放同一个 `nodeId` 仍提示 conflict，通常原因是“非正常移除”导致注册表/存储残留（例如 WorldEdit/setblock、依附方块破坏的时序差异等）。

建议按以下步骤排查：

1) 开启调试：`config.yml` 中 `debug.enabled: true`，然后 `/fta reload`
2) 使用 conflict 提示中的坐标传送过去，确认该位置是否仍存在节点牌子
3) 若该位置区块此前未加载：先靠近/传送使其加载，再次尝试建牌触发自愈清理

## 快速写入节点牌子

若你已经摆好了牌子方块但不想手动输入长 nodeId，可对准该牌子后执行：

- `/fta graph sign set waypoint <nodeId>`
- `/fta graph sign set autostation <nodeId>`
- `/fta graph sign set depot <nodeId>`

该命令会把牌子前面写成 TrainCarts 格式（`[train]` + `action` + `nodeId`），随后即可用 `/fta graph build` 扫描并建图。
