# 时刻表编制的作用域与跨运营商冲突仲裁——设计交接

本文是**设计遍**的产物，读者是实现遍。判定标准：实现者遇到任何需要做选择的地方，都应该能在这里查到答案；
查不到就是本文漏了，请补本文而不是现场猜。本文不含实现代码，但给到签名、文件清单与测试清单的粒度。

基线：分支 `wip/dynamic-dispatch-stabilization`，HEAD `df3b838`，全量 `clean check` 绿（215 suites / 1975 tests）。
时刻表现状见 `docs/dev/timetable.md`；本文只写增量。

---

## 0. 结论速览

| 题 | 决策（一句话） |
| --- | --- |
| A1 | **解耦**。build 单位仍是 line；冲突检查作用域是「资源足迹相交的已发布邻表」，与 line/operator/company 无关 |
| A2 | 边界 = **同一世界里、资源足迹与我相交的全部已发布时刻表**。不是连通分量：冲突是成对发生在资源上的，第三方与我不共资源就不可能与我冲突，不需要传递闭包 |
| A3 | 足迹在 build 时由 `prepare` 已经算出的逐边路径展开，不缓存、不落库；邻表的足迹在 build 时用当前图重新展开路径、用它**落库的时分**定时 |
| A4 | 混合分量只做**报告**：列出共用资源但没有已发布表的线，明说"未做联合排布、干扰单向"；运行时不改 |
| B1 | **先到先得**，写成显式语义：已发布的表是路权事实，后 build/后 publish 的表只能避让它 |
| B2 | 不做联合编表 |
| B3 | publish 时若邻表集合较 build 时有变化就**重检**，有冲突拒绝发布；unpublish/delete 不级联；在跑的车不受影响 |
| B4 | 不变量 2 改写为（确切措辞见 §2.4）："同一份网络状态 + 同一份配置 + **同一组已发布邻表**（以 id 与 updatedAt 逐一标识）永远产出同一张表" |
| C1 | 不引入"基础设施管理者"。仲裁者就是**发布顺序**；图是世界维的物理事实，任何 operator 的表都在同一张图上避让 |
| C2 | `fta_operators.priority` **不参与**编表。它是运行时占用排队的点数优势，编表期没有队列可排 |
| C3 | 沿用 `fetarute.timetable.manage` + `canManageCompany`；读邻表不需要新权限（已发布 = 路网公开事实，只显示 code 与时刻）；新增只读子命令 `neighbors` |
| C4 | 跨 company 与跨 operator **同一套答案**；只是报告里把对方标成 `company/operator/line/code` |
| D1 | 绑不上车次必须留痕：`TIMETABLE_ASSIGN_MISS` 带最近车次与偏差；status 加计数 |
| D2 | 最小不静默补丁 = 阶段 S1：build 报告"与 XX 线共用 N 个资源、未做联合排布"，只算足迹不投影运行 |
| D3 | 区分内部/外部冲突；外部冲突列出对方 code，建议只有四种：改 headway（已自动）/ 改 separation / 换股道 / 找对方运营方 |
| E（直通运转） | 一条 route 可以跑到别的 operator 甚至别的 company 的资源上；出库/回库线路可用 route metadata 显式指定跨 operator 的 CREATE/RETURN；ReclaimManager 兜底改为按终点全局找 RETURN。**不支持**一辆车跨两条线的表接班 |

---

## 1. 术语

- **资源键**：`TimetableConflictChecker` 里的资源标识，形如 `edge:a~b`、`platform:节点`、`platform-group:OP:S:NAME`、`single:<section>`、`junction:节点`。
- **足迹**（footprint）：一份时刻表全部 route（含 CREATE/RETURN）路径展开后触及的资源键集合。
- **邻表**（neighbor）：与我足迹相交、处于 PUBLISHED、且在同一世界的其它时刻表。可属于任何 line/operator/company。
- **外部冲突**：一方是我、一方是邻表的冲突。邻表之间的冲突不属于我，不报。
- **路权**：一份表发布后，它的运行在冲突模型里是不可移动的事实。
- **零点**：冲突检查里所有时刻的公共原点 = 我的 `serviceStartSecondOfDay` 在服务日 D 的时刻。
- **基线**（baseline）：build 时读到的邻表集合快照（id + updatedAt），落在 `fta_timetable_baselines`，publish 时用来判断"要不要重检"。

---

## 2. 决策与理由

### 2.1 A 作用域

**决策**：build 单位 = line（不变）；检查作用域 = 足迹相交的已发布邻表（世界维、跨 operator/company）。

**理由**：
- 冲突是成对发生在某个资源上的。若 C 只与 B 共资源、不与我共资源，C 的任何运行都不可能与我的运行在同一资源上重叠。
  所以"分量"退化成"直接相交"，不需要传递闭包，也就不需要缓存一张全网的分量图。
- 足迹用逐边路径而不是申报路径点：prompt 里 MT/DS 的 18 个共用点是下界，真实共用面在两站之间的走行边上。
  `prepare` 已经为每条 route 算出 `SegmentTiming.edges()`，足迹就是它的投影，零额外代价。
- 邻表的足迹在 build 时重算：`TimetableRoutePlan` 只落了站间时分没落路径。用当前图对邻表的 route 重新寻路得到边，
  用它落库的 `TimetableStop` 偏移定时（那是它实际在跑的时刻），逐边时刻在每个区段内按重算路径等比分摊。
  若重算出的站间时分与落库值不一致，说明邻表基于旧图，标 `staleAgainstGraph=true` 并在报告里警告，但仍然参与检查。

