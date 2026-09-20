# 时刻表测试 routine

**适用版本**：`wip/dynamic-dispatch-stabilization`，跨线作用域 S1–S4 + P0/P1 + 限速覆盖 + Q1/Q2/Q3 + 折返改为终到站 dwell。
上一版 routine（建立在 `--turnaround` 默认 180 秒之上）作废，期望数字全变了，原因见下。

实服：`../fetarute_experimental`。company `FTAS` / operator `SURC` / 线路 `WS`(4 route) `MT`(6) `DS`(2)。

每阶段给「做什么 / 期望 / **判别量** / 失败看哪」。判别量是能证明这一层真生效的那个数——
只看"没报错"会漏掉"功能根本没跑起来"，这在本项目已经栽过不止一次。

---

## 这一版和上一版的四个关键差异

1. **折返不再是 180 秒常数**。按各 route 终到停靠点的 dwell 逐条取（`TurnaroundTable`），
   `--turnaround` 降级为显式全线覆盖。WS 两条终到 CHT 的 route 没配 dwell → 走 `--dwell` 兜底 **20 秒**。
   离线复核里这一个参数就占了 CHT 九成占用，所以**所有与冲突数、可行间隔有关的期望都要重新建立**。
2. **行程时分变了**。build 此前没接 `EdgeSpeedResolver`，边基础限速为 0 时全线按默认 8 bps 算，慢两到三倍；
   现在读 `fta_rail_edge_overrides` 的**永久**限速（临时限速与封锁带截止时刻，为了确定性不进表）。
3. **三个模型缺陷已修**（PASS 路径点不再算停站、进站路径点不再消耗站台容量、同一辆车不再撞自己）。
   旧报告里那 2160 处冲突有相当部分是伪冲突，不要拿它当基准。
4. **命令面变了**：`<line>` 接受逗号列表、多了 `--group-headway` / `--max-wait`、publish 变成整组语义、
   报告多了五类行与一排动作按钮。

## 已知瓶颈（编表前先知道）

`SURC:S:CHT:3` 是 CHT 在图里唯一的 STATION 节点（容量 1），WS 三条运营 route 加回库 route **全部**以它为端点。
P1 的端点串行会把容量 1 的端点当串行资源处理，CHT 的冲突应当**按构造归零**，代价是班次被延后。
离线复核（折返 20 + PASS 修正，但**未套限速覆盖**）给出的预期是：`--max-trips 4` 时约 **180 s** 可行，
`--max-trips 7` 时约 **240–300 s**。实服接了限速覆盖，时分不同，这两个数只是量级参考，不是验收线。

---

## L0 代码层

```
230 suites / 2062 tests / 0 failures / 0 errors / 0 skipped
SpotBugs main 0 / test 0
```

重跑：`./gradlew spotlessApply clean check shadowJar`，然后解 `build/test-results/test/*.xml` 看 failures，
**不要只看 `BUILD SUCCESSFUL`**。jar 指纹在 `build-info.properties` 的 `gitCommit`。

---

## L1 部署与冷启动自检

### 做什么

服务器停稳后（`logs/latest.log` 末行是 `MoonriseCommon` 那几条）：

```bash
cd /Users/acatine/Documents/CodeLib/FetaruteTCAddon
D=../fetarute_experimental
cp "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite" "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite.bak-$(date +%m%d-%H%M)"
cp "$D/plugins/FetaruteTCAddon/config.yml" "$D/plugins/FetaruteTCAddon/config.yml.bak-$(date +%m%d-%H%M)"
cp build/libs/FetaruteTCAddon-0.0.2.jar "$D/plugins/FetaruteTCAddon-0.0.2.jar"
```

起服，然后 `/fta graph build`（L6 的发布重检要图快照，没有会被拒）。

### 期望

- `SMART_RUNTIME_BUILD_FINGERPRINT ... gitCommit=<本地 HEAD>`
- `config.yml` 的 `config-version` 变成 `33`，多出 `reclaim:` 段（`enabled: false`）
- `timetable:` 段全部默认关

