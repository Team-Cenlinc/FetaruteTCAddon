# 发车时刻的自由度：从全局网格到单股道端点的资源串行——设计交接

本文是**设计遍**的产物，读者是实现遍。判定标准同 `timetable-scope-design.md`：实现者遇到任何需要做选择的地方，
都应该能在这里查到答案；查不到就是本文漏了。本文不含实现代码，但给到签名、文件清单与测试清单的粒度。

基线：分支 `wip/dynamic-dispatch-stabilization`，HEAD `2f24f48`，全量 `clean check` 绿（219 suites / 2002 tests）。
时刻表现状见 `docs/dev/timetable.md`；跨线作用域见 `timetable-scope-design.md`；本文只写增量。

---

## 0. 诊断结论（先读这个：题目变了）

对实服库（`../fetarute_experimental`，`fetarute.sqlite` 的**只读副本**）离线跑 WS 的 build 内部流程
（`prepare` + 逐 headway `attempt`，反射调用，不经过命令层），窗口 06:00–23:00，dwell 20 / max-trips 4 /
折返 180 / 裕量 30 / 模型 8 bps。复现方法见 §11。

### 0.1 三步实测的结果

**① CHT 的 Stay 时长分布。** CHT 上每一段"两班之间"的待命长度在同一 headway 下**全部相等**（不是分布，是常数）：
120 s 时 501 s = 折返 180 + **网格等待 321**；末班到发回库票的待命恒为 180（RETURN 早就锚在车上了，`returnSecond = 到达 + 折返`）。
网格等待随 headway 的变化是：

| headway | 120 | 150 | 180 | 240 | 300 | 360 | 480 | 600 | 900 | 1200 | 1800 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 两班之间待命（s） | 501 | 291 | 441 | 261 | 441 | 621 | 981 | 1341 | 441 | 741 | 1341 |
| 其中网格等待（s） | 321 | 111 | 261 | 81 | 261 | 441 | 801 | 1161 | 261 | 561 | 1161 |
| CHT 站台占用合计（s，窗口 61200） | 48609 | 26985 | 29646 | 16155 | 17928 | 18963 | 20097 | 21654 | 5949 | 6447 | 7605 |

它不是均匀分布在 `[0, h)`、期望也不是 `h/2`。原因：2C 只占 SWRR 七个时隙里的三个（0、3、5 号），
1L 到达 CHT 的相位固定，FIFO 选车后所有车等的都是同一个"下一个 2C 时隙"。等待量是**相位余数**，随 h 非单调跳动。

**② 840 处站台冲突里 CHT 占多少。** 120 s 时（本地复现为 1575 处，见 §0.4 的口径说明）：站台 605 处里 **582 处在 CHT**（96%），
单线对向 427 处里 **354 处是 CHT 的岔线**（`single:...bridge:SURC:S:CHT:3~SWITCHER:-137:90:368`，83%），
区间与道岔各有 141 处落在 CHT 进站边与进站道岔。合计约 77% 直接压在 CHT 这一根股道上，其余主要是 LWN 车库咽喉。
**主因确实是 CHT，题目不用重写。**

**③ 非单调性。** 剔除下面 §0.2 的伪冲突后，网格模型的真实冲突数随 headway 是：

| headway | 120 | 150 | 180 | 240 | **300** | 360 | 480 | 600 | **900** | 1200 | 1800 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 原始冲突数 | 1575 | 1227 | 630 | 319 | 122 | 304 | 111 | 88 | 40 | 28 | 20 |
| 其中伪冲突（同一辆车撞自己） | 287 | 174 | 193 | 146 | 117 | 98 | 40 | 33 | **40** | **28** | **20** |
| 真实冲突 | 1288 | 1053 | 437 | 173 | **5** | 206 | 71 | 55 | **0** | 0 | 0 |
| 其中 CHT 站台 / CHT 单线 | 295/354 | 233/3 | 102/94 | 10/9 | 3/0 | 4/0 | 66/3 | 55/0 | 0 | 0 | 0 |

既不是"先降后平"也不是"单调上升"：300 s 几乎可行（5 处），360 s 又回到 206 处，900 s 起干净。
**你的推导 `T·折返/h + T/2` 是错的**，错在两处：(a) 网格等待不是 `h/2` 的均匀量，是随 h 跳动的相位余数，所以不存在一个与 h 无关的
`T/2` 下界（占用合计 48609 → 5949 是明显下降的，只是不单调）；(b) "放宽到 480 仍不可行"的直接原因根本不是占用下界，
是 **900 s 以下每个 headway 都有伪冲突把它判成不可行**——900、1200、1800 三档的"冲突"**全部**是伪冲突，
说明搜索无论放宽到多少倍都不可能成功。

但你的**结论**是对的：单靠放宽 headway 救不回来。原因不同：可行性由 SWRR 七时隙模式与走行时分的相位对齐决定，随 h 闪烁，
10 s 步长、4 倍上限的搜索在 120–480 之间恰好没有干净点（300 差 5 处）。

### 0.2 两个模型缺陷（比"自由度"更先要修）

**缺陷 A：同一辆车撞自己。** `TimetableConflictChecker` 只用 `code` 相等来豁免自撞；Stay 的 code 是 duty 号（`D001`），
运行的 code 是车次号（`WS-2C_FullR-001`）或走行代号（`D001-RETURN`）。它们是同一辆车，却被当成两辆。
配合缺陷 B，每次经过 CHT 必然产生两处伪冲突：进站班次经过 `SURC:S:CHT:3:003` 的占用与自己随后的待命只差 18 s（< 裕量 30），
出站/回库经过 `:003` 与自己刚结束的待命也只差 18 s。实测样本：

```
PLATFORM platform-group:SURC:S:CHT: D001-RETURN [3999–4019] 与 WS-1L_ShortC-005 [4021–4041]   ← 这是真的（两辆车）
PLATFORM platform-group:SURC:S:CHT: D001 [4779–4959] 与 WS-2N_FullR-001 [4963–4983]            ← 这是假的（D001 就是 2N-001 的车）
```

**缺陷 B：进站路径点被算成站台。** `platformsOf` 对 route 的**每个**停靠点都造一个 `Platform`，站台组由 `groupOf(nodeId)` 从命名解析：
`SURC:S:CHT:3:003` 解析出组 `SURC:S:CHT`。而 `platformCapacity` 只数 `STATION`/`DEPOT` 类型的节点（`:003` 是 `WAYPOINT`）。
于是占用按"叫 S: 的都算"、容量按"真站台才算"，口径不一致：一辆车在进站路径点上也在消耗站台组容量。
实测 `platform:SURC:S:CHT:3:003` 一项就贡献 141 处冲突。

**缺陷 C：PASS 路径点被算了停站。** `TimetableTimingCalculator.resolveDwellSeconds` 不看 `passType`，PASS 行的 `dwell_secs` 为 NULL
就用 `--dwell` 兜底值 20。WS 每条 route 有 6–7 个 PASS 路径点，全程时分被虚增：1L 459→319、2C 1103→1003、2N 1161→1001、
回库 442→322（修正后值）。CHT 进站 141 blocks 本该 18 s，现在是 38 s。它同时让每个 PASS 点都成为一段 20 s 的"站台占用"，
把缺陷 B 放大。

这三条不是自由度问题，是**模型错误**，放在 P0 先修（§8）。修完之后网格模型对 WS **在 900 s 可行**（今天的口径）、
**在 480 s 可行**（PASS 修正后的时分），搜索会成功——但成功在 300–480 这一档，离目标 120 还很远。所以自由度仍然要做。

