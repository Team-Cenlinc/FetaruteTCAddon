# 编表排不出来、运行时却跑得动：冲突预算、相位第三层与四处结构缺陷——设计交接

本文是**设计遍**的产物，读者是实现遍。判定标准同前两轮：实现者遇到任何需要做选择的地方都应该能在这里查到答案，
查不到就是本文漏了。本文不含实现代码，但给到签名、文件清单与测试清单的粒度。

基线：分支 `wip/dynamic-dispatch-stabilization`，HEAD `168c28a`（折返来自终到站 dwell、Q1–Q3 已落地），
全量 `clean check` 绿（230 suites / 2062 tests）。时刻表现状见 `docs/dev/timetable.md`；本文只写增量。

---

## 0. 诊断结论（先读这个：prompt 的两条主假设都不成立，题目要改）

对实服库副本（`../fetarute_experimental/plugins/FetaruteTCAddon/data/fetarute.sqlite` 的**只读副本**）离线跑当前算法。
口径与 prompt 第二部分一致：窗口 05:00–24:00、`--dwell 20`、折返按终到站 dwell、套 445 条永久限速覆盖、`--max-trips 4`、
**单次 attempt**（反射进 `prepare/attempt`，绕过搜索——严格失败的 `TimetableBuildResult` 里没有冲突列表，
prompt 附的探针在失败档聚合出来的"route 对"全是空的，这是它只能给冲突总数的原因）。探针 `.handoff/LiveNetworkProbeTest.v2.java.txt`，
原始输出 `.handoff/probe-2026-09-20.log.txt`，复现见 §11。

### 0.1 步骤 2：等待预算不是主因

把 `--max-wait` 从 60 提到 300 / 600 / 1800（累计容差跟着提到同值，绕过 `hold-max-seconds` 封顶）：

| | max-wait 0 | 60 | 300 | 600 | 1800 |
| --- | --- | --- | --- | --- | --- |
| **WS @ 300 s** 冲突 | 2222 | 1909 | **1909** | **1909** | **1909** |
| WS 让车数 / 最长 | 0 | 71 / +24 s | 71 / +24 s | 71 / +24 s | 71 / +24 s |
| **MT @ 1200 s** 冲突 | 2272 | 921 | **253** | 253 | 225 |
| MT 让车数 / 最长 | 0 | 65 / +50 s | 176 / +50 s | 176 / +50 s | 160 / +1200 s |

- WS：预算从 60 放到 1800，**一处都没少**，让车始终停在 71 处、最长 +24 s。挡住它的不是上限，是让车机制的结构：
  单步修复 + "冲突总数不减就回滚"。WS 的冲突几乎全是 `WS-1L_ShortC`（从 LWN 车库出库的运营班）与**回库走行**在 LWN 咽喉
  （单线桥链 `SWITCHER:587~705`、道岔 545/558）上对撞：延后回库走行 = 车在 CHT（容量 1）多待 → CHT 上撞下一班到达；
  延后 1L = 它到 CHT 晚了 → 同样撞。每一步都"别处多出一处"，于是回滚。这类冲突要的是**把整条 1L 网格相对回库流挪一个相位**，
  不是逐班让车——那是相位层的活（§0.3、B）。
- MT：60→300 从 921 降到 253，**上限 60 确实过紧**（A3 成立），但 300 与 600 一模一样：剩下 253 处是流量不平衡
  （§0.4），等多久都等不掉。

**运行时那一侧的对照表也要改。** `spawn.queued-ticket-max-age-seconds = 7200` 约束的是**发车队列里的票据**能挂多久，
不是一辆车能在资源前等多久——两者不是同一件事。车在红灯前等待在运行时**没有上限**：健康监控在 STOP 信号下给 60 s 宽限
（`progress-stop-grace-seconds`），超过后走"重刷信号 → 清自持残留 → 重下硬 STOP"的非动车恢复链，**从不强制动车、从不销毁**
（`deadlock-destroy-enabled` 默认 false）。计划扣留（`StationStopCoordinator.holdsDeparture`）是另一回事：上限 `hold-max-seconds`
（120，硬顶 150），而且**早到超过上限时直接放行**（`SCHEDULED_DEPARTURE_HOLD_SKIPPED`），车照常出发、到资源前再等。
所以"编表 60 vs 运行时 7200"这个比值是错的；正确的说法是"运行时无界、编表 60 且被一个不相干的上限封顶"。结论一样：要解耦（A3）；
但它不是让 WS 排不出来的原因。

### 0.2 步骤 3：2C/2N 相位共振不成立

WS 扫 290–430（纯走行、max-wait 60）：

| 间隔 | 290 | **300** | 310 | 320 | 330 | 340 | 350 | 360 | 370 | 380 | 390 | 400 | 410 | **420** | 430 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 冲突 | 791 | **1909** | 1196 | 1023 | 738 | 1129 | 1006 | 801 | 957 | 1009 | 741 | 322 | 384 | **0** | 0 |
| 2C 余数 `(575+30) mod h` | 25 | 5 | 295 | 285 | 275 | 265 | 255 | 245 | 235 | 225 | 215 | 205 | 195 | 185 | 175 |

- 余数与冲突数**没有关系**：余数 5 → 1909，余数 265 → 1129，余数 185 → 0，余数 25 → 791。"余数接近 0 就撞、接近半周期就不撞"不成立。
- 每一档冲突最多的 route 对都是 `WS-1L_ShortC × 回库走行`、`WS-1L_ShortC × WS-2C_FullR`（在 LWN 附近的道岔）；
  **`2C × 2N` 的单线对向冲突在任何一档都不进前六**。两列 Full 车同相出发没有在线路中点撞上，因为 WS 主线是双线
  （2C 走 `:2` 股道、2N 走 `:1` 股道，见 `fta_route_stops`），单线只有 CHT 岔线与 LWN 咽喉。
- 非单调是真的，但它是**车库咽喉的共振**：出库流（1L，每 h 一班）与回库流（回库票 = 末班到达 + 折返，末班又是 1L→2C→2N 链的产物）
  在同一根单线桥链上错峰还是同峰，随 h 变。420 起归零是错峰。

所以 B 部分的"沿线交会点"要做，但对象不是"往返对在正线上的交会"，而是**任何共用受限资源（单线区段、道岔、咽喉、容量 1/2 站台）
上各周期流的相对相位**；往返对锚定的"端点零等待"目标要保留（它没错），第三层在它之上加的是"资源上错峰"。

### 0.3 步骤 1：MT 在 1200 s 失败是三处结构缺陷，不是预算、也不是污染

MT@1200 纯走行 **921** 处，反而比带污染（WS-1C / DS-1F / DS-1W 混进来）的 **209** 处多四倍。整张表倒出来看（`.handoff/probe-2026-09-20.log.txt`）：

