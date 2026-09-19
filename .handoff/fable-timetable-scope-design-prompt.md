# 任务：设计「时刻表编制的作用域与跨运营商冲突仲裁」

这是**两遍走的第一遍**。设计和实现会用不同 effort 分开跑：
你这一遍把所有需要**权衡**的东西用完，下一遍的实现者只做**执行**。

判定标准很硬：实现者遇到任何需要做选择的地方，都应该能在你的交接文档里查到答案。
查不到，就是你这一遍漏了。所以本轮不要写实现代码，但要给到方法签名、文件清单、测试清单的粒度。

# 系统背景（最小充分集）

## 组织与物理两套层级，它们不重合

行政：company → operator → line → route。`fta_lines.operator_id` 非空，一条线属于且仅属于一个 operator。
物理：调度图 `fta_rail_nodes` / `fta_rail_edges` 主键是 `(world_id, node_id)` / `(world_id, node_a, node_b)`——
**图是世界维的**。节点 id 形如 `SURC:S:HHU:1`（operator code + 站 + 股道号）、`SURC:ZKW:HHU:1:004`（走行线中间点）、
`SWITCHER:Towny:x:y:z`（按世界坐标）。前缀里的 operator code 是命名空间，不构成资源隔离：
两个 operator 的 route 可以在同一条边、同一个道岔上相遇。

## 时刻表现状

```
/fta timetable build <company> <operator> <line> <code>
        [--headway <sec>] [--start <HH:mm>] [--end <HH:mm>] [--dwell <sec>]
        [--max-trips <n>] [--max-duty-minutes <n>] [--turnaround <sec>]
        [--separation <sec>] [--strict] [--name] [--prefix] [--zone]
/fta timetable list|info|duties|publish|unpublish|delete|export|status ...
```

build 五步：逐段时分（`TimetableTimingCalculator`，按每段自己的限速积分，不用全线平均速度）
→ 按 weight 排班（`WeightedTripAllocator`，smooth weighted round-robin，确定性无随机源）
→ 派车成有限交路（`VehicleDutyPlanner`）→ 冲突检查（`TimetableConflictChecker`）→ 报告 + 落库。

冲突检查把「全部运行 = 运营班次 + 出库/回库走行 + 站台待命」投影到共享资源上扫重叠：

| 资源 | 规则 |
| --- | --- |
| 区间（边） | 互斥，不分方向；相邻占用之间留 `--separation`（默认 30s） |
| 站台 | 具体股道容量 1；站台组 `OP:S:NAME` 容量 = 图里该站股道数 |
| 单线区段 | 对向互斥（复用运行时同一份 `SingleLineSectionIndex` 桥链索引） |
| 道岔 | 两次通过之间留 separation |

待命按 duty 建模而不是按班次（同一辆车"到站→折返→再发车"是一段连续占用，拆开会留假空档）；
不停靠经过单股道车站的车同样登记一次站台占用。

目标 headway 有冲突时，以 10s 为步长向上搜索最小可行 headway（最多放宽到目标的 4 倍），
默认回退并列出目标间隔下的冲突明细，`--strict` 则失败。搜索只放宽 headway，不挪单个班次。

运行时两处行为变化，都由 `timetable.enabled` 总开关控制（默认关）：出站门控里的计划扣留
（`RuntimeDispatchService#checkDeparture` 在申请任何占用之前问 `StationStopCoordinator#holdsDeparture`），
以及表定出票（`timetable.spawn-enabled`，有 PUBLISHED 表的 route 其 headway 票被拦下）。

## 缺口（这就是要你设计的东西）

`TimetableBuilder.BuildInput` 只有一个 `lineId` 和它自己那份 `routes`。checker 只看见调用方递进去的
movements，所以**两条线共用一段线路时，各自 build 都报"无冲突"，两张表都乐观**。

好消息是接缝都现成。checker 本身作用域无关：

```java
Report check(GraphIndex index, Map<UUID, RouteProfile> profiles,
             List<Movement> movements, List<Stay> stays, int separationSeconds)
record Movement(String code, UUID routeId, int startSeconds)   // 时刻相对同一个零点
record Stay(String code, Platform platform, int from, int to)
record Platform(String nodeId, String group, boolean dynamic)
```

`TimetableRepository.listPublished()` 已经返回全网 PUBLISHED 时刻表（含 trips 与 duties），运行时预热在用。

## 实测数据（别当假设，这是真服务器的库）

一个 company（FTAS 交通局-生存部）+ 一个 operator（SURC 生存中部铁路）+ 三条线：
WS 浦蓝线 4 route、MT 大都会线 6 route、DS 探索线 2 route。
第二个 company `ChongchowTransportLimited` 已存在但还没有 operator——跨 operator 是将来时，不是假想。

