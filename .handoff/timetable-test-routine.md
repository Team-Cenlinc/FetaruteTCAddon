# 时刻表测试 routine（jar 2f24f48）

实服：`../fetarute_experimental`。company `FTAS` / operator `SURC` / 线路 `WS`(4 route) `MT`(6) `DS`(2)。

**2026-09-19 实测更新**：这张网上**没有干净的线**。WS 的 build 报告给出 WS↔DS 共用 **243** 处、
WS↔MT 共用 **229** 处。此前从申报停靠点只统计出 18 处、且 WS 一处都没有——那是下界，足迹按展开后的
路径算，量级差十倍以上。**下面凡是"拿某条线当独立对照组"的设计都已作废**（原 L4 的前提）。

**已知瓶颈**：`SURC:S:CHT:3` 是 CHT 在图里唯一的 STATION 节点（容量 1），而 WS 的三条运营 route
（`WS-1L_ShortC` 末站 / `WS-2C_FullR` 首站 / `WS-2N_FullR` 末站）加回库 route `WS-1C_ShortD` 首站
**全部**以它为端点。叠加"待命按 duty 建模"（车在端点等下一班的整段时间都占股道），WS 在 120s 目标间隔下
有 2160 处冲突，站台占 840，放宽到 4 倍上限 480s 仍不可行。这是拓扑问题，不是参数问题。

每一阶段给「做什么 / 期望 / **判别量** / 失败看哪」。判别量是能证明这层真生效的那个数——
只看"没报错"会漏掉"功能根本没跑起来"这一类失败，这在这个项目上已经栽过。

---

## L0 代码层（已完成，2026-09-19）

```
219 suites / 2002 tests / 0 failures / 0 errors / 0 skipped
SpotBugs main 0 / test 0
gitCommit=2f24f48  sourceFingerprint=562167555aac
```

重跑：`./gradlew clean check shadowJar`，然后解 `build/test-results/test/*.xml` 看 failures，
**不要只看 `BUILD SUCCESSFUL`**。

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

起服。

### 期望

- 日志里 `SMART_RUNTIME_BUILD_FINGERPRINT ... gitCommit=2f24f48`
- `config.yml` 的 `config-version` 变成 `33`，多出 `reclaim:` 段（`enabled: false`）
- `timetable:` 段全部默认关（`enabled: false` / `spawn-enabled: false`）
- 库里多出 4 张表

### 判别量

```bash
sqlite3 $D/plugins/FetaruteTCAddon/data/fetarute.sqlite \
  "select name from sqlite_master where type='table' and name like 'fta_timetable%';"
# 期望 4 行：fta_timetables / fta_timetable_trips / fta_timetable_duties / fta_timetable_baselines
```

**`fta_timetable_baselines` 必须在**。它缺席 = 跑的是旧 jar，后面 L5/L6 全部无效。
不要靠"某个新命令存在与否"判断版本——用 fingerprint。

### 失败看哪

`grep -i "timetable\|schema\|SQLITE_ERROR" $D/logs/latest.log | head -40`

---

## L2 单线编表 + 确定性（不开任何开关）

`timetable.enabled: false` 不影响 build，它是纯计算 + 落库。

### 做什么

```
/fta graph build                     ← 先确保有图快照，否则 L5 的重检会被跳过
/fta timetable build FTAS SURC WS WS-D1 --start 06:00 --end 23:00
/fta timetable info FTAS SURC WS WS-D1
/fta timetable duties FTAS SURC WS WS-D1
/fta timetable export FTAS SURC WS WS-D1 200
/fta timetable neighbors FTAS SURC WS WS-D1
```

然后**原样再 build 一次**（同 code、同参数），再 export 一次。

### 期望

报告里逐项过一遍：班次数、计划窗口、**间隔来源**（哪来的 baseline）、冲突检查结果、
**共用资源**、交路数、全天出库次数与峰值同时在线车数、目标 vs 实际服务比例、
"所有交路都以回库收尾"、被取消的班次。

### 判别量

1. **确定性**：两次 export 必须**逐字节相同**，包括 trip/duty 主键。
   把两次输出存文件 `diff` 一下。不同 = 不变量 2 破了，后面全部不用测了。
2. **回库行**：必须是"所有交路都以回库收尾"。不是 = `VehicleDutyPlanner` 有问题。
3. **被取消的班次**：如果大面积 `NO_RETURN_ACCESS` / `NO_CREATE_ACCESS`，说明 WS 缺出库或回库途径，
   先修线路定义再往下走。
