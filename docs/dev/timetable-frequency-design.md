# 按组频率编表：带客 route 与纯走行、规整子网格、相位、让车写进表、多线联编——设计交接

本文是**设计遍**的产物，读者是实现遍。判定标准同前两轮：实现者遇到任何需要做选择的地方，都应该能在这里查到答案；
查不到就是本文漏了。本文不含实现代码，但给到签名、文件清单与测试清单的粒度。

基线：分支 `wip/dynamic-dispatch-stabilization`，HEAD `2f24f48` + 工作树里未提交的「发车自由度」P0/P1
（`docs/dev/timetable-departure-design.md` §7.5，全量 `clean check` 2023 tests 绿）。本文假定那批改动先合入。

---

## 0. 结论速览

| 题 | 决策（一句话） |
| --- | --- |
| A1 | 分类落在 **route** 上，不在组上：**带客 route**（OPERATION，或中途有 STOP 的 CREATE/RETURN）是班次，上子网格；**纯走行 route**（中途没有 STOP 的 CREATE/RETURN）只做交路的两头。CREATE/RETURN/CRET/DSTY 只决定车的生灭。不加配置键；"作业组"这个角色不存在。实现时的修正：OPERATION 不看中途——`A→B` 两站的小交路本来就没有中途 |
| A2 | **已确认（用户 2026-09-19）**：MT-2、DS 那种 CREATE→RETURN 完整生灭的 route 对也是交路的一员——带客的 CREATE 按 `OPERATION + CRET` 处理、带客的 RETURN 按 `OPERATION + DSTY` 处理，落 trip 行、运行时出运营票；一个交路可以只有这两班。「RETURN 不落 trip 行」细化为「**不带客的**走行不落 trip 行」 |
| B1 | 频率来源改为**每个服务组一个间隔**：`spawn_groups[].baselineSec` = 该组每个**方向**多久一班；组内同方向多条 route 按 weight 切份额。线路级 `spawn_freq_baseline_sec` 只在组没配时兜底 |
| B2 | 组的间隔作用于组内**全部带客 route**，含带客的出入库班（WS_Short 150 = 1L 每 150 s 从车库发一班到 CHT）。纯走行由派车器在交路两头生成，没有频率。车辆流量由此**完全由配置决定**：注入多于 Full 需要的车就形成 `出库班 → 回库` 的短交路，报告按交路形状（几个来回）计数 |
| C1 | 每个服务组的每个方向一张**规整子网格**（周期 = 组间隔 × Σw / w_route），代替全线一张 SWRR 网格；SWRR 只保留在同方向多 route 的份额切分上 |
| C2 | **相位**分两层：往返对的相位差 = 走行 + 折返（锚定，端点零等待）；组与组之间在共用起点站上按"最大间隔最小、并列时最小间隔最大、再并列取最小偏移"扫描选相位（10 s 步长），确定性。只看最大间隔时 600/300 两组"同时发车"与"错开 150"打平，实现时补了第二键 |
| C3 | 大小交路 = 两个服务组在共用区间上的交错；报告新增"共用区间合成间隔 min/med/max"，机制不做特殊处理 |
| D1 | **让车写进表**：把 P1 的端点串行推广为按资源的"只延后"修复——冲突的后车整趟延后隐含等待量、沿交路链传播、再查，直到干净；单处延后 > `--max-wait`（默认 60）才是真冲突 |
| D2 | 真冲突仍由搜索消解：把**所有服务组的间隔按同一比例放宽**（步长 = 最小间隔的 10 s，上限 4 倍），报告按组列出回退后的间隔 |
| E1 | **多线联编**：`build` 接受同一 operator 下的多条线，共享零点、子网格、相位与修复，产出每线一张表、同一 `code` 与 `updatedAt`、互为基线；publish 按同 code 整组发布。跨 operator/company 仍走邻表先到先得 |
| F1 | 不变量 3 改写：weight 是**同一组同一方向内**带客 route 的目标比例；组与组之间由频率决定；纯走行 route 没有 weight |
| F2 | "建议值是一个数"改为"建议值是每组一个间隔"，回写目标是 `spawn_groups[].baselineSec`（`/fta route group set --baseline`），线路级 baseline 不再回写 |

---

## 1. 术语

- **生灭**：`CREATE` 或首站 `CRET` 的 route 实体化一辆车；`RETURN` 或以 `DSTY` 收尾的 route 跑完销毁。这只是车的生命周期，不决定它是不是班次。
- **带客 route / 纯走行 route**：见 A1。**库里现有的每一条 route 都带客**：WS-1L（LWN 车库→CHT 停 5 站）与 WS-1C（CHT→LWN 车库停 4 站）、
  DS 的两条、MT-2 的两条、MT-1 的四条。"纯走行"（中途全 PASS 的出入库 route）目前一条都没有，机制上保留它只是为了完备。
  MT-1_Short 里 `_ShortR` 一对是正线折返、`_Short/_ShortD` 一对是出入库，同组同方向按 weight 切份额。