### 0.3 自由度实测：锚在车上不够，要锚在资源上

用同一份 build 产物做两个后处理模拟，再过一遍（未改动的）冲突检查，只数真实冲突：

- **锚在车上**（方案 2：容量 1 站台出发的续班 = 到达 + 折返，不等网格）：CHT 占用合计 48609→26460（120 s）、20097→6480（480 s），
  但真实冲突 120 s 时 1288→892，**150 s 时 1053→1304、480 s 时 71→119 反而变多**——提前发车的 2C 在岔线上撞上别的车的 2N 进站
  （480 s 时单线冲突 3→51）。发车只看自己就绪、不看岔线上有没有别人，等于把冲突从站台挪到岔线。
- **按资源串行**（本文方案：把容量 1 的端点当串行资源，进站要等端点空出来——整趟延后，续班 = 到达 + 折返）：

| headway | 120 | 150 | 180 | 240 | 300 | 360 | 480 | 600 | 900 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 网格模型真实冲突 | 1288 | 1053 | 437 | 173 | 5 | 206 | 71 | 55 | 0 |
| 串行后真实冲突 | **68** | 30 | 21 | 7 | **0** | 66 | **0** | 0 | 0 |
| 其中 CHT | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 被延后的班次 / 总数 | 177/216 | 113/171 | 96/144 | 38/109 | 30/87 | 25/72 | 34/53 | 29/44 | 9/29 |
| 最大延后（s） | 1870 | 718 | 962 | 654 | 710 | 441 | 801 | 1161 | 261 |
| 2C 发车间隔 min/med/max | 304/840/1726 | 1050/1050/1306 | 434/1260/2086 | 1680 恒 | 2100 恒 | 2520 恒 | 3360/3360/3886 | 4200/4200/4606 | 6300 恒 |

CHT 的冲突按构造归零；剩下的全在 LWN 车库咽喉（`single:...bridge:SWITCHER:587:74:1028~705:74:1172`）和 HHU 附近的
545/558 两个道岔——那是下一个瓶颈，不在本轮（§10）。代价是延后：120 s 时 82% 的班次被推后、最长 31 分钟，
因为 CHT 每次折返占岔线 ≈ 286 s（今天的时分）而每 840 s 要经过两次（利用率 68%），排队自然长。

### 0.4 口径说明与两个顺带发现

- 本地复现 120 s 时是 1575 处而不是实服报告的 2160：离线图由 `fta_rail_nodes/edges` 直接还原，**没有**套 `fta_rail_edge_overrides`
  的限速覆盖与联锁快照，走行时分有出入。各类冲突的**比例**与 CHT 占比一致，结论不受影响；实现者在 P0 收尾时用实服图再量一次（§8）。
- **`--max-trips` 默认 4 把份额截断了。** WS 的交路只能是 `1L → 2C → 2N → 回库`（第 4 班若是 2C 会终到没有回库线路的 NTA，
  `selectHost` 不接），所以每个交路只带一班 2C，SWRR 分给 2C 的 3/7 时隙里有 2/3 因 `NO_CREATE_ACCESS` 被取消：
  120 s 时 507 班排了、**291 班取消**，份额 1L:2C:2N = 78:69:69（目标 14/43/43%，实际 36/32/32%）。
  `--max-trips 7` 时是 78:136:136、取消 158。这不是本轮题目，但要知道：报告里那张"2160 处冲突"的表其实是一张 Full 每 14 分钟一班的稀疏表，
  它照样不可行——说明问题在相位与模型，不在密度。
- **CHT 的股道数**：库里 `SURC:S:CHT:3` 是唯一 STATION，`:3:001–003` 三个 WAYPOINT 是它的进站路径（45+37+59 blocks），
  `fta_stations.CHT.graph_node_id` 也指向 `:3`。没有 `:1`/`:2` 的任何痕迹。本设计**不依赖**这个前提：串行只在图说"容量 1"的端点上触发，
  将来扫出 `:1`/`:2`，容量变 3，串行自然不再触发，一切回到网格。

### 0.5 折返 180 s 不是物理事实，是 build 的参数（用户指出，复核后成立）

上面所有"每次折返占用 246 s"的算术都用了 `--turnaround` 的默认值 180（`VehicleDutyPlanner.Limits.DEFAULT_TURNAROUND_SECONDS`，
Javadoc："终端折返时间，同时用作出库到站后的就绪时间"）。运行时**没有**这样一个最短折返：终到即待命，下一班的票一到就复用，
车在起点只停它自己那个 STOP 的 dwell（CHT 上 2C 的首站 dwell = 20 s）。运行时代码里找不到任何折返最小值，180 只存在于 build 侧。
所以物理上的每次折返占用是 **进站 18 + dwell 20 + 出站 18 + 裕量 30 ≈ 86 s**，不是 246 s。

把折返换成 20 s（并修 PASS 停站）重跑：

| headway | 120 | 150 | 180 | 240 | 300 |
| --- | --- | --- | --- | --- | --- |
| max-trips 4：网格真实冲突（其中 CHT 站台/单线） | 74（6/4） | 30（4/4） | 22（4/2） | 20（4/2） | 7（2/2） |
| max-trips 4：串行后真实冲突 | 38 | 6 | **0** | 0 | 0 |
| max-trips 7（份额接近 1:3:3）：网格真实冲突（CHT） | 867（249/122） | 51（8/8） | 198（150/2） | 340（100/66） | 7 |
| max-trips 7：串行后真实冲突 | 138 | 31 | 44 | 1 | 0 |
| CHT 站台占用合计（max-trips 4，秒） | 4430 | 8576 | 11427 | 14815 | 16888 |

对比折返 180：120 s 时 CHT 占用 48609 → 4430，网格真实冲突 1288 → 74。**`--turnaround 180` 一个参数就贡献了 CHT 九成的占用。**
折返 60 s 的结果介于两者之间（max-trips 7、PASS 修正后：120 s 网格 1314 / 串行 68；180 s 网格 1444 / 串行 24）。

这条推翻 §2.2 原来的说法"120 s 在结构上不可能"：那是 180 s 折返下的结论。用物理折返算，CHT 的全线时隙下界 ≈ 3.5 × 86 / 7 ≈ 43 s，
120 s 在容量上绰绰有余；剩下的是相位与另外两个瓶颈（NTA 站台、LWN 咽喉）。§2.2 已按此改写。
`--turnaround` 的默认值该不该从 180 改成"终到站 dwell + 掉头余量"，是用户的决定（§10），本轮不改。

---

## 1. 结论速览