### 判别量

```bash
sqlite3 $D/plugins/FetaruteTCAddon/data/fetarute.sqlite \
  "select name from sqlite_master where type='table' and name like 'fta_timetable%';"
# 期望 4 行，含 fta_timetable_baselines
```

`fta_timetable_baselines` 缺席 = 跑的是旧 jar，L5/L6 全部无效。**用 fingerprint 判断版本，不要用"某命令在不在"。**

---

## L2 单线编表 + 确定性（不开任何开关）

`timetable.enabled: false` 不影响 build，它是纯计算 + 落库。

### 做什么

```
/fta timetable build FTAS SURC WS WS-D1 --start 05:00 --end 24:00
/fta timetable info    FTAS SURC WS WS-D1
/fta timetable duties  FTAS SURC WS WS-D1
/fta timetable export  FTAS SURC WS WS-D1 200
```

然后**原样再 build 一次**（同 code、同参数），再 export 一次。

> `--end 24:00` 是 24 小时。`--end` 是"末班必须**跑完**"的时刻，不是末班发车时刻；
> `--start 00:00 --end 00:00` 会被拒（`serviceEnd <= serviceStart`），跨零点写 `25:00` 这类形式。

### 期望：报告要逐项看的行

| 行 | 看什么 |
| --- | --- |
| 交路组间隔 / 相位 / 合成间隔 | 每组每方向一张子网格；往返对锚定的相位说明 |
| **结构下界** | 每次折返占用 = 进站 + 折返 + 出站 + 裕量，**「折返取 …」要写「终到站 dwell」** |
| **端点串行** | CHT 的经过次数、占用百分比、偏离网格班次数、无处等待、截断 |
| **让车** | N 处已写进表（涉及 M 班，最长 +Xs，`--max-wait` Ys） |
| 共用资源 | 与 DS / MT 各共用多少处 |
| 冲突检查 | 目标间隔下的冲突数与明细 |
| 取消班次 | 按 route 与原因归组（注意新原因 `STUB_SATURATED`） |
| 交路数 / 峰值同时在线 / 目标 vs 实际服务比例 / 所有交路都以回库收尾 | 同旧版 |

### 判别量

1. **确定性**：两次 export **逐字节相同**，含 trip/duty 主键。不同 = 不变量 2 破了，后面不用测。
2. **折返来源**：「结构下界」那行必须写 **`折返取 终到站 dwell 20s`** 一类字样。
   写成 `--turnaround 180` 说明跑的是旧 jar；写 `终到站 dwell` 但数字明显偏大说明 route 的 dwell 配大了。
3. **CHT 不在冲突里**：端点串行之后 CHT 的站台/单线冲突应当为 0。仍然有 = 串行没生效。
4. **回库行**：必须是"所有交路都以回库收尾"。
5. **共用资源 > 0**：WS 与 DS/MT 实测共用 243 / 229 处。报 0 才是异常。

### 失败看哪

报告没出来也没报错：build 在异步线程算、主线程报，看控制台异常栈。

---

## L3 折返来源与结构下界（本轮改动的正面验证）

### 做什么

同一条线、同一参数，跑三次，只改折返来源：

```
/fta timetable build FTAS SURC WS WS-T1                      ← 折返 = 终到站 dwell（默认路径）
/fta timetable build FTAS SURC WS WS-T2 --turnaround 180     ← 显式覆盖成旧默认值
/fta timetable build FTAS SURC WS WS-T3 --dwell 60           ← 抬高兜底 dwell（CHT 两条 route 没配 dwell）
```

### 判别量（这一层最重要）

| | WS-T1 | WS-T2 | WS-T3 |
| --- | --- | --- | --- |
| 「折返取」 | 终到站 dwell | `--turnaround 180` | 终到站 dwell 60s（CHT 侧） |
| 结构下界 | 最小 | 明显更大 | 介于两者之间 |
| 端点占用 % | 最低 | 最高 | 中间 |

