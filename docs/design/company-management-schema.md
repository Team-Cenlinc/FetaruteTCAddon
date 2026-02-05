# 铁路公司管理数据模型草案

## 设计目标
- 为 Company → Operator → Line → Route/Station 的组织结构提供稳定主数据来源。
- 通过 DAO/Repository 抽象解耦业务逻辑与底层数据库，使 SQLite / MySQL / 其他实现可替换。
- 提供扩展点支撑财务、成员权限、调度图绑定与 route/站点映射。
- 每个实体同时保留“系统 ID（UUID）+ 人类可读 code/slug”，命令行或 API 可直接使用短 code 锁定目标。

## 核心实体与字段
> `id` 推荐使用 UUID（文本或 BINARY16），如与外部系统对接可改成雪花或自增，但需在 DAO 层统一。

### PlayerIdentity
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Identity 主键，供其他表引用 |
| player_uuid | UUID | Bukkit 报告的 UUID，可为离线/在线模式 |
| name | VARCHAR | 当前玩家名 |
| auth_type | ENUM | ONLINE / OFFLINE / MANAGED 等，标记身份来源 |
| external_ref | VARCHAR | 绑定外部账户（Web/Discord），可空 |
| metadata | JSON | 附加属性（历史别名等） |
| created_at / updated_at | TIMESTAMP | 审计 |
> 离线 / 在线模式切换时只需更新 Identity，引用该 ID 的业务数据无须迁移。

### Company
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Company 主键 |
| code | VARCHAR | 人类可读短 ID（命令、API 使用），全局唯一 |
| name | VARCHAR | 主显示名称（必填） |
| secondary_name | VARCHAR | 第二语言展示名，可为空 |
| owner_identity_id | UUID | 归属玩家身份（外键 → `PlayerIdentity`） |
| status | ENUM | ACTIVE/SUSPENDED/DELETED |
| balance_minor | BIGINT | 账户余额（以分为单位，便于 Vault 适配） |
| metadata | JSON | 描述/区域/联系方式等自定义属性 |
| created_at / updated_at | TIMESTAMP | 审计字段 |

### CompanyMember
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| company_id | UUID | 外键 → Company |
| player_identity_id | UUID | 外键 → `PlayerIdentity` |
| roles | JSON | 角色集合，枚举值包括 OWNER/MANAGER/STAFF/VIEWER/DRIVER |
| joined_at | TIMESTAMP | 加入时间 |
| permissions | JSON | 细粒度权限开关（可选） |
> 主键建议 `(company_id, player_identity_id)`，`roles` 字段可存 `['MANAGER','DRIVER']` 以支持多角色。

### CompanyMemberInvite
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| company_id | UUID | 外键 → Company |
| player_identity_id | UUID | 外键 → `PlayerIdentity` |
| roles | JSON | 邀请时附带的角色集合 |
| invited_by_identity_id | UUID | 邀请发起人身份 |
| invited_at | TIMESTAMP | 邀请时间 |
> 用于实现“邀请需确认”流程；接受后写入 CompanyMember 并删除邀请记录。

### Operator
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Operator 主键 |
| code | VARCHAR | 人类可读短 ID，Company 维度唯一 |
| company_id | UUID | 归属公司 |
| name / secondary_name | VARCHAR | 主/次名称 |
| color_theme | VARCHAR | MiniMessage 颜色或 HEX |
| priority | INT | 调度优先级，数字越小越优先 |
| description | TEXT | 简介 |
| metadata | JSON | 扩展字段 |
| created_at / updated_at | TIMESTAMP | 审计 |

### Line
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Line 主键 |
| code | VARCHAR | 线路编号（1/NE 等），在同一 Operator 下唯一 |
| operator_id | UUID | 归属运营商 |
| name / secondary_name | VARCHAR | 展示名称 |
| service_type | ENUM | METRO/REGIONAL/COMMUTER/LRT/EXPRESS… |
| color | VARCHAR | 线路色（HEX/MiniMessage） |
| status | ENUM | PLANNING/ACTIVE/MAINTENANCE |
| spawn_freq_baseline_sec | INT | 基准发车间隔，可选 |
| metadata | JSON | 额外信息（自动广播、首末班等） |
| created_at / updated_at | TIMESTAMP | 审计 |