| 题 | 决策（一句话） |
| --- | --- |
| P0 | 先修三个模型缺陷：PASS 不停站；进站路径点不是站台；同一辆车不撞自己。它们让搜索在任何 headway 下都不可能成功 |
| A1 | 自由度放在**"容量为 1 的端点"这一类资源**上：端点按资源串行——进站班次只能延后（等端点空出来），续班锚在车上（到达 + 折返），其余一切仍在网格 |
| A2 | 失效场景：端点前一站也是容量 1（无处等待）、或车库咽喉同样是单线（WS 的 LWN 就是）。仍然选它：它用一条规则同时消掉站台、岔线、道岔三类冲突，且不引入搜索 |
| A3 | 预估分两档（§2.3）：`--turnaround` 仍是默认 180 时，WS 目标 120 被下界直接拦下，`--headway 150` 起 CHT 冲突归零、回退落在 300–480 s；`--turnaround 20`（物理值）时目标 120 直接可搜，回退落在 **150–180 s**（max-trips 4）或 240–300 s（max-trips 7）。两档下 CHT 都不再出现在冲突里。达不到的判据见 §2.3 |
| A4 | 结构下界 = 按**配置的折返值**算的每次占用 × 按权重的经过次数；报告先算再搜，目标低于下界时明说"结构上不可能"并**打印所用折返值**。用默认 180 算 WS 是 123 s，用物理折返 20 算是 43 s——公式本身对，结论随参数走（§0.5） |
| B1 | 确定性保住：串行是按（最早可发时刻, 车次号）排序的事件过程；车次序号改为按**实际**发车顺序派生 |
| B2 | SWRR 比例不变：串行不增减班次（只在超限时截断交路并上报）；"短窗口不饿死"在班次数上仍成立，在发车**间距**上不再承诺 |
| B3 | "建议值是一个数"**保住**：headway 搜索照旧；报告加两行（端点串行、结构下界）。写回 `spawn_freq_baseline_sec` 的仍是回退后的间隔 |
| B4 | 不规整间隔只发生在容量 1 端点始发的 route 上，由图决定、不加配置；报告列出偏离网格的班次 |
| C1 | 邻表仍是不可移动事实：串行时把邻表在该端点的占用当成已预订的时段，我的车绕着它排 |
| C2 | headway 搜索保留、上限不变；顺序是 SWRR → 派车 → **端点串行** → 冲突检查，每次 attempt 内部都这样 |
| C3 | 运行时**不改**：延后的是计划里的待命（Stay），不是早到扣留；`hold-max-seconds`、`assign-tolerance` 语义不变 |
| C4 | "谁来接"仍按名义时刻 FIFO 决定；"几点开"由串行决定；顺序不可交换的不变量改写为三步（§2.6） |

---

## 2. 决策与理由

### 2.1 A1：自由度放在哪一层

**决策**：按资源串行，作用域 = 容量为 1 的站台组，且它是至少一条 OPERATION route 的起点或终点。

对经过这种端点的班次：

1. **进站班次**（终点在端点）：到达时刻 `= max(名义到达, 端点空闲时刻 + 进站走行)`；差值加到这一班的发车上——整趟车延后，在它的起点等（起点容量 ≥ 2 才等得起，见 A2）。**只延后，永不提前。**
2. **续班**（起点在端点，且是同一交路的下一班）：发车 `= 到达 + 折返`，不等网格。可能早于、也可能晚于它的名义时隙。
3. **回库走行**（RETURN）：已经是"到达 + 折返"，不变。
4. 端点的空闲时刻 `= 出站发车 + 出站走行 + 裕量`；出站发车在进站时就已知（续班锚在车上），所以一次进站就能立刻登记下一次可用时刻。
5. 其余班次：`发车 = max(名义时隙, 本车就绪)`——和今天一样。

**为什么不是每 route 一个相位偏移（方案 1）**：CHT 有两路进站流（1L 从车库、2N 从 NTA），相位不同（120 s 时分别是 39 与 141/381/621 mod 840）；
一个偏移只能对齐其中一路。而且实测网格等待是随 h 跳动的相位余数（§0.1），偏移量必须随 h 重算，"建议值可写回配置"就名存实亡。

**为什么不是纯粹锚在车上（方案 2）**：实测它把冲突从站台搬到岔线（§0.3）。端点是一根股道，进出共用，"我就绪就走"不看别人是否正在进来。

**为什么不是逐班次平移 / 约束求解（方案 3）**：它要解决的是同一个问题——端点上的顺序。串行就是最小的那个求解器：
只在一类资源上、只做一次前向扫描、只有一个决定（谁先用端点）、决定规则固定（名义到达早者先）。多出来的通用性没有对应的需求。

**反例（让它失效的场景）**：

- 端点的前一站也是容量 1（例如两根股道的支线尽头连着一个单股道中间站）：延后的车无处可等，延后本身就制造新冲突。
  处理：串行只延后**起点容量 ≥ 2** 的进站班次；起点也是容量 1 时不延后、照常登记，交给冲突检查报出来（报告里标 `无处等待`）。
- 车库咽喉是单线桥（WS 的 LWN：`SWITCHER:587~705`）：它不是站台组，串行不管它。实测串行后剩余冲突全在这里。
  这是本轮明确不做的下一个瓶颈（§10），A3 的判据里把它列为"达不到时先查这个"。
- 端点被邻表大量占用：我的进站班次可能被推到窗口之外。处理：截断交路（§2.5），原因 `STUB_SATURATED`，如实上报。

仍然选它，因为这三种情况下它都**不比今天差**（今天是直接不可行），而在 CHT 这种最常见的"支线单股道尽头"形态上它把问题解干净。

### 2.2 A4：结构下界与失败文案

**决策**：build 在搜索之前，对每个容量 1 端点算一个**结构下界**，并把它写进报告；目标 headway 低于下界时，失败文案明确说"结构上不可能"。

下界的算法（纯算术，无搜索）：

```
每次折返占用 v = max(进站走行) + 折返 + max(出站走行) + 裕量        # CHT：18 + 180 + 18 + 30 = 246 s（PASS 修正后）
SWRR 一个周期（Σweight 个时隙）内经过该端点的次数 n = Σ(weight × 该 route 以它为起点或终点的次数) / 2
                                                    # 起点算一次、终点算一次，一次折返 = 一进一出，所以除 2；WS：(1+3+3+... )见下
全线时隙下界 h_min = ceil(n × v / Σweight)
```

WS：1L 终到（w1）、2C 始发（w3）、2N 终到（w3）；回库 RETURN 不带权重，按"每个交路末尾一次出站"折进 1L 的一进一出里。
n = (1 + 3 + 3) / 2 = 3.5。折返取**配置值**：`--turnaround 180`（今天的默认）→ v = 246，h_min ≈ **123 s**，目标 120 在下界之下；
`--turnaround 20`（终到 dwell，见 §0.5）→ v = 86，h_min ≈ **43 s**，120 绰绰有余。同一条公式，结论完全由折返参数决定，
所以报告必须把所用折返值打在这一行里，失败文案里的建议第一条是"核对 --turnaround 是否远大于终到站的 dwell"。

这个数是**下界不是保证**：它假设端点利用率 100%、进出严丝合缝。实测折返 20 时串行后 120 s 仍剩 38 处冲突（在别处），说明下界只回答"值不值得搜"。

**失败文案分两种**（`TimetableBuildReportText` 新增）：

- 目标 < 下界：`目标间隔 120s 低于端点 SURC:S:CHT（单股道）的结构下界 123s（按 --turnaround 180 计）：任何发车安排都不可行。可做的事：核对 --turnaround 是否远大于终到站的 dwell、降低经过该端点的 route 权重、或增加股道`
- 目标 ≥ 下界但搜索失败：`目标间隔 120s 在放宽到 480s 的范围内没有找到无冲突的间隔；剩余冲突集中在 <前三个资源>`

第二种要列出剩余冲突最多的三个资源键（`describeConflicts` 已有按资源计数的素材）。

### 2.3 A3：可检验的预估

实现 P0 + P1 之后，在实服对 WS 执行 `/fta timetable build FTAS SURC WS WS-D1 --start 06:00 --end 23:00`，分两档验：

**档一：不传 `--turnaround`（默认 180）**

1. 报告出现一行 `结构下界: SURC:S:CHT …（按 --turnaround 180 计）`，数值在 120–145 之间；目标 120 低于它，build **直接失败**并给结构文案，不搜索。
2. 加 `--headway 150` 再 build：目标 150 下的冲突明细里**不再出现**任何 `SURC:S:CHT` 资源；冲突总数 ≤ 300，集中在
   `SWITCHER:Towny:587:74:1028~705:74:1172`（LWN 咽喉）与 `545/558` 两个道岔；回退间隔落在 300–480 s。