**反例（会让它失效的场景）**：
1. 两条线不共任何资源，但都要过同一个道岔的**相邻两条边**，制动距离让运行时授权窗口覆盖到对方的边。
   足迹不相交，本模型报无冲突，运行时仍可能互相扣停。——仍然选它：这是冲突模型本身的边界（不建模授权窗口与制动距离），
   不是作用域的问题；`docs/dev/timetable.md` 已写明。回放校验（阶段 8）是发现这类漏项的地方。
2. 邻表基于旧图，其某条 route 在当前图上已不可达：重算路径失败。——处理：该 route 的足迹按空处理、报告里列为"邻表 X 的 route Y 在当前图上不可达"，不阻塞我的 build。

**A4 混合分量**：只报告。理由：headway 出票的线没有"表定运行"可投影，任何猜测都会把不确定性伪装成结论。
运行时不改：`TimetableSpawnManager` 只拦有表 route 的 headway 票，这条现状保留。

### 2.2 B 确定性 vs 先来后到

**决策**：先到先得，显式语义。不做联合编表。

**理由**：
- 联合编表要求能挪动别人的运行，而别人的表属于别的 operator/company，本插件的权限模型里没有任何人有这个权力（管理员除外，但管理员挪动也要对方重发布）。
- 联合编表的"结果唯一"需要一个全网的总目标函数（谁先谁后、谁让谁），那正是 C 题里没有的仲裁者。
- 先到先得下不变量 2 仍可检验：把邻表集合当作显式输入并落库，"同样输入同样输出"就在更大的输入上成立。

**反例**：两条线互为邻表，A 先发布把最紧的间隔用掉，B 只能放宽到 2 倍。运营方觉得 B 更重要。——仍然选它：解法在运营流程，
不在算法：unpublish A → build B → publish B → rebuild A → publish A。B3 的级联规则保证这条流程每一步都有明确结果。
"更重要"这件事本插件没有量可以表示（见 C2），任何自动仲裁都会把这个判断藏进代码里。

**B3 级联**：
- `build`：读当前 PUBLISHED 邻表，避让它们，把基线落库。
- `publish`：重新读 PUBLISHED 邻表；若集合（id + updatedAt）与基线完全一致 → 直接发布；否则用同一套投影对我的表做一次冲突检查，
  有外部冲突 → **拒绝发布**，列出冲突，提示 `build` 重编；无冲突 → 更新基线并发布。没有 `--force`。
- `unpublish` / `delete`：不级联。别人的表是避让着我编的，我消失只会让约束变少，它们仍然可行。
- 在跑的车：表是计划，运行时按各自的表扣留与出票；发布切换只影响下一次出票与绑定。

### 2.3 C 跨 operator / company

**C1**：不引入新概念。图是世界维的，资源不按 operator 隔离（前缀只是命名空间），所以"谁有权编排"这个问题的答案是：
每个 operator 编排自己的 line，冲突由发布顺序仲裁。将来若要引入路网管理者，插入点是 `TimetableNeighborhoodLoader`
（决定哪些表算邻表、以什么顺序算路权）——本轮不做，也不留半成品接口。

**C2**：`fta_operators.priority` 现有语义（已读代码确认）：`SchedulePlanner` 把它作为 ServiceTrip 的 priority
（route metadata 可覆盖），`DispatchPriorityResolver` 把它作为占用请求的基础优先级，`SimpleOccupancyManager` 把它换算成
排队点数优势（`QUEUE_PRIORITY_POINT_ADVANTAGE`）。它回答的是"两辆车此刻抢同一资源谁先走"，是运行时量。
编表期没有队列，把它拿来决定"谁的表可以挪谁的表"会把运行时让路语义偷换成路权语义。**不用**。
缺的东西是"路权等级"，而本文的决策是用发布顺序代替它，所以不需要补。

**C3**：`fetarute.timetable.manage` 今天 = 命令级权限节点 + `CompanyAccessChecker.canManageCompany`（公司成员角色或 `fetarute.admin`）。
粒度是 **company**：同 company 内跨 operator 已经能编。跨 company 不需要新权限：我只读对方已发布的表，不写它。
新增只读子命令 `/fta timetable neighbors <company> <operator> <line> <code>`，权限 `fetarute.timetable` + `canReadCompany(我的公司)`。
报告里对方只显示 `company/operator/line/code`、共用资源数、冲突时刻与资源键，不显示对方 route 的停靠明细。

**C4**：同一套答案。差别只在显示。

### 2.4 B4 不变量 2 的确切措辞

替换 `docs/dev/timetable.md` 三条不变量里第一段"同一份网络状态 + 同一份配置，永远产出同一张表"为：

> **同一份网络状态 + 同一份配置 + 同一组已发布邻表，永远产出同一张表，包括 trip/duty 主键。**
> 邻表集合是 build 的显式输入而不是隐含状态：报告列出它，`fta_timetable_baselines` 落库它（每条邻表以 `timetableId + updatedAt` 标识）。
> 换一组邻表可以换出不同的表，这是路权先到先得的定义，不是不确定性。

### 2.5 E 直通运转（跨 line / operator / company 的 route）

现状已确认：
- RouteStop 引用车站走 `findByOperatorAndCode(自己的 operator)`；引用别的 operator 的站台只能写裸节点 id（`OP2:S:XXX:1`）或 DYNAMIC 串。
  这条不改：直通 route 用裸节点 id 定义，`RouteDefinitionCache` 与 `TimetableTimingCalculator` 对命名空间无感。
- 冲突足迹自然覆盖外方资源：直通 route 的路径边、外方站台、外方单线区段都在我的足迹里，外方的已发布表就是我的邻表。§2.1 已覆盖。
- 缺口一：出库/回库线路。`collectRoutes` 只在本 operator 全部线路里找 CREATE/RETURN；直通 route 的终点在外方，
  没有本 operator 的 RETURN 从那里回库，班次会被 `NO_RETURN_ACCESS` 取消。