MT 与 DS 共用 18 个申报路径点，含**具体股道号**（不是站台组）和**同一个车库的 1/3 道**：

```
SURC:S:CSB:1/:2  SURC:S:HAS:1/:2  SURC:S:HHU:1/:4  SURC:S:OFL:1/:2
SURC:S:SCC:1/:2  SURC:S:ZKW:1/:2  SURC:D:HHU:1/:3
SURC:JBS:CSB:2:001  SURC:ZKW:HHU:1:002  SURC:ZKW:HHU:1:004  SURC:ZKW:HHU:4:003
```

⚠️ **18 是下界**：这个统计只比了申报的停靠点与路径点，没有在图上展开两站之间的走行边。
真实共用面必须靠路径展开来算。同理「WS 看着独立」未经证实，不要把它当成已知条件。

`fta_operators` 有一列 `priority INTEGER NOT NULL`（现有语义去代码里确认，别从名字推断）。

## 必须保住的不变量（系统级，不可协商）

1. **时刻表是算出来的，不是录出来的**。输入只能是 route 定义 + 图 + 运营参数，
   永远不能是历史跑车记录或调度器回放。
2. **同一份网络状态 + 同一份配置，永远产出同一张表**，包括 trip/duty 主键
   （由 `timetableId + code` 名字派生，不用随机 UUID——否则"构建两次一致"在字段层成立、在主键层不成立，
   而主键会进库、会被引用、会出现在导出里）。
3. weight 是目标服务比例（SWRR），不是每次发车的抽签概率。
4. 每辆实体车都有有限交路且最终必须回库；终止性要能在**构建产物**上直接断言，不靠 runtime。
5. 先定班次、再派车，顺序不可交换——绝不允许"某终点恰好停着一辆车于是多发这条线"。
6. 宁可少排一班，也不排一班发不出去或回不了库的车（排不进的班次取消并逐条报告）。
7. 冲突模型只做纯数据运算、不跑调度器；将来的回放只用于校验，永远不作为 build 输入。

# 要回答的问题

## A. 作用域

- **A1** build 的**单位**和冲突检查的**作用域**应不应该解耦？（今天两者都是 line）
- **A2** 检查作用域的正确边界是什么——line / operator / company / world / 「共享资源连通分量」？
  正面论证，并给出一个让你选中的方案失效的具体反例。
- **A3** 若选连通分量：分量怎么算（申报路径点不够，要展开到边/道岔/单线区）、多大代价、
  什么时候算（build 时？图 build 时缓存？）、图变了怎么失效。
- **A4** 一个分量里只有一部分线有时刻表、其余还在 headway 自由跑，怎么办？
  现状是有表的 route 其 headway 票被拦下、没表的完全不受影响——**干扰是单向的**。

## B. 确定性 vs 先来后到（本题最尖锐的矛盾）

若 build 要读其它已 PUBLISHED 的时刻表作为输入，不变量 2 立刻出问题：结果依赖**编表顺序**；
而且别人的表已发布、不能被我这次 build 挪动，搜索最小可行 headway 时我只能挪自己的，
于是先编的人系统性占便宜。

- **B1** 接受"路权先到先得"并把它写成显式语义，还是追求"给定一组线路，联合编表结果唯一"？
- **B2** 若联合编表：谁触发、以什么为单位、一次 build 出多张表怎么落库与发布（原子性）、
  某条线单独 rebuild 时怎么办。
- **B3** 若先到先得：重编、重发布、撤销发布时上下游怎么级联？已经在跑的车怎么办？
- **B4** 不变量 2 要不要被重写成更弱但仍可检验的形式？给出你的**确切措辞**，它会进文档。

## C. 跨 Operator 仲裁

- **C1** 跨 operator 共用资源时谁有权编排？现实对应物是路网管理者统一编图 + 路权分配，
  在本插件的对象模型里对应谁——company？world？一个新的"基础设施管理者"概念？
- **C2** `fta_operators.priority` 能不能当仲裁量？不够的话缺什么。
- **C3** 权限面：`fetarute.timetable.manage` 今天是什么粒度，跨 operator 编排需要什么新权限/新命令。
- **C4** 两个 operator 属于不同 company 时，上面的答案变不变？

## D. 失败模式与可观测性

- **D1** 现状最危险的连锁：跨线干扰 → 晚点 → 超过 `assign-tolerance-seconds`（默认 300）→
  车不再绑车次、**静默退回自由运行**。日志上只表现为 `TIMETABLE_ASSIGN` 变少，
  没有任何一行说明原因。设计要怎么让这件事可归因？