4. **共用资源**：已实测为 DS 243 / MT 229，都标"无已发布时刻表——它按 headway 发车，干扰单向"。
   这是"只报告不检查"的正常形态。报 0 才是异常。
5. **目标间隔**：WS 的线路级 baseline 是 120s（`fta_lines.spawn_freq_baseline_sec`），
   而搜索上限是**目标的 4 倍**。不手动传 `--headway` 的话搜索范围只有 120–480，
   对 WS 这条线肯定不够。先用 `--headway 1800` 把窗口撑开，再谈可行性。

### 失败看哪

`build` 在异步线程算、主线程报。报告没出来但也没报错，看控制台异常栈。

---

## L3 线内冲突与 headway 回退（MT，六条 route 互相压）

### 做什么

```
/fta timetable build FTAS SURC MT MT-D1 --start 06:00 --end 23:00
```

再用一个**明知太密**的间隔跑一次，和一个 `--strict`：

```
/fta timetable build FTAS SURC MT MT-D2 --headway 60
/fta timetable build FTAS SURC MT MT-D3 --headway 60 --strict
```

### 期望

- `MT-D1`：间隔来源那行说明默认值从哪来（线路 `spawnFreqBaselineSec` → 交路组 baseline → 300）
- `MT-D2`：目标 60 秒有冲突 → 回退到最小可行间隔，报告标出"目标间隔下的冲突明细"
- `MT-D3`：**构建失败**，不落库

### 判别量

- `MT-D2` 的"实际使用的间隔" > 60，且列出了目标间隔下的冲突数。
  如果 60 秒就直接通过无冲突，**怀疑冲突检查没跑**——MT 六条 route 共用股道，60 秒不可能干净。
- `MT-D3` 必须失败。成功了说明 `--strict` 没接上。
- `/fta timetable list FTAS SURC MT` 里不应有 `MT-D3`。

---

## L4 跨线：共用资源报告（DS 未发布时）

这一层验的是 S1：**没有表的邻居只报告、不检查**。

### 做什么

保证 DS **没有已发布**时刻表，然后（`--headway` 要给足，否则搜索窗口撑不开，报告会停在"仍找不到无冲突的间隔"）：

```
/fta timetable list FTAS SURC DS          ← 确认没有 PUBLISHED
/fta timetable build FTAS SURC MT MT-N1
```

记下报告里两个数：**共用资源那几行**，和**实际使用的间隔**（记作 `H_before`）。

### 期望

报告出现类似：

```
  共用资源:
    ... DS（无时刻表） 共用 N 处区间/站台/单线/道岔
```

外部冲突那行**不出现**（DS 没发布，不进检查）。

### 判别量

共用资源数 **N > 0**。N = 0 说明足迹展开有问题——库里已经能查到 18 个共用申报点，
而足迹是按展开后的路径算的，只会更多不会更少。

---

## L5 跨线：路权先到先得（核心）

这一层验 S3：**已发布的邻表作为不可移动的事实进入检查**。

### 做什么

```
/fta timetable build FTAS SURC DS DS-P1
/fta timetable publish FTAS SURC DS DS-P1        ← DS 先占路权
/fta timetable build FTAS SURC MT MT-N2          ← 同 L4 的参数，唯一变量是 DS 发布了
```

### 期望

`MT-N2` 的报告里 DS-P1 作为**已发布邻表**出现，并且出现

```
  外部冲突（目标间隔下，与已发布邻表）: ...
```

### 判别量（这一层最重要的一个）

```
H_after（MT-N2 实际间隔）  vs  H_before（MT-N1 实际间隔）
```

**共用资源 N > 0 且 H_after == H_before 且外部冲突为 0 —— 要怀疑邻表根本没进检查。**
不是绝对证据（DS 只有 2 条 route，班次稀疏时真的可能不冲突），但必须去查一眼：

```bash
sqlite3 $D/plugins/FetaruteTCAddon/data/fetarute.sqlite \
  "select timetable_id, neighbor_code, shared_resources, conflicts_at_target, stale_against_graph
   from fta_timetable_baselines;"
```

`MT-N2` 必须有一行指向 DS-P1，`shared_resources > 0`。**这行是"邻表真的被读到了"的唯一硬证据**。