**档二：`--turnaround 20`（终到 dwell，物理值）**

3. 结构下界一行给出 ≈ 43 s，不拦截。
4. 目标 120 s 下 CHT 不出现在冲突里；冲突总数 **≤ 80**（max-trips 4）；回退间隔落在 **150–180 s**。
5. 加 `--max-trips 7`：份额接近 1:3:3（2C、2N 各 ≈ 135 班），目标 120 下冲突 ≤ 200、全在 NTA 站台与 LWN 咽喉，回退落在 240–300 s。
6. 两档下 `export` 里 2C 的发车都不再落在 headway 的整数倍上；报告里 `端点串行` 那行给出偏离班次数与最大偏移。

达不到时怎么归因：

- 第 2 条不成立 → 端点识别错了：检查 `stubGroups` 是否用 `GraphIndex.platformCapacity()`（数 STATION/DEPOT 节点）而不是别的口径；
  或 `approachIn/approachOut` 取错了停靠点（要用 profile 里**属于该站台组**的路径点，见 §5.1）。
- 第 3 条数量对但资源不对（CHT 之外冒出新资源）→ 延后的车在起点等不起：看 `无处等待` 标记，NTA 容量 2 不够时会在 `platform:SURC:S:NTA:2` 上报冲突，那是真实的、应保留。
- 第 4 条落在 480 以上 → LWN 咽喉是第二个串行资源，需要 §10 的扩展；本轮不做，报告应已把它列为剩余冲突的首位。
- 第 1 条数值明显偏离 → 时分口径（实服图有限速覆盖）；用实服图重跑 §11 的量表。

### 2.4 B1 确定性

串行是一个事件过程；确定性靠三条：

- 事件队列按 `(最早可发时刻, 车次临时 code)` 全序；同一时刻按 code 字典序。
- 端点空闲时刻表按站台组键（字符串）排序的 `TreeMap`；邻表预订按 `(from, code)` 排序后合并。
- 车次序号在串行**之后**按 `(实际发车, 名义发车, 临时 code)` 派生，主键仍由 `timetableId + tripCode` 名字派生。

**不变量 2 新措辞**（替换 `timetable.md` 对应段落）：

> 同一份网络状态 + 同一份配置 + 同一组已发布邻表，永远产出同一张表——包括每一班的**实际**发车时刻、车次序号与 trip/duty 主键。
> 端点串行是确定性的事件过程：事件按（最早可发时刻，车次号）排序，端点空闲时刻按站台组键排序，邻表预订按（起始时刻，车次号）排序；
> 不引入任何随机源，也不依赖任何哈希遍历序。

### 2.5 B2 SWRR 比例与 B6 宁少排

串行**不增减班次**：哪些班次存在、按什么顺序、归哪条 route，全部在 SWRR 与派车两步定死。串行只改时刻。
所以份额报告（`shares`）在串行前后逐字段相同——测试矩阵里有这一条。

唯一的例外是**截断**：延后使某一班的 `发车 + 全程 + 收尾走行` 超过交路时长上限，或实际发车越过计划窗口末尾时，
从那一班起截断交路：弹掉尾段班次直到 `Legs.closable(last)`（与 `VehicleDutyPlanner` 窗口末尾的处理同一条规则），
弹掉的班次以新原因 `STUB_SATURATED` 进 `droppedTrips`，份额按保留班次重算（今天取消班次也这么做）。

**不变量 6 补一句**：

> 端点串行造成的延后使交路超过时长上限或越过计划窗口时，从那一班起截断交路并如实上报（`STUB_SATURATED`），而不是排一班到不了的车。

"短窗口不饿死"：班次**数量**上的性质不变（SWRR 序列没动）；发车**间距**上不再承诺——2C 在 120 s 时的间隔 min/med/max = 304/840/1726。
文档里把这条性质的表述从"不出现同一条线连续霸占"限定为"班次序列上不出现"。

### 2.6 C4 与不变量 5

派车（`selectHost`，按 `readyAt` 最早 FIFO）在**名义**时刻上做；串行在其后按实际时刻推进。两者不耦合成联合决策：
串行永远接受派车给的交路链，只改链上各班的时刻。代价是派车可能在名义时刻上选了一辆实际上并不最早就绪的车——接受，
因为它是确定性的，而且更优的选择需要把派车也变成时间推进过程（那才是联合决策）。

**不变量 5 新措辞**：

> 先定班次（存在、顺序与名义时隙）、再派车（谁来跑）、最后按资源定实际时刻（几点开）。三步顺序不可交换；
> 第三步只在**容量为 1 的端点**上偏离名义时隙：续班锚在车上（到达 + 折返），进站班次只能延后、不能提前；其余班次仍在网格上。

### 2.7 B3 / B4 报告与"建议值"

headway 搜索保留（§2.8），所以"实际使用的间隔"仍是一个数，仍能写回 `spawn_freq_baseline_sec`。报告新增两行，位置在"冲突检查"之后：

```
  结构下界: SURC:S:CHT（单股道）每次折返占用 ≥ 246s（进站 18 + 折返 180 + 出站 18 + 裕量 30），按权重每 7 班经过 3.5 次 → 全线间隔下界 123s
  端点串行: SURC:S:CHT 经过 147 次、占用 42%；177 班偏离网格（延后 174 / 提前 3，最大 +1870s）；无处等待 0 班；截断 0 班
```

不规整间隔只发生在容量 1 端点始发的 route 上，判据就是图，不加配置项。运营者要规整间隔的办法与 A4 的建议相同（缩短折返、减权重、加股道）。

### 2.8 C2 与搜索

搜索逻辑与上限不变。每次 `attempt` 内部顺序改为：SWRR → 派车 → **串行** → 车次编号 → 冲突检查。
串行在每个候选 headway 上都跑（它是 O(n log n) 的一次扫描，比冲突检查便宜）。
新增的下界检查放在搜索**之前**：目标 < 下界直接失败并给结构文案，不搜。

`timetable.md`"搜索只放宽 headway、不挪动单个班次"那段改为：

> 搜索仍只放宽 headway；但每次尝试内部，容量为 1 的端点会把经过它的班次按资源串行（见「单股道端点」一节）。
> 表的结构（SWRR 序列、duty 链）在任何间隔下都用同一套规则生成，因此建议值仍是一个可以直接写回配置的数；
> 端点上偏离网格的班次在报告里单独列出。

### 2.9 C1 邻表

邻表在端点的占用是预订：从 `NeighborTimetable.stays()` 里取 `platform.group() == 端点组` 的区间，
再用它的 `profiles()` 算进站/出站走行，登记为 `[from − 进站, to + 出站 + 裕量]` 的不可用时段；我的进站班次只能落在空档里。
邻表的运行本身一动不动，路权语义与 S3 完全一致。publish 重检**不需要**再串行：落库的已经是实际时刻，重检只投影、只检查。

### 2.10 C3 运行时

不改。理由：串行改变的是**计划**里两班之间的待命长度（Stay），运行时对它的处理就是今天的"接班没车 = 等"——车绑在交路上，
在起点待命到表定时刻由 `TimetableSpawnManager` 出票、`SimpleTicketAssigner` 复用。
`StationStopCoordinator#holdsDeparture` 扣的是**早到**的车，与计划待命无关；`assign-tolerance-seconds`（300）比较的是实际与表定时刻，
表定时刻不规整不影响比较。一个要注意的点：串行后同一 route 两班可能相距 264 s（< 300），`TripMatcher` 按最近车次匹配时两班都在容差内，
但已绑交路的车只接自己交路的车次（`DutyLedger.acceptsVehicle`），不会错绑；未绑车只接首班。测试矩阵里放一条回归。