- 缺口二：运行时兜底。`ReclaimManager#assignReturnTicket` 只在列车 `FTA_OPERATOR_CODE` 那个 operator 的线路里找 RETURN，
  直通车若滞留在外方终点，兜底回收找不到线路（日志 `回收失败: 未找到匹配 terminal`）。

**决策**：
- E1 route metadata 新增两个键：`timetable_create_route`、`timetable_return_route`，值为 `<company>/<operator>/<line>/<route>`，
  允许跨 operator 与跨 company。build 时它们与本 operator 全线搜索**并集**进入 `Legs`；显式指定的优先（同一站有多条时先取指定的，再按走行最短）。
  被引用的 route 必须是对应类型（CREATE/RETURN），否则报 InfeasibleRoute 原因 `指定的出库/回库线路类型不符`。
- E2 被引用线路必须本身是可发车服务（它所在 line 配了 depot 与 spawn 开关），否则运行时 `TIMETABLE_SPAWN_SKIP reason=no-spawn-service`——这是现有行为，报告里在 build 时就预警：
  `TimetableBuildAssembler` 检查 `SpawnPlan` 快照里有没有该 route 的服务，没有就加警告。
- E3 `ReclaimManager` 的 RETURN 搜索改为：先本 operator，再全部 operator，首站按站点 code / 裸节点 id / DYNAMIC 规范三种写法都能匹配。
  **已实现**（提前于 S4，与 S1 一起交付）。同时加了滞留兜底：该回收却派不出 RETURN 票的车滞留超过 `reclaim.stranded-destroy-seconds`
  （默认 1800，0 关闭）就销毁，有乘客或有进行中折返事务的不碰——健康监控把待命车排除在清除之外，没有这条兜底的话直通车会永远留在外方终点。
  这条为此新增了配置键，`config-version` 32→33（§3.2 与 §9 相应更新）。
- E4 车辆归属：外方 CREATE 实体化的车先带外方 line/operator 标签，接我的运营票时 `applyDispatchLifecycleTags` 会改写成我的 route；交路绑定（`DutyKey`）在派发回调里建立，与标签无关。**不需要**新逻辑。
- E5 **不支持**"一辆车跨两条线的表接班"（duty 里混两份表的 trip）。直通运转的正确表达是"一条 route 属于一条 line，路径跑到别人的资源上"。
  反例：运营方想让 WS 的车到 C 站后直接接 MT 的班。——不做：duty 是构建产物、终止性在产物上断言，跨表的 duty 没有单一的构建者。
- E6 跨 company 使用别人的轨道没有许可模型（图是世界维的、物理上本来就能开过去）。冲突仍由发布顺序仲裁。
  **不要**在本轮加 `timetable_guests` 之类的准入白名单——那是 C1 里"路网管理者"的一部分，与本轮决策冲突。

### 2.6 D 失败模式与可观测性

- D1 `TimetableService.assign` 在 `best == null` 时目前无声返回。新增日志
  `TIMETABLE_ASSIGN_MISS train=<t> route=<uuid> stopIndex=<i> nearest=<tripCode>@<deviationSeconds> tolerance=<s> candidates=<n> reason=<out-of-tolerance|no-trips|all-claimed>`，
  按 `train + stopIndex + reason` 节流（同一组合 60 秒内只记一次）。`StatusSnapshot` 增加 `assignMisses`（自启动累计）。
  这样"跨线干扰 → 晚点 → 退回自由运行"至少能在日志里读出 `out-of-tolerance` 与偏差值。
- D2 阶段 S1 就交付"足迹报告"：build 报告新增一节
  `共用资源: MT-2026A（SURC/MT）共用 31 个资源，已发布，将参与冲突检查` / `DS（SURC/DS）共用 12 个资源，无已发布时刻表，未做联合排布——它按 headway 发车，干扰单向`。
- D3 冲突报告分两节：`内部冲突` 与 `外部冲突（与已发布邻表）`。外部冲突每条带对方 `company/operator/line/code` 与对方运行代号。
  建议固定四条：目标间隔已自动放宽到 Xs / 加大 `--separation` / 换股道（改 DYNAMIC 范围或站台）/ 与 XX 运营方协商由其 unpublish 重编。

---

## 3. 数据模型与命令面

### 3.1 新表（本项目无 schema 迁移：**新表**安全，`CREATE TABLE IF NOT EXISTS` 会建；**不改任何既有表的列**）

```sql
CREATE TABLE IF NOT EXISTS fta_timetable_baselines (
    timetable_id          <uuid> NOT NULL,
    neighbor_timetable_id <uuid> NOT NULL,
    neighbor_code         <string> NOT NULL,    -- company/operator/line/code，供报告与 neighbors 命令显示
    neighbor_updated_at   <timestamp> NOT NULL,
    shared_resources      <int> NOT NULL,       -- 足迹交集大小
    conflicts_at_target   <int> NOT NULL,       -- build 时目标间隔下与该邻表的外部冲突数
    stale_against_graph   <int> NOT NULL,       -- 0/1
    PRIMARY KEY (timetable_id, neighbor_timetable_id),
    FOREIGN KEY (timetable_id) REFERENCES fta_timetables(id) ON DELETE CASCADE
);
```

`StorageSchema` 加 `timetableBaselines(dialect)` 与 `index("timetable_baselines_neighbor", "timetable_baselines", "neighbor_timetable_id")`。
删除时刻表时 `JdbcTimetableRepository#delete` 的 `deleteChildren` 列表加上这张表（与 trips/duties 同理，不依赖外键级联）。
运维影响：**无**（新表自动创建）。

### 3.2 配置