**T2 的端点占用应当显著高于 T1**（离线复核是九倍量级）。两者一样 = 折返表没接上，`--turnaround` 或 dwell 有一边没生效。

T3 用来确认兜底路径：CHT 那两条 route 的终到点**没配 dwell**，所以 `--dwell` 直接决定它们的折返。
T3 的下界不动 = 兜底没走通。

> 顺带：`--turnaround` 现在只在**显式传了**的时候才出现在报告的重建命令里。T1 的 [按放宽后的间隔重建] 按钮
> 生成的命令里不该有 `--turnaround`。

---

## L4 线内冲突与 headway 搜索（MT，六条 route 互相压）

### 做什么

```
/fta timetable build FTAS SURC MT MT-D1
/fta timetable build FTAS SURC MT MT-D2 --headway 60
/fta timetable build FTAS SURC MT MT-D3 --headway 60 --strict
/fta timetable build FTAS SURC MT MT-D4 --group-headway MT-1_Short=150 --group-headway MT-2_Fueya=120
```

MT 的组名在 `fta_lines.metadata` 里：`MT-1_Short`(baseline 150) 与 `MT-2_Fueya`(120)。

### 判别量

- `MT-D1` 的「间隔来源」说明默认从哪来（线路级 `spawn_freq_baseline_sec=120` 优先）。
- `MT-D2`：目标 60 秒应有冲突 → 回退，报告标出目标间隔下的冲突明细。60 秒直接干净要怀疑检查没跑。
- `MT-D3` **必须失败**且不落库（`/fta timetable list` 里没有它）。
  注意 `--strict` 现在**只对真冲突失败**——能靠让车写进表的不算。
- `MT-D4`：报告的「交路组间隔」要分别显示两个组的值，不是一个全线数。
- **搜索上限是目标的 4 倍**。目标给小了搜索窗口跟着小；不确定量级时先 `--headway 1800` 把窗口撑开。

---

## L5 跨线：共用资源与路权先到先得

### L5a 邻表未发布时只报告不检查

确认 DS 没有 PUBLISHED，然后 `/fta timetable build FTAS SURC MT MT-N1`。
记下**实际使用的间隔**（`H_before`）与共用资源数。

期望：DS 那行写「无已发布时刻表——它按 headway 发车，干扰单向，无法联合排布」，**外部冲突那行不出现**。

### L5b 邻表发布后进入检查

```
/fta timetable build   FTAS SURC DS DS-P1
/fta timetable publish FTAS SURC DS DS-P1        ← DS 先占路权
/fta timetable build   FTAS SURC MT MT-N2        ← 同 L5a 参数，唯一变量是 DS 发布了
```

### 判别量（整套 routine 里最值钱的两个之一）

```bash
sqlite3 $D/plugins/FetaruteTCAddon/data/fetarute.sqlite \
  "select neighbor_code, shared_resources, conflicts_at_target, stale_against_graph
   from fta_timetable_baselines;"
```

`MT-N2` 必须有一行指向 `DS-P1` 且 `shared_resources > 0`。**这是"邻表真被读到了"的唯一硬证据**——
报告文本可能骗人，这张表不会。

再看 `H_after` vs `H_before`：共用资源 > 0 却两次完全一样、外部冲突为 0，要回到上面那条 SQL 确认。
（DS 只有 2 条 route，稀疏时真的可能不撞；想逼出冲突就给 MT 一个很密的 `--headway`。）

### L5c 多线联编（Q3 新增）

```
/fta timetable build FTAS SURC MT,DS MD-1
```

期望：**一份报告**、逐线给 [详情] [交路] [导出]、一个 [整组投入运行]；两条线**互不算邻表**（一起编的线共享相位与让车）。

判别量：`fta_timetables` 里应当出现**两张**表（MT 一张、DS 一张），`updatedAt` 相同，且**互相记了基线**。
只出一张 = 拆表没做对。另外 duty 号每表都从 `D001` 起，车池不跨线（一辆车不会同时跑 MT 和 DS）。

---

## L6 publish 重检与基线回归

### 做什么

顺序很重要：