想更强地逼出冲突：给 MT 一个很密的 `--headway`，共用段上必然撞。

### 另一个方向的判别量

反过来再 build 一次 DS（此时 MT 未发布）：DS 的报告里 MT 应该是"无时刻表"那类，
**不进检查**——证明"同一组邻表"确实只算已发布的。

---

## L6 publish 重检拒绝（S3 的闸门 + 刚修的基线 bug 回归）

### 做什么

顺序很重要：

```
1. 确保 DS 无已发布表（unpublish DS-P1）
2. /fta timetable build FTAS SURC MT MT-G1          ← 此时基线里没有 DS
3. /fta timetable publish FTAS SURC DS DS-P1        ← 邻表集合变了
4. /fta timetable publish FTAS SURC MT MT-G1        ← 应当被拦
```

### 期望

第 4 步：控制台出现 `TIMETABLE_PUBLISH_REJECTED：邻表自 build 以来有变化，与已发布邻表有 N 处冲突`，
**发布被拒绝**，`MT-G1` 仍是 DRAFT。

若重检发现无冲突，则提示"邻表有变化但无冲突，已更新基线"并正常发布——也是合法结果，
但那时要回到 L5 的判别量确认它是真的算过。

### 判别量

- `/fta timetable list FTAS SURC MT` 里 `MT-G1` 状态未变成 PUBLISHED
- `fta_timetable_baselines` 里 `MT-G1` 的那几行**没有被清空**

### 基线不被 save 抹掉（2f24f48 修的 bug，必须回归）

```
5. /fta timetable publish  FTAS SURC WS WS-D1      ← 任意一次状态变更
6. /fta timetable unpublish FTAS SURC WS WS-D1
7. /fta timetable neighbors FTAS SURC WS WS-D1
```

**期望**：neighbors 里基线那行仍然是"与当前邻表一致"或"已过期"，**不能变成
"无（build 时没有邻表，或基线尚未落库）"**。变成"无"= 状态变更把基线冲掉了，这个 bug 回来了。

同样可直接查表：状态变更前后 `select count(*) from fta_timetable_baselines;` 不应变小。

### 预期中的坑（不是 bug，别误报）

- `--separation` **不落库**，publish 重检固定用默认 30 秒。你 build 时用了 `--separation 60`，
  重检结果和 build 报告不一致是正常的。
- 找不到图快照时：有基线→拒绝发布并提示先 `/fta graph build`；没有基线→跳过重检直接发。
- `unpublish` / `delete` **不级联**通知别人。

---

## 别踩：`--spawn-weight 0` 不等于停开某条 route

写 `<= 0` 会**删掉** `spawn_weight` 这个键（`FtaRouteCommand` 写入路径），而读取端键缺失时**默认回 1**
（`FtaTimetableCommand#readWeight`）。想用它把某条 route 从表里摘出去做隔离实验，实际效果是把权重悄悄改成 1。

---

## L7 直通运转 / 外方引用（现有网络只能测半程）

你现在只有一个 operator，**跨 operator 闭环测不了**。能测的是引用解析与校验：

```
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route FTAS/SURC/DS/DS-1F_Full
    → 期望：DS-1F_Full 不是 RETURN 类型 → build 时报"指定的回库线路类型不符"
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route FTAS/SURC/XX/NOPE
    → 期望：写入成功（只校验格式），build 时只警告不中断
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route 乱写
    → 期望：命令层拒绝，提示格式应为 <company>/<operator>/<line>/<route>
/fta route set FTAS SURC MT MT-2F_Short --timetable-return-route-clear
    → 清掉，别把脏数据留到后面的阶段
```

**判别量**：大小写按原样存。写 `FTAS/SURC/DS/ds-1f_full` 再读回来必须还是小写那个串
（仓储按 code 精确匹配，被改大小写就永远匹配不上）。

要测完整闭环，得先造第二个 operator + 一条终到它车站的 route + 它名下的 RETURN 线路。
那是一整套地面工程，建议单独排一轮，别混在这次。

---

## L8 按表运行（要改配置 + 重启）

前面全过了再做这一层。**只发布一条线的表**——MT 和 DS 同时按表跑属于 L5 没验完就上强度。

```yaml
timetable:
  enabled: true
  spawn-enabled: true
```

重启，确认那条线的表是 PUBLISHED。

### 期望的 trace 顺序（`debug.enabled: true`）