S1 已随滞留兜底新增 `reclaim.stranded-destroy-seconds`（模板里补上了整个 `reclaim:` 段，默认值与解析缺省一致），`config-version` 32→33。
其余阶段不再新增配置键。`ConfigUpdaterTest` 的版本断言读模板，不需要改。

### 3.3 命令

| 命令 | 变化 |
| --- | --- |
| `build` | 报告新增「共用资源」「外部冲突」两节；`--strict` 语义扩展为"任何冲突（内部或外部）即失败" |
| `publish` | 按 §2.2 重检；拒绝时列出外部冲突并提示 `build` |
| `neighbors <company> <operator> <line> <code>`（新） | 只读：列出足迹相交的已发布表与各自共用资源数、基线是否过期（updatedAt 变了）、当前外部冲突数 |
| `info` | 末尾加一行"基线邻表 N 份，其中 M 份已变化" |

权限：`neighbors` 用 `fetarute.timetable` + `canReadCompany`；其余不变。

### 3.4 route metadata 新键（E1）

`timetable_create_route`、`timetable_return_route`：字符串 `<company>/<operator>/<line>/<route>`。写入方式沿用现有 `/fta route meta set`（无需新命令；实现者先确认该命令存在并支持任意键，不存在就在本文补记再实现）。

---

## 4. 逐文件改动清单

标注 [S1]/[S2]/[S3]/[S4] 为所属阶段（见 §7）。

### 新增

| 文件 | 职责（一句话） | 阶段 |
| --- | --- | --- |
| `dispatcher/schedule/timetable/scope/TimetableFootprint.java` | 从 `RouteProfile` 集合算资源键集合；两个足迹求交 | S1 |
| `dispatcher/schedule/timetable/scope/NeighborTimetable.java` | 一份邻表在冲突模型里的形态：profiles + movements + stays + 元信息 | S3 |
| `dispatcher/schedule/timetable/scope/TimetableNeighborhoodLoader.java` | 从存储读 PUBLISHED 表，按世界与足迹筛邻表，投影成 `NeighborTimetable` | S1（只算足迹）→ S3（投影运行） |
| `dispatcher/schedule/timetable/scope/TimetableBaseline.java` | 基线记录 | S3 |
| `dispatcher/schedule/timetable/TimetableOccupancyProjector.java` | 把一份 `Timetable`（trips + duties + profiles）投影成 movements + stays；自己的 attempt 与邻表共用 | S2 |
| `dispatcher/schedule/timetable/TimetableBuildReportText.java` | 报告文案（取消班次、冲突、共用资源）从 builder 抽出 | S2 |
| `dispatcher/schedule/timetable/TripMatcher.java` | 从 `TimetableService` 抽出车次匹配（assign、stillWaitingAtOrigin、claims） | S2 |
| `dispatcher/schedule/timetable/DutyLedger.java` | 从 `TimetableService` 抽出交路进度、交路绑定与两道闸 + acceptsVehicle | S2 |
| `dispatcher/schedule/timetable/TimetableBuildAssembler.java` | 从命令抽出"收集 route / 解析走行线路 / 读邻表 / 组 BuildInput"，不依赖 Bukkit，可测 | S3 |
| 测试：`scope/TimetableFootprintTest`、`scope/TimetableNeighborhoodLoaderTest`、`TimetableOccupancyProjectorTest`、`TimetableBuilderNeighborsTest`、`TripMatcherTest`、`DutyLedgerTest`、`TimetableBuildAssemblerTest` | 见 §6 | 各阶段 |

### 修改

| 文件 | 改动 | 阶段 |
| --- | --- | --- |
| `TimetableConflictChecker.java` | `Movement`/`Stay` 加 `owner`；`Conflict` 加 `firstOwner/secondOwner`；扫描时跳过双外部对；新增 `resourceKeysOf(RouteProfile, GraphIndex)` 供足迹使用（把 `project` 里拼键的逻辑抽成静态 `ResourceKeys`，两处共用） | S1（键）/ S3（owner） |
| `TimetableBuilder.java`（873 行，**必须瘦身**） | 用 `TimetableOccupancyProjector` 替换 `collectDutyOccupancy` 及 movements/stays 组装；文案交给 `TimetableBuildReportText`；`BuildInput` 加 `neighbors`；`Attempt` 检查时合并邻表；结果加外部冲突与基线 | S2 / S3 |
| `TimetableBuildResult.java` | 加 `externalConflictsAtTarget`、`neighbors`（摘要）、`baselines` | S3 |
| `TimetableService.java`（996 行，**必须瘦身**） | 委托 `TripMatcher` 与 `DutyLedger`，本类只剩快照、设置、dueTrips/dueLegs、status；D1 日志与计数在 `TripMatcher` | S1（日志）/ S2（抽出） |
| `repository/TimetableRepository.java` + `JdbcTimetableRepository.java` | `replaceBaselines(UUID, List<TimetableBaseline>)`、`listBaselines(UUID)`；`deleteChildren` 加新表 | S3 |
| `storage/schema/StorageSchema.java` | 新表 DDL + 索引 | S3 |
| `command/FtaTimetableCommand.java` | build 报告两节；publish 重检；`neighbors` 子命令；组装逻辑移到 `TimetableBuildAssembler` | S1 / S3 |
| `dispatcher/runtime/ReclaimManager.java` | RETURN 搜索：先本 operator 再全部 operator | S4 |
| `docs/dev/timetable.md` | 不变量 2 改写（§2.4）；「已知边界」删掉"跨线"那条、加"跨表 duty 不支持"；运行时一节加 D1 日志前缀；命令一节加 `neighbors` | S1 起逐阶段更新 |
| `src/main/resources/plugin.yml` | `neighbors` 归入 `fetarute.timetable`（已有节点，无需新节点；确认 description 提到它） | S3 |

