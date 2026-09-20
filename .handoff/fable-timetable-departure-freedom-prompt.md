# 任务：设计「发车时刻的自由度」——从全局网格到逐班次

又是**两遍走的第一遍**（设计 / 实现分开跑、用不同 effort）。规矩同上一轮：
这一遍把所有需要**权衡**的东西用完，实现者只做**执行**；他遇到任何需要做选择的地方，
都应该能在你的交接文档里查到答案，查不到就是你漏了。本轮不写实现代码，但要给到
方法签名、文件清单、测试清单的粒度。

上一轮的产物 `docs/dev/timetable-scope-design.md` 就是期望的形状，可以照着来。

---

# 第一部分：我遇到了什么（真实案例，不是假设）

## 现场

实服 `../fetarute_experimental`，company `FTAS` / operator `SURC`。给浦蓝线 WS 编表，
不传 `--headway`（于是取线路级 baseline 120s），得到这份报告：

```
===== 构建报告 =====
 ! 目标间隔 120s 有 2160 处冲突（站台 840、单线对向 602、区间 401、道岔 317）；放宽到 480s
 仍找不到无冲突的间隔，检查单线区段、站台数量与折返时间
  共用资源:
    FTAS/SURC/DS 共用 243 个资源，无已发布时刻表——它按 headway 发车，干扰单向，无法联合排布
    FTAS/SURC/MT 共用 229 个资源，无已发布时刻表——它按 headway 发车，干扰单向，无法联合排布
```

注意：DS 与 MT 都没有已发布时刻表，所以**这 2160 处全部是 WS 线内自己撞的**，与跨线无关。
跨线作用域那一轮（S1–S4，已 merge，`docs/dev/timetable-scope-design.md`）工作正常，
共用资源 243/229 正是它算出来的，不要去动它。

## WS 这条线长什么样

```
fta_lines:  WS 浦蓝线  spawn_freq_baseline_sec=120
            metadata: spawn_groups=[{WS_Short, baselineSec 150, maxOperationTrips 4},
                                    {WS_Full,  baselineSec 150, maxOperationTrips 8}]
fta_routes: WS-1L_ShortC  OPERATION  weight 1   末站 SURC:S:CHT:3 (TERMINATE)
            WS-2C_FullR   OPERATION  weight 3   首站 SURC:S:CHT:3 (STOP)
            WS-2N_FullR   OPERATION  weight 3   末站 SURC:S:CHT:3 (TERMINATE)
            WS-1C_ShortD  RETURN     weight 1   首站 SURC:S:CHT:3 (STOP)
```

**四条 route 全部以 CHT 为一端**。而 CHT 在调度图里只有一个 STATION 节点：

```sql
select node_id, node_type from fta_rail_nodes where node_id like 'SURC:S:CHT%';
-- SURC:S:CHT:3    | STATION      ← 只有这一个，所以站台容量 = 1
-- SURC:S:CHT:3:001| WAYPOINT
-- SURC:S:CHT:3:002| WAYPOINT
-- SURC:S:CHT:3:003| WAYPOINT
```

WS 沿线其余车站多为 2 股道，HHU 4 股道，只有 CHT 是 1。

⚠️ **一个还没查清的前提**：节点编号是 `:3`，通常意味着现场还有 1、2 号股道。
所以"CHT 容量 1"有两种可能——地形本来就只有一条股道，或者 `/fta graph build` 没扫全。
**你的设计必须在两种情况下都成立**，不能把"加股道"当作方案的一部分。如果你的结论依赖这个前提，
请显式说明依赖，并给出在另一种情况下的替代路径。

## 机制：发车时刻被钉在一张全局网格上

```java
// TimetableBuilder.java:369
int departureSeconds = (int) (allocation.slot() * headwaySeconds);
```

全线**一张公共网格**，所有 route 的所有班次只能落在 headway 的整数倍上。SWRR 决定的是
"第 k 个格子归哪条 route"，不决定"这一班几点开"。选车侧也帮不上忙：
`VehicleDutyPlanner#selectHost` 挑 `readyAt` 最早的那辆（FIFO），它决定"谁来接"，
不决定"几点开"。

于是在 CHT：车**到达**的时刻由走行时分决定，是个任意值；**发车**只能等到下一个网格点。
中间这段是被强制出来的等待，而"待命按 duty 建模"意味着这段等待整段算作站台占用
（`TimetableConflictChecker.Stay`）。单股道端点上，它就是冲突。

## 为什么放宽 headway 救不回来（非单调）

强制等待长度均匀分布在 `[0, h)`，期望 ≈ `h/2`。放宽 headway 时：

- 班次数 ↓ 约 `T/h`
- 每辆车在端点的强制等待 ↑ 约 `h/2`

端点总占用 ≈ `(T/h) × (折返 + h/2)` = `T·折返/h + T/2`，**后一项与 h 无关**。
也就是单股道端点的死占用存在一个与间隔无关的下界。搜索从 120 一路放宽到 480（上限 = 目标 ×4）
全部失败，不是"还没搜够"，是这条路本身不通。