```
TIMETABLE_RELOAD                              表被读进来了
TIMETABLE_SPAWN_TICKET kind=CREATE duty=…      出库票，早于首班「走行+折返」
TIMETABLE_SPAWN_DISPATCHED                     票派给了哪辆车
TIMETABLE_DUTY_BOUND                           车绑到交路
TIMETABLE_ASSIGN                               车绑到具体车次
SCHEDULED_DEPARTURE_HOLD                       早到的车在等点
TIMETABLE_DUTY_CLOSED                          交路额度用完
TIMETABLE_SPAWN_TICKET kind=RETURN duty=…      回库票
```

### 判别量

- `TIMETABLE_ASSIGN` 的条数应当**随时间稳定增长**。增长停滞 = 车退回自由运行了 → 直接看 L9。
- `TIMETABLE_DUTY_BIND_CONFLICT` 应当**为 0**。非 0 = 有车绑错交路。
- 每个 `TIMETABLE_DUTY_CLOSED` 后面应当跟得到一条同 duty 的 `kind=RETURN`。
  跟不到就看是不是 `TIMETABLE_SPAWN_SKIP reason=no-spawn-service`。

### 不该出现

- `SCHEDULED_DEPARTURE_HOLD_SKIPPED`（早到幅度超上限）——多半是绑错车次
- `SCHEDULED_DEPARTURE_PLAN_FAILED`——计划源抛异常

---

## L9 绑不上车次的可归因性（新做的 D1，值得主动逼一次）

以前跨线干扰会让车静默退回自由运行，日志上只表现为 `TIMETABLE_ASSIGN` 变少。现在应该问得出原因。

### 做什么

把容差调到明显不够：

```yaml
timetable:
  assign-tolerance-seconds: 5
```

重启，跑一会儿。

### 期望

大量 `TIMETABLE_ASSIGN_MISS ... reason=out-of-tolerance`，带最近车次与偏差。
`/fta timetable status` 的"绑定失败累计"持续上涨。

### 判别量

`status` 里那个累计数 **> 0 且在涨**。恒为 0 = 计数器没接上，D1 白做了。

三个 reason 的含义：`out-of-tolerance`（偏差超容差，正偏差=晚到）、
`no-trips`（这条 route 在表里没有这个停靠点的车次）、`all-claimed`（同一趟被别的车绑走了）。

测完**记得把容差改回 300**。

---

## L10 滞留销毁兜底（破坏性，可选，放最后）

⚠️ 这一层**会销毁列车**。在你确认不心疼车的时候做。

```yaml
reclaim:
  enabled: true
  stranded-destroy-seconds: 120     ← 临时调小才观察得到，默认 1800
```

### 期望

派不出 RETURN 票、又滞留超时的待命车被销毁，日志 `RECLAIM_STRANDED_DESTROY`；
有乘客或有进行中折返事务的车被跳过，`RECLAIM_STRANDED_SKIP`。

### 判别量

`RECLAIM_STRANDED_SKIP` 必须真的出现过至少一次（找一辆车坐上去，别让它被销毁）。
只有 DESTROY 没有 SKIP，说明"有乘客不碰"这条保护没验到。

测完把 `stranded-destroy-seconds` 改回 1800，`reclaim.enabled` 按你原本的意愿设。

---

## 出事了怎么回到干净状态

```bash
# 停服后
D=../fetarute_experimental
cp "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite.bak-XXXX" "$D/plugins/FetaruteTCAddon/data/fetarute.sqlite"
cp "$D/plugins/FetaruteTCAddon/config.yml.bak-XXXX" "$D/plugins/FetaruteTCAddon/config.yml"
```

只想清时刻表、不想回滚整个库：

```sql
delete from fta_timetable_baselines;
delete from fta_timetable_duties;
delete from fta_timetable_trips;
delete from fta_timetables;
```

（有 `ON DELETE CASCADE`，删 `fta_timetables` 理论上够，但显式删四张更保险。）

---

## 建议的执行顺序

一次坐下来能做完 L1–L6，这六层不改配置、不重启、不碰实体车，是纯"算 + 落库"的验证，
也是这次改动的主体。L7 只做校验半程。L8–L10 各要一次重启，分开做。

L5 的 `H_before` vs `H_after` 和 `fta_timetable_baselines` 那条查询，是整套 routine 里
最值钱的两个判别量——跨线这一整轮的成败就压在它们上面。