- **方向**：一条 route 的（起点站台组, 终点站台组）。同组同方向的多条 route（MT-1N_Short 与 MT-1N_ShortR 都是 OFL→PPK）共用一张子网格。
- **子网格**：某个方向的规整发车序列：`phase + k × period`，`period = 组间隔 × Σw_方向 / w_route`。
- **相位**：子网格的起点偏移（相对计划窗口起点，`[0, period)`）。
- **让车**：为消解一处冲突，后车整趟延后 `w = 前车离开 + 裕量 − 后车进入` 秒。写进表里，运行时按表定时刻扣留。
- **合成间隔**：一段共用区间上所有组的发车叠加后的相邻间隔。
- **表集**：一次 `build` 产出的多张表（每线一张），同一 `code`、同一 `updatedAt`。

---

## 2. 决策与理由

### 2.1 A1 分类落在 route 上

**决策**：每条 route 只问一件事——中途有没有 `STOP`。

1. **带客 route**：中途至少一个 `STOP`（首末站不算）。它是班次，上它所属组的子网格，落 trip 行，运行时出运营票。类型只决定生灭：
   `CREATE` 或首站 `CRET` → 这一班实体化一辆车（派车器的 `startsAtDepot`，与今天 WS-1L 的路径相同）；
   `RETURN` 或 `DSTY` 收尾 → 这一班跑完车就销毁（`endsAtDepot`，`ROUTE_ENDS_AT_DEPOT`）。
2. **纯走行 route**：只有 PASS。只做交路的两头（今天的 `Legs`），不落 trip 行，时刻由派车器推出（首班发车 − 走行 − 折返 / 末班到达 + 折返）。
3. 组只是"共用一个间隔的一批带客 route"；一个组里全是纯走行 route 时它没有间隔，配了只警告。
4. 没配 `spawn_group` 的 route：一律进 `default` 组。（设计稿原本写"按起点推导"，实现时证伪：正向从 A 出发、反向从 B 出发会被拆成两组，
   往返对锚不到一起，`TimetableBuilderStubTerminalTest` 的 RB 时隙全部错位、派车器与串行互相拆台。一条线没分组就是一个组。）

**理由**：用户的建模里 MT-2 与 DS 就是 `CREATE → RETURN` 完整生灭的交路，它们是交路的一员而不是"作业"；WS 用 `OPERATION + CRET` 表达同一件事。
按类型分角色会把这两种写法拆成两类，按"带不带客"分则统一。派车器已经支持两头进库的交路（`startsAtDepot` / `endsAtDepot`），
一个只有 `CREATE 班 → RETURN 班` 的交路今天就是合法产物，只差 trip 行。

**反例**：一条 RETURN 中途只停一站再回库——落 trip，乘客看到一班"到 X 为止"的车，是对的。一条 CREATE 从车库直达首站、中途只 PASS——纯走行，
和今天一样只做出库段；这种 route 库里现在没有，测试用合成夹具覆盖。

### 2.2 A2 带客作业进发车表（已确认）

**决策**：见 A1 第 1 条。运行时侧唯一的改动是 `TimetableSpawnManager.buildTicket` 对 `kind == CREATE` 的 trip 出带出库语义的票
（今天 `buildLegTicket` 的分支），`TripMatcher` / `DutyLedger` 不需要知道类型——首班就是首班。

**流量由配置决定**：带客的出入库班上了组的子网格之后，注入速率不再由交路长度反推。WS_Short 是一对完整的往返方向（1L LWN→CHT、1C CHT→LWN），
WS_Short 150 + WS_Full 150 意味着 CHT 每 150 s **到两辆**（1L、2N）、**发两班**（2C、1C），进出平衡；派车器 FIFO 决定哪辆车接 2C、哪辆接 1C，
结果是一半交路形如 `1L → 2C → 2N → 1C`、一半形如 `1L → 1C`（LWN–CHT 段经车库的"小交路"）。这正是那份配置的字面意思；
报告新增"交路形状"一行（几个来回各多少条），运营者看到不对就改间隔，而不是让编表器猜。
带客的 RETURN（1C）既是班次也是交路的收尾：它有自己的时隙，从容量 1 端点始发时同样锚在车上；没有车接的 1C 时隙照"宁少排"取消。

### 2.3 B1 / B2 频率来源

**决策**：

- 服务组间隔 = `spawn_groups[].baselineSec`，含义是**该组每个方向多久一班**（WS_Full 150 = CHT→NTA 每 150 s、NTA→CHT 每 150 s）。
- 组内同方向多条 route：`period_route = 间隔 × Σw_方向 / w_route`，序列用 SWRR 交错（沿用 `WeightedTripAllocator`，输入改为该方向的候选）。
- 服务组没配 baseline → 用线路级 `spawn_freq_baseline_sec`；也没有 → 300。报告"间隔来源"按组一行。
- `--headway <sec>`：给所有服务组同一个间隔（覆盖 baseline），保留给快速实验；`--headway <组名>=<sec>` 逐组覆盖（新语法，可重复）。
- 组的间隔作用于组内全部带客 route（含带客的出入库班）；只有纯走行 route 的组没有间隔，配了就警告。