---

## 3. 数据模型与命令面

- **无新表、无新列、无配置项**，`config-version` 保持 33。`TimetableTrip.departureSecondOfDay` 落库的是**实际**时刻；名义时隙不落库
  （报告与 `TimetableBuildResult` 里有，`info` 不显示）。想要的话将来走"新表"，本轮不做。
- `VehicleDuty.plannedStartSecondOfDay` 随首班延后而后移（出库票要跟着晚发），`returnSecondOfDay`/`plannedEndSecondOfDay` 随末班实际到达重算。
- 命令：无新子命令、无新 flag。`build` 报告加两行（§2.7）与两种失败文案（§2.2）。
- 权限、locale 键：不变。

---

## 4. 逐文件改动清单

### 新增

| 文件 | 职责 | 阶段 |
| --- | --- | --- |
| `dispatcher/schedule/timetable/TerminalSerializer.java` | 容量 1 端点的资源串行：识别端点、进出站走行、事件扫描、截断、下界；纯数据，无 Bukkit | P1 |
| `dispatcher/schedule/timetable/TimetableTripNumbering.java` | 从 `TimetableBuilder.attempt` 抽出"取消 / 重编号 / 主键派生 / duty 引用替换"这一段（今天 `attempt` 的后半，约 90 行）；串行后要按实际时刻重编号，正好一起抽 | P1 |
| 测试：`TerminalSerializerTest`、`TimetableBuilderStubTerminalTest`、`TimetableTestFixtures#stubTerminal`（夹具，见 §6） | | P1 |
| 测试：`TimetableTimingCalculatorTest#passStopsHaveZeroDwell`、`TimetableConflictCheckerTest#approachWaypointIsNotAPlatform` / `#aVehicleDoesNotConflictWithItsOwnLegs` | | P0 |

### 修改

| 文件 | 改动 | 阶段 |
| --- | --- | --- |
| `TimetableTimingCalculator.java` | `resolveDwellSeconds`：`passType == PASS` → 0，不看兜底值 | P0 |
| `TimetableConflictChecker.java` | `platformsOf` 多一个 `Map<NodeId, NodeType>` 参数，只对 `STATION`/`DEPOT` 节点造 `Platform`；`check(...)` 多一个 `Function<String, String> vehicleOf`（code → 车辆标识），`Occupation` 用它判"同一辆车"；旧签名保留为 `vehicleOf = code -> code` | P0 |
| `TimetableBuilder.java`（794 行） | `prepare` 传 `nodeTypes` 给 `platformsOf`；`attempt` 改为 SWRR → 派车 → 串行 → `TimetableTripNumbering` → 检查；`build` 搜索前算下界；`attempt` 里的编号段抽走后应回到 ~700 行 | P0 / P1 |
| `TimetableBuildResult.java` | 加 `List<TerminalSerializer.Shift> shifts`、`List<TerminalSerializer.TerminalReport> terminals`；`DroppedTrip` 复用 | P1 |
| `TimetableBuildReportText.java` | 两行新报告文案、两种失败文案、`describe(STUB_SATURATED)` | P1 |
| `VehicleDutyPlanner.java` | `UnassignedReason` 加 `STUB_SATURATED`；其余不动 | P1 |
| `command/FtaTimetableCommand.java` | `sendBuildReport` 打两行；`scopeCheck` 的 `check` 调用传 `vehicleOf`（由落库的 trips/duties 建） | P0 / P1 |
| `scope/TimetableNeighborhoodLoader.java` | **只改一处**：`rebasedProfileOf`/`profileOf` 调 `platformsOf` 时传 `index.nodeTypes()`（签名跟着 P0 变，不改语义） | P0 |
| `docs/dev/timetable.md` | 不变量 2/5/6 措辞（§2.4/2.5/2.6）、搜索段落（§2.8）、新节「单股道端点」、报告两行、已知边界加"车库咽喉不串行" | P1 |

**不改**：`TimetableOccupancyProjector`（车辆身份由 `vehicleOf` 在 checker 侧解析，投影器一行不动）、`scope/` 其余文件、
`TimetableService`、`TimetableSpawnManager`、`SimpleTicketAssigner`、`StationStopCoordinator`、`RuntimeDispatchService`、
`config.yml`/`ConfigManager`、`StorageSchema`。

---

## 5. 完整签名（Javadoc 摘要级，无方法体）

### 5.1 串行器

```java
package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

/**
 * 容量为 1 的端点按资源串行：进站要等端点空出来（整趟延后、只延后不提前），续班锚在车上（到达 + 折返）。
 * 纯数据运算、确定性：事件按（最早可发时刻, 车次 code）排序，端点空闲表按站台组键排序。
 * 只处理站台组容量为 1 且是某条 OPERATION route 起点或终点的组；其余一切保持网格。
 */
public final class TerminalSerializer {

  /** 输入：派车后的临时表（trips 的时刻是名义时隙）、各 route 投影、图索引、零点、折返与裕量、走行段与上限、邻表。 */
  public record Input(
      Timetable provisional,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int zeroSecondOfDay,
      int horizonSeconds,
      int turnaroundSeconds,
      int separationSeconds,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      List<NeighborTimetable> neighbors) {}

  /** 一班的时刻偏离：名义时隙、实际发车、原因（延后等端点 / 续班锚在车上 / 跟随本车就绪）。 */
  public record Shift(UUID tripId, String provisionalCode, int nominalSeconds, int actualSeconds, Reason reason) {
    public enum Reason { WAIT_FOR_TERMINAL, ANCHORED_TO_VEHICLE, VEHICLE_READY }
  }

  /** 一个端点的统计：经过次数、占用秒数与占比、每次折返的占用 v、按权重的全线时隙下界、无处等待与截断的班次数。 */
  public record TerminalReport(
      String group, int visits, int occupiedSeconds, double utilization,
      int visitCostSeconds, double visitsPerCycle, int headwayFloorSeconds,
      int nowhereToWait, int truncated) {}

  /** 输出：时刻已改写的表（trips、duties 的 plannedStart/returnSecond/plannedEnd）、偏离清单、截断的班次、各端点报告。 */
  public record Result(
      Timetable timetable,
      List<Shift> shifts,
      List<TimetableBuildResult.DroppedTrip> dropped,
      List<TerminalReport> terminals) {}

  /** 串行主入口。没有容量 1 端点时原样返回（shifts 为空、terminals 为空）。 */
  public static Result serialize(Input input);

  /**
   * 只算下界不串行：build 在搜索前调用。返回每个端点的 TerminalReport（visits/occupied 为 0），
   * headwayFloorSeconds = ceil(visitsPerCycle × visitCost / Σweight)。
   */
  public static List<TerminalReport> floors(
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<WeightedTripAllocator.Candidate> candidates,
      List<TimetableRoutePlan> operationPlans,
      TimetableConflictChecker.GraphIndex index,
      int turnaroundSeconds,
      int separationSeconds);

  /** 容量 1 且被某条 OPERATION route 用作起点或终点的站台组。按组键排序返回，确定性。 */
  static List<String> terminalGroups(
      TimetableConflictChecker.GraphIndex index, Collection<TimetableConflictChecker.RouteProfile> operationProfiles);

  /** 进站走行：从 profile 里最后一个**不属于**该站台组的停靠点的发车偏移，到终点到达偏移。没有这样的点时取终点到达偏移。 */
  static int approachIn(TimetableConflictChecker.RouteProfile profile, String group);

  /** 出站走行：从起点发车到 profile 里第一个**不属于**该站台组的停靠点的到达偏移。没有时取 0。 */
  static int approachOut(TimetableConflictChecker.RouteProfile profile, String group);

  /** 邻表在该组的预订时段：stays 里 platform.group() 相符的区间，前后各扩进站/出站走行与裕量。 */
  static List<int[]> neighborBookings(List<NeighborTimetable> neighbors, String group, int separationSeconds);
}
```