```
相位: [MT-1_Short 往返对 SURC:OFL:MLU:2:004→SURC:S:PPK / SURC:S:PPK→SURC:OFL:MLU:2:004 锚定 ≡ 247s]
plan MT-1N_Short    OPERATION D:OFL:1→S:PPK:1        全程 247s   ← 起点键是车库（CRET DYNAMIC:SURC:D:OFL）
plan MT-1N_ShortR   OPERATION OFL:MLU:2:004→S:PPK:1  全程 227s   ← 起点键是一个路径点
plan MT-2F_Short    CREATE    D:HHU:3→S:PPK:1        全程 207s   ← 带客出库班，从 HHU 车库来
D002 出库 05:00:00 | MT-2F_Short-001 05:00:00→05:03:27 | MT-1O_ShortR-001 05:04:07→05:09:17 | MT-1N_ShortR-001 05:20:00→05:23:47 | 回库 05:24:07(MT-2O_ShortD)
D001 出库 05:00:38 | MT-1N_Short-001 05:00:38→05:04:45 | MT-1O_ShortR-002 05:24:07→05:29:17 | ...            ← 在 PPK 空等 19 分钟
D009 出库 06:20:29 | MT-1N_Short-005 06:20:29→06:24:36 | 回库 06:25:10(MT-2O_ShortD)                              ← 从 OFL 来的车回 HHU 车库
· PLATFORM platform-group:SURC:S:PPK: D001 [05:04:45–05:24:07] 与 D002 [05:23:47–05:24:07]
· TRACK edge:SURC:SPB:JBS:1:002~SWITCHER:-566:77:1179: MT-2F_Short-002 [05:21:58–05:22:00] 与 MT-1N_ShortR-001 [05:22:18–05:22:20]
```

**缺陷 ①：方向键取自 route 的首末路径点，不是乘客意义上的首末站。** `ServiceGroupClassifier.originGroupOf` 用 `waypoints.get(0)`：
MT-1N_Short 的首站是 `CRET DYNAMIC:SURC:D:OFL`（车库），MT-1N_ShortR 的首站是路径点 `OFL:MLU:2:004`，于是同一个乘客方向
OFL→PPK 被拆成**两个方向、两张满间隔的子网格**，进 PPK 的流量翻倍；PPK→OFL 的 MT-1O_ShortR 终点也是那个路径点，
只和 ShortR 配成往返对，ShortR 的车到了 PPK 没有它自己的反向时隙。WS 的 1L_ShortC（`CRET SURC:D:LWN:2`）同样把起点键写成了车库。

**缺陷 ②：组间交错只看共用起点，看不见共用区间。** MT-2（HHU 出发）与 MT-1（OFL 出发）起点不同、`PhasePlanner` 第二层不交错，
两组都是相位 0，MT-2F 与 MT-1N_ShortR 在 SPB 汇合后**前后只差 20 s**，沿 SPB→PTK→RVS→PPK 一路撞（裕量 30）。带污染时
DS-1F 恰好与 MT-2F 共用起点 HHU，把 MT-2 推了 600 s，无意中错开了——这就是"纯走行反而更差"的全部原因。

**缺陷 ③：派车器让车在端点空等到下一个时隙。** 进 PPK 的流有三路（Short、ShortR、MT-2F），出 PPK 的运营时隙只有一路（ShortR 每 1200 s 一班），
多出来的车在两股道的 PPK 上一等就是 19 分钟，把 PPK 撑爆（`platform-group:SURC:S:PPK` 是 MT 所有档位的头号资源）。
运行时不会这样：待命车闲置超过 `reclaim.max-idle-seconds`（300）就被回收回库。编表侧没有对应的规则。

**缺陷 ④：回库线路按走行最短选，不看车从哪来。** 从 OFL 车库出来的 MT-1 车在 PPK 收口时选了 `MT-2O_ShortD`（232 s）而不是
`MT-1O_ShortD`（302 s），全部回了 HHU 车库；MT-2 的回库线路上"回库走行 × 回库走行"247 处站台冲突由此而来（`PTK:2/RVS:2/SPB:2` 各 58）。

三处修掉之后 MT 的进出流各 1 路 / 1200 s、按往返对锚定、多余的车按 max-idle 回库、回库走自己的线路——按构造 1200 s 应当接近干净；
**这一条要在实现遍用探针复核，本文不做没有实现的承诺**。

### 0.4 2.6 的 bug 坐实，且比 prompt 说的多一层

带污染档里 `DS-1F_Full`、`DS-1W_FullD`、`WS-1C_ShortD` 进了 MT 的表：DS-1F 作为**班次**排了（D001 = DS-1F_Full-001 → 回库），
WS-1C 作为回库走行被 MT 的车用。`Timetable.managedRouteIds()` 只排除 `external`（跨 operator 显式引用），所以 MT 一发布就会拦掉
DS 自己的 headway 票。这是 bug，修法见 D。

### 0.5 顺带：性能瓶颈的位置

WS@300 单次 attempt 54–56 s（其中 max-wait 0 的一档 65 ms——全在 `ResourceRepair` 的循环里）；MT@420 60 s。每施加一处让车整张表重投影一遍，
`cap = 4 × trips + 8`。§2.10 给了不增加扫描次数的方案。

---

## 1. 结论速览

| 题 | 决策（一句话） |
| --- | --- |
| A1 | 目标从"零冲突"改为"**零不可吸收冲突**"：残余的可吸收冲突带着预计等待发布，交给运行时让车；不放宽、不失败 |
| A2 | 可吸收 = 单步预计等待 ≤ `--max-wait` **且** 让车点（后车的上一停靠点）有站台容量 **且** 不是容量 1 端点、不是与邻表；其余不可吸收 |
| A3 | `--max-wait` 与 `hold-max-seconds` **解耦**，默认 300（= `assign-tolerance-seconds`），硬上限 1800；报告标出超过 hold-max 的让车"在资源前等" |
| A4 | 表里的让车对 PIDS/ETA/车次绑定是事实，对调度器是"≤ hold-max 的部分在站台扣留、其余到资源前等"；两者都成立，不是二选一 |
| B1 | 加第三层 **资源相位层**：在往返对锚定之上，对每个方向扫一个 δ，目标是共用受限资源上的周期冲突最少；δ 对反向方向是端点多等的时间（≤ `--max-idle`），对整组是整体偏移 |
| B2 | 优先级：锚定（零等待）是缺省；第三层只在能减少**不可吸收**冲突时才动 δ；组间起点错开降为并列时的次键 |
| B3 | 全部确定性扫描（10 s 步长、稳定序、并列取小）；评估用同一个冲突模型投影 K=3 个周期 |
| B4 | 报告每个往返对的余数 `(走行+折返) mod 间隔`，**不**据此跳档（实测余数不预测冲突）；咽喉共振由第三层处理 |
| C1 | 边界：build 保证"没有不可吸收冲突"和"每处可吸收残余都有让车点与预计等待"；实际等待由运行时的占用队列做 |
| C2 | `--strict` = 有任何不可吸收残余就失败；报告新增"残余冲突: N 处可吸收（最长 X s，M 处超过 hold-max 将在资源前等）/ K 处不可吸收" |
| C3 | 与邻表的残余**永远不可吸收**（路权先到先得，邻表不会让我），仍走放宽 |
| D1 | 别的线的带客 CREATE/RETURN 被排进本表是 bug |
| D2 | 修在 **builder 的 `prepare`**：非本线 route 一律只做走行段，并把它的 plan 标 `external=true` |
| D3 | `external` 语义扩为"不受本表管辖的走行线路（别的线或别的 operator）"，`managedRouteIds()` 不改代码 |
| E1 | 方向键改按**乘客首末站**（首个/末个落在车站的停靠点，DYNAMIC 取组）取 |
| E2 | 派车器加 `--max-idle`（默认 `reclaim.max-idle-seconds`）：在端点等不到下一班就回库 |
| E3 | 回库线路偏好：回到出库车库 > 显式指定 > 走行最短 |
| E4 | 让车修复的回滚判据从"单步"改为"最多 K=6 步的连锁段"，性能改增量重扫 |