**理由**：运营者配的本来就是"这一组多久一班"；线路级 120 + 权重 1:3:3 + 组 150/150 三者今天互相矛盾，靠 `1/Σ(1/b)` 合并糊过去。
"每方向"而不是"组合成"：往返对是同一批车，两个方向天然同频，运营者只想说一个数。

**反例**：一个组只有单方向 route（环线，或另一方向的 route 挂在别的组）——按方向定义仍然成立，只是没有往返对可锚定，相位由 C2 第二层选。
DS 的 `CREATE DEP→WYB` 与 `RETURN WYB→DEP` 起终点互换，是往返对，同样锚定。

### 2.4 C1 子网格代替全线网格

**决策**：每个服务组的每个方向生成规整序列 `phase + k × period`，k 从 0 到窗口末；全线不再有共享网格。
`WeightedTripAllocator` 只在"同方向多 route"时决定第 k 个格子归哪条 route。

**理由**：全线一张网格 + SWRR 让每条 route 的间隔变成 360/240/240 这种没规律的数，端点的相位余数就是这么来的（上一轮 §0.1）；
乘客要的是"每 4 分 40 秒一班"。

**反例**：两个服务组频率互质（150 与 140）时合成间隔永远不规整——这是运营配置问题，报告里用合成间隔 min/med/max 把它亮出来，不在机制里救。

### 2.5 C2 相位

两层，顺序固定：

1. **往返对锚定**：同组内起终点互换的一对 route（A→B, B→A）：`phase_BA = (phase_AB + run_AB + 折返) mod period`。
   一辆车到达 B 折返完正好是下一班 B→A 的时隙，端点零等待。多条候选（同方向多 route）取走行最短的那条算。
2. **组间交错**：所有服务组按（组名）顺序，第一组 `phase = 0`；后面每一组在 `[0, period)` 上以 10 s 步长扫描，
   目标是**共用起点站台组上相邻发车的最大间隔最小**（周期取各组 period 的最小公倍数，封顶计划窗口），并列取最小相位。
   纯走行由派车器生成，不参与相位选择，但参与之后的修复。

**理由**：大小交路的价值全在交错；往返对锚定把 CHT 那类端点的等待从根上消掉，P1 的端点串行退成兜底。

**反例**：三个以上的组共用一段，逐组贪心不一定全局最优——接受，确定性比最优重要，报告的合成间隔会暴露它。

### 2.6 D1 让车写进表（通用延后修复）

**决策**：把 `TerminalSerializer` 推广成按资源的修复 `ResourceRepair`，放在 派车 → **相位/子网格** → 派车 之后、编号之前：

1. 用（未改动的）`TimetableConflictChecker` 扫一遍；冲突按（发生时刻, 资源键, 后车 code）排序。
2. 取第一处：后车 = 进入较晚的占用；它整趟延后 `w = 前车离开 + 裕量 − 后车进入`（同 P1：延后的是发车，车在起点多站）。
   `w > --max-wait`（默认 60 s，上限不许超过 `timetable.hold-max-seconds`）→ 这处是**真冲突**，记下、不动、继续下一处。
3. 延后沿交路链传播（后续班次 `ready` 后移；起点在容量 1 端点的续班仍锚在车上）；同一班累计延后 > `assign-tolerance-seconds`（300）
   或交路超上限 → 从那一班起截断（沿用 `STUB_SATURATED` 的规则与原因）。
4. 重扫，直到没有可修的冲突或达到迭代上限（班次数 × 2）。结果里区分 `让车`（已写进表）与 `真冲突`。
5. 邻表占用是不可移动的前车；我永远是后车。

**理由**：预算只"容忍"的话，PIDS 与车次绑定看到的仍是没让过的时刻，运行时让完就漂；写进表则 `holdsDeparture` 把早到的车扣到表定时刻，
ETA 自然对。实测（上一轮 §7.5）默认参数的 WS 在 120 s 下 84 处冲突全在 30 s 以内——按这条规则它直接是一张 120 s 的表。

**反例**：让车会在别处制造新冲突（连锁）——所以要重扫；上限 60 s 时连锁很浅，实测区间/道岔/单线的隐含等待均值 10–26 s。
真正连锁不止的是 NTA:2 那种几百秒的，它们本来就 > max-wait，走 D2。

**与 P1 端点串行的关系**：端点串行是本机制在"容量 1 端点"上的特例（进站延后 = 让车，续班锚定 = 相位第一层）。
实现上 `ResourceRepair` 吸收 `TerminalSerializer` 的事件循环与截断逻辑；`TerminalSerializer` 保留为 `ResourceRepair` 的一部分或删除，
测试矩阵里它的 9 个用例必须原样通过。

### 2.7 D2 真冲突的消解

**决策**：还有真冲突时，把所有服务组的间隔按同一比例放宽（每步让最小的组间隔 +10 s，其余等比），重跑相位与修复，上限 4 倍。
`--strict` 仍是"目标下有真冲突就失败"。报告"实际使用的间隔"按组列出。

**理由**：只放宽撞了的那个组会改变组间比例，运营者写回配置时对不上；等比放宽保住"每组一个建议值"。