不改：`RuntimeDispatchService`（一行都不加）、`SimpleTicketAssigner`、`TimetableSpawnManager`、`config.yml`、`ConfigManager`。

---

## 5. 完整签名（Javadoc 摘要级，无方法体）

### 5.1 足迹与邻表

```java
package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

/** 一份时刻表触及的资源键集合。键与 TimetableConflictChecker 的资源键同一口径，两边不得各拼一套。 */
public record TimetableFootprint(UUID timetableId, String code, Set<String> resourceKeys) {
  /** 由 route 投影汇总足迹；含 CREATE/RETURN 线路。 */
  public static TimetableFootprint of(
      UUID timetableId, String code,
      Collection<TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index);

  /** 与另一份足迹的交集大小；0 表示不是邻表。 */
  public int sharedWith(TimetableFootprint other);
}

/**
 * 一份邻表在冲突模型里的形态：它的运行已经按我的零点对齐、按服务日 -1/0/+1 展开并裁剪到我的窗口附近。
 * movements/stays 的 owner 都是本表的 displayCode。
 */
public record NeighborTimetable(
    UUID timetableId,
    String displayCode,                 // company/operator/line/code
    Instant updatedAt,
    ZoneId zoneId,
    int sharedResources,
    boolean staleAgainstGraph,          // 某条 route 重算时分与落库值不一致
    boolean zoneApproximated,           // 与我时区不同，按当日偏移换算
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
    List<TimetableConflictChecker.Movement> movements,
    List<TimetableConflictChecker.Stay> stays,
    List<String> warnings) {}           // 例如"route X 在当前图上不可达，足迹按空计"

/** 基线：build 时读到的一份邻表的身份与与它的冲突计数。 */
public record TimetableBaseline(
    UUID timetableId, UUID neighborTimetableId, String neighborCode,
    Instant neighborUpdatedAt, int sharedResources, int conflictsAtTarget, boolean staleAgainstGraph) {}

/**
 * 从存储读已发布时刻表并筛出邻表。只读、无副作用；DB 访问由调用方放在合适的线程上。
 * 决定"谁算邻表"的唯一地方——将来的路网管理者策略在这里插入。
 */
public final class TimetableNeighborhoodLoader {
  public TimetableNeighborhoodLoader(
      TimetableTimingCalculator timingCalculator,
      RailTravelTimeModel travelTimeModel,
      Function<UUID, Optional<RouteDefinition>> routeDefinitions,
      Function<UUID, List<RouteStop>> routeStops,
      Function<UUID, Optional<RouteOwnership>> ownership);   // 解析 company/operator/line code 供显示

  /**
   * 阶段 S1：只算足迹交集，不投影运行。
   * @param excludeLineId 我这条 line：它名下的全部表（含正在重编的旧记录）都不算邻表，见 §5.4
   * @param worldId 我的路径所在世界；不在该世界的表直接排除
   */
  public List<FootprintNeighbor> footprints(
      List<Timetable> published, UUID excludeLineId, UUID worldId,
      RailGraph graph, TimetableConflictChecker.GraphIndex index, TimetableFootprint mine);

  /** 阶段 S3：足迹相交的邻表投影成运行，零点 = 我的 serviceStart，展开服务日 -1/0/+1 并裁剪到 [-3600, horizon+3600]。 */
  public List<NeighborTimetable> project(
      List<Timetable> published, UUID excludeLineId, UUID worldId,
      RailGraph graph, TimetableConflictChecker.GraphIndex index,
      TimetableFootprint mine, int myServiceStartSecondOfDay, int myHorizonSeconds,
      ZoneId myZone, LocalDate referenceDate);

  /**
   * 无表但与我共资源的线：从 route 定义直接展开足迹（它们按 headway 跑，只报告不检查）。
   * @param otherRoutes 本世界里不属于任何 PUBLISHED 表、也不属于我这条 line 的 route（调用方从 provider 收集）
   * @param routeLine 每条 route 归属的 line 显示码，用于把结果按 line 归组
   */
  public List<UnscheduledNeighbor> unscheduled(
      List<RouteDefinition> otherRoutes, Map<UUID, String> routeLine,
      RailGraph graph, TimetableConflictChecker.GraphIndex index, TimetableFootprint mine);

  public record FootprintNeighbor(UUID timetableId, String displayCode, Instant updatedAt, int sharedResources) {}
  public record UnscheduledNeighbor(String displayCode, int sharedResources) {}
  public record RouteOwnership(String companyCode, String operatorCode, String lineCode, String routeCode) {}
}
```

零点换算规则（写进 `project` 的 Javadoc，实现者照抄）：
- 邻表 trip：`rel = departureSecondOfDay + d*86400 − myServiceStart`，d ∈ {−1,0,+1}，再加 `zoneOffsetSeconds`。
- 邻表 duty：`plannedStart/returnSecond/plannedEnd` 已是相对其服务日零点的未取模秒数，同样加 `d*86400 − myServiceStart + zoneOffsetSeconds`。
- `zoneOffsetSeconds` = 我的时区在 `referenceDate` 的零点 − 邻表时区在同一 `referenceDate` 的零点（秒）；时区相同为 0。不同则 `zoneApproximated=true`。
- 裁剪：运行的 `[start, start + 全程]` 与 `[-3600, myHorizon + 3600]` 无交集则丢弃。

### 5.2 投影器（S2，零行为变化）

