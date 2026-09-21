# F4 实现计划：让车修复的增量重扫与连锁段

这是**实现遍**的交接。设计已经定稿在 `docs/dev/timetable-feasibility-design.md` 的 §2.10（决策）与 §5.4（签名），
本文不重新设计，只给：当前代码的确切状态、动手顺序、我读代码时发现的坑、验收方法。

---

# 0. 你接手时的状态

分支 `wip/dynamic-dispatch-stabilization`，HEAD `a2a31e4`。F1–F3 已提交并全绿：

```
233 suites / 2092 tests / 0 failures / 0 errors
SpotBugs main 0 / test 0
```

四个 commit（从旧到新）：

| commit | 内容 |
| --- | --- |
| `6bbacbb` | 设计交接：三步实测推翻两条主假设，MT 的失败是四处结构缺陷 |
| `7c4589d` | **F1** 方向键按乘客首末站、端点闲置回库、回库回自己的车库、跨线泄漏、让车预算解耦 |
| `fb6fab4` | **F2** 残余按能不能被运行时吸收分类；报告发送器从命令类抽出 |
| `a2a31e4` | **F3** 相位第三层按资源选 δ |

实服进展（探针 v2 对实服库副本，纯走行）：

| | 起点 | F1 后 | F2 后 | F3 后 |
| --- | --- | --- | --- | --- |
| MT@1200 冲突 | 921 | **0** | 0 | 0（让车 58 → 0） |
| WS@300 冲突 | 1909 | 1909 | 1909 | **1399** |
| WS@300 让不掉的 | — | — | **189** | **88**（全部 `STUB_TERMINAL`） |
| WS@300 单次 attempt 耗时 | 127 s | — | 57 s | **43 s** |

**F4 要解决的就是最后那一行**，顺带把连锁修复打开。

---

# 1. F4 的两件事

## 1.1 增量重扫（性能）

`ResourceRepair` 每施加一处让车就把**整张表重新投影一遍**再全扫。jstack 抓到的热点：

```
ResourceRepair.repair → scan → TimetableConflictChecker.check → project → resource
  ↳ HashMap.computeIfAbsent（字符串键）
```

循环上界 `cap = 4 × trips + 8`（`ResourceRepair.java:187`），WS@300 有 902 班，所以最坏 3616 次全表投影。

**做法**：新增 `OccupationIndex`，只重投影被挪的那条交路、只扫它触及的资源。

## 1.2 连锁段（行为）

回滚判据从"单步后总数不减"改成"最多 6 步的连锁段结束后总数不减"。
WS@300 的 1909 处**没有一处是预算问题**（最长让车 +28 s，上限 300），全是连锁：单步判据把每一步都拒了。
连锁段让"把一串 1L 各挪 20 s"成为一次可以接受的修复。

---

# 2. 动手顺序（严格按这个来）

## 第 0 步：先立两条等价性测试，让它们在**重构之前**就绿

设计稿的「别自作主张」点名了这一条：

> **不要**改 `TimetableConflictChecker` 的比较语义：`OccupationIndex.scanAll` 必须与 `check` 逐字段相等，用测试钉住。

新建 `OccupationIndexTest`：

- `scanAllEqualsCheck`：造一批有冲突的占用（四类资源各来一点，含邻表 owner、含容量 >1 的站台组、含单线对向），
  分别走 `TimetableConflictChecker.check(...)` 与 `OccupationIndex.of(...).scanAll(...)`，**两个 `Report` 逐字段相等**
  （`conflicts()` 列表逐项比，不是只比 size）。
- `incrementalRepairEqualsFullRescan`：同一输入两条路径——全量重扫版与增量版——产物 `Timetable` 逐字段相等。

**先让第一条在"`OccupationIndex.scanAll` 直接委托给 `check`"的空壳实现下变绿**，再往里搬逻辑。这样任何语义漂移立刻红。

## 第 1 步：`vehicle` 从"扫描时现算"改成"投影时存下"

**这是整个 F4 唯一碰到核心比较逻辑的地方，风险集中在这里。**

现状（`TimetableConflictChecker.java:737` 的 `Resource.scan`）：