```
1. unpublish DS-P1（确保 DS 无已发布表）
2. /fta timetable build   FTAS SURC MT MT-G1      ← 此时基线里没有 DS
3. /fta timetable publish FTAS SURC DS DS-P1      ← 邻表集合变了
4. /fta timetable publish FTAS SURC MT MT-G1      ← 应当被拦
```

期望第 4 步：`TIMETABLE_PUBLISH_REJECTED：邻表自 build 以来有变化，与已发布邻表有 N 处冲突`，`MT-G1` 仍是 DRAFT。
若重检发现无冲突则提示「邻表有变化但无冲突，已更新基线」并正常发布——也是合法结果。

### 基线不被 save 抹掉（`2f24f48` 修的 bug，必须回归）

```
/fta timetable publish   FTAS SURC WS WS-D1
/fta timetable unpublish FTAS SURC WS WS-D1
/fta timetable neighbors FTAS SURC WS WS-D1
```

**判别量**：基线那行仍是「与当前邻表一致」或「已过期」，**不能变成「无（build 时没有邻表，或基线尚未落库）」**。
变成"无"就是状态变更又把基线冲掉了。也可直接看 `select count(*) from fta_timetable_baselines;` 前后不减。

### 整组发布（Q3）

`publish FTAS SURC MT,DS MD-1`：任一张撞上外部邻表则**整组不发**，成功时两张同一时刻发布并互记基线。
判别量：失败时两张都还是 DRAFT，不能一张发了一张没发。

### 预期中的坑（不是 bug，别误报）

- `--separation` **不落库**，publish 重检固定用默认 30 秒，和 build 报告对不上是正常的。
- 没有图快照时：有基线→拒绝并提示先 `/fta graph build`；无基线→跳过重检直接发。
- `unpublish` / `delete` **不级联**通知别人。

---

## L7 让车写进表（Q2 新增）

### 做什么

```
/fta timetable build FTAS SURC WS WS-Y1 --max-wait 0     ← 关闭修复，冲突原样上报
/fta timetable build FTAS SURC WS WS-Y2                  ← 默认 60
/fta timetable build FTAS SURC WS WS-Y3 --max-wait 120   ← 封顶 timetable.hold-max-seconds（默认 120）
```

### 判别量

- `WS-Y1` 的报告写「让车: 关闭（`--max-wait 0`），冲突原样上报」，冲突数应当 **≥** Y2。
- `WS-Y2` 写「让车: N 处已写进表（涉及 M 班，最长 +Xs）」，且**总冲突数比 Y1 少**。一样多 = 修复没生效。
- `--max-wait` 超过 `timetable.hold-max-seconds` 会被封顶——因为运行时扣留不了那么久，
  表里写了车也等不到。传 300 看看是不是被夹回 120。

---

## 别踩的两个坑

- **`--spawn-weight 0` 不等于停开某条 route**：写 `<= 0` 会删掉 `spawn_weight` 键，而读取端键缺失时**默认回 1**。
- **别拿旧报告的冲突数当基准**：P0 修掉伪冲突之前的数字（例如那张 2160 处的截图）已经没有可比性。

---

## L8 直通运转 / 外方引用（现有网络只能测半程）

只有一个 operator，跨 operator 闭环测不了。能测引用解析与校验：

```
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route FTAS/SURC/DS/DS-1F_Full
    → DS-1F_Full 不是 RETURN 类型 → build 时报"指定的回库线路类型不符"
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route FTAS/SURC/XX/NOPE
    → 写入成功（只校验格式），build 时只警告不中断
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route 乱写
    → 命令层拒绝，提示格式应为 <company>/<operator>/<line>/<route>
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route-clear
```

**判别量**：大小写按原样存。写 `FTAS/SURC/DS/ds-1f_full` 读回来必须还是小写那串（仓储按 code 精确匹配）。

完整闭环要先造第二个 operator + 终到它车站的 route + 它名下的 RETURN 线路，单独排一轮。

---

## L9 按表运行（要改配置 + 重启）