---

## 2. 决策与理由

### 2.1 A1 / C1：目标改为"零不可吸收冲突"

**决策**：`attempt` 的产物分三类：让车（已写进表）、**可吸收残余**（写不进表但运行时能让）、**不可吸收残余**。
成功判据是"不可吸收残余为空"；搜索放宽与 `--strict` 只看不可吸收。可吸收残余随报告与 publish 重检一起呈现，不落库（重检时按同一判据复算）。

**理由**：运行时对所有这类冲突的处理都是"后车在资源前等"，而且没有上限；编表一律要求全表无冲突是过度承诺——它把运行时每天在做的事判成了不可行。
数据：WS@300 的 1909 处里让车机制修不掉的那部分，单步等待都在 30 s 内（让车最长 +24 s），是纯粹的连锁问题。

**反例**：可吸收残余多到运行时晚点累积、车次绑定超出 `assign-tolerance`。仍然选它，因为（1）每处残余都带预计等待，报告可读；
（2）残余上限用"单班累计预计等待 ≤ `--max-wait`"约束（同让车的累计容差），超过就是不可吸收；（3）`--strict` 仍能要求全干净。

### 2.2 A2：可吸收的定义（`ConflictAbsorption`）

对一处冲突 `c`（前车 `first`、后车 `second`，与 `ResourceRepair.moveFor` 同一口径决定谁是后车）：

1. **不与邻表**：`c.external()` 为真 → 不可吸收（C3）。
2. **不在容量 1 端点**：资源是 `platform-group:` 或 `platform:` 且该站台组容量为 1，或资源是该端点的进站单线桥链 → 不可吸收
   （运行时在这里等就是堵死岔线；P1 已按构造消掉，剩下的只可能来自邻表或截断）。
3. **预计等待**：`w = first.to + separation − second.from`；`w > maxWait` → 不可吸收。
4. **让车点有容量**：后车是班次时让车点 = 它的起点站台（组容量 − 该时刻的待命数 ≥ 1，从 `Occupancy.stays()` 数）；后车是待命或出库走行时让车点 = 车库（容量无限）；
   后车是回库走行时让车点 = 末班终点（同 2）。没有容量 → 不可吸收。
5. **连锁深度**：后车所在交路里因这 `w` 而就绪晚于名义时隙的后续班次数 ≤ 2（`ResourceRepair.State.delayTrip` 的传播长度）；更深 → 不可吸收。

需要的量：类型、资源键、`w`、让车点与其容量、连锁深度、是否单线对向。单线对向本身**可吸收**（运行时按区段互斥在区段外等），
不可吸收的是"等的地方是容量 1 站台"那一种，已被第 2 条覆盖。

**反例**：两列车在单线区段两端同时到达、双方让车点都没有容量——第 4 条把它判成不可吸收，正确。

### 2.3 A3 / A4：`--max-wait` 与扣留解耦

**决策**：`--max-wait` 默认 300（= `timetable.assign-tolerance-seconds` 默认值），允许到 1800（`stuck-cleanup-passenger-threshold-seconds` 的默认值，
运行时唯一可能销毁一辆等待列车的阈值，且默认关闭），**不再**被 `hold-max-seconds` 封顶。累计容差 `Repair.tolerance` 仍取 assign-tolerance，
但**下限抬到 maxWait**（累计不能小于单步）。

**理由**：`hold-max` 约束的是"早到的车在站台被扣多久"，不是"车能等多久"。扣留超上限时运行时**放行**（`HOLD_SKIPPED`），车到资源前照样等——
表上写 300 s 的让车，运行时的行为是"站台扣 120 s + 资源前等 180 s"，车次绑定按表时刻判偏差（`TripMatcher` 用表定时刻 ± tolerance），仍绑得上。
所以 A4 的回答是：让车写进表对 PIDS/ETA/绑定是**事实**，对调度器是**部分约束**（≤ hold-max 的那段），剩下的由占用队列兑现。
`hold-max` 的正确角色是"报告里区分让车发生在哪"：`≤ hold-max` 站台扣留，`> hold-max` 资源前等待。

**反例**：某处让车 300 s 写在表上、车实际在区间里等——乘客看到的 PIDS 时刻是对的，但车不在站台。这是运行时选择的等待点，编表侧只能把它说清楚。

### 2.4 B：相位第三层——资源相位（`ResourcePhasePlanner`）

**决策**：`PhasePlanner.plan` 之后、生成子网格之前，加一层：

1. 输入：各方向的（间隔、锚定后相位）、每条 route 的投影（`RouteProfile`，含逐边时刻）、图索引、折返表、`--max-idle`。
2. 对每个**周期流**建"一个周期的占用模板"：方向的一班从相位 0 出发在各资源上的 `[enter, exit]`；一趟班次结束后**紧接的回库走行**
   （交路只有一班时——出库班→回库、或 DS/MT-2 形态）也是周期流，模板 = 班次 + 折返 + 回库走行。
3. 评估函数 `score(phases)`：把模板按各自相位铺 K=3 个周期到公共窗口 `[0, 3 × lcm 上限)`（lcm 超过 3600 时取 3 × 最大间隔），
   用 `TimetableConflictChecker.check` 算冲突，按 A2 分为不可吸收 / 可吸收，返回 `(不可吸收数, 总数, 总预计等待)`，字典序比较。
4. 扫描：组按名字序，组内方向按键序。正向方向的 δ 是**组整体偏移**（取代第二层的起点扫描；起点相邻间隔的最小/最大作为最后两个并列键保留），
   反向方向的 δ ∈ `[0, --max-idle]` 是**端点多等**（相位 = 锚定 + δ，`GroupGrid` 不变，`VehicleDutyPlanner` 的 readyAt 天然吃下这段等待）。
   步长 10 s，取字典序最小的分数，并列取最小 δ。前面定下的方向不再回头改（贪心，确定）。
