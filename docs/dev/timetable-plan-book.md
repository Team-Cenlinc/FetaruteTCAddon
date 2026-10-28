# 运行计划书：分时段频率与定点发车

运行计划书用书与笔写出一份时刻表的**服务模式**：某个交路组在哪个时段多久一班（分时段频率），以及哪些班次必须在指定时刻发车（定点）。
计划书挂在运营商下、按 code 引用，**以时刻表编号为单位绑定**，不挂在 route 上——同一批 route 可以在工作日表、周末表各用一本计划书。

本文只说输入、存储与命令。编表算法怎样使用计划书写在 `docs/dev/timetable.md`。

## 书本写法

```
# 工作日（# 之后是注释）
[时段]
WS/WS_Full 06:00-07:00 300
WS_Full    07:00-09:30 180
[定点]
05:32     WS-2C_FullR
07:15:30  MT-3N_DPExp 加开
```

- **段头**：`[时段]`（或 `[bands]`）、`[定点]`（或 `[pins]`），不分大小写，也认全角括号 `【时段】`。同一段可以分几次写。
  段头之前的内容、不认识的段头都报错（不认识的段头只报一次，段内各行不再逐行报错）。
- **注释与空行**：一行中 `#` 之后的内容忽略；空行忽略。
- **时刻**：`HH:mm` 或 `HH:mm:ss`，小时 0–47，分秒 0–59；全角冒号视同半角。跨零点写成 `24:30`（= 88200 秒）。

### 时段行

`组键 起-止 间隔秒`

- **组键**：`组` 或 `线/组`，与 `--group-headway` 同语义。只写组名时，凡有这个组名的线都适用；写 `线/组` 时只作用于那条线。
  组名就是 route metadata 的 `spawn_group`，没配组的 route 属于 `default`。
- **起-止**：左闭右开 `[起, 止)`，起须早于止。中间可以有空格（`06:00 - 07:00`），也接受 `~`。
- **间隔**：正整数秒。
- **同一组键的时段不能重叠**，报错时指出另一行的页码与行号。首尾相接（`06:00-07:00` 与 `07:00-08:00`）不算重叠。
  `WS_Full` 与 `WS/WS_Full` 是两个组键，可以重叠：后者只对 WS 线生效，比前者更具体（编表时如何取舍见 `timetable.md`）。

### 定点行

`时刻 route [加开]`

- `route` 是 route 的 code。
- 末尾写 `加开`（也认 `extra`、`+`）表示在周期之外另加一班；不写表示替换同方向最近的周期格位。
- 同一时刻同一 route 写两次报错；同一时刻不同 route 可以。

### 解析结果与报错

解析得到 `ServicePattern(bands, pins)`，两张表都按书里的顺序。每处问题带页码、页内行号（含注释与空行）与中文原因。
**有任何问题时整本不落库**，`define` 逐条列出问题；不存在「保存能解析的那一半」。一本既没有时段也没有定点的书也算问题。

`define` 还会检查计划书点名的线路、交路组与 route 在运营商下是否存在，找不到时给黄色提示但照常保存（可以先写计划书、后建线路）。

## 绑定规则

- 绑定键是 **(运营商, 时刻表编号)**，每个编号至多绑定一本计划书；计划书必须属于同一运营商。
- 编号区分大小写，与 `/fta timetable build` 的 `<code>` 一致。几条线联编时各线的表同一编号，因此一次绑定覆盖整组联编。
- 编号不必已经存在：可以先绑定、后编表。
- 再次 `bind` 同一编号即改绑。
- 绑定只在**编表时**读取：改了计划书或改了绑定，已编成的表不变，须重新 `build`。
- 删除计划书前须先解除它的全部绑定（与编组方案的删除规则一致）；运营商删除时计划书与绑定一并删除。

## 与编表参数的优先级

间隔仍按交路组解析。计划书只在它写到的时段内生效：

1. `--group-headway` **显式点名的组**全天使用命令给出的间隔，覆盖计划书里这个组的全部时段。点名的写法与解析间隔同一规则：
   `线/组` 里线路编号不分大小写，组名按原样比。
2. 其余组：落在计划书时段内的时刻用计划书的间隔。
3. 时段之外按原有优先级：`--headway` > 交路组 `spawn_groups[].baselineSec` > 线路 `spawnFreqBaselineSec` > 300 秒。