事件扫描的规则（实现者照此写，不要发明别的）：

1. 每个交路的首班入队，键 = 名义时隙（首班永远不锚在车上；出库票由 `plannedStart` 跟随首班）。
2. 出队一班：`origin` 属于端点组 → `dep = ready`（锚在车上）；否则 `dep = max(nominal, ready)`。
3. 终点属于端点组 → `earliestArrival = freeAt(group) + approachIn`，且要落在邻表预订的空档里；`arr < earliestArrival` 时
   `dep += 差值`。**若起点也属于端点组则不延后**（记 `nowhereToWait++`，照常登记）。
4. 若延后后 `dep ≥ horizon`，或 `dep + duration + 收尾走行 − dutyStart > maxDutyDuration`：从这一班起截断交路（§2.5），本交路不再入队。
5. 登记 `freeAt(group) = arr + turnaround + approachOut(下一班或回库线路) + separation`——续班的发车此刻已知。
6. 下一班入队：`ready = arr + turnaround`，键按第 2 条算。回库：`returnSecond = arr + turnaround`。
7. 全部出队后改写 trips 与 duties，产出 `Shift` 清单与 `TerminalReport`。

### 5.2 车次编号（从 builder 抽出）

```java
/** 派车之后的收尾：丢掉没派上车的班次、按实际发车顺序重编号、按名字派生主键、把 duty 里的临时引用换成正式主键。 */
final class TimetableTripNumbering {
  record Numbered(List<TimetableTrip> trips, List<VehicleDuty> duties, List<TimetableBuildResult.DroppedTrip> dropped,
                  List<WeightedTripAllocator.Allocation> keptAllocations) {}

  /** 排序键：(实际发车, 名义发车, 临时 code)；序号按 route 各自连续；主键 = deterministicTripId(timetableId, tripCode)。 */
  static Numbered number(UUID timetableId, TimetableBuildOptions options, List<VehicleDutyPlanner.PlannedTrip> planned,
                         Map<UUID, Integer> actualDeparture, VehicleDutyPlanner.Result plannedDuties,
                         Map<UUID, WeightedTripAllocator.Allocation> allocationByProvisional,
                         List<TimetableRoutePlan> operationPlans, List<TimetableBuildResult.DroppedTrip> alreadyDropped);
}
```

### 5.3 checker 改动（P0）

```java
/** 站台映射：只有图里类型为 STATION / DEPOT 的节点才是站台；路径点（WAYPOINT）即使命名在站台命名空间下也不是。 */
public static List<Platform> platformsOf(
    List<TimetableStop> stops, List<RouteStop> routeStops, List<NodeId> waypoints, Map<NodeId, NodeType> nodeTypes);

/** 冲突扫描；vehicleOf 把占用的 code 映射成车辆标识（duty 号），同一辆车的占用之间不报冲突。旧签名等价于 code -> code。 */
public static Report check(GraphIndex index, Map<UUID, RouteProfile> profiles, List<Movement> movements, List<Stay> stays,
                           int separationSeconds, Function<String, String> vehicleOf);
```

`Occupation` 增加 `vehicle` 字段（由 `vehicleOf(code)` 得到）；三个 `scan*` 里 `current.code().equals(next.code())` 改为 `current.vehicle().equals(next.vehicle())`。
builder 与命令层的 `vehicleOf`：`tripCode → duty.dutyCode()`（由 `trip.dutyId()` 查）、`"Dxxx-CREATE"/"Dxxx-RETURN" → "Dxxx"`、
duty 号 → 自身、查不到 → code 本身。邻表的占用不需要映射（两外部之间本来不报）。

### 5.4 builder 与结果（P1）

`attempt` 的新骨架（注释级）：

```
allocation  = WeightedTripAllocator.allocate(...)                    // 不变
planned     = VehicleDutyPlanner.plan(...)                           // 不变，名义时刻
provisional = 临时 Timetable（trips 用临时 code、名义时刻；duties 用临时引用）
serialized  = TerminalSerializer.serialize(Input(provisional, ...))  // 新
numbered    = TimetableTripNumbering.number(..., serialized 的实际时刻, ...)
timetable   = 正式 Timetable
conflicts   = TimetableConflictChecker.check(index, profiles + 邻表, 投影(timetable) + 邻表, separation, vehicleOf)
Attempt(headway, timetable, dropped ++ serialized.dropped, shares(按保留班次), conflicts, serialized.shifts, serialized.terminals)
```

`build`：`prepare` 之后、`attempt(target)` 之前调 `TerminalSerializer.floors(...)`；任一端点的 `headwayFloorSeconds > targetHeadway`
→ 直接 `failure(结构文案)`，不搜索。`--strict` 与否都一样（结构不可能不是"可回退"的情况）。

`TimetableBuildResult` 新字段：`List<TerminalSerializer.Shift> shifts`、`List<TerminalSerializer.TerminalReport> terminals`，
`failure(...)` 给空列表。

---

## 6. 测试矩阵

夹具 `TimetableTestFixtures#stubTerminal()`：直链 `DEP – A(两股道 A:1/A:2) – B(两股道) – X:1:001(WAYPOINT) – X:1(STATION，唯一)`。
route：`RA` A→B→X（终到 X，w=1）、`RB` X→B→A（始发 X，w=1）、`RC` B→X（第二路进站流，w=1，让 X 有两种到达相位）、
`CRT` DEP→A（CREATE）、`RET` X→A→DEP（RETURN，DSTY）。折返 60、裕量 30、headway 300 时网格模型在 X 上必然撞
（两路进站 + 锚定的出站挤一根股道），串行后必须干净——这就是 CHT 的抽象形态。**不要**拿 `DEP-A-B-C` 当正例（它在 300 + 180 下本来不可行）。