### Station
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Station 主键 |
| code | VARCHAR | 站代码（拼音/缩写），Operator 范围内唯一 |
| operator_id | UUID | 归属运营商（或再加 `company_id` 缓存） |
| primary_line_id | UUID | 主线路（可为 NULL 表示换乘站） |
| name / secondary_name | VARCHAR | 名称 |
| world | VARCHAR | 所在世界 |
| x / y / z / yaw / pitch | DOUBLE | 方块空间位置（供 API/地图用） |
| graph_node_id | VARCHAR | 对应 `RailNode` ID，便于调度绑定 |
| amenities | JSON | 设施、售票、换乘说明 |
| metadata | JSON | 扩展 |
| created_at / updated_at | TIMESTAMP | 审计 |
> 若站点通过首个 Track 牌子进行定位，可在同步流程中读取 Sign 坐标更新上述位置字段。

### Route
| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | UUID | Route 主键 |
| code | VARCHAR | Route 短 ID（如 `EXP01`），在 Line 下唯一 |
| line_id | UUID | 归属线路 |
| name / secondary_name | VARCHAR | 名称 |
| pattern_type | ENUM | LOCAL/SEMI_EXPRESS/EXPRESS/LIMITED_EXPRESS |
| operation_type | ENUM | OPERATION/RETURN |
| tc_route_id | VARCHAR | （可选）映射到 TrainCarts route / destination，用于兼容旧系统 |
| distance_m | INT | 预估线路长度（可选） |
| runtime_secs | INT | 全程运行时间（可选） |
| metadata | JSON | 调度附加信息（车次号、运营时段） |

> Route metadata 常用键：
> - `spawn_train_pattern`：TrainCarts SpawnableGroup 字符串，用于 `/fta depot spawn` 的默认车型/编组。

#### RouteStop（关联 Route 与 Station / Waypoint）
| 字段 | 类型 |
| --- | --- |
| route_id | UUID |
| sequence | INT |
| station_id | UUID | 若为真实站点 |
| waypoint_node_id | VARCHAR | 对应非站点节点，如库/折返，二选一 |
| dwell_secs | INT |
| pass_type | ENUM | STOP/PASS/TERMINATE |
| notes | VARCHAR |
> 主键 `(route_id, sequence)`，并在 DAO 层提供顺序重排工具。

### Code 与命令解析
- 所有含 `code` 的字段需建立唯一索引（Company/Operator/Line/Station/Route），作用域如表格所述（全局或上级实体范围）。
- 命令解析优先按 `code` 命中，未找到时再接受 UUID；REST / RPC API 返回 `id + code`，方便客户端缓存。
- 若未来需要多别名，可新增 `<entity>_aliases` 表：`id`, `entity_id`, `alias`, `scope`，命令解析按 alias 回退。

### Financial Records（可选扩展）
- `company_accounts`：拆分多货币或虚拟/现实账户。
- `company_transactions`：`id`, `company_id`, `type`, `amount`, `currency`, `source`, `note`, `created_at`。

## DAO / Repository 抽象
```
interface CompanyRepository {
    Optional<Company> findById(UUID id);
    List<Company> listAll();
    Company save(Company company);
    void delete(UUID id);
}
```
- 每个实体有对应 Repository，入参与返回均使用不可变数据模型。
- `StorageProvider` 工厂根据配置加载具体实现（SQLite/MySQL/Mock）。
- 事务通过 `StorageTransaction` 抽象暴露，SQLite 初期实现可用单连接 + `BEGIN IMMEDIATE`，未来换数据源仅替换实现。
- `CompanyService` 等领域服务组合多个 Repository，处理业务校验（余额扣除、成员邀请等）。
- 连接池统一使用 HikariCP：SQLite 强制单连接池，MySQL 可按 `storage.pool.*`（最大连接数/超时/生命周期）调整，若驱动或连接失败将回退为占位存储并抛出 StorageException。