时段盖住整个编表窗口时只有一个制式，照样用计划书的间隔。计划书写了时段，非严格模式也不放宽间隔：排不开时报出让不掉的冲突，
由调整时段间隔或 `--strict` 取消来解决。只写了定点、没写时段的计划书不定间隔，非严格模式照常放宽。

## 定点语义

- 定点是**硬约束**：编表时别的车让它，不让它去让别的车；严格模式取消让不掉的班次时也不取消定点班次。
- 不写 `加开`：替换同方向（与该 route 同一起终点站台组的方向）里时刻最近的周期格位，该格位不再另发。
- 写 `加开`：周期格位照旧，另外加一班。
- 替换时前后各一班向定点平滑一半偏差（偏 Δ 秒时它们各挪 Δ/2），乘客看到的班距不匀减半；会越过定点或越过另一侧那一班时不挪。
- 挪动顺着车辆接续传下去：被挪的班次（定点与平滑的两班）的车到终点后本来要接的那一班，若终点换不了车（没有出入库）、
  车提前或推迟之后接不上（早于就绪、或要等过闲置上限），就把那一班挪到刚好接得上为止；本来接得上的不挪。传到终点能出入库为止，
  至多 4 程（`PinnedSlots#MAX_HOPS`）。不这样做时，定点把车提前送到对面的单端折返站，原来要接的那班要等过闲置上限，
  派车会一边退掉到站的班次、一边退掉没车发的班次（定点两侧各丢两班）。
- 分时段时两种定多停的方案各完整排一次，按"预计丢掉的班次"（已取消的加上让不掉的残余里要让的那一方）取少的，
  其次不可吸收残余少、让车总秒数少（`TimetableBuilder#REGIME_ORDER`）。

## 存储

| 表 | 列 | 约束 |
| --- | --- | --- |
| `<prefix>timetable_plans` | `id`, `operator_id`, `code`, `name`, `body`, `created_at`, `updated_at` | `UNIQUE (operator_id, code)`；运营商删除级联 |
| `<prefix>timetable_plan_bindings` | `operator_id`, `timetable_code`, `plan_id` | 主键 `(operator_id, timetable_code)`；计划书删除级联、运营商删除级联 |

`body` 存书的原文，页与页之间用换页符 `\f`（U+000C）分隔，`edit` 时按原样还原成书页，报错能指到原来的页码。
`name` 是显示名，未用 `--name` 起名时与 `code` 相同。

代码入口：

- 解析：`dispatcher.schedule.timetable.plan.TimetablePlanBook`（`parsePages`/`parse`，时刻读写 `parseClock`/`formatClock`）。
- 仓库：`TimetablePlanRepository`，经 `StorageProvider#timetablePlans()` 取得；JDBC 实现 `JdbcTimetablePlanRepository`。
- 编表只读入口：`TimetablePlans.resolve(provider, operatorId, timetableCode)` → `Optional<ResolvedPlan>`。编号没有绑定时为空；
  库里正文解析失败时 `issues` 非空、`pattern` 为 `ServicePattern.NONE`，调用方应当报错而不是按没有计划书编表。

## 命令

权限：查看用 `fetarute.timetable`，其余用 `fetarute.timetable.manage`；另按公司成员身份检查可读/可管理（与 `/fta consist` 相同）。

| 命令 | 说明 |
| --- | --- |
| `/fta timetable plan` | 帮助 |
| `/fta timetable plan create <company> <operator> <plan> [--name "<name>"]` | 领取空白计划书（带示例注释）；`plan` 只能用字母、数字与 `_ - . +`，至多 64 字符 |
| `/fta timetable plan edit <company> <operator> <plan> [--name "<name>"]` | 领取载入现有计划的书；`--name` 改显示名 |
| `/fta timetable plan define` | 手持计划书校验并保存；书里用 PDC 记着公司、运营商与 code，不用再写参数 |
| `/fta timetable plan info <company> <operator> <plan>` | 时段表、定点班次、绑定的编号，以及 [编辑] [绑定] [删除] |
| `/fta timetable plan list <company> <operator>` | 列出计划书，点名字看详情 |
| `/fta timetable plan delete <company> <operator> <plan> --confirm` | 删除；仍有绑定时拒绝 |
| `/fta timetable plan bind <company> <operator> <code> <plan>` | 绑定或改绑；编号补全给各线已有编号与已绑定编号 |
| `/fta timetable plan unbind <company> <operator> <code>` | 解除绑定；编号补全只给已绑定编号 |