> **修订（2026-09-27）**：等比放宽之后再逐组收紧（`TimetableBuilder#tightenGroups`）。等比放宽会把没卡住的组一起拖慢——
> 实服三线联编里只有 WS 卡在 CHT 折返与车库咽喉，DS 却跟着从 200 放到 235、MT 从 150 放到 176。收紧后每组仍各有一个建议值，
> 按这组值重建是干净的，"写回配置对不上"的顾虑不成立；按车接续的组同一间隔，作为一个单元一起收紧。

### 2.8 E1 多线联编（用户问题："怎样让多条线一起被编表"）

**现状**：build 单位是 line；两条线共用资源只能先编一条、发布、再编另一条（先到先得）。

**决策**：`build` 接受同一 operator 下的**多条线**：

```
/fta timetable build <company> <operator> <line>[,<line>...] <code> [同今天的 flags]
```

- 所有线的服务组一起分角色、一起生成子网格、一起选相位（C2 第二层跨线）、一起派车（各线各自的交路，车不跨线——不变量"一辆车不跨两份表"不变）、
  一起修复（D1 里的前车可以是别的线的班次）、一起搜索（D2 等比放宽作用于全部组）。
- 产出**每线一张表**，同一 `code`、同一 `builtAt/updatedAt`；每张表的基线里互相引用（`fta_timetable_baselines` 现有结构够用：
  邻表 id + updatedAt）；不加新表新列。
- `publish <company> <operator> <line>[,<line>...] <code>`：整组发布，逐张过现有的重检（它们互为基线、updatedAt 相同，重检必然一致）；
  任一张拒绝则整组不发。`unpublish`/`delete` 同理接受多线。
- 约束：同一世界、同一时区、同一计划窗口；跨 operator/company 仍只能做邻表（先到先得）——路权仲裁不变。
- `neighbors`、`info`、`export` 不变（按单线）。

**理由**：把两条线放进同一次相位选择与修复，才是"联合排布"；分开编只能一方避让另一方。运行时不需要知道"表集"——它消费的仍是每线一张表。

**反例**：两条线的运营者希望各自独立发布节奏——那就别放进同一次 build，走邻表。

### 2.9 F1 / F2 不变量与措辞

**不变量 3 新措辞**（替换 `timetable.md` 对应段落）：

> weight 是**同一组同一方向内**各带客 route 的目标服务比例，不是抽签概率；组与组之间的班次数由各组的间隔决定；纯走行 route 没有 weight，
> 它们的班次数等于交路的两头。

**"建议值"新措辞**：

> 有冲突时 build 把所有服务组的间隔按同一比例放宽到最小可行值；报告按组给出实际使用的间隔，它们可以直接写回各组的
> `spawn_groups[].baselineSec`。线路级 `spawn_freq_baseline_sec` 只在组没配间隔时兜底，不再是建议值的载体。

不变量 2（确定性）不变：子网格、相位扫描、修复排序全部确定。不变量 5（三步）不变。不变量 6 沿用 P1 的截断补句。

---

## 3. 数据模型与命令面

- **无新表、无新列、无 config 键**；`config-version` 保持 33。`--max-wait` 是 build flag，不落库（同 `--separation`）。
- `spawn_groups[].baselineSec` 语义收紧为"服务组每方向间隔"；作业组配了只警告。`maxOperationTrips` 不参与编表（它是出票器的并发上限）。
- 命令：`build` / `publish` / `unpublish` / `delete` 的 `<line>` 参数接受逗号分隔的多条线；`--headway` 新增 `<组名>=<sec>` 形式；新增 `--max-wait <sec>`。
- 报告新增：每组一行"间隔来源 / 实际间隔 / 相位"；"共用区间合成间隔 min/med/max"（按共用的起点站台组）；"让车 N 处，涉及 M 班，最长 x s"；
  作业组一行"出入库作业提供的服务：X 段每 Y s 一班（由交路长度 k 决定）"。P1 的"端点串行 / 结构下界"两行保留。
- 失败文案：沿用 P1 的两种（利用率 > 100% / 范围内没找到），"范围内没找到"改按组列出放宽到多少。

---

## 4. 逐文件改动清单

### 新增

| 文件 | 职责 | 阶段 |
| --- | --- | --- |
| `dispatcher/schedule/timetable/ServiceGroupClassifier.java` | 从 route 定义与 `spawn_group` 分组、分角色、分方向；纯数据 | Q1 |
| `dispatcher/schedule/timetable/GroupGrid.java` | 每个服务组每个方向的规整子网格；同方向多 route 的 SWRR 交错 | Q1 |
| `dispatcher/schedule/timetable/PhasePlanner.java` | 往返对锚定 + 组间交错扫描 | Q1 |
| `dispatcher/schedule/timetable/ResourceRepair.java` | 通用"只延后"修复，吸收 `TerminalSerializer` 的事件循环、截断、邻表预订 | Q2 |
| `dispatcher/schedule/timetable/TimetableSetBuilder.java` | 多线联编：组装多个 `BuildInput`，共享相位/修复/搜索，拆回每线一张表 | Q3 |
| 测试：`ServiceGroupClassifierTest`、`GroupGridTest`、`PhasePlannerTest`、`ResourceRepairTest`、`TimetableSetBuilderTest`、`TimetableBuilderInterleaveTest` | §6 | 各阶段 |