**这个推导是我在会话里做的，没有实测验证。你的第一步就是验证或推翻它**——见下面的「先做这件事」。

## 被挑战的那条设计决策

`docs/dev/timetable.md` 现在写着：

> 搜索只放宽 headway、不挪动单个班次：表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成，
> 因此建议值是一个可以直接写回配置的数。

这条决策换来的是"产出一个能直接写回配置的标量"。CHT 这个案例说明它的代价：
**当瓶颈是某一个资源的局部占用时，一个全局标量没有能力表达解。**

---

# 第二部分：先做这件事（在设计之前）

不要直接开始设计。先把诊断坐实，否则你会为一个错的病因开药：

1. **量一下 CHT 的 Stay 时长分布**。用现有的 `TimetableConflictChecker.Stay` 投影，
   在 WS 的 build 产物上统计落在 `SURC:S:CHT:3` 上的待命区间长度。
   确认它们确实是 `折返 + 约 h/2` 量级，而不是别的成因（比如 duty 链在窗口末尾挂长尾、
   或者 RETURN 票发出时刻把车钉在那里）。
2. **确认 840 处站台冲突里 CHT 占多少**。如果 CHT 只占一小部分，那主因另有其人，
   本轮题目要重写。
3. **确认非单调**：对同一份输入跑 headway = 120 / 240 / 480 / 900 / 1800，
   记录 CHT 站台冲突数与 Stay 总时长。如果它不是"先降后平"或"单调上升"，我的推导就是错的，
   **直接说错在哪**，不要顺着我的话往下设计。

这三步可以用测试夹具做，也可以直接对实服库做只读分析。结论写进交接文档的最前面。

---

# 第三部分：要回答的问题

## A. 自由度选在哪一层

我在会话里草草列了三条，它们只是起点，不是选项清单——你可以提出更好的：

1. **每条 route 一个相位偏移**：不改网格，给每条 route 在周期内一个自己的起点偏移。
   SWRR 比例不变、确定性不变、"建议值可写回配置"基本不变。
2. **折返班次锚在车上而不是钟上**：终到端点的下一班取 `到达 + 折返` 而不是下一个网格点。
   代价是该 route 发车间隔不再规整，乘客侧可预期性下降。
3. **允许逐班次平移**：周期性事件调度 / 约束求解那一类。代价最大，直接冲击确定性与
   "建议值是一个数"。

- **A1** 选哪一层？给一个明确决策，不要列菜单。
- **A2** 给一个让你选中的方案失效的具体场景，并说明为什么仍然选它。
- **A3** 你的方案在 WS/CHT 这个真实案例上能把 2160 降到多少？给一个**可检验的预估**，
  以及"如果达不到，说明我哪一步判断错了"。
- **A4** 如果 CHT 真的只有一条股道且四条 route 都必须在那里折返，是否存在**任何**
  发车时刻安排能让它可行？如果不存在，`build` 应该怎么把这个结论告诉运营者——
  现在的文案是"仍找不到无冲突的间隔，检查单线区段、站台数量与折返时间"，
  它没有区分"再搜搜看也许有"和"结构上不可能"。

## B. 与现有性质的冲突

- **B1 确定性**：现在的措辞是"同一份网络状态 + 同一份配置 + 同一组已发布邻表，永远产出同一张表，
  包括 trip/duty 主键"。你的方案若引入搜索或迭代，必须是**确定性的**消解过程
  （固定顺序、无随机源、无哈希序依赖）。说明怎么保证，以及主键派生规则要不要跟着改
  （现在是 `timetableId + tripCode` 名字派生，tripCode 含 `%s%s-%03d` 的序号）。
- **B2 SWRR 的比例**：weight 是目标服务比例，不是抽签概率。平移或加相位之后，
  比例还成不成立？短窗口不 starvation 这条性质还在不在？
- **B3 "建议值是一个可以直接写回配置的数"**：这条还要不要保？不保的话，
  `build` 报告里"间隔来源"和"实际使用的间隔"两行该换成什么？运营者拿什么写回 `spawn_freq_baseline_sec`？
- **B4 乘客侧可预期性**：不规整的发车间隔在运营上是不是可接受？如果只在某些 route 上接受，
  判据是什么、谁来配置？

## C. 与已落地部分的关系

- **C1** 跨线邻表检查（S1–S4）刚 merge，`TimetableOccupancyProjector` / `TimetableNeighborhoodLoader` /
  `fta_timetable_baselines` / publish 重检都在跑。你的方案会不会改变"邻表的运行是不可移动的事实"
  这条路权语义？我平移自己的班次时，邻表仍然不动——这一点要说清。
- **C2** headway 搜索（10s 步长、上限 4 倍）还留不留？留的话和新自由度怎么组合、谁先谁后？
- **C3** 运行时侧要不要改？出站门控的计划扣留（`StationStopCoordinator#holdsDeparture`，
  `hold-max-seconds` 默认 120、再被 150s 硬上限封顶）是按表定时刻扣留早到的车。
  如果表定时刻变得不规整，`assign-tolerance-seconds`（默认 300）与 `TripMatcher` 的匹配还成不成立？
