# HUD 模板系统（Template Service）

本模块用于统一管理 HUD 文本模板（MiniMessage + `{placeholder}`），并按线路绑定模板供 BossBar/ActionBar/Scoreboard 等展示层复用。

## 模板类型
- `BOSSBAR`：车上 BossBar 标题（单行，支持 MiniMessage）。
- `ACTIONBAR`：车上 ActionBar 文本（单行，支持 MiniMessage）。
- `ANNOUNCEMENT`：ActionBar 广播（预留）。
- `PLAYER_DISPLAY`：Scoreboard LCD（预留）。
- `STATION_DISPLAY`：站牌显示（预留）。

## 渲染顺序
1) `{placeholder}` 替换
2) MiniMessage 解析
3) Adventure Component 输出

## 默认模板回退顺序
默认模板优先从 `plugins/FetaruteTCAddon/default_hud_template.yml` 读取：
- `bossbar.template`
- `actionbar.template`

当线路未绑定模板且 config 未配置时，BossBar/ActionBar 会回退到对应默认模板；最后才会回退到语言文件中的 `display.hud.bossbar.template` / `display.hud.actionbar.template`。

## 状态模板（可选）
BossBar/ActionBar 支持按状态选择模板行，格式为：

```text
IDLE_1: <template line>
DEPARTING: <template line>
ARRIVING_1: <template line>
ARRIVING_2: <template line>
IN_TRIP: <template line>
TERM_ARRIVING: <template line>
AT_LAST_STATION: <template line>
```

- 状态：`IDLE` / `AT_LAST_STATION` / `AT_STATION` / `ON_LAYOVER` / `DEPARTING` / `ARRIVING` / `TERM_ARRIVING` / `IN_TRIP`
- 可选后缀 `_n` 表示轮播顺序（按数字升序轮播）
- 若模板内没有任何状态行，则保持“整段文本作为单行标题”的兼容行为
- 若需要未匹配状态的默认内容，可额外写一行“无前缀模板行”作为 fallback
- AT_STATION 以运行时 `dwellRemainingSec > 0` 判定，IDLE 为非停站静止（临时停车）
- ON_LAYOVER 表示终到后折返/待命，优先级高于 AT_LAST_STATION/AT_STATION
- AT_LAST_STATION 表示停在终点站（EOP），优先级高于 AT_STATION
- 兼容旧模板的 `STOP`/`LAYOVER`/`TERMINAL_ARRIVING` 前缀，解析时会视为 `AT_STATION`/`ON_LAYOVER`/`TERM_ARRIVING`

轮播间隔可通过模板行配置：
```text
rotate_ticks: 40
```

## BossBar 进度表达式（可选）
BossBar 进度条支持由模板表达式驱动（ActionBar 不使用进度）：

```text
progress: {progress}
progress: {eta_minutes} / 10
```

- 表达式会先做 `{placeholder}` 替换，再进行基础运算（`+ - * /` 与括号）
- 结果会被 clamp 到 0.0 ~ 1.0，解析失败则回退到默认进度算法

## 线路绑定
绑定记录存储在 `hud_line_bindings`：
- `line_id` + `template_type` → `template_id`

绑定后 BossBar 会按线路自动选择模板。

## 命令（MVP）
模板管理：
- `/fta template create <type> <company> <name>`
- `/fta template edit <type> <company> <name>`
- `/fta template define`
- `/fta template info <type> <company> <name>`
- `/fta template list <company> [type]`
- `/fta template delete <type> <company> <name> --confirm`

线路绑定：
- `/fta line hud set <company> <operator> <line> <type> <template>`
- `/fta line hud clear <company> <operator> <line> <type>`
- `/fta line hud show <company> <operator> <line>`

## 占位符
BossBar/ActionBar 支持以下占位符（模板中使用 `{xxx}`）：

### 基础字段（线路/站点）
- `line`：线路显示名（优先 Line.name，其次 line code）；缺失为 `-`
  - 例：`{line}` → `S2`
- `line_lang2`：线路第二语言名（Line.secondaryName，缺失回退到 `line`）
  - 例：`{line_lang2}` → `东湾 Line`
- `line_code`：线路 code（优先 Line.code，其次 RouteMetadata.lineId）
  - 例：`{line_code}` → `S2`
- `line_name`：线路名称（Line.name）
  - 例：`{line_name}` → `东湾快线`
- `line_color`：线路颜色（Line.color，形如 `#RRGGBB`；缺失为 `""`）
  - 例：`<# {line_color}>` → `<#2BC4FF>`（模板内自行包裹）
- `line_color_tag`：线路颜色标签（`#RRGGBB` 或默认 `dark_aqua`）
  - 例：`<{line_color_tag}>{line}` → `<#2BC4FF>LINE`
- `operator`：运营商 code（RouteMetadata.operator）
  - 例：`{operator}` → `SURN`
- `company`：公司显示名（优先 Company.name，其次 company code）
  - 例：`{company}` → `FetaRail`
