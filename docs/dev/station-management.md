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

## 车站组（/fta station group）

车站组是乘客视角的一座换乘站，成员是车站记录，可以跨运营商、跨公司（例如 FTA 的 SL 线车站与 SURC 的车站位于同一处）。

- 同一运营商、同一站码的不同股道本来就是同一站，**无需**建组；车站组只用于把不同的车站记录归为同一换乘站。
- 每个车站最多隶属于一个车站组。
- 每个成员标注换乘方式：`SAME_PLATFORM` 同台换乘、`IN_STATION` 站内换乘（默认）、`OUT_OF_STATION` 出站换乘；可选填写步行秒数。
- 组代码在所属公司内唯一（不区分大小写）。多家公司存在同代码的组时，以 `公司代码:组代码` 指定；运营商代码同理可写成 `公司代码:运营商代码`。按代码未命中时也接受 UUID。

### 命令

| 命令 | 说明 |
|------|------|
| `/fta station group create <company> <code> <name> [secondaryName]` | 创建车站组，归属 `<company>` |
| `/fta station group add <groupCode> <operator> <stationCode> [SAME_PLATFORM\|IN_STATION\|OUT_OF_STATION] [walkSecs]` | 加入成员；车站已在本组时更新换乘方式与步行秒数（保留原排序）。新成员排在已有成员之后 |
| `/fta station group remove <groupCode> <operator> <stationCode>` | 移除成员 |
| `/fta station group info <groupCode>` | 列出成员、各成员的停靠线路与换乘方式 |
| `/fta station group list [company]` | 列出车站组；不指定公司时列出有读取权限的全部公司 |
| `/fta station group delete <groupCode> --confirm` | 删除车站组，成员一并移除 |

所有参数均支持 Tab 补全。`add` 的运营商未写公司前缀时，优先匹配车站组所属公司的运营商。

### 权限

- 车站组属于创建时指定的公司；`create`、`add`、`delete` 需要该公司的管理权限（Owner/Manager 或 `fetarute.admin`）。
- 将**其他公司**的车站加入车站组：当前要求操作者同时具备两家公司的管理权限，否则拒绝，并提示需联系对方公司管理员办理。邀请与确认流程尚未开放。
- `remove`：需要车站组所属公司的管理权限。
- `info`、`list`：需要车站组所属公司的读取权限。

### 数据与联动

- 数据表：`fta_station_groups`（组）与 `fta_station_group_members`（成员，`station_id` 全局唯一），见 [schema 设计](../design/company-management-schema.md)。
- 删除公司、运营商、车站或车站组时，相关成员与车站组由仓库在同一事务内显式删除，不依赖数据库外键级联。
- 车站组、车站、线路、运营商的改动会立即刷新车站目录（内存索引）：公开 API 的 `StationApi#linesServing`、`findGroupOfNode` 等查询随即返回新数据，`FetaruteApi#dataRevision()` 递增；车站组变化还会在下一 tick 发出 `StationGroupChangedEvent`。详见 [公开 API](api.md) 的“车站组与停靠线路”一节。
- 停靠线路只统计 `STOP` 与 `TERMINATE`，DYNAMIC 停靠计入其所在车站；出库、回库、运营各阶段的交路均计入。