```java
/**
 * 把一份时刻表（trips + duties）连同各 route 的投影，展开成冲突模型里的运行与站台待命。
 * builder 检查自己的 attempt 和 loader 投影邻表用的是同一个实现，避免两套占用口径。
 */
public final class TimetableOccupancyProjector {
  /** @param zeroSecondOfDay 零点（相对服务日的秒数）；@param owner 空 = 自己 */
  public static Occupancy project(
      Timetable timetable,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      int zeroSecondOfDay,
      Optional<String> owner);

  public record Occupancy(
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {}
}
```

builder 的 `attempt` 改为：先组出 `Timetable`（已有），再 `project(timetable, profiles, options.serviceStartSecondOfDay(), Optional.empty())`。
现有 `collectDutyOccupancy` 删除。`TimetableBuilderTest` 全部用例结果不得变化（这是 S2 的验收）。

### 5.3 checker 改动

```java
public record Movement(String code, UUID routeId, int startSeconds, Optional<String> owner) {
  /** 自己的运行。 */
  public Movement(String code, UUID routeId, int startSeconds);
}
public record Stay(String code, Platform platform, int from, int to, Optional<String> owner) {
  public Stay(String code, Platform platform, int from, int to);
}
public record Conflict(
    Kind kind, String resource, String first, String second,
    int firstFrom, int firstTo, int secondFrom, int secondTo,
    Optional<String> firstOwner, Optional<String> secondOwner) {
  /** 一方是邻表。 */
  public boolean external();
}
public record Report(List<Conflict> conflicts) {
  public List<Conflict> internal();
  public List<Conflict> external();
  public Map<String, Integer> externalByOwner();
}
/** 资源键的唯一出处：project 与足迹都调它。 */
static List<String> resourceKeysOf(RouteProfile profile, GraphIndex index);
```

扫描规则改动只有一条：`first.owner` 与 `second.owner` **都非空**的对不产生 Conflict。

### 5.4 builder 与结果

```java
public record BuildInput(
    UUID timetableId, UUID companyId, UUID operatorId, UUID lineId, String code, String name,
    List<RouteInput> routes, RailGraph graph, RailTravelTimeModel travelTimeModel,
    Optional<String> notes,
    List<NeighborTimetable> neighbors) {             // 新增；旧 10 参构造保留，neighbors = List.of()
}

public record TimetableBuildResult(
    ...既有字段...,
    List<TimetableConflictChecker.Conflict> conflictsAtTarget,   // 含内部与外部
    List<NeighborSummary> neighbors,                              // 新增
    List<TimetableBaseline> baselines,                            // 新增，成功时非空
    List<String> warnings) {
  public List<TimetableConflictChecker.Conflict> externalConflictsAtTarget();
  public record NeighborSummary(String displayCode, int sharedResources, int conflictsAtTarget, boolean stale, boolean scheduled) {}
}
```

`attempt` 里的检查调用变为：`profiles` = 我的 + 全部邻表的（`Map<UUID, RouteProfile>` 按 routeId 合并），
`movements`/`stays` = 我的 + 邻表的，`separation` 不变。`searchFeasibleHeadway` 不变：只重排我的运行，邻表运行在每次 attempt 里原样加入。

routeId 键冲突的唯一来源是"同一 line 的另一份 PUBLISHED 表"（同一条 route 出现在两份表里）。**决策**：同 line 的表与我是替代关系不是并存关系，
一律不作为邻表；`excludeTimetableId` 的语义因此是"排除同 line 的全部表"（loader 按 `lineId` 过滤，而不是按单个 id）。
这样合并 profiles 时不会发生键冲突；若实现中仍出现同 routeId 两份 profile，视为 loader 的 bug 而不是要处理的情况，直接抛 `IllegalStateException`。

### 5.5 TimetableService 拆分（S2，零行为变化）

```java
/** 车次匹配：绑定、起点等点判定、trip 占用。所有 TIMETABLE_ASSIGN* 日志在这里。 */
final class TripMatcher {
  TripMatcher(Consumer<String> debugLogger);
  Optional<TimetableAssignment> match(String key, UUID routeId, List<Timetable> timetables, StationStopEvent event, Settings current, Snapshot snapshot);
  boolean stillWaitingAtOrigin(TimetableAssignment existing, StationStopEvent event, Settings current, Snapshot snapshot);
  Optional<TimetableAssignment> release(String key, String reason);
  void retain(Set<String> keys);  void clear();
  Collection<TimetableAssignment> assignments();
  long assignMisses();                                  // D1 计数
}

/** 交路账本：进度、绑定、两道闸、候选判定。所有 TIMETABLE_DUTY_* / RETURN_DENIED / CANDIDATE_REJECT 日志在这里。 */
final class DutyLedger {
  DutyLedger(Consumer<String> debugLogger);
  void startOrAdvance(String key, Timetable timetable, TimetableTrip trip);
  void bind(String trainName, String key, DutyKey duty, String reason);
  boolean allowsLayoverReuse(String key, String trainName);
  boolean allowsReturn(String key, String trainName);
  boolean acceptsVehicle(TicketIntent intent, String key, String trainName);
  Optional<DutyProgress> progressOf(String key);  Optional<DutyKey> bindingOf(String key);
  void release(String key, String trainName, String reason);  void retain(Set<String> keys);  void clear();
  void dropOutside(Set<UUID> publishedTimetableIds);
}
```

`TimetableService` 公开 API **一个签名都不变**（`FetaruteTCAddon`、`TimetableSpawnManager`、`SimpleTicketAssigner` 的接线不动），内部委托。
`Settings`/`DutyKey`/`TicketIntent`/`DutyProgress`/`DueTrip`/`DueLeg` 仍是 `TimetableService` 的嵌套类型（挪出去会改所有引用，本轮不做）。

### 5.6 命令侧组装（S3）