| 不变量 / 决策 | 测试类 | 用例（新增标 ★） |
| --- | --- | --- |
| P0 缺陷 C | `TimetableTimingCalculatorTest` | ★`passStopsHaveZeroDwellRegardlessOfFallback`：PASS 路径点 arrival == departure，兜底 20 只作用于没配 dwell 的 STOP |
| P0 缺陷 B | `TimetableConflictCheckerTest` | ★`approachWaypointIsNotAPlatform`：`OP:S:X:1:001` 是 WAYPOINT → 不占 `platform-group:OP:S:X`；`X:1` 是 STATION → 占 |
| P0 缺陷 A | `TimetableConflictCheckerTest` | ★`aVehicleDoesNotConflictWithItsOwnLegsAndTrips`：Stay `D001` + Movement `D001-RETURN` 经过同组节点、相距 < 裕量 → 无冲突；换成 `D002-RETURN` → 有 |
| P0 零行为变化 | 全部既有 timetable 用例 | 不改一行仍绿（`platformsOf` 旧调用点传 `index.nodeTypes()` 后，`DEP-A-B-C` 系列的期望值可能因 PASS 不停站而变——那是修正，逐条核对后更新期望并在 commit 说明） |
| A1 串行 | `TerminalSerializerTest` | ★`arrivalsAreDelayedNeverAdvanced`；★`continuationFromTerminalIsAnchoredToVehicle`（发车 = 到达 + 折返，可早于名义时隙）；★`nonTerminalTripsStayOnGrid`；★`twoArrivalStreamsAreSerializedFifoByNominalArrival` |
| A2 反例 | `TerminalSerializerTest` | ★`arrivalFromAnotherTerminalIsNotDelayedAndCounted`（`nowhereToWait`）；★`dutyIsTruncatedWhenDelayBreaksLimits`（`STUB_SATURATED`，弹到 `closable` 为止） |
| A4 下界 | `TerminalSerializerTest` + `TimetableBuilderStubTerminalTest` | ★`floorUsesWeightedVisitsPerCycle`（手算：v=进站+折返+出站+裕量，n=Σw×端点次数/2）；★`buildFailsFastBelowFloorWithStructuralMessage`（文案含"结构下界"，不搜索——用 `TimetableTimingCalculator` 的 spy 数 compute 次数或断言 warnings 里没有"放宽到"） |
| A3 | `TimetableBuilderStubTerminalTest` | ★`stubTerminalIsCleanAfterSerialization`：夹具在 headway 300 下网格模型有 X 上的冲突，build 后 `conflictsAtTarget` 里没有任何 `OP:S:X` 资源 |
| B1 确定性 | `TimetableBuilderStubTerminalTest` | ★`serializedBuildIsDeterministic`：两次 build 逐字段相等（含实际时刻、tripCode、主键）；★`tripCodesFollowActualDepartureOrder` |
| B2 比例 | `TimetableBuilderStubTerminalTest` | ★`sharesAreUnchangedBySerialization`：`shares` 与串行前（用 `Limits` 极大、无截断的情况）逐字段相同；截断时按保留班次重算 |
| B3 报告 | `TimetableBuildReportTextTest`（新）或既有 | ★`terminalLinesAreRendered`（两行文案）；★`failureTextDistinguishesStructuralFromSearch` |
| C1 邻表 | `TerminalSerializerTest` | ★`neighborBookingsAreRespected`：邻表在 X 有一段待命 → 我的进站落在它之后；邻表运行未被改动 |
| C2 搜索 | `TimetableBuilderStubTerminalTest` | ★`headwaySearchStillRelaxesForNonTerminalConflicts`：在 A–B 边上制造网格冲突，回退照旧发生且每次 attempt 都串行 |
| C3 运行时 | `TimetableServiceTest` / `TripMatcherTest` | ★`irregularPlannedDeparturesWithinToleranceDoNotRebindAcrossDuties`：同 route 两班相距 < assign-tolerance，绑定交路的车只接自己交路那班 |
| 不变量 4 | `TimetableBuilderStubTerminalTest` | ★`everyDutyStillReturnsToStorageAfterSerialization`（`allDutiesReturnToStorage`、`returnSecond ≥ 末班实际到达 + 折返`） |
| 不变量 7 | `TerminalSerializer` 无任何 runtime 依赖 | 编译期即保证：包 `dispatcher.schedule.timetable` 不 import `dispatcher.runtime.*`（ArchUnit 没有，就靠 review） |

---

## 7. 命令面与数据模型影响

无新参数、无新列、无新表、无配置项；`config-version` 保持 33；`plugin.yml` 不变；`lang/zh_CN.yml` 不变（报告文案走 `Component.text`，与 timetable 命令现状一致）。
`export` 的 CSV 列不变（时刻列本来就是实际时刻）。

---

## 7.5 实现状态（2026-09-19，实现遍）

- **P0 与 P1 已实现，P2 并入 P1**（邻表预订在串行器里一并做了）。全量 `clean check` 见 §8 判别量。
- 与本文的偏差，实现者已按实测改：
  - **没有"目标低于下界就直接失败"**。按权重的下界假设每班都排得上，而 `--max-trips` 会把班次截掉一半（§0.4），照下界拦会误伤。
    改为每次 attempt 算端点**实际利用率**（占用 / 计划窗口）：搜索失败时利用率 > 100% 说"结构上不可能"，否则说"范围内没找到"并点名前三个资源；
    利用率 > 100% 同时进 warnings。下界仍在报告里，只作参考。
  - **进出站走行按单线区段索引算**，不按"最后一个不在站台组的停靠点"：冲突模型独占的是整条桥链，夹具里桥链从 A:2 一直到 X，按停靠点算会漏。
    终点前的边不在任何单线区段时才退回按命名算。
  - 车辆身份不改投影器：`TimetableConflictChecker.check` 多一个 `vehicleOf` 参数，builder 与 publish 重检各自从表里建映射。
  - 命令层没有新的 `TimetableBuildAssembler`；两行报告直接在 `sendBuildReport` 里打。
- 新文件：`TerminalSerializer`、`TimetableTripNumbering`；测试 `TerminalSerializerTest`（9）、`TimetableBuilderStubTerminalTest`（5）、
  `TimetableBuildReportTextTest`（2），P0 三条回归在 `TimetableTimingCalculatorTest` / `TimetableConflictCheckerTest`。
- 未做（§10）：车库咽喉串行、冲突预算、`--max-trips` 与 `--turnaround` 的默认值。
- **离线复核（P0+P1 之后，`test/data/debug/fetarute.sqlite` 与实服副本结果逐字段相同，06:00–23:00，走 `TimetableBuilder.build()` 公共入口）**：

| 参数 | 目标 120 下冲突（CHT） | 回退间隔 | CHT 占用 | 偏离网格 | 剩余冲突在哪 |
| --- | --- | --- | --- | --- | --- |
| 折返 180 / max-trips 4（今天的默认） | 129（0） | **160 s** | 45% | 119 班，最大 +776 s | LWN 咽喉 `SW587~SW705` 30、道岔 545/558 各 13 |
| 折返 20 / max-trips 4 | 16（0） | **130 s** | 20% | 172 班 | 同上，各个位数 |
| 折返 60 / max-trips 7 | 68（0） | 220 s | 25% | 145 班 | `platform:S:NTA:2` 66 |
| 折返 20 / max-trips 7 | 227（0） | 300 s | 9% | 30 班 | LWN 咽喉 54、NTA:2 19 |
| 折返 180 / max-trips 4，目标 150 | 207（0） | 160 s | 45% | 119 班 | LWN 咽喉 68 |

  同一份输入从"2160 处冲突、放宽到 480 仍不可行"变成"129 处、回退 160 s"，CHT 在任何一档都不再出现在冲突里，§2.3 的预估被超过。

- **第四个模型缺陷（同日发现并修）**：build 的 `DynamicTravelTimeModel` 没接 `EdgeSpeedResolver`，图里边基础限速是 0，全线按默认 8 bps 算；
  库里 445 条永久限速覆盖（16.7 / 22.2 bps）全被忽略。接上之后（`TimetableEdgeSpeeds`，只认永久覆盖）走行时分：1L 319→226、2C 1003→575、2N 1001→582 s，
  CHT 进站从 22 s 缩到 10 s。重跑：