### 修改

| 文件 | 改动 | 阶段 |
| --- | --- | --- |
| `TimetableBuilder.java` | `prepare` 调分类器；`attempt` 用 `GroupGrid` + `PhasePlanner` 生成班次，不再 `allocate(slots)`；派车后调 `ResourceRepair`；搜索改等比放宽 | Q1 / Q2 |
| `TimetableHeadwayDefaults.java` | 改为按组解析：返回 `Map<组, Choice>`；`--headway` 两种语法 | Q1 |
| `TimetableBuildOptions.java` | `headway` 改为 `Map<String, Duration> groupHeadways` + 可选全局覆盖；加 `maxWait` | Q1 / Q2 |
| `TimetableBuildResult.java` | 加每组间隔与相位、合成间隔、让车统计；`TerminalReport` 保留 | Q1 / Q2 |
| `TimetableBuildReportText.java` | 上述各行文案 | Q1 / Q2 |
| `TimetableRoutePlan.java` | 不改结构；`kind` 保留原类型，带客作业的 plan 进 trips（A2） | Q1 |
| `spawn/TimetableSpawnManager.java` | `buildTicket` 对 CREATE 类型的 trip 出带出库语义的票（今天 `buildLegTicket` 的逻辑）；仅当 A2 确认 | Q1 |
| `command/FtaTimetableCommand.java` | 多线参数、`--headway` 新语法、`--max-wait`、报告行；publish 整组 | Q1 / Q3 |
| `docs/dev/timetable.md` | 不变量 3 与"建议值"措辞、新节「按组频率」「让车」「多线联编」、命令、报告、已知边界 | 逐阶段 |

**不改**：`TimetableOccupancyProjector`、`scope/`、`TimetableConflictChecker`（修复只调用它）、`TimetableService`、`RuntimeDispatchService`、`config.yml`、`StorageSchema`。

---

## 5. 完整签名（Javadoc 摘要级，无方法体）

### 5.1 分类器

```java
/** 把 build 输入的 route 按 spawn_group 分组、按定义分角色、按（起点组, 终点组）分方向。纯数据，确定性。 */
public final class ServiceGroupClassifier {
  /** 一个方向：起点站台组、终点站台组、该方向的带客候选（route + weight，按 code 排序）。 */
  public record Direction(String originGroup, String terminalGroup, List<WeightedTripAllocator.Candidate> candidates, List<UUID> routeIds) {}
  /** 一个交路组：名称、带客 route 的方向列表、纯走行 route 列表；纯走行只做交路两头。 */
  public record Group(String name, List<Direction> directions, List<UUID> pureLegs) {}
  public record Classification(List<Group> groups, List<String> warnings) {}
  public static Classification classify(List<TimetableBuilder.RouteInput> routes, Map<UUID, TimetableConflictChecker.RouteProfile> profiles);
  /** 中途至少一个 STOP（首末站不算）。 */
  static boolean carriesPassengers(TimetableBuilder.RouteInput route);
  /** 生灭：CREATE 或首站 CRET → 实体化；RETURN 或 DSTY 收尾 → 销毁。与 P1 之前的 startsAtDepot/endsAtDepot 同一条规则。 */
  static boolean spawnsVehicle(TimetableBuilder.RouteInput route);
  static boolean destroysVehicle(TimetableBuilder.RouteInput route);
}
```

### 5.2 子网格与相位

```java
/** 一个方向的规整发车序列：phase + k × period，period = 组间隔 × Σw / w_route；同方向多 route 用 SWRR 决定第 k 格归谁。 */
public final class GroupGrid {
  public record Slot(int departureSeconds, UUID routeId, int candidateIndex) {}
  public record DirectionGrid(ServiceGroupClassifier.Direction direction, int intervalSeconds, int phaseSeconds, List<Slot> slots) {}
  public static DirectionGrid of(ServiceGroupClassifier.Direction direction, int intervalSeconds, int phaseSeconds, int horizonSeconds, WeightedTripAllocator.FeasibilityCheck feasible);
}

/** 相位：往返对锚定（走行 + 折返），组间在共用起点站台组上按"最大间隔最小"扫描；10 s 步长，并列取最小。 */
public final class PhasePlanner {
  public record Phases(Map<String, Integer> phaseByDirectionKey, List<String> notes) {}
  public static Phases plan(List<ServiceGroupClassifier.Group> serviceGroups, Map<String, Integer> intervalByGroup,
                            Map<UUID, TimetableConflictChecker.RouteProfile> profiles, int turnaroundSeconds, int horizonSeconds);
  /** 共用起点站台组上的合成间隔 min/med/max，供报告。 */
  public record Interleave(String originGroup, int minGap, int medianGap, int maxGap) {}
  public static List<Interleave> interleaves(List<GroupGrid.DirectionGrid> grids);
}
```

### 5.3 修复