```java
/** 把命令参数变成 BuildInput：收集 route、解析显式走行线路、读邻表。不依赖 Bukkit。 */
public final class TimetableBuildAssembler {
  public TimetableBuildAssembler(StorageProvider provider, RouteDefinitionCache routes, RailGraphService graphs,
                                 TimetableNeighborhoodLoader loader, RailTravelTimeModel model, SpawnPlan spawnPlanSnapshot);
  /** 失败原因是给用户看的一句话（无 OPERATION、找不到图、显式走行线路类型不符…）。 */
  public AssembleResult assemble(Line line, Operator operator, Company company, String code, TimetableBuildOptions options, LocalDate referenceDate);
  public record Assembled(TimetableBuilder.BuildInput input, List<TimetableNeighborhoodLoader.UnscheduledNeighbor> unscheduled, List<String> warnings) {}
  /** 二者恰有其一非空。仓库没有 Either 类型，不引入第三方函数式库。 */
  public record AssembleResult(Optional<Assembled> value, Optional<String> failure) {}
}
```

调用点：`FtaTimetableCommand.handleBuild` 在主线程做 `assemble`（含 DB 读），异步线程 `build`，主线程 `finishBuild` 里 `save` + `replaceBaselines`。
`handleStatusChange(PUBLISHED)`：主线程 `listBaselines` + `listPublished` 比对；变化了则 `assemble`（只为了 profiles + neighbors）→ 投影我的表 → `check` → 有外部冲突拒绝。

### 5.7 ReclaimManager（S4）

`assignReturnTicket` 中 `allReturnRoutes` 的来源改为：
`本 operator 的 RETURN` ++ `provider.operators().listAll() 其余 operator 的 RETURN`，顺序保证本 operator 在前；匹配逻辑不变。
新增 debug 行 `回收: 使用外方 RETURN route=<code> operator=<op>`。

---

## 6. 测试矩阵

| 不变量 / 决策 | 测试类 | 用例（新增标 ★） |
| --- | --- | --- |
| 1 算出来不是录出来 | `TimetableBuilderTest` | `timingComesFromTheNetwork`（既有）；★`neighborsAreProjectedFromPersistedTimesNotRecorded`：邻表的运行时刻来自其落库 stop，不来自任何观测 |
| 2 确定性（新措辞） | `TimetableBuilderNeighborsTest` | ★`sameNeighborsSameTable`：同一 neighbors 列表两次 build 逐字段一致；★`differentNeighborsMayDifferAndIsRecorded`：换邻表结果可变且 baselines 记录了它 |
| 3 weight | `WeightedTripAllocatorTest` | 既有；★`externalConflictsDoNotChangeShareOrder`：邻表只放宽 headway，不改 SWRR 序列 |
| 4 有限 duty 回库 | `VehicleDutyPlannerTest` | 既有 |
| 5 先班次后派车 | `TimetableBuilderTest` | 既有 |
| 6 宁少排 | `TimetableBuilderTest` | 既有 |
| 7 纯数据 | `TimetableConflictCheckerTest` | ★`bothExternalPairsAreNotReported`；★`ownerIsCarriedIntoConflict` |
| A 作用域 | `TimetableFootprintTest` | ★`footprintUsesExpandedEdgesNotDeclaredStops`（两条线申报点不重叠、走行边重叠 → 相交）；★`differentWorldIsNotNeighbor` |
| A 邻表投影 | `TimetableNeighborhoodLoaderTest` | ★`zeroPointAlignment`（邻表 23:50 与我 00:10 跨日相撞被查出）；★`staleNeighborIsFlaggedButStillProjected`；★`sameLinePublishedTableIsExcluded`；★`zoneDifferenceIsApproximatedAndFlagged` |
| A4 混合 | `TimetableBuildAssemblerTest` | ★`unscheduledNeighborIsReportedNotChecked` |
| B 先到先得 | `TimetableBuilderNeighborsTest` | ★`neighborMovementsNeverMove`（放宽后邻表 movements 逐项等于输入）；★`externalConflictRelaxesHeadway` |
| B3 publish 重检 | `FtaTimetableCommand` 无单测 → `TimetableBuildAssemblerTest` + `JdbcRepositoryTest` | ★`baselinesRoundTrip`；★`changedNeighborDetectedByUpdatedAt` |
| C2 priority 不参与 | `TimetableBuilderNeighborsTest` | ★`operatorPriorityIsNotAnInput`（BuildInput 根本没有它——用例断言 BuildInput 记录组件不含 priority，防止将来偷偷加） |
| D1 | `TripMatcherTest` | ★`missIsLoggedWithNearestTripAndThrottled` |
| E1 显式走行线路 | `TimetableBuildAssemblerTest` | ★`declaredCrossOperatorReturnRouteBecomesALeg`；★`declaredRouteOfWrongTypeIsInfeasible` |
| E3 Reclaim | `ReclaimManagerTest` | ★`fallsBackToForeignOperatorReturnRouteMatchingTerminal`（本 operator 在前） |
| S2 零行为变化 | `TimetableBuilderTest`、`TimetableServiceTest` | 全部既有用例不改一行仍绿 |

夹具：
- 现有 `DEP-A-B-C`（`TimetableBuilderTest`）在 headway 300 + 折返 180 下**本来就不可行**（待命在 A 的车挡回库车）。跨线正例用 headway 600、折返 60。
- ★`TwoLinesFixture`：`DEP1-A-B-C` 与 `DEP2-C-D`，共用 C 站单股道；line 2 先"发布"（构造好的 `NeighborTimetable`），line 1 build 期望外部 PLATFORM 冲突。
- ★`CrossOperatorFixture`：节点 `SURC:S:A:1 … SURC:S:C:1` 与 `CHT:S:C:1`? **不要**这么造——同一物理站台不会有两个 id。正确造法：line 2 的 route 直接引用 `SURC:S:C:1`（裸节点 id），line 2 的 operator code 是 `CHT`。
- 邻表夹具直接构造 `Timetable` 记录（同 `TimetableServiceTest` 的 `timetable()` 写法），不要走 build 再取结果，那会把两个被测对象耦合。