| 参数（真实限速） | 目标 120 下冲突（CHT） | 回退间隔 | CHT 占用 | 剩余冲突在哪 |
| --- | --- | --- | --- | --- |
| 折返 180 / max-trips 4（默认） | 84（0） | **130 s** | 50% | 道岔 545/558、LWN 咽喉边，各 12 |
| 折返 20 / max-trips 4 | 7（0） | **130 s** | 16% | 个位数 |
| 折返 60 / max-trips 7 | 550（0） | **放宽到 480 仍不可行** | — | `platform:S:NTA:2` 200、`platform-group:S:NTA` 85 |
| 折返 20 / max-trips 7 | 90（0） | **放宽到 480 仍不可行** | — | `platform:S:NTA:2` 68、LWN 咽喉 7 |

  车跑快了以后 max-trips 4 更好（130 s），但 max-trips 7 反而彻底不可行：份额一到 1:3:3，Full 的两个方向全在 **NTA 的 2 号道**折返，
  它虽然属于两股道的站台组，却被两条 route 钉死在同一股道上——具体股道层容量是 1，等网格的待命把它占满。这正是 CHT 的问题换了个地方：
  串行器只认"站台组容量 1"，认不出"站台组有两股道、但 route 把终到与始发钉在同一股道"的端点。下一步（§10 第一条之前）应把串行资源从
  "容量 1 的站台组"推广到"被 route 钉死的具体终到股道"：非 DYNAMIC 的终到节点本身就是容量 1 的资源；进出站走行仍按单线区段索引算，
  不在单线区段里的（NTA 是双线进站）进出站走行取 0，只串行站台本身。
  下一个瓶颈如预期是 LWN 车库咽喉与 NTA:2（两个方向的 Full 都用 2 号道折返，而 NTA 有两股道）。max-trips 7 反而回退到 300：
  班次多了以后咽喉与 NTA 先撞，与 CHT 无关。份额截断（§0.4）仍在：max-trips 4 时三条 route 各占三分之一。

## 8. 阶段划分（每阶段独立编译、独立过 `./gradlew spotlessApply clean check`）

| 阶段 | 内容 | 验收（实服判别量） |
| --- | --- | --- |
| **P0 模型纠错** | PASS 不停站；`platformsOf` 看节点类型；`vehicleOf` 自撞豁免；既有用例期望核对 | WS `build` 目标 120 的冲突明细里不再有 `platform:SURC:S:CHT:3:003`，也没有任何 `Dxxx` 与 `Dxxx-RETURN` / 自己交路车次成对的条目；报告里 1L 全程 ≈ 319 s（不是 459）；用实服图重跑 §11 的量表，把本文 §0 的数字换成实服值 |
| **P1 端点串行** | `TerminalSerializer` + `TimetableTripNumbering` 抽出 + builder/result/report + 下界与失败文案 + 文档改写 | §2.3 的五条预估 |
| **P2 邻表预订** | `neighborBookings` 接入串行；`TimetableBuilderNeighborsTest` 加"邻表占端点"用例；`neighbors` 命令不改 | 造一份在 CHT 有待命的邻表（DS/MT 没有；用测试夹具），我的进站被推到它之后 |

P0 单独就有价值（它让搜索在 300–480 之间能成功），先合。P1 依赖 P0 的 `vehicleOf`（否则串行后的检查仍全是伪冲突）。P2 依赖 P1。

---

## 9. 给实现者的「别自作主张」清单

- **不要**给 `fta_timetable_trips` 加"名义时隙"列，也不要为它建新表。报告与 `TimetableBuildResult` 里有，够用。
- **不要**加任何配置项（`stub-max-delay` 之类）。截断规则只用既有的交路时长上限与计划窗口。
- **不要**改 `Limits.DEFAULT_MAX_TRIPS`。份额被截断（§0.4）是另一个题，先报告给用户。
- **不要**改 `Limits.DEFAULT_TURNAROUND_SECONDS`（180）。它远大于物理折返（§0.5），但改默认值影响所有已有的 build 结果与测试期望，是用户的决定。
  本轮只做两件事：下界报告打印所用折返值；失败文案第一条建议是核对它。
- **不要**把串行扩到容量 ≥ 2 的站台或车库咽喉。那是 §10 的事，本轮的边界就是"容量 1 的站台组"。
- **不要**提前任何进站班次；**不要**让续班等网格。两条各自违反一半，串行就白做了。
- **不要**改 `HEADWAY_SEARCH_MAX_MULTIPLIER`、步长、`--strict` 语义。下界检查放在搜索之前，不是改搜索。
- **不要**动 `TimetableOccupancyProjector` 与 `scope/`（`platformsOf` 签名连带的一行调用除外）。车辆身份在 checker 侧由 `vehicleOf` 解析。
- **不要**把邻表的占用改成可移动的；**不要**在 publish 重检里再串行一遍。
- **不要**用"实服录一遍"或调度器回放当 build 输入；§11 的量表是校验，不是输入。
- **不要**为了对上 2160 这个数去调时分模型。对上的是比例与结构，不是绝对值。
- 涉及具体字段、类名、列名时先读代码确认（本文签名按 HEAD `2f24f48` 写，`platformsOf` 在 `TimetableConflictChecker.java:126`、
  `resolveDwellSeconds` 在 `TimetableTimingCalculator.java:171`、网格在 `TimetableBuilder.java:369`、`selectHost` 在 `VehicleDutyPlanner.java:173`）。
- **禁止主动 commit**，全绿也只能报告可提交状态等用户确认。

---

## 10. 后续（本轮明确不做）

- **车库咽喉串行**：WS 的 LWN 咽喉（`SWITCHER:587~705` 单线桥）是串行后剩余冲突的全部来源。它不是站台组，
  要串行得把"单线区段 + 两端道岔"作为一类资源，出库/回库走行的发车跟着排。做法与本轮相同，只是资源类不同。
- **冲突预算**：允许表里保留某类残余冲突（例如咽喉上 ≤ N 处）交给运行时让车，而不是一律放宽 headway。
  用户的直觉（"让列车在一些情况下 wait，这是 Dynamic Dispatcher 的事"）指向这里；本轮不做，因为需要先定义"哪类冲突运行时一定能让、代价可控"。
- **max-trips 默认值与 spawn group 的 `maxOperationTrips` 对齐**：§0.4。
- **`--turnaround` 默认值与终到站 dwell 对齐**：§0.5。候选做法是"缺省 = 终到站 dwell + 固定掉头余量（例如 10 s）"，按端点各算而不是全线一个数；
  这会让 `Limits.turnaroundSeconds` 从一个标量变成按站查表，牵动 `VehicleDutyPlanner` 的 readyAt/closingTail，单独排一轮。
- **派车按实际时刻推进**（把 `selectHost` 也放进事件过程）：解决 C4 里"名义最早未必实际最早"的次优，代价是三步合二。

---

## 11. 诊断复现

- 量表脚本：`.handoff/WsChtDiagnosisTest.java.txt`（已移出仓库，`git show 6efe6c1:.handoff/WsChtDiagnosisTest.java.txt` 取回；一个 JUnit 用例，反射调用 `TimetableBuilder.prepare/attempt`，输出网格 / 锚定 / 串行三种口径）。
  复制到 `src/test/java/org/fetarute/fetaruteTCAddon/dispatcher/schedule/timetable/WsChtDiagnosisTest.java`，
  把实服 `plugins/FetaruteTCAddon/data/fetarute.sqlite` **复制**到脚本里 `DB` 常量指向的路径（永远别直接打开实服库），
  `./gradlew test --tests '*WsChtDiagnosisTest'`，结果在 `build/test-results/test/TEST-*WsChtDiagnosisTest.xml` 的 `system-out`。用完删掉，不提交。
- 与实服口径的差异：脚本用 `RailGraphService.buildGraphFromRecords` 直接还原图，没有套限速覆盖；`--max-trips` 4 / 7、dwell 兜底 20 / 0、折返 180 / 60 / 20 各档都在输出里（`diagnose()` 里的 `variant` 数组）。
- 自撞伪冲突的判别：把 Stay 的 code、`Dxxx-CREATE/RETURN`、以及 `trip.dutyId` 对应的 duty 号映射到同一辆车，成对同车即为伪冲突。P0 完成后这一类应为 0。