```java
private List<Conflict> scan(int separation, Function<String, String> vehicleOf) {
  List<Occupation> sorted = new ArrayList<>(occupations.size());
  for (Occupation occupation : occupations) {
    String vehicle =
        occupation.owner().isPresent()
            ? occupation.owner().get() + "|" + occupation.code()
            : "|" + vehicleOf.apply(occupation.code());
    sorted.add(new Occupation(..., vehicle));
  }
  sorted.sort(Comparator.comparingInt(Occupation::from).thenComparing(Occupation::code));
  ...
}
```

`vehicle` 是在 scan 里现算的，所以**没法按车移除占用**。要支持 `replaceVehicle` 必须在 `add` 时就算好。

改法：`project`/`addPlatform` 多接一个 `Function<String,String> vehicleOf`，`Resource.add` 用**同一个表达式**算出 vehicle 存进 `Occupation`，
`scan` 不再重算、直接排序。表达式一个字都不要改。

## 第 2 步：拆投影与扫描

`check()`（`TimetableConflictChecker.java:98`）本来就是干净的两段：

```java
Map<String, Resource> resources = new LinkedHashMap<>();   // 投影
for (Movement m : movements) project(m, profile, resources, sections, nodeTypes, platformCapacity);
for (Stay s : stays)          addPlatform(resources, platformCapacity, s.platform(), s.code(), s.from(), s.to(), s.owner());
List<Conflict> conflicts = new ArrayList<>();               // 扫描
for (Resource r : resources.values()) conflicts.addAll(r.scan(separation, vehicles));
conflicts.sort(...);
```

抽成一个返回 `TreeMap<String, Resource>` 的包内方法（**TreeMap 不是 LinkedHashMap**：索引要按资源键稳定遍历）。
`check()` 改成"建表 + 全扫"，行为不变。

`Resource` 与 `Occupation` 现在是 `private`（`:721` / `:708`），放宽到包内可见。

## 第 3 步：`OccupationIndex`

签名见设计稿 §5.4。三点注意：

- `replaceVehicle` 返回**前后触及的资源键并集**——只扫新占用的资源会漏掉"车挪走之后原来那处不再冲突"。
- 移除靠 `Occupation.vehicle` 相等，所以第 1 步是前提。
- `scan(keys)` 的排序必须和 `scanAll` 一致（同一个 `Comparator`），否则 `Report` 比不出相等。

## 第 4 步：接进 `ResourceRepair`

- `scan(input, allProfiles, current)`（`:250`）换成索引的增量重扫。
- 被挪的是一条交路（`State` 里的 `chains.get(d)`）：重投影它的全部班次 + 走行 + 待命，一次 `replaceVehicle`。
- `cap` 从 `4 × trips + 8` 改成 `2 × 起始冲突数 + 4 × trips`（`:187`）。

## 第 5 步：连锁段

`CHAIN_LIMIT = 6`。施加一处 → 增量重扫 → 新增的冲突若**全部可修**（后车是我、`w ≤ maxWait`）就继续修，
深度优先按检查器序，直到没有新增或到 6 步；段末比较 `(不可吸收数, 总数)`，变糟则**整段回滚**。
`State.Snapshot` 已经有快照/恢复机制（`:415` 附近），把它从"一步一拍"改成"一段一拍"。

新增两条用例（设计稿 §6）：`chainOfSmallYieldsIsAcceptedAsOneSegment`、`worseningChainIsRolledBackAsAWhole`。
**既有 8 例 `ResourceRepairTest` 一行都不要改**——它们是语义护栏。

---

# 3. 验收

| 项 | 判据 |
| --- | --- |
| 语义不变 | `scanAllEqualsCheck` 绿；既有 2092 例一行不改全绿 |
| 增量正确 | `incrementalRepairEqualsFullRescan` 绿 |
| 性能 | WS@300 单次 attempt **从 43 s 降到 5 s 以内** |
| 连锁 | WS@300 让车处数明显上升、让不掉的处数下降（现在是让车 202 / 让不掉 88） |

## 怎么量