```java
/**
 * 按资源的"只延后"修复：冲突的后车整趟延后隐含等待量、沿交路链传播、重扫，直到干净或只剩超过 maxWait 的真冲突。
 * 吸收 TerminalSerializer：容量 1 端点的续班锚定与进站延后是它的特例。确定性同 P1。
 */
public final class ResourceRepair {
  public record Input(Timetable provisional, Map<UUID, TimetableConflictChecker.RouteProfile> profiles, TimetableConflictChecker.GraphIndex index,
                      int zeroSecondOfDay, int horizonSeconds, int turnaroundSeconds, int separationSeconds, int maxWaitSeconds, int assignToleranceSeconds,
                      VehicleDutyPlanner.Legs legs, VehicleDutyPlanner.Limits limits, Set<UUID> routesEndingAtDepot,
                      List<WeightedTripAllocator.Candidate> candidates, List<TimetableRoutePlan> operationPlans, List<NeighborTimetable> neighbors) {}
  /** 一处让车：资源、前车、后车、延后秒数。 */
  public record Yield(String resource, String first, String second, int waitSeconds) {}
  public record Result(Timetable timetable, List<TerminalSerializer.Shift> shifts, List<Yield> yields, List<UUID> truncatedTripIds,
                       TimetableConflictChecker.Report remaining, List<TerminalSerializer.TerminalReport> terminals) {}
  public static Result repair(Input input);
}
```

### 5.4 多线联编

```java
/** 多条线一次编表：共享零点、相位、修复、搜索；每线一张表。车不跨线。 */
public final class TimetableSetBuilder {
  public record SetInput(List<TimetableBuilder.BuildInput> lines, List<NeighborTimetable> neighbors) {}
  public record SetResult(Map<UUID /*lineId*/, TimetableBuildResult> byLine, List<String> warnings) {}
  public SetResult build(SetInput input, TimetableBuildOptions options, Instant now);
}
```

单线 `TimetableBuilder.build` 保留，实现为 `TimetableSetBuilder` 的单元素情况。

### 5.5 选项与默认

```java
public record TimetableBuildOptions(
    int serviceStartSecondOfDay, int serviceEndSecondOfDay,
    Map<String, Duration> groupHeadways,      // 组名 → 间隔；空表示用各组 baseline
    Optional<Duration> headwayOverride,       // --headway <sec>：所有服务组同一个间隔
    Duration defaultDwell, VehicleDutyPlanner.Limits dutyLimits, String tripCodePrefix, ZoneId zoneId,
    Duration separation, Duration maxWait, boolean strictConflicts) {}

public final class TimetableHeadwayDefaults {
  /** 每个服务组一个 Choice：--headway 组覆盖 > --headway 全局 > 组 baseline > 线路 baseline > 300。 */
  public static Map<String, Choice> resolve(TimetableBuildOptions options, List<ServiceGroupClassifier.Group> groups,
                                            Optional<Integer> lineBaselineSeconds, List<SpawnGroup> configuredGroups);
}
```

---

## 6. 测试矩阵

夹具（新增到 `TimetableTestFixtures`）：

- **`interleaveFixture`（大小交路）**：直链 `DEP – A(两股道) – B – C – D(两股道)`，全站 STATION、路径点 WAYPOINT。
  Full 组：A↔D 一对；Short 组：A↔B 一对（正线折返）；出库 DEP→A、回库 A→DEP。Full 300 s、Short 300 s 时共用段 A–B 的合成间隔应为 150 s 恒定。
- **`accessOnlyFixture`（DS 形态）**：只有 CREATE（DEP→…→X，中途 3 个 STOP）与 RETURN（X→…→DEP，中途 3 个 STOP）。编出的每个交路恰好两班，以销毁收尾。
- **`dynamicTerminalFixture`（MT 形态）**：终点是 DYNAMIC 站台组，服务组的往返对锚定按组一层算。
- 既有 `stubTerminal`（P1）继续用于端点串行的回归。