### 连接池与配置
- `storage.pool.maximum-pool-size`：最大连接数，SQLite 会自动收敛为 1，MySQL 可按并发适当调高（默认 5）。
- `storage.pool.connection-timeout-ms`：获取连接超时（默认 30 秒），避免线程长时间阻塞。
- `storage.pool.idle-timeout-ms`：空闲连接回收时间（默认 10 分钟）。
- `storage.pool.max-lifetime-ms`：连接最大生命周期（默认 30 分钟），防止 MySQL 端断开造成僵尸连接。
- SQLite JDBC URL 形如 `jdbc:sqlite:<dataFolder>/data/fetarute.sqlite`，MySQL JDBC URL 形如 `jdbc:mysql://host:port/db?...`，均由 Hikari 工厂生成。

## 序列化 / DTO
- 内部模型：Java 记录或 Lombok-less 数据类，字段均非 null，使用 `Optional` 暴露缺省值。
- API DTO：由 `RailwayCompanyDtoMapper` 转换，确保对外 JSON 序列化与内部模型解耦。
- 多语言字段：`name` + `secondaryName` 方案，若未来需要更多语言，可在 metadata 中放 `Map<Locale, String>`。
- Line metadata 常用键：
  - `spawn_depots`：线路可用 Depot 池（字符串或对象列表，支持 `weight/enabled`）。
  - `spawn_max_trains`：线路最大车数（可选）。

## 与调度图的衔接
- Station `graph_node_id` 指向 `RailNode`，RouteStop 通过 station 或 waypoint node 映射到 `RailGraph`。
- Line 可绑定一组 `EdgeId` 或 `GraphSegmentId`（存于 metadata），便于 API 导出线路路径。
- Operator/Company 可通过服务接口查询旗下所有节点，用于封锁与安全检查。

## 运行时控制策略
- 调度层每当列车抵达 `RouteStop` 时，解析 RouteStop 元数据（包含特殊标记、动态站台配置），计算下一目标节点，再通过 `TrainProperties.setDestination(nodeId)` 或 PathFinder API 告诉 TrainCarts 要去的 waypoint，TC 负责底层寻路。
- 由于逐节点更新 destination，`tc_route_id` 字段仅作兼容或导出使用，不再依赖 TrainCarts 的 DestinationRoute 列表；RouteStop 可携带 `CHANGE_LINE`、`DYNAMIC_PLATFORM` 等自定义标记，详见 `docs/design/route-stop-markers.md`。
- 列车属性（MetaTag）需缓存 `routeId`, `routeCode`, `nextSequence` 等信息，跨服或重载时可恢复调度上下文。

## 跨服/实例同步
- 多服务器共享 `Route`/`RouteStop` 数据时，以 `code` 作为跨服识别键，`tc_route_id` 作为 TrainCarts 实例的折返名称；无论在哪个服 spawn 列车，只需携带 `routeCode + routeId` 就能查询到完整停靠表与下一目标节点。
- 列车跨服交接时，序列化 `RouteProgress`（当前 routeId、sequence、下一个 stop 的 nodeId），目标服务器读取后按照相同 Route 数据继续写 destination。
- 如需要“子服务器自知要把列车引导到哪里”，可传递 `nextStop.graphNodeId` 或 `tc_route_id`，目标服在接管时将该节点设置为 TrainCarts destination，后续停靠再由共享的 RouteStop 列表决定。
- 所有实例的 DAO/StorageProvider 应指向同一数据库或通过消息总线同步，确保 route 名称与结构保持一致。

## 后续扩展点
1. **审批流**：当前使用 `company_member_invites` 记录邀请；若需审批状态可在此表扩展 status 字段或新增申请表。
2. **资产管理**：车辆、车厂、调度权限等实体均可通过 `metadata` 拓展，再拆分独立表。
3. **审计日志**：统一 `audit_log`，记录谁在何时修改了实体。
4. **缓存**：在内存中维持只读快照（Guava Cache 或自研）以减少 DB 读；写操作后广播失效事件。

## 迁移策略
- 采用 Liquibase/Flyway 或自研 `schema_version` 表管理迁移。
- 单元测试中提供 `InMemoryStorageProvider`（如 H2/SQLite 内存模式）确保 DAO 行为一致。
- 未来切换 MySQL：实现新的 `SqlDataSourceFactory`，依赖 HikariCP，DAO 层沿用相同 SQL（使用标准 ANSI 语法）。