5. 输出：`Phases` 增加 `deltaByDirection`（端点多等）与 `resourceNotes`（每个方向选了哪个 δ、消掉了哪些资源上的冲突）。

**理由**：MT 的 ②（并线走廊）、WS 的咽喉共振、以及真正的单线正线交会，都是"两条周期流在同一资源上的相对相位"这一件事；第二层只看起点是它的特例。
把评估交给现有冲突模型而不是另写一个几何模型，是为了口径一致（A2 的判据也复用）。

**B2 优先级**：锚定给出的零等待是缺省（δ=0 永远是候选且并列时胜出）；只有 δ>0 能减少**不可吸收**数时才付出端点等待。
端点等待有容量代价（车占着站台），所以 δ 上限是 `--max-idle`，并且 score 里的模板包含这段待命（它会在容量 1/2 端点上制造站台冲突，自动被惩罚）。

**B3 确定性**：所有候选来自固定步长；排序键全是整数；组/方向/资源按稳定键遍历；无哈希序依赖（`resources` 用 `TreeMap`）。

**B4**：报告每个往返对一行"往返对 X/Y：走行 a + 折返 b ≡ r（间隔 h）"，不据此跳档；实测余数不预测冲突，跳档会跳掉 420 这种干净档。

**反例**：两组间隔互质（150 与 120 的 lcm 600 还好；130 与 170 的 lcm 2210）时 3 个周期的模板窗口很长，评估慢。上限 3 × 最大间隔截断，
代价是评估只覆盖前几个周期——可接受，因为周期流的相对相位在整个窗口里是重复的，前几个周期已经代表全部。

**成本**：候选数 = Σ 方向 × (间隔 / 10)，每个候选一次小窗口 check（几十条运行）。WS 四个方向 × 30 = 120 次，每次毫秒级。不增加 `ResourceRepair` 的扫描次数。

### 2.5 C2：`--strict`、报告与 publish

- `--strict`：不可吸收残余 > 0 就失败；可吸收残余不算失败。
- 报告新增两行：`残余冲突: N 处可吸收（最长预计等待 X s；其中 M 处超过 hold-max，运行时在资源前等；涉及 P 班）` 与
  `不可吸收: K 处（原因分布：邻表 a / 容量 1 端点 b / 超过 max-wait c / 让车点无容量 d / 连锁过深 e）`。可吸收残余明细列前 5 条（同让车明细格式，多一列"让车点"）。
- publish 重检：现有 `scopeCheck` 的外部冲突判据不变（外部一律不可吸收）；内部残余不参与重检（build 时已判）。

### 2.6 D：2.6 的 bug

**决策**：修在 `TimetableBuilder.prepare`。`BuildInput.lineByRoute` 现在只在联编时非空；改为**单线也由命令层填满**（本线 route → lineId），
`prepare` 对不在 `lineByRoute` 里的 route：CREATE/RETURN 无论有没有中途 STOP 都只做走行段，`TimetableRoutePlan.external = true`；
OPERATION 不在本线的（今天不会出现，`collectRoutes` 只收本线的 OPERATION）判不可行并说明。`Timetable.managedRouteIds()` 已按 `external` 排除，不改。

**为什么不是命令层或分类器**：命令层不该知道"带客"这条模型规则（它已经 2600 行）；分类器是纯函数、没有"本线"概念且被联编复用。
builder 是唯一同时知道归属与分类的地方。

**D3**：`external` 的 Javadoc 与 `timetable.md` 措辞改为"不受本表管辖的走行线路：别的线或别的 operator 的"。直通运转（跨 operator 显式指定）行为不变。

**反例**：DS 那种整条线只有 CREATE+RETURN 的线，被 WS 借了它的 RETURN 当回库走行——仍然允许（走行段不受管辖），DS 自己编表时它是班次。两张表并存时
DS 的 RETURN 在 WS 表里是 external 走行、在 DS 表里是受管辖的班次，运行时按 `managedRouteIds` 只有 DS 的表拦它的票。正确。

### 2.7 E1：方向键按乘客首末站

**决策**：`ServiceGroupClassifier.originGroupOf/terminalGroupOf` 改为：起点 = 第一个 `passType ∈ {STOP, TERMINATE}` 且解析得到站台组的停靠点
（DYNAMIC 停靠取 `DYNAMIC:` 目标的组），终点 = 最后一个这样的停靠点；都找不到才退回首末路径点。CRET/DSTY 只影响生灭，不影响方向。

**理由**：方向是乘客看到的"从哪到哪"，车库与路径点不是站。缺陷 ① 的直接修法。**副作用要写进测试**：MT-1N_Short 与 MT-1N_ShortR 合成一个方向后按 weight 分格，
每 1200 s 只有一班进 PPK 而不是两班；1L_ShortC 的方向变成 HHU→CHT（首个 STOP 是 HHU:2）。

**反例**：一条 route 首站是 PASS 的换乘站、乘客实际从第二站上车——按第一个 STOP 取正是想要的。

### 2.8 E2：`--max-idle`——端点等不到下一班就回库

**决策**：`VehicleDutyPlanner.Limits` 加 `maxIdleSeconds`（默认取 `reclaim.max-idle-seconds`，命令层传入；`Limits.defaults()` 用 300）。
`selectHost` 多一条：`trip.departureSeconds() − duty.readyAtSeconds > maxIdle` 的 host 不接（它早该回库了）；
`closeReasonAfter` 多一个原因 `IDLE_LIMIT`：接完这一班后，若下一个能接的同池班次的最早时刻 − readyAt > maxIdle 则封口。
后者需要"下一班最早时刻"——按方向网格可知（`GroupGrid` 的下一格），传 `Map<originNode, NavigableSet<Integer>>` 给 planner。

**理由**：与运行时 `ReclaimManager` 的 `idleSec > maxIdleSec → 回收` 同一条规则；缺陷 ③ 的直接修法。**这条会改变所有既有夹具的交路形状**
（等待超过 300 s 的续班今天是合法的），`TimetableBuilderTest` 里 600 s 间隔、折返 20 的用例首当其冲——测试矩阵 §6 列了要改的。

**反例**：间隔 1200、往返走行 500：车到端点等 700 s 才有回程时隙，按 max-idle 300 它会回库、再出一辆——出库次数翻倍。
这正是运行时今天的行为（回收 + 再发），编表照实反映；运营者要的话把间隔调到 600 或把 `--max-idle` 调大。报告里"交路形状"一行会把它显出来。

### 2.9 E3：回库线路偏好