| 不变量 / 决策 | 测试类 | 用例（★ 新增） |
| --- | --- | --- |
| A1 | `ServiceGroupClassifierTest` | ★`routesWithIntermediateStopsCarryPassengersRegardlessOfType`（DS 的 CREATE/RETURN、WS-1L、WS-1C）；★`passOnlyRoutesArePureLegs`（合成夹具：中途全 PASS 的 CREATE）；★`directionsAreKeyedByOriginAndTerminalGroups`；★`sameDirectionRoutesOfMixedTypesShareADirection`（MT-1N_Short 与 _ShortR） |
| A2 | `TimetableBuilderTest` | ★`createToReturnPairFormsACompleteDuty`（accessOnlyFixture：每个交路恰两班、以销毁收尾、trips 非空）；★`pureLegsStayOffTheTripTable`（合成夹具）；★`scheduledReturnTripClosesTheDuty`（WS-1C 形态：回库班有时隙、也是交路收尾）；★`surplusInjectionsFormDepotShortTurns`（WS 形态：出现 `1L → 1C` 交路，报告"交路形状"计数） |
| B1 | `TimetableHeadwayDefaultsTest` | ★`groupBaselineIsPerDirection`；★`headwayFlagOverridesAllGroups`；★`groupSpecificHeadwayOverridesOne`；★`accessGroupBaselineIsIgnoredWithWarning` |
| C1 | `GroupGridTest` | ★`slotsAreRegular`（间隔恒定）；★`sameDirectionRoutesShareTheGridBySwrr`（MT-1N_Short / _ShortR 1:1 交替）；★`gridIsDeterministic` |
| C2 | `PhasePlannerTest` | ★`returnPairIsAnchoredToRunPlusTurnaround`；★`secondGroupIsInterleavedAtSharedOrigin`（300/300 → 合成 150）；★`tiesPickTheSmallestPhase`；★`scanIsDeterministic` |
| C3 | `TimetableBuilderInterleaveTest` | ★`sharedSectionGapIsReported`；★`fullAndShortVehiclesMayChain`（一辆车 Full 后接 Short 合法） |
| D1 | `ResourceRepairTest` | ★`yieldsUnderMaxWaitAreWrittenIntoTheTable`；★`yieldsPropagateAlongTheDuty`；★`conflictsOverMaxWaitRemainAsRealConflicts`；★`cumulativeWaitOverToleranceTruncates`；★`neighborIsAlwaysTheLeadingTrain`；★`repairIsDeterministic`；P1 的 `TerminalSerializerTest` 9 个用例改指向 `ResourceRepair` 后原样通过 |
| D2 | `TimetableBuilderTest` | ★`relaxationScalesAllGroupsProportionally`；★`strictFailsOnRealConflictsOnly`（只有让车不算失败） |
| E1 | `TimetableSetBuilderTest` | ★`twoLinesShareOnePhasePlan`；★`vehiclesNeverCrossLines`；★`resultsCarryTheSameCodeAndUpdatedAt`；★`baselinesReferenceEachOther`；★`singleLineBuildIsTheOneElementCase` |
| 不变量 2 | `TimetableBuilderTest` | 既有 `buildIsDeterministic` + ★`interleavedBuildIsDeterministic` |
| 不变量 3 | `TimetableBuilderTest` | ★`weightsOnlySplitWithinADirection`；`weightControlsServiceShare` 改为组内断言 |
| 不变量 4/5/6 | 既有 | 不改一行仍绿；截断用例沿用 P1 |
| 零行为变化的边界 | 既有跨线用例 | `TimetableBuilderNeighborsTest`、`TimetableNeighborhoodLoaderTest` 不改一行仍绿 |

---

## 7. 阶段划分（每阶段独立编译、独立过 `./gradlew spotlessApply clean check`）

| 阶段 | 内容 | 验收（实服判别量） |
| --- | --- | --- |
| **Q0 确认** | A2 已由用户确认；剩 B1 的"每方向"语义与 E1 范围 | 本文 §11 清空 |
| **Q1 分组 + 子网格 + 相位（已落地，2026-09-19）** | 分类器、`GroupGrid`、`PhasePlanner`、按组的 headway 默认、A2、报告行；派车与 P1 修复不变。命令面暂为 `--group-headway "组=秒,…"`，`--headway` 仍是一个数给全部组 | WS 不传 `--headway`：2C/2N 各 150 s 恒定间隔；CHT 上 2N 到达与 2C 发车相位差 = 走行 + 折返，端点串行的"偏离网格"班次数降到接近 0；DS 能编出表 |
| **Q2 让车写进表（已落地，2026-09-19）** | `ResourceRepair` 接在 `TerminalSerializer` 之后（未吸收，复用其改写/截断）；`--max-wait`；等比放宽；回滚判据 | 默认参数的 WS：目标下"让车 N 处、真冲突 0"，不放宽；`export` 里能看到被延后的班次 |
| **Q3 多线联编（已落地，2026-09-19）** | `TimetableSetBuilder`、车池、多线命令、整组发布 | `build FTAS SURC WS,MT X`：两张表同 code、互为基线、共用资源上的合成间隔在报告里；`publish` 整组 |
| **Q4 回写与清理** | `route group set --baseline` 的建议值提示；`timetable.md` 全部措辞；删掉线路级 baseline 作为建议值的说法 | 文档与报告一致 |

---

## 8. 给实现者的「别自作主张」清单

- **不要**加"组角色"配置键；角色只从 route 定义推。
- **不要**按类型分"服务/作业"：只看中途有没有 STOP。纯走行 route 永远不落 trip 行（库里现在一条也没有，别为它设计特例）。
- **不要**改 `spawn_groups` 的 JSON 形状；`baselineSec` 语义变了但键不变。
- **不要**让车不写进表只"容忍"；不要在运行时侧加任何 hold 逻辑。
- **不要**让相位扫描依赖组的 HashMap 序；组按名字排序。
- **不要**给 `--max-wait` 一个超过 `hold-max-seconds` 的默认值；也不要把它落库。
- **不要**让一辆车跨线接班；多线联编只共享相位与修复。
- **不要**删除 `TerminalSerializerTest` 的用例；改成对 `ResourceRepair` 的断言后必须原样通过。
- **不要**动 `TimetableOccupancyProjector`、`scope/`、`TimetableConflictChecker` 的语义。
- **不要**动 `Limits.DEFAULT_MAX_TRIPS` / `DEFAULT_TURNAROUND_SECONDS`（仍是用户的事）。
- 涉及具体字段、类名时先读代码确认；**禁止主动 commit**。