- **C4** `VehicleDutyPlanner#selectHost` 现在按 `readyAt` 最早选（FIFO）。
  新自由度下"谁来接"和"几点开"会不会耦合成一个联合决策？如果会，先定班次再派车这条
  顺序不可交换的不变量还守不守得住？

---

# 必须保住的不变量（系统级，不可协商）

1. 时刻表是从路网**算**出来的，不是从历史跑车记录**录**出来的。
2. 同一份网络状态 + 同一份配置 + 同一组已发布邻表，永远产出同一张表，包括 trip/duty 主键。
3. weight 是目标服务比例（SWRR），不是抽签概率。
4. 每辆实体车都有有限交路且最终必须回库；终止性要能在**构建产物**上直接断言。
5. 先定班次、再派车，顺序不可交换。
6. 宁可少排一班，也不排一班发不出去或回不了库的车。
7. 冲突模型只做纯数据运算、不跑调度器；回放只用于校验，永远不作为 build 输入。

其中 2 与 5 最可能被你的方案挤压。**挤压可以，但必须显式重写措辞并说明新说法为什么仍然可检验**，
不能悄悄放弃。新措辞会进 `docs/dev/timetable.md`，请给确切文字。

---

# 交付物

落盘 `docs/dev/timetable-departure-design.md`，实现者只读它就能开工：

1. **诊断结论**（第二部分那三步的实测结果）放最前面。推翻了我的推导就直说。
2. **决策**：A/B/C 各给明确选择，不要"取决于"。
3. **理由 + 反例**：每个决策配一个会让它失效的场景，并说明为什么仍然选它。
4. **逐文件改动清单**。注意 `TimetableBuilder` 已 ~900 行、`TimetableService` ~1000 行，
   仓库有「重构优先、拒绝补丁」的硬要求，该抽协作者就点名抽哪个。
5. **完整方法签名** + 中文 Javadoc 摘要，不要方法体。
6. **测试矩阵**：每条不变量对应哪个用例；新自由度要什么新夹具。
   现有夹具注意：`DEP-A-B-C` 在 headway 300 + 折返 180 下**本来就不可行**，别拿它当无冲突正例。
   建议新增一个"多条 route 共用单股道端点"的夹具——那正是 CHT 的抽象形态。
7. **命令面与数据模型影响**：新参数/新列/新报告字段/config-version（现为 33）。
8. **阶段划分**：每阶段独立编译、独立过 `./gradlew clean check`、独立验证。
9. **给实现者的「别自作主张」清单**。

---

# 实现者会撞上的仓库硬约束

- **本项目没有 schema 迁移**。加列意味着开发库删表重建；新表走 `CREATE TABLE IF NOT EXISTS` 可以
  （`fta_timetable_baselines` 就是这么加的）。改配置要同步 `ConfigUpdater` 与 `ConfigUpdaterTest`。
- `RuntimeDispatchService` 方法数逼近 1000 上限，越线 SpotBugs **整类跳过检查**。要加能力先抽协作者。
- 4 空格缩进、K&R、**所有注释与 Javadoc 用中文**、解释设计动机而非显而易见的语句。
  收尾跑 `./gradlew spotlessApply clean check`。
- `lang/zh_CN.yml` 的 MiniMessage 占位符只能 `[a-z0-9_-]`，**禁止驼峰**。
- **禁止主动 commit**，全绿也只能报告可提交状态等用户确认。

# 禁止

- 不要提出"先在实服录一遍再编表"——违反不变量 1。
- 不要让 build 依赖调度器回放——违反不变量 7。
- 不要把"给 CHT 加股道"当作方案的一部分（那是地形工程，且前提还没查清）。
- 不要动跨线作用域那一套（S1–S4 刚 merge 且工作正常）。
- 不要在本遍写实现代码。
- 涉及具体字段、类名、列名时先读代码确认。

# 位置

```
src/main/java/org/fetarute/fetaruteTCAddon/dispatcher/schedule/timetable/
    TimetableBuilder.java:369          ← 网格：departureSeconds = slot * headway
    TimetableBuilder.java:344-360      ← slotCount 与可行性判据
    WeightedTripAllocator.java         ← SWRR
    VehicleDutyPlanner.java:173-215    ← selectHost，按 readyAt 最早选
    TimetableConflictChecker.java      ← Stay / Movement 投影与扫描
    TimetableOccupancyProjector.java   ← 邻表投影（跨线那轮的产物，别动）
    scope/                             ← 邻表与基线（别动）
docs/dev/timetable.md                  ← 主文档
docs/dev/timetable-scope-design.md     ← 上一轮的设计交接文档，照这个形状写
.handoff/timetable-test-routine.md     ← 实服测试 routine，含 CHT 的实测记录
```

分支 `wip/dynamic-dispatch-stabilization`，HEAD `2f24f48`，
全量 `clean check` 绿（219 suites / 2002 tests / 0 failures，SpotBugs main+test 0）。
