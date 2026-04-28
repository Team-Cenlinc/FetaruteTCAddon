# 车站区间限速设计

## 目标

业务层面以“车站区间”管理限速，例如从 A 站到 B 站设置临时慢行；底层仍展开为调度图中的 `rail_edge_overrides`。这样运营人员面对的是线路语义，而运行时、ETA 与控车层继续复用已经实现的 edge override 口径。

## 分层原则

- `section` 是运维视图，不是新的速度来源。
- 写入时把 `<fromNodeId> -> <toNodeId>` 解析为当前 `RailGraph` 最短路径，并对路径上的每条 `RailEdge` 写入或清理 `rail_edge_overrides`。
- 运行时仍按“图默认速度 / edge base speed / edge override / 临时 restriction / component caution”取有效速度。
- 限速不会改变 `CONFLICT` 资源、单线走廊划分或可通行性；封锁才会影响寻径与放行。

## 已实现命令（第一阶段）

```text
/fta speed section preview <fromNodeId> <toNodeId> [speed] [ttl]
/fta speed section set <fromNodeId> <toNodeId> <speed> [ttl]
/fta speed section clear <fromNodeId> <toNodeId>
/fta speed section list [page]
/fta speed section list node <nodeId> [page]
/fta speed stick give <speed> [ttl]
```

- `speed` 复用现有 edge speed 单位：`80kmh`、`80km/h`、`8bps`、`0.4bpt`。
- `ttl` 复用现有临时限速语义：`90s`、`5m`、`1h30m`、`1d`。
- Cloud 补全会优先提示常用速度：`25kmh`、`40kmh`、`60kmh`、`80kmh`、`8bps`、`0.4bpt`。
- Cloud 补全会优先提示常用 TTL：`90s`、`5m`、`15m`、`30m`、`1h`。
- `preview` 只展开路径和显示将写入的 edge，不落库。
- `preview` 会输出摘要与每条展开 edge，并提供点击动作：
  - `[应用]`：只填充 `/fta speed section set ...`，需要运维确认后回车。
  - `[清除]`：只填充 `/fta speed section clear ...`，需要运维确认后回车。
  - `[详情]`：执行只读 `/fta graph edge get ...` 或 `/fta graph edge get path ...`。
- `list` 每行提供 `[详情]` 与 `[清除]`；`[清除]` 仍只是填充命令，不会直接执行。
- 点击动作中的 nodeId 会统一加双引号；手动输入时也建议给 nodeId 加引号，避免冒号、空格或中文文本被命令解析拆开。
- `stick give` 会发放一根独立的“限速设置棍”，携带预设 `speed` 与可选 `ttl`：
  - 左键第一个节点牌子或其对应轨道选择起点。
  - 左键第二个节点牌子或其对应轨道后，按当前调度图最短路径直接应用，与 `/fta speed section set ...` 写入同一批 `rail_edge_overrides`。
  - 右键清除当前选点，不会执行写入。
  - 写入成功后输出 `[详情]` 与 `[清除]`；`[清除]` 只是填充命令，仍需回车确认。
- 第一阶段采用位置参数而不是 Cloud flag；`set` 不要求 `--confirm`，运维前应先执行 `preview` 核对展开路径。
- `list` 读取当前世界中生效的 edge speed override，因此会同时显示 `/fta graph edge speed ...` 和 `/fta speed section ...` 写入的覆盖。

## 后续命令形态

后续如果需要更强防误操作能力，可以把写入命令扩展为：

```text
/fta speed section set <fromNodeId> <toNodeId> <speed> [--ttl <duration>] [--confirm]
/fta speed section clear <fromNodeId> <toNodeId> [--confirm]
/fta speed section list [--node <nodeId>] [page]
/fta speed section preview <fromNodeId> <toNodeId> [--speed <speed>] [--ttl <duration>]
```

## Path 解析与写入

1. 根据 `fromNodeId` 所在世界读取内存图快照。
2. 校验 `fromNodeId` 与 `toNodeId` 均存在且属于同一世界/同一图快照。
3. 使用现有 `RailGraphPathFinder` 求最短路径。
4. 将路径节点对转换为 `EdgeId` 列表。
5. 对每条 edge 写入或清理 `rail_edge_overrides`。

若路径不唯一，第一阶段使用当前 cost model 的稳定最短路径，并在 `preview` 输出 hops、distance、edge count。后续若需要运营选择具体路径，可以扩展为候选路径列表或通过中间点参数约束。

## 异常处理

- 图快照缺失：拒绝执行，提示先 `/fta graph build`。
- 图 stale：内存快照不存在时会拒绝写入；要求先重建图，避免把限速写到即将变成 orphan 的 edge。
- 节点缺失：拒绝执行，并提示检查牌子注册、`rail_nodes` 与当前世界加载状态。
- 路径不可达：拒绝执行，输出起终点与当前连通分量信息。
- TTL 到期：沿用 edge override 的临时限速失效机制；失效后不应残留为永久限速。

## 与现有命令衔接

- `/fta graph edge speed path ...` 是 edge/path 级底层入口。
- `/fta speed section ...` 是业务区间入口，最终仍写同一批 `rail_edge_overrides`。
- `/fta speed stick give ...` 是玩家现场操作入口，底层仍复用 `section` 最短路展开与 edge override 写入口径；它不是新的速度来源。
- 线路改造、节点改名或图重建后，运维应使用 `/fta graph edge overrides orphan list|cleanup` 检查孤儿 override。
- 第一阶段尚未保存独立的 section 审计记录；CSV/审计视图应以后续 `section key + edge list + 操作者 + TTL` 记录实现。

## 后续 TODO

- 为 section 写入保存一份可审计的业务层记录，例如 section key、起终点、操作者、TTL 与展开出的 edge 列表。
- 支持带中间点的 section path，解决复杂换线区路径不唯一的问题。
- 在 ETA 与 HUD 诊断中显示“该 ETA 受 section speed limit 影响”的来源摘要。