**决策**：`VehicleDutyPlanner.Legs.returnLegAt(station)` 改为 `returnLegAt(station, preferredDepot)`：同站多条时，`depotNodeId` 与交路的 `startDepotNodeId` 同一车库组
（`groupOf` 前三段）的优先，其次 `declared`，再次走行最短。`closable/closingTail` 用不带偏好的存在性判断（不变）。

**理由**：缺陷 ④。运行时 `ReclaimManager` 回库也是先本 operator 再全部，没有"回原车库"的规则——但那是兜底回收，不是计划；计划里车回自己的库是运营常识，
也让 MT-2 的回库线路只被 MT-2 的车用。

### 2.10 E4：让车修复的连锁与性能

**决策**：
1. **连锁段**：`ResourceRepair` 的回滚判据从"单步后总数不减"改为"最多 K=6 步的连锁段结束后总数不减"：施加一处 → 重扫 → 若新增的冲突全部可修（后车是我、
   `w ≤ maxWait`）就继续修它们（深度优先、按检查器序），直到没有新增或到 K；段末比较 `(不可吸收数, 总数)`，变糟则整段回滚。
2. **增量重扫**：`TimetableConflictChecker` 增加"资源索引"形态：`Index.of(occupations)` 一次投影全部占用到 `TreeMap<resourceKey, NavigableSet<Occupation>>`；
   `Index.replaceVehicle(vehicle, newOccupations)` 只换一辆车的占用；`Index.scan(resourceKeys)` 只扫给定资源。`ResourceRepair` 施加一处让车后只重投影被挪的交路、
   只扫它触及的资源。复杂度从 O(全表) 降到 O(一条交路 × 它的资源)。
3. `cap` 改为 `2 × conflicts_at_start + 4 × trips`，连锁段的每一步计入。

**理由**：WS@300 的 1909 处里没有一处是预算问题，全是连锁——单步判据把每一步都拒了；连锁段让"把一串 1L 各挪 20 s"成为一次接受的修复。
增量重扫是 §0.5 的性能瓶颈的解法，且不改冲突语义（同一资源、同一比较规则）。

**反例**：连锁段 K=6 仍不够（一串 20 班）——那正是第三层该处理的相位问题；修复不追求替代相位层。

### 2.11 不变量 6 的新措辞（进 `docs/dev/timetable.md`）

> 宁可少排一班，也不排一班发不出去或回不了库的车。表上允许保留的冲突只有一种：**运行时一定能让、让的地方有站台容量、单步预计等待不超过 `--max-wait`**
> 的残余冲突——每一处都在报告里指名让车点与预计等待秒数，`--strict` 可以拒绝它们。不可吸收的冲突仍然只有两条出路：放宽间隔，或取消班次。

可检验：build 产物（`TimetableBuildResult.absorbable()`）里每条残余都能指出（资源、后车、让车点、等待秒数）；publish 重检用同一个 `ConflictAbsorption.classify`
复算，两次分类必须一致——这是不变量 2 在残余上的形态。

---

## 3. 数据模型与命令面

- **无新表、无新列、无 config 键**，config-version 保持 33。`--max-idle` 的默认值读现有 `reclaim.max-idle-seconds`；`--max-wait` 默认值改用现有
  `timetable.assign-tolerance-seconds`；上限 1800 是常量。
- 命令：`build` 新增 `--max-idle <sec>`（0–3600）；`--max-wait` 范围改 0–1800，去掉 hold-max 封顶与那条黄字；`--strict` 语义改（§2.5）。
- 报告新增：残余两行 + 明细、往返对余数一行、资源相位一行（每个方向的 δ 与消掉的资源）、`交路形状` 保留。
- `TimetableBuildResult` 新增：`absorbable`（`List<ConflictAbsorption.Residual>`）、`unabsorbable`（同型）、`phaseDeltas`（`Map<String,Integer>`）。
  `conflictsAtTarget` 保留 = 全部残余（可吸收 + 不可吸收），`headwayRelaxed()` 只看不可吸收。
- 存储：残余不落库。

---

## 4. 逐文件改动清单

`TimetableBuilder` ~950 行、`FtaTimetableCommand` ~2700 行：本轮**新逻辑一律进新类**，两个大类只加调用。

### 新增

| 文件 | 职责 | 阶段 |
| --- | --- | --- |
| `dispatcher/schedule/timetable/ConflictAbsorption.java` | 残余冲突分类：可吸收 / 不可吸收 + 原因 + 预计等待 + 让车点；build 与 publish 重检共用 | F2 |
| `dispatcher/schedule/timetable/ResourcePhasePlanner.java` | 相位第三层：周期流模板、K 周期评估、δ 扫描 | F3 |
| `dispatcher/schedule/timetable/PeriodicTemplate.java` | 一个周期流在各资源上的占用模板（班次 + 可选的紧接回库） | F3 |
| `dispatcher/schedule/timetable/OccupationIndex.java` | 冲突检查的资源索引形态（增量替换、按资源扫描） | F4 |
| `command/TimetableBuildReportSender.java` | 从 `FtaTimetableCommand` 抽出 `sendBuildReport/sendNeighborReport/sendExternalConflicts` 与动作按钮 | F2 |

### 修改

| 文件 | 改动 | 阶段 |
| --- | --- | --- |
| `ServiceGroupClassifier.java` | `originGroupOf/terminalGroupOf` 按乘客首末站（§2.7） | F1 |
| `VehicleDutyPlanner.java` | `Limits.maxIdleSeconds`；`selectHost` 的闲置判据；`CloseReason.IDLE_LIMIT`；`Legs.returnLegAt(station, preferredDepot)`；`plan` 多一个"各起点下一时隙"参数 | F1 |
| `VehicleDuty.java` | `CloseReason` 加 `IDLE_LIMIT` | F1 |
| `TimetableBuilder.java` | `prepare`：非本线 route 只做走行段并标 external；`attempt`：调第三层、调分类、成功判据改不可吸收；搜索只在不可吸收非空时进 | F1/F2/F3 |
| `TimetableBuildOptions.java` | `Repair.maxWait` 默认 300、上限 1800、`tolerance ≥ maxWait`；`Limits` 经命令层带 maxIdle | F1 |
| `TimetableBuildResult.java` | `absorbable/unabsorbable/phaseDeltas`；`headwayRelaxed()` 只看不可吸收 | F2 |
| `TimetableBuildReportText.java` | 残余两行 + 明细、余数一行、资源相位一行 | F2/F3 |
| `PhasePlanner.java` | 暴露相对相位与往返对信息给第三层；第二层的起点扫描降为并列键 | F3 |
| `ResourceRepair.java` | 连锁段回滚；用 `OccupationIndex` 增量重扫；cap 公式 | F4 |
| `TimetableConflictChecker.java` | `OccupationIndex` 的构造入口（`project` 拆成"生成占用"与"扫描"两步，语义不变） | F4 |
| `TimetableRoutePlan.java` | `external` Javadoc 措辞 | F1 |
| `TimetableSetBuilder.java` | 单线成员也填 `lineByRoute` | F1 |
| `FtaTimetableCommand.java` | 单线也传 `lineByRoute`；`--max-idle`；`--max-wait` 范围与默认；报告与动作按钮迁到 `TimetableBuildReportSender`；publish 用 `ConflictAbsorption` | F1/F2 |
| `docs/dev/timetable.md` | 不变量 6 措辞；让车/残余/相位第三层/方向键/max-idle/external 语义 | 各阶段 |