- `company_code`：公司 code
  - 例：`{company_code}` → `FETA`
- `company_name`：公司名称
  - 例：`{company_name}` → `FetaRail Co.`
- `route_code`：班次/route code（RouteMetadata.serviceId）
  - 例：`{route_code}` → `EXP-01`
- `route_name`：班次/route 展示名（Route.name，来自 RouteMetadata.displayName）
  - 例：`{route_name}` → `机场直达`
- `route_id`：RouteId 原始值（如 `OP:LINE:ROUTE`）
  - 例：`{route_id}` → `SURN:NE:EXP-01`
- `next_station`：下一停靠站（RouteStop 中非 PASS 的下一站）；缺失为 `-`
  - 例：`Next: {next_station}` → `Next: Central`
- `next_station_code`：下一站 code（用于精简显示）
  - 例：`{next_station_code}` → `CEN`
- `next_station_lang2`：下一站第二语言名（缺失为 `-`）
  - 例：`{next_station_lang2}` → `Central`
- `current_station`：当前站（由运行时快照推断站名）；缺失为 `-`
  - 例：`本站 {current_station}` → `本站 Central`
- `current_station_code`：当前站 code
  - 例：`{current_station_code}` → `CEN`
- `current_station_lang2`：当前站第二语言名（缺失为 `-`）
  - 例：`{current_station_lang2}` → `Central`
- `dest_eor`：End of Route（最后一个站点，PASS 也算；缺失回退 `-`）
  - 例：`To {dest_eor}` → `To Central`
- `dest_eor_code`：End of Route code
  - 例：`{dest_eor_code}` → `CEN`
- `dest_eor_lang2`：End of Route 第二语言名（缺失为 `-`）
  - 例：`{dest_eor_lang2}` → `Central`
- `dest_eop`：End of Operation（最后一个 STOP/TERM；无则回退到 `dest_eor`）
  - 例：`To {dest_eop}` → `To Depot`
- `dest_eop_code`：End of Operation code
  - 例：`{dest_eop_code}` → `DEP`
- `dest_eop_lang2`：End of Operation 第二语言名（缺失为 `-`）
  - 例：`{dest_eop_lang2}` → `Depot`
- `label_line`：语言文件里的“线路”标签
  - 例：`<dark_aqua>{label_line}</dark_aqua>` → `线路`
- `label_next`：语言文件里的“下一站”标签
  - 例：`<dark_aqua>{label_next}</dark_aqua>` → `下一站`

### ETA 字段
- `eta_status`：ETA 状态短文本（Arriving/3m/Delayed 5m 等）
  - 例：`ETA {eta_status}` → `ETA 3m`
- `eta_minutes`：ETA 分钟数（四舍五入；无 ETA 为 `-`）
  - 例：`{eta_minutes}m` → `3m`

### 速度字段
- `speed`：带单位的速度（km/h）
  - 例：`{speed}` → `42.3 km/h`
- `speed_kmh`：数值（km/h，不含单位）
  - 例：`{speed_kmh}` → `42.3`
- `speed_bps`：数值（blocks per second，不含单位）
  - 例：`{speed_bps}` → `11.76`
- `speed_unit`：速度单位文本（来自语言文件）
  - 例：`{speed_unit}` → `km/h`

### 乘客侧字段
- `player_carriage_no`：玩家所在车厢序号（从 1 开始，无法解析为 `-`）
  - 例：`{player_carriage_no}` → `2`
- `player_carriage_total`：列车编组总车厢数（无法解析为 `-`）
  - 例：`{player_carriage_total}` → `8`

### 信号/占用字段
- `signal_status`：中文信号提示（通行/注意/停车）
  - 例：`Signal {signal_status}` → `Signal 注意`
- `signal_aspect`：枚举值（PROCEED/PROCEED_WITH_CAUTION/CAUTION/STOP/UNKNOWN）
  - 例：`{signal_aspect}` → `PROCEED`

### 运行状态字段
- `service_status`：营运状态（营运中/待命）
  - 例：`{service_status}` → `待命`
- `progress`：进度值（0.0 ~ 1.0）
  - 例：`{progress}` → `0.65`
- `progress_percent`：进度百分比（0-100）
  - 例：`{progress_percent}%` → `65%`
- `train_name`：列车名
  - 例：`Train {train_name}` → `Train S2-01`
- `layover_wait`：待命持续时间（分钟，例 `3m`；非待命为 `-`）
  - 例：`Layover {layover_wait}` → `Layover 5m`

### 综合示例
```text
<dark_aqua>{label_line}</dark_aqua> <white>{line}</white> <dark_gray>|</dark_gray>
<dark_aqua>{label_next}</dark_aqua> <white>{next_station}</white> <dark_gray>|</dark_gray>
<gold>{eta_status}</gold> <dark_gray>|</dark_gray> <aqua>{speed_kmh} {speed_unit}</aqua>
```

## 存储表
- `hud_templates`：模板主表（company + type + name 唯一）
- `hud_line_bindings`：线路绑定（line + type 唯一）