---

## 9. 仓库硬约束

同前两轮：无 schema 迁移（本轮无新表新列）；`RuntimeDispatchService` 不加方法；中文 Javadoc；MiniMessage 占位符只用 `[a-z0-9_-]`；
收尾 `./gradlew spotlessApply clean check` 并解析 `build/test-results/test/*.xml`。

---

## 10. 后续（本轮明确不做）

- 车库咽喉作为串行资源（D1 的通用修复会把咽喉冲突当让车处理，超过 max-wait 的仍是真冲突）。
- 相位的全局最优（多组联合扫描）。
- `--max-trips` / `--turnaround` 默认值。
- 跨 operator 的联编（路权仲裁不变，仍先到先得）。

---

## 11. 待用户确认的问题（Q0）

1. ~~A2~~ 已确认：带客的 CREATE/RETURN 是交路的一员，落 trip 行。
2. **B1**：`spawn_groups[].baselineSec` 按"每方向间隔"理解，是否符合你配 `WS_Full 150` 时的本意？连带的含义：WS_Short 150 = 每 150 s 从 LWN 发一班 1L 到 CHT，
   多出来的车走 `1L → 1C` 短交路（§2.2）。
3. ~~`--max-wait` 默认 60~~ 已同意，上限跟 `hold-max-seconds`。
4. **E1** 的范围：先做同 operator 内的多线；跨 operator 仍走邻表。

---

## 12. 实现状态（2026-09-19）

- **Q1 已落地**：`ServiceGroupClassifier` / `GroupGrid` / `PhasePlanner` 新增；`TimetableBuilder.attempt` 按组按方向出格、相位两层、搜索等比放宽；
  `TimetableBuildOptions.groupIntervals` + `TimetableHeadwayDefaults.resolveGroups`；`TimetableBuildResult` 加 `groupIntervals / interleaves / dutyShapes / phaseNotes`；
  带客 CREATE 上网格、带客 RETURN 落 trip 行（`TimetableTripNumbering.appendReturnTrips`，投影与 `tripsBetween` 跳过）；命令 `--group-headway`。
  与设计稿的两处出入见 §0 A1、§2.1 第 4 条、§0 C2。`TimetableBuildOptions` 没按 §4 改成 `Map<String, Duration>`，而是在原 `headway` 之外加 `Map<String, Integer> groupIntervals`，
  少动一层调用方。
- **Q2 已落地（2026-09-19）**：`ResourceRepair`（按资源只延后、沿链传播、累计超限截断、邻表永远是前车）、`--max-wait`（缺省 60、封顶 `hold-max-seconds`，
  累计上限取 `assign-tolerance-seconds`）、`TimetableBuildOptions.Repair`、结果与报告的 `yields`、`Shift.Reason.YIELDED`；`--strict` 只对真冲突失败。
  与设计稿的出入：(1) `TerminalSerializer` **没有被吸收**，仍作为前置特例先跑，`ResourceRepair` 复用它抽出来的 `rewrite / truncateFrom / exceedsLimits`——
  9 个用例一行没改；(2) 多了一条**回滚判据**：一处让车若使冲突总数不减或真冲突变多就回滚并判为真冲突（否则修 A 站台的让车会把 X 端点推出新冲突，
  `TimetableBuilderStubTerminalTest` 就是这么发现的）；(3) 累计上限只算让车这一轮，串行自己的偏离不计入；(4) 命令面的动作按钮
  （`[投入运行] [详情] [交路] [导出]`、`[按放宽后的间隔重建]`、`[写回 <组> baseline]`）与可重复的 `--group-headway`（带组名补全）一并做了。
- **Q3 已落地（2026-09-19）**：`TimetableSetBuilder`（合成一次 `TimetableBuilder.build` 再按 route 归属拆表）、`VehicleDutyPlanner.PlannedTrip.pool`
  （车池：每条线一个，一辆车不跨线）、`BuildInput.lineByRoute`、`TimetableNeighborhoodLoader` 的多线排除；命令 `build/publish/unpublish/delete`
  的 `<line>` 接受逗号列表（补全跟着最后一段走），整组发布同一时刻并互记基线。与设计稿的出入：`SetResult` 不是 `Map<lineId, TimetableBuildResult>` 而是
  一份联编报告 + 每线一张表 + 每线一份基线——报告本来就是全部线一起的，拆开只会重复；`--headway` 没加 `组=秒` 语法，用 `--group-headway`（可重复）。
- 未验实服：Q1 的验收量（WS 2C/2N 各 150 s、CHT 偏离网格班次数、DS 能编出表）要拿 `.handoff/WsChtDiagnosisTest.java.txt`（已移出仓库，`git show 6efe6c1:.handoff/WsChtDiagnosisTest.java.txt` 取回）对 `../fetarute_experimental` 跑一遍。