---

## 5. 完整签名（Javadoc 摘要级，无方法体）

### 5.1 分类器与派车

```java
public final class ServiceGroupClassifier {
  /** 起点站台组：第一个落在车站上的停靠点（STOP/TERMINATE；DYNAMIC 取目标组）；都没有才退回首路径点。车库与路径点不是站。 */
  static String originGroupOf(TimetableBuilder.RouteInput route);
  /** 终点站台组：最后一个落在车站上的停靠点；规则同上。 */
  static String terminalGroupOf(TimetableBuilder.RouteInput route);
}

public final class VehicleDutyPlanner {
  /** 交路硬上限：加闲置上限——在端点等不到下一班就回库，与 reclaim.max-idle-seconds 同一条规则。 */
  public record Limits(int maxTripsPerDuty, int maxDutyDurationSeconds, TurnaroundTable turnaround, int maxIdleSeconds) {
    public static final int DEFAULT_MAX_IDLE_SECONDS = 300;
  }
  /**
   * 派车。nextSlotByOrigin：各起点上后续班次的名义时刻（升序），封口判据用它回答"下一班最早什么时候"；为空则退化为今天的行为。
   */
  public static Result plan(UUID timetableId, List<PlannedTrip> trips, Legs legs, Limits limits,
                            Map<String, NavigableSet<Integer>> nextSlotByOrigin);
  public record Legs(...) {
    /** 同站多条回库线路时的选择：回到 preferredDepot 所在车库组的 > 显式指定的 > 走行最短的。存在性判断（closable）不变。 */
    public Optional<Leg> returnLegAt(String stationNodeId, String preferredDepotNodeId);
  }
}

public record VehicleDuty(...) {
  public enum CloseReason { ..., /** 在端点等不到下一班（超过闲置上限），按计划回库。 */ IDLE_LIMIT }
}
```

### 5.2 残余分类

```java
/** 残余冲突的分类：运行时能不能让、在哪让、让多久。build 成功判据与 publish 重检都用它，两边必须一致。 */
public final class ConflictAbsorption {
  public enum Verdict { ABSORBABLE, EXTERNAL, STUB_TERMINAL, OVER_MAX_WAIT, NO_WAITING_CAPACITY, CHAIN_TOO_DEEP }
  /**
   * 一处残余。
   * @param conflict 冲突
   * @param mover 后车 code（最终车次号 / duty 号）
   * @param waitSeconds 预计等待
   * @param waitingPoint 让车点（站台组 / 车库 / 空）
   * @param chainDepth 因它后移的后续班次数
   * @param verdict 判决
   */
  public record Residual(TimetableConflictChecker.Conflict conflict, String mover, int waitSeconds,
                         Optional<String> waitingPoint, int chainDepth, Verdict verdict) {
    public boolean absorbable();
  }
  /**
   * 分类。
   * @param report 修复后的冲突报告
   * @param timetable 最终表（找后车所在交路与上一停靠点）
   * @param occupancy 最终表的投影（数让车点在该时刻的待命）
   * @param index 图索引（站台组容量）
   * @param separationSeconds 裕量
   * @param maxWaitSeconds 单步上限
   */
  public static List<Residual> classify(TimetableConflictChecker.Report report, Timetable timetable,
                                        TimetableOccupancyProjector.Occupancy occupancy,
                                        TimetableConflictChecker.GraphIndex index,
                                        int separationSeconds, int maxWaitSeconds);
  /** 报告用：按判决计数。 */
  public static Map<Verdict, Integer> countByVerdict(List<Residual> residuals);
}
```

### 5.3 相位第三层

```java
/** 一个周期流在各资源上的一周期占用模板：一班运行，若交路只有这一班则连同折返与紧接的回库走行。 */
public record PeriodicTemplate(String key, int intervalSeconds,
                               List<TimetableConflictChecker.Movement> movements,
                               List<TimetableConflictChecker.Stay> stays) {
  /** 从方向与投影建模板；单班交路（起点是车库、终点有回库线路且不再接班）把回库走行也放进来。 */
  public static PeriodicTemplate of(ServiceGroupClassifier.Direction direction, int intervalSeconds,
                                    Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
                                    VehicleDutyPlanner.Legs legs, TurnaroundTable turnarounds, boolean singleTripDuty);
  /** 铺 cycles 个周期、整体加 phase 后的占用（code 带周期序号）。 */
  List<TimetableConflictChecker.Movement> unroll(int phase, int cycles);
}

/**
 * 相位第三层：在往返对锚定之上，为每个方向选一个 δ，使共用受限资源上的周期冲突最少。正向方向的 δ 是组整体偏移，
 * 反向方向的 δ 是端点多等（≤ maxIdle）。评估用同一个冲突模型投影 K 个周期；全部确定性。
 */
public final class ResourcePhasePlanner {
  public static final int SCAN_STEP_SECONDS = 10;
  public static final int CYCLES = 3;
  /**
   * @param phases 前两层的结果
   * @param groups 分类
   * @param intervalByGroup 各组间隔
   * @param templates 各方向的模板（键 = 方向键）
   * @param index 图索引
   * @param separationSeconds 裕量
   * @param maxWaitSeconds A2 用
   * @param maxIdleSeconds 反向 δ 上限
   * @return 带 deltaByDirection 与 resourceNotes 的新 Phases
   */
  public static PhasePlanner.Phases refine(PhasePlanner.Phases phases, List<ServiceGroupClassifier.Group> groups,
                                           Map<String, Integer> intervalByGroup, Map<String, PeriodicTemplate> templates,
                                           TimetableConflictChecker.GraphIndex index, int separationSeconds,
                                           int maxWaitSeconds, int maxIdleSeconds);
  /** 评分：(不可吸收数, 总数, 总预计等待)，字典序。 */
  record Score(int unabsorbable, int total, long waitSeconds) implements Comparable<Score> {}
}

public final class PhasePlanner {
  /** 相位结果加两项：各方向的端点多等 δ、第三层的说明。 */
  public record Phases(Map<String, Integer> phaseByDirection, Map<String, Integer> offsetByGroup,
                       Map<String, Integer> deltaByDirection, List<String> notes, List<String> resourceNotes) {}
  /** 往返对余数：报告用，不参与决策。 */
  public record Residue(String forwardKey, String reverseKey, int runSeconds, int turnaroundSeconds, int intervalSeconds, int residue) {}
  public static List<Residue> residues(List<ServiceGroupClassifier.Group> groups, Map<String, Integer> intervalByGroup,
                                       Map<UUID, Integer> runSecondsByRoute, TurnaroundTable turnarounds);
}
```