- **D2** 完整方案落地前，最小的"不静默"补丁是什么——哪怕只是 build 时报告
  "本表与 XX 线在 N 处共用资源、未做联合排布"？
- **D3** 冲突报告要不要区分"我内部的冲突"与"和别人的表撞的冲突"？
  后者的可操作建议是什么（我不能挪别人，只能挪自己 / 改 separation / 换股道 / 报给运营方）？

# 交付物

产出一份落盘的交接文档 `docs/dev/timetable-scope-design.md`，实现者只读它就能开工。必须包含：

1. **决策**：A/B/C/D 各给一个明确选择。不要列菜单，不要"取决于"，不要把选择权留给实现者。
2. **理由 + 反例**：每个决策配一个会让它失效的具体场景，并说明为什么仍然选它。
   这一段是给未来的人看的——他们会想推翻你的决定，你要先回答他们。
3. **逐文件改动清单**：哪些文件新增、哪些修改、各自职责一句话。
   注意 `TimetableBuilder` 已 873 行、`TimetableService` 已 996 行，
   本仓库有「重构优先、拒绝补丁」的硬要求，该抽协作者就抽，并在清单里点名抽哪个。
4. **完整方法签名**：新类型（如 `ScopeResolver` / `PathAllocation` / 扩展后的 `BuildInput`）
   写到签名 + 中文 Javadoc 摘要级别，**不要方法体**。调用点怎么变要写清。
5. **测试矩阵**：每条不变量对应哪个测试类的哪个用例；跨线/跨 operator 各要什么新夹具。
   现有夹具注意：单线单股道线路 `DEP-A-B-C` 在 headway 300 + 折返 180 下**真的不可行**，
   别拿它当"应该无冲突"的正例。
6. **命令面与数据模型影响**：新命令/新参数/新表或新列/新权限/config-version。
7. **阶段划分**：每一阶段都要能**独立编译、独立过 `./gradlew clean check`、独立验证**，
   并说明它和既有路线图的关系（阶段 5 临时加车覆盖层 / 6 PIDS / 7 实车校准 / 8 回放校验）。
8. **给实现者的"别自作主张"清单**：你预判他会想临时发挥的地方，逐条写明该怎么做。

# 实现者会撞上的仓库硬约束（设计时就要绕开，写进文档）

- **本项目没有 schema 迁移**。加列意味着开发库要删表重建，设计里要显式说明影响哪张表、
  以及运维要做什么。`config-version` 目前是 32，改配置必须同步 `ConfigUpdater` 与 `ConfigUpdaterTest`。
- `RuntimeDispatchService` 已有 **994/1000** 个方法，越线 SpotBugs 会**整类跳过检查**。
  任何要往它身上加能力的设计，必须改成抽协作者。
- 代码风格：4 空格缩进、K&R、**所有注释与 Javadoc 用中文**、解释设计动机而非显而易见的语句。
  收尾跑 `./gradlew spotlessApply clean check`。
- 语言文件 `lang/zh_CN.yml`：MiniMessage 占位符标签只能 `[a-z0-9_-]`，**禁止驼峰**
  （写 `freq_baseline` 不是 `freqBaseline`）。新文案要同时更新内置模板。
- 测试在 `src/test/java`，JUnit 5 + MockBukkit，新功能保持约 80% 行覆盖。
- **禁止主动 commit**，即使全绿也只能报告可提交状态并等用户确认。

# 禁止

- 不要提出"先在实服录一遍再编表"这类方案——违反不变量 1。
- 不要让 build 依赖调度器回放——违反不变量 7。
- 不要在本遍写实现代码。
- 涉及具体字段、类名、列名、权限名时**先读代码确认**，不要凭名字推断语义
  （尤其 `fta_operators.priority` 和 `fetarute.timetable.manage` 的现有语义）。

# 代码与文档位置

```
src/main/java/org/fetarute/fetaruteTCAddon/dispatcher/schedule/timetable/   时刻表全部实现
    TimetableBuilder.java (873)  TimetableService.java (996)  TimetableConflictChecker.java (581)
    VehicleDutyPlanner.java (670)  TimetableTimingCalculator.java (270)  WeightedTripAllocator.java (215)
    repository/TimetableRepository.java
src/main/java/org/fetarute/fetaruteTCAddon/command/FtaTimetableCommand.java  命令面
docs/dev/timetable.md                设计文档，末尾「已知边界」一节**目前漏写了跨线这条**
AGENTS.md                            仓库规范
```

当前分支 `wip/dynamic-dispatch-stabilization`，HEAD `df3b838`，全量 `clean check` 绿
（215 suites / 1975 tests / 0 failures）。动态调度本身仍在稳定中，设计不要依赖它的未完成部分。