---

## 7. 阶段划分（每阶段独立编译、独立过 `./gradlew spotlessApply clean check`）

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 可观测 | D1 日志与计数；`TimetableFootprint` + `resourceKeysOf`；loader 的 `footprints/unscheduled`；build 报告「共用资源」一节；`docs/dev/timetable.md` 已知边界更新；**外加**用户要求的滞留兜底（E3 提前） | 报告能说出"与谁共用多少资源、对方有没有表"；除兜底外无行为变化 |
| S2 重构 | ✅ `TimetableOccupancyProjector`、`TimetableBuildReportText`、`TripMatcher`、`DutyLedger` 抽出；builder 873→669、service 996→742（嵌套记录占了篇幅，未达 500） | 既有 `TimetableBuilderTest`/`TimetableServiceTest` 一行不改全绿 |
| S3 邻表输入 | ✅ checker owner；`BuildInput.neighbors`；loader `project`；结果与基线；新表与仓储；publish 重检；`neighbors` 命令；不变量 2 改写进文档。实现偏差：命令层的组装仍在 `FtaTimetableCommand` 内（`NeighborInputs` 记录），没有抽 `TimetableBuildAssembler` | 矩阵里 A/B/C/D 用例全绿 |
| S4 直通运转 | route metadata 显式走行线路；ReclaimManager 跨 operator 兜底；跨 operator 夹具 | E 用例全绿 |

与既有路线图的关系：S1–S3 应在**阶段 5（临时加车）之前**——加车落库前要过的就是这套邻表检查，否则加车会绕开路权。
阶段 6 PIDS、阶段 7 校准不受影响。阶段 8 回放校验是发现 §2.1 反例 1 那类漏项的地方。

---

## 8. 给实现者的「别自作主张」清单

1. **不要给既有表加列**。基线只能进新表 `fta_timetable_baselines`；`Timetable` 记录不加字段（会改 20 处构造调用），基线走仓储的独立方法。
2. **不要让 `TimetableBuilder` 读数据库**。邻表由 `TimetableBuildAssembler` 在命令层读好、投影好再交给 `BuildInput.neighbors`；builder 保持纯函数，这是不变量 2 可测的前提。
3. **不要挪邻表的运行**。`searchFeasibleHeadway` 只重排我的；任何"把对方往后推 30 秒试试"的念头都违反 B1。
4. **不要用 `fta_operators.priority`**，不要用 `company` 或 `operator` 过滤邻表。邻表只按世界与足迹筛。
5. **不要重算邻表的站间时分来定时**。用它落库的 `TimetableStop` 偏移；重算只用来拿路径边，并在不一致时标 `stale`。
6. **零点对齐按 §5.1 的公式**，服务日展开 −1/0/+1，别只展开 0（会漏跨日相撞）。
7. **同 line 的 PUBLISHED 表不是邻表**（是替代关系）；loader 按 `excludeLineId` 排除同 line 全部表。
8. **邻表之间的冲突不报**（双 owner 对跳过）。它们在各自发布时已经被检查过；报出来只会淹没我的问题。
9. **publish 拒绝时没有 `--force`**。运营方要么 build 重编，要么让对方 unpublish。
10. **`RuntimeDispatchService` 一行不加**（994/1000）。D1 日志在 `TripMatcher`；Reclaim 改动在 `ReclaimManager` 自己。
11. **`TimetableService` 公开签名不变**。拆出的 `TripMatcher`/`DutyLedger` 是包私有；`FetaruteTCAddon` 的接线不动。
12. **S2 是零行为变化**：既有用例一行不改；发现既有用例过不了，那是重构错了，不是用例错了。
13. **日志前缀照抄本文**：`TIMETABLE_ASSIGN_MISS`、`TIMETABLE_NEIGHBOR`（S1 报告用）、`TIMETABLE_PUBLISH_REJECTED`。
14. **报告文案里对方只显示 code**：`company/operator/line/code`，不显示对方站序与停靠。
15. **不做跨表 duty**（E5）、**不做准入白名单**（E6）、**不做联合编表**（B2）。看到"顺手就能做"的地方，写进本文末尾"后续"一节，不写代码。
16. **禁止主动 commit**。每阶段结束报告"可提交"并等待确认。

---

## 9. 仓库硬约束（设计已绕开，实现时仍要照办）

- 无 schema 迁移：本设计只加新表，不加列。
- `config-version` 现为 33（S1 加了 `reclaim.stranded-destroy-seconds`）；之后各阶段不再动配置。
- `RuntimeDispatchService` 994/1000 方法：不加方法、不加字段。
- 4 空格、K&R、Javadoc 与注释全中文、解释动机；收尾 `./gradlew spotlessApply clean check`。
- `lang/zh_CN.yml` MiniMessage 占位符只能 `[a-z0-9_-]`。本设计的命令文案沿用 `FtaTimetableCommand` 现有的直接 `Component.text` 写法，不新增 lang 键。
- JUnit 5 + MockBukkit；新代码约 80% 行覆盖。
- 禁止主动 commit。

---

## 10. 后续（本轮明确不做，留给将来）

- 路网管理者 / 路权等级：插入点 `TimetableNeighborhoodLoader`。
- 跨表 duty（一辆车接两条线的班）。
- 邻表使用的资源准入白名单（`timetable_guests`）。
- 邻表的 DYNAMIC 股道范围进入站台组容量（现状用图里的物理股道数）。