### 5.4 修复与增量重扫

```java
/** 冲突检查的资源索引形态：占用按资源键分桶，可替换一辆车的占用、只扫给定资源。语义与 check() 完全一致。 */
public final class OccupationIndex {
  public static OccupationIndex of(TimetableConflictChecker.GraphIndex index, Map<UUID, RouteProfile> profiles,
                                   List<Movement> movements, List<Stay> stays, Function<String, String> vehicleOf);
  /** 换掉一辆车（duty 号）的全部占用；返回它前后触及的资源键并集。 */
  public Set<String> replaceVehicle(String vehicle, List<Movement> movements, List<Stay> stays);
  /** 只扫这些资源。 */
  public TimetableConflictChecker.Report scan(Set<String> resourceKeys, int separationSeconds);
  /** 全扫（与 check 等价）。 */
  public TimetableConflictChecker.Report scanAll(int separationSeconds);
}

public final class ResourceRepair {
  /** 连锁段深度上限。 */
  public static final int CHAIN_LIMIT = 6;
  public record Input(..., int maxWaitSeconds, int toleranceSeconds, ...) {}   // 不变
  /** 修复：每处让车后只重投影被挪的交路、只扫它触及的资源；新增的可修冲突在同一连锁段内继续修（≤ CHAIN_LIMIT），段末不变好则整段回滚。 */
  public static Result repair(Input input);
}
```

### 5.5 builder、结果、命令

```java
public final class TimetableBuilder {
  public record BuildInput(..., Map<UUID, UUID> lineByRoute) {
    /** 这条 route 归本表管辖：在 lineByRoute 里。单线也要填，空表示"全部归我"（仅单元测试用）。 */
    boolean owns(UUID routeId);
  }
}

public record TimetableBuildResult(..., List<ConflictAbsorption.Residual> absorbable,
                                   List<ConflictAbsorption.Residual> unabsorbable, Map<String, Integer> phaseDeltas, ...) {
  /** 只有不可吸收残余会触发放宽。 */
  public boolean headwayRelaxed();
}

public final class TimetableBuildReportText {
  public static List<String> describeResiduals(List<ConflictAbsorption.Residual> absorbable, List<ConflictAbsorption.Residual> unabsorbable,
                                               int holdMaxSeconds, int serviceStartSecondOfDay, int detailLimit);
  public static List<String> describeResidues(List<PhasePlanner.Residue> residues);
  public static List<String> describeResourcePhases(Map<String, Integer> deltaByDirection, List<String> resourceNotes);
}

/** 从 FtaTimetableCommand 抽出的报告发送器：只做 Component 拼装与动作按钮，不读库。 */
final class TimetableBuildReportSender {
  TimetableBuildReportSender(CommandSender sender, int holdMaxSeconds);
  void sendBuildReport(TimetableBuildResult result, TimetableBuildOptions options, TimetableHeadwayDefaults.Choice headway, Map<String, String> groupSources);
  void sendNeighborReport(NeighborReport neighbors, List<TimetableBuildResult.NeighborSummary> summaries);
  void sendExternalConflicts(List<TimetableConflictChecker.Conflict> external, int serviceStartSecondOfDay, boolean relaxed);
  void sendSavedActions(List<ResolvedLine> lines, List<Timetable> saved, TimetableBuildOptions options, TimetableBuildResult result);
}
```

---

## 6. 测试矩阵

新夹具（加到 `TimetableTestFixtures`）：

- **`resonantPairFixture`（往返对共振，prompt 要求）**：单线 `A – B – C`（一股道），RA A→C、RB C→A，走行 + 折返 = 间隔的整数倍。
  期望：第三层给反向 δ，使交会落在 B（B 给两股道），δ 进报告；`--max-idle` 允许时才动。
- **`mergingCorridorFixture`（并线走廊，MT 形态）**：两组从不同起点（X、Y）汇入共用走廊 `M – N – T`，起点不共用。期望：第三层错开，冲突 0；第二层单独做不到。
- **`terminalIdleFixture`（端点闲置，MT 形态）**：三路进 T（其中一路 CREATE 带客）、一路出，T 两股道。期望：`--max-idle 300` 时多余的车回库（`IDLE_LIMIT`），T 上无站台冲突；`--max-idle 3600` 时复现今天的 19 分钟待命与冲突。
- **`yardThroatFixture`（车库咽喉，WS 形态）**：出库班（CRET）每 h 一班经单线咽喉出去，单班交路立即回库经同一咽喉回来。期望：不动相位时某些 h 撞；第三层选 δ 后干净；连锁段修复能把残余压到可吸收。
- 既有 `stubTerminal`、`interleave`（两股道大小交路）继续用。

| 不变量 / 决策 | 测试类 | 用例（★ 新增，☆ 改期望） |
| --- | --- | --- |
| E1 方向键 | `ServiceGroupClassifierTest` | ★`directionKeysUsePassengerEndpoints`（CRET 车库起点、路径点终点、DYNAMIC 终点三种都归到站台组）；★`depotStartAndSidingStartShareADirection` |
| E2 max-idle | `VehicleDutyPlannerTest` | ★`hostIdleBeyondLimitIsNotReused`；★`dutyClosesWithIdleLimitWhenNextSlotIsTooFar`；☆ `loopRouteCannotChainForever` 等既有用例显式传 `maxIdle = 3600` 保持原期望 |
| E3 回库偏好 | `VehicleDutyPlannerTest` | ★`returnLegPrefersTheOriginDepot`；★`declaredStillBeatsShorterWhenDepotsTie` |
| D2 2.6 | `TimetableBuilderTest` | ★`foreignPassengerCreateIsOnlyALegAndMarkedExternal`；★`managedRouteIdsExcludeForeignLegs`；`TimetableSetBuilderTest` 既有用例不改一行仍绿 |
| A2 分类 | `ConflictAbsorptionTest` | ★六个判决各一例；★`classificationIsDeterministic`；★`publishRecheckAgreesWithBuild`（同一表两次分类相等） |
| A1/C2 | `TimetableBuilderTest` | ★`absorbableResidualsDoNotRelaxOrFail`；★`unabsorbableResidualsStillRelax`；☆ `strictModeFailsInsteadOfRelaxing` 改为不可吸收夹具；★`strictAcceptsAbsorbableResiduals` |
| A3 | `TimetableBuildOptionsTest`（新） | ★`maxWaitDefaultsToTolerance`；★`toleranceNeverBelowMaxWait`；命令层 `--max-wait 1800` 不再被夹回 |
| B1–B3 | `ResourcePhasePlannerTest` | ★`resonantPairGetsATerminalDeltaOnlyWhenItRemovesUnabsorbable`；★`mergingCorridorIsInterleaved`；★`deltaIsBoundedByMaxIdle`；★`refineIsDeterministic`；★`zeroDeltaWinsTies` |
| B4 | `PhasePlannerTest` | ★`residuesAreReportedNotActedOn` |
| E4 连锁 | `ResourceRepairTest` | ★`chainOfSmallYieldsIsAcceptedAsOneSegment`；★`worseningChainIsRolledBackAsAWhole`；既有 8 例不改 |
| E4 增量 | `OccupationIndexTest` | ★`scanAllEqualsCheck`（随机夹具逐字段相等）；★`replaceVehicleTouchesOnlyItsResources`；★`incrementalRepairEqualsFullRescan`（同输入两条路径产物相等） |
| 不变量 2 | `TimetableBuilderTest` | 既有 `buildIsDeterministic` + ★`residualsAndDeltasAreDeterministic` |
| 不变量 4/5/6 | 既有 | 不改一行仍绿；`everyDutyStillReturnsToStorageAfterSerialization` 覆盖 `IDLE_LIMIT` 收口 |
| 零行为变化 | `TimetableBuilderNeighborsTest` 等跨线用例 | 不改一行仍绿（外部残余永远不可吸收，与今天同） |