```bash
# 1. 实服库复制一份，永远别直接打开实服库
cp ../fetarute_experimental/plugins/FetaruteTCAddon/data/fetarute.sqlite <scratch>/live.sqlite

# 2. 装探针，改顶部 DB 常量指向副本
cp .handoff/LiveNetworkProbeTest.v2.java.txt \
   src/test/java/org/fetarute/fetaruteTCAddon/dispatcher/schedule/timetable/LiveNetworkProbeTest.java

# 3. 只跑需要的那个用例
./gradlew test --tests '*LiveNetworkProbeTest.step3WsResonanceScan'
# 输出在 <scratch>/probe.log（探针每行同时写文件）

# 4. 用完删掉，别提交
rm src/test/java/.../LiveNetworkProbeTest.java
```

探针的 `step3WsResonanceScan` 开头就是 WS@300/360/420/600 与 MT@1200 的残余分列，耗时在每行末尾的括号里。

---

# 4. 我踩过的坑，你别再踩

- **探针留在 `src/test` 里会让 `./gradlew check` 跑它**，一次十几分钟。量完立刻删。
- **探针不经过命令层**，所以它用的是 `Limits` 的默认 `maxIdle=300`；实服走命令层时 `reclaim.enabled` 默认关着，
  我加的守卫会把它设成 86400（不回收）。所以探针在稀疏间隔上的「取消班次」数比实服悲观得多（WS@600 探针取消 227 班，实服不会）。
- **`ResourceRepair` 的 `scan` 每次重建整张资源表**，这正是 F4 要修的；在修好之前别用探针做需要反复迭代的实验，一轮 40 秒起。
- 报告里 `聚合冲突数` 与 `残余数` 可能不等：严格模式失败时 `TimetableBuildResult` 里没有冲突列表（走的是 `failure()` 工厂）。
- `TimetableConflictChecker` 的 `groupOf` 要求节点 id 至少四段且第二段是 `S`/`D`，走行路径点解析出来是空串——
  凡是按组索引的地方都要想到"解析不出"这一支。

---

# 5. F4 之后还欠着的一件事（F3 的尾巴）

F3 只把让不掉的冲突从 189 降到 88，**没到设计稿说的「接近 0」**。原因不是实现漏了，是周期流抽象的边界：

WS@300 是 902 班 / 228 交路 ≈ 每交路 4 班，一条 4 班交路的回库走行**每 4 个周期才出现一次**，
不是方向间隔的周期流，`PeriodicTemplate` 装不下它；只有单班交路的 tail 进了模板。而咽喉上撞的大量正是这种回库。

**补法**：把多班交路的回库也建成周期流——周期 = `interval × 每交路班次数`，相位取决于交路从第几个时隙开始。
改动集中在 `PeriodicTemplate`，`ResourcePhasePlanner` 不用动。

之所以排在 F4 后面：现在验证一次要 43 秒，F4 把它压到 5 秒之后，同样的工夫能试多得多的方案。

---

# 6. 仓库硬约束

- **没有 schema 迁移**。F4 不该碰存储，真要加列就是开发库删表重建。
- `RuntimeDispatchService` 方法数逼近 1000 上限，越线 SpotBugs **整类跳过检查**。F4 不碰它，但别顺手往里加东西。
- 4 空格缩进、K&R、**所有注释与 Javadoc 用中文**，解释设计动机而不是复述语句。
- 收尾跑 `./gradlew spotlessApply clean check`，并**自己解 `build/test-results/test/*.xml`** 看 failures，
  不要只看 `BUILD SUCCESSFUL`。
- **禁止主动 commit**：全绿也只能报告可提交状态，等用户确认。
- 提交信息用 conventional prefix + 中文摘要，正文写动机、关键取舍与运维变更，末尾加
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。

# 7. 位置速查

```
src/main/java/.../dispatcher/schedule/timetable/
    TimetableConflictChecker.java   839 行  check():98  project():241  addPlatform():334
                                            Occupation:708  Resource:721  Resource.scan():737
    ResourceRepair.java             664 行  cap:187  repair 主循环:189-215  scan():250  Snapshot:415
    ConflictAbsorption.java         336 行  F2 的判据，连锁段的「变糟」判定要用它
    PeriodicTemplate.java           F3，第 5 节那件事要改这里
    ResourcePhasePlanner.java       F3
docs/dev/timetable-feasibility-design.md    §2.10 决策 / §5.4 签名 / §6 测试矩阵 / §8 别自作主张
.handoff/LiveNetworkProbeTest.v2.java.txt   探针
.handoff/probe-f2-2026-09-20.log.txt        F2 那轮的原始日志，可作对比基线
```
