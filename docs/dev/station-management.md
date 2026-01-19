# 站点主数据管理（/fta station）

站点（Station）承载 PIDS/信息屏/停站表解析需要的“可读名称”等主数据。本插件提供 `/fta station` 用于维护站点名称、图节点绑定与可选坐标。

## 显示名约定

- `Station.name` 作为显示名（PIDS/信息屏默认展示）。
- 若由图构建自动创建，`name` 默认等于站点 `code`，可再用 `--name` 覆盖。

## 常用命令

### 列表

`/fta station list <company> <operator>`

### 查看详情

`/fta station info <company> <operator> <station>`

### 清理无用站点（dump）

当线路/牌子已撤销，但数据库残留 Station 记录时，可用 dump 做清理：

`/fta station dump <company> <operator> [--confirm]`

判定为“可清理”的站点需同时满足：

- 不再被任何 RouteStop（stationId）引用；
- 在已加载世界的 `rail_nodes` 中也未发现对应的站点节点（`WaypointKind.STATION`）。

### 修改主数据

`/fta station set <company> <operator> <station> [flags...]`

可用 flags：

- `--name "<name>"`：设置站点名称（显示名）。
- `--secondary "<secondaryName>"`：设置第二语言名称（可选）。
- `--secondary-clear`：清空第二语言名称。
- `--node <nodeId>`：设置绑定的图节点（`graph_node_id`），用于把站点映射到运行图节点。
- `--node-clear`：清空图节点绑定。
- `--here`：将站点位置设置为玩家当前位置（world + x/y/z/yaw/pitch）。
- `--location-clear`：清空站点位置（world/location）。

## 权限与校验

- 命令会检查公司管理权限：公司 Owner/Manager 或 `fetarute.admin` 才能执行 `set`/`dump`。
- `list/info` 需要具备公司读取权限（公司成员或管理员）。

## 自动创建（graph build）

执行 `/fta graph build` 后，会异步扫描构建结果中的站点类节点（`WaypointKind.STATION`），并自动创建/补全 Station 记录：

- 新建时默认 `name=code`（可后续用 `/fta station set --name` 修改）
- 不覆盖既有 name/secondary，仅补全缺失的 `graph_node_id` / `world` / `location`