---

## 7. 阶段划分（每阶段独立编译、独立过 `./gradlew spotlessApply clean check`、独立验证）

| 阶段 | 内容 | 验收（实服判别量，用探针 v2 对实服副本跑） |
| --- | --- | --- |
| **F1 四处缺陷** | E1 方向键、E2 max-idle、E3 回库偏好、D2 2.6、A3 解耦（默认 300 / 上限 1800） | MT@1200 纯走行：`platform-group:SURC:S:PPK` 不再是头号资源，冲突从 921 降一个数量级；MT 表里每条交路回自己的车库；WS 表里没有 DS/MT 的 route；`--max-wait 300` 不再被夹回 120 |
| **F2 残余分类与语义** | `ConflictAbsorption`、结果与报告、`--strict`/publish 语义、`TimetableBuildReportSender` 抽出 | WS@300：报告给出"可吸收 N / 不可吸收 K"，K < N；`--strict` 只对 K 失败；`FtaTimetableCommand` 行数下降 |
| **F3 资源相位层** | `PeriodicTemplate`、`ResourcePhasePlanner`、余数报告 | WS@300 与 WS@290–410：咽喉上的 `1L × 回库走行` 冲突接近 0，曲线不再锯齿；MT@1200 纯走行接近 0；四个新夹具全绿 |
| **F4 连锁与增量** | `OccupationIndex`、连锁段回滚、cap 公式 | WS@300 单次 attempt 从 55 s 降到 5 s 以内；产物与全量重扫逐字段相等 |
| **F5 文档** | `timetable.md` 不变量 6 与各节；routine 更新 | 文档与报告一致 |

F1 独立可发，且是实服最急的（MT 今天任何间隔都排不出来）。

---

## 8. 给实现者的「别自作主张」清单

- **不要**把"可吸收残余"落库；publish 重检按同一判据复算，两次分类不一致就是 bug。
- **不要**让第三层改往返对的锚定关系（反向相位只能是"锚定 + δ"，δ ≥ 0、≤ max-idle）；也不要让它引入哈希序或随机源。
- **不要**据余数跳档（B4）；余数只进报告。
- **不要**在命令层或分类器里做 2.6 的过滤（D2 定了 builder）。
- **不要**给 `--max-wait` 恢复 hold-max 封顶；也不要新增 config 键。
- **不要**把 `--max-idle` 的默认写死 300：读 `reclaim.max-idle-seconds`（命令层），`Limits.defaults()` 才是 300。
- **不要**改 `TimetableConflictChecker` 的比较语义：`OccupationIndex.scanAll` 必须与 `check` 逐字段相等，用测试钉住。
- **不要**在联编（`TimetableSetBuilder`）里另做一套归属判断：单线也填 `lineByRoute`，一套规则。
- **不要**动跨线作用域 S1–S4 与折返表的产物（外部残余永远不可吸收，与今天行为相同）。
- 涉及具体字段、类名时先读代码确认（本文签名按 HEAD `168c28a` 写：`selectHost` 在 `VehicleDutyPlanner.java:173`，`originGroupOf` 在
  `ServiceGroupClassifier.java:193`，`moveFor` 在 `ResourceRepair.java`，`holdsDeparture` 在 `StationStopCoordinator.java:104`，`HOLD_CEILING` 150 在同文件第 37 行）。
- **禁止主动 commit**。

---

## 9. 与 prompt 的分歧（明说）

1. "等待预算差 60–120 倍是主因"：**不成立**。WS 预算放到 1800 一处不少；MT 是流量不平衡。7200 那个数约束的是发车队列票据，不是等待。
2. "2C/2N 相位共振"：**不成立**。余数与冲突无关，2C×2N 从不进前六；WS 主线是双线。非单调来自车库咽喉的出入库流共振。
3. "MT 的失败可能是污染"：**相反**——去掉污染更差，因为污染碰巧提供了共用起点让第二层错开了 MT-2。
4. "冲突预算（表带着冲突发布）"：**成立但要收窄**——只有 A2 定义的可吸收残余；它不是放宽的替代品，不可吸收的仍要放宽或取消。
5. "hold-max 封顶是错误耦合"：**成立**。

---

## 10. 后续（本轮明确不做）

- 容量 1 端点的进站桥链作为"让车点无容量"的判据做得很粗（按端点组判），做细要把区段两端的等待点建模。
- 联编时第三层跨线（今天联编已经共享相位，第三层自然跨线；未单独验证）。
- `--max-trips` 默认与 `maxOperationTrips` 对齐（仍是用户的事）。
- 派车按实际时刻推进（上一轮 §10 的最后一条）。

---

## 11. 诊断复现

```
cp ../fetarute_experimental/plugins/FetaruteTCAddon/data/fetarute.sqlite <scratch>/live.sqlite     # 只读副本
cp .handoff/LiveNetworkProbeTest.v2.java.txt src/test/java/org/fetarute/fetaruteTCAddon/dispatcher/schedule/timetable/LiveNetworkProbeTest.java
# 把文件顶部 DB 常量改成副本路径；探针把每一行同时写到副本旁边的 probe.log
./gradlew test --tests '*LiveNetworkProbeTest'
rm src/test/java/.../LiveNetworkProbeTest.java     # 用完删掉，不提交
```

四个用例按顺序：`step1MtAt1200`（整张表 + route 对 + 资源）、`step2MtMaxWait`、`step3WsResonanceScan`、`step2WsMaxWait`（慢，WS@300 每档约 55 s）。
本文全部数字来自 `.handoff/probe-2026-09-20.log.txt`。