前面全过了再做。**只发布一条线的表**。

```yaml
timetable:
  enabled: true
  spawn-enabled: true
```

### 期望的 trace 顺序（`debug.enabled: true`）

```
TIMETABLE_RELOAD                             表被读进来了
TIMETABLE_SPAWN_TICKET kind=CREATE duty=…     出库票
TIMETABLE_SPAWN_DISPATCHED                    票派给了哪辆车
TIMETABLE_DUTY_BOUND                          车绑到交路
TIMETABLE_ASSIGN                              车绑到具体车次
SCHEDULED_DEPARTURE_HOLD                      早到的车在等点
TIMETABLE_DUTY_CLOSED                         交路额度用完
TIMETABLE_SPAWN_TICKET kind=RETURN duty=…     回库票
```

### 判别量

- `TIMETABLE_ASSIGN` 条数**随时间稳定增长**。停滞 = 车退回自由运行 → 看 L10。
- `TIMETABLE_DUTY_BIND_CONFLICT` **为 0**。
- 每个 `TIMETABLE_DUTY_CLOSED` 后跟得到同 duty 的 `kind=RETURN`；跟不到看是不是
  `TIMETABLE_SPAWN_SKIP reason=no-spawn-service`。
- **折返的现场校验**：某辆车在 CHT 终到后，下一班的实际发车应当约等于「到达 + 该 route 终到站 dwell」，
  不再是「到达 + 180」。这是本轮改动在实车上的唯一可观察点。

### 不该出现

`SCHEDULED_DEPARTURE_HOLD_SKIPPED`（早到超上限，多半绑错车次）、`SCHEDULED_DEPARTURE_PLAN_FAILED`（计划源抛异常）。

---

## L10 绑不上车次的可归因性

把容差调到明显不够（`timetable.assign-tolerance-seconds: 5`），重启，跑一会儿。

期望大量 `TIMETABLE_ASSIGN_MISS ... reason=out-of-tolerance`，`/fta timetable status` 的「绑定失败累计」持续上涨。

**判别量**：那个累计数 > 0 且在涨。恒为 0 = 计数器没接上。

三个 reason：`out-of-tolerance`（偏差超容差，正偏差 = 晚到）、`no-trips`（这条 route 在表里没有这个停靠点的车次）、
`all-claimed`（同一趟被别的车绑走）。测完**改回 300**。

---

## L11 滞留销毁兜底（破坏性，可选，放最后）

⚠️ **会销毁列车**。

```yaml
reclaim:
  enabled: true
  stranded-destroy-seconds: 120     ← 临时调小才观察得到，默认 1800
```

期望 `RECLAIM_STRANDED_DESTROY`；有乘客或有进行中折返事务的记 `RECLAIM_STRANDED_SKIP`
（`reason=has-passengers` / `reason=dispatch-attempt-in-progress`）。

**判别量**：`RECLAIM_STRANDED_SKIP` 必须真的出现过至少一次（找辆车坐上去）。只有 DESTROY 没有 SKIP，
说明"有乘客不碰"这条保护根本没验到。测完改回 1800。

---

## 出事了怎么回到干净状态

```bash
D=../fetarute_experimental   # 先停服
cp "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite.bak-XXXX" "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite"
cp "$D/plugins/FetaruteTCAddon/config.yml.bak-XXXX" "$D/plugins/FetaruteTCAddon/config.yml"
```

只清时刻表：

```sql
delete from fta_timetable_baselines;
delete from fta_timetable_duties;
delete from fta_timetable_trips;
delete from fta_timetables;
```

---

## 建议的执行顺序

L1–L7 一次坐下来能做完：不改配置、不重启、不碰实体车，是纯"算 + 落库"的验证，也是这几轮改动的主体。
L8 只做校验半程。L9–L11 各要一次重启，分开做。

整套里最值钱的两个判别量：**L3 的 T1/T2 端点占用对比**（证明折返改动真的生效）和
**L5 的 `fta_timetable_baselines` 查询**（证明跨线邻表真的被读到）。
