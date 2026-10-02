# HUD 模板系统（Template Service）

本模块用于统一管理 HUD 文本模板（MiniMessage + `{placeholder}`），并按线路绑定模板供 BossBar/ActionBar/Scoreboard 等展示层复用。

## 模板类型
- `BOSSBAR`：车上 BossBar 标题（单行，支持 MiniMessage）。
- `ACTIONBAR`：车上 ActionBar 文本（单行，支持 MiniMessage）。
- `ANNOUNCEMENT`：ActionBar 广播（预留）。
- `PLAYER_DISPLAY`：Scoreboard LCD（车内 PIDS）。
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

**升级提醒**：`default_hud_template.yml` 只在文件不存在时生成；语言文件只补缺失的键，而每个模板整块是一个键。
所以新版本给默认模板加的状态行（如 1.7.0 的 `OUT_OF_SERVICE`）**不会进入已部署服务器**，需要手动把新行加进
`plugins/FetaruteTCAddon/default_hud_template.yml`（或删掉该文件让插件重新生成），以及已有的线路绑定模板。

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

- 状态：`DEFAULT` / `IDLE` / `AT_LAST_STATION` / `AT_STATION` / `ON_LAYOVER` / `DEPARTING` / `ARRIVING` / `TERM_ARRIVING` / `IN_TRIP` / `OUT_OF_SERVICE`
- 可选后缀 `_n` 表示轮播顺序（按数字升序轮播）
- 若模板内没有任何状态行，则保持“整段文本作为单行标题”的兼容行为
- 若某状态未定义，会优先回退到 `DEFAULT`，再回退到“无前缀模板行”
- AT_STATION 表示“在站”：从到站（进度推进到本站）起，到拿到发车许可为止；停站计时（`dwellRemainingSec > 0`）也算。
  只看停站计时会有两个空档——计时要等停稳若干 tick 才开始、计时到期后还要关门过门控——HUD 会在空档里按“已推进的下一站”闪一下，
  或把站台上关门的车显示成临时停车。IDLE 为站外静止（临时停车）
- ON_LAYOVER 表示终到后折返/待命，优先级高于 AT_LAST_STATION/AT_STATION
- OUT_OF_SERVICE 表示回库车已越过运营终点（「回库 / Not in Service」，与 `dest_eop` 显示「回库」同一判定），优先于除 ON_LAYOVER 外的所有状态。
  **只有模板写了 `OUT_OF_SERVICE` 行才生效**：没写的模板（包括此前的全部模板）仍按停站、运行、临时停车等状态显示，不会回退到 `DEFAULT`。
  越过运营终点后 `next_station` 为 `-`（不再退回去显示已经过的终点站）。已部署服务器的默认模板需手动加上这一状态，见上文“升级提醒”
- AT_LAST_STATION 表示停在终点站（EOP），优先级高于 AT_STATION
- 兼容旧模板的 `STOP`/`LAYOVER`/`TERMINAL_ARRIVING` 前缀，解析时会视为 `AT_STATION`/`ON_LAYOVER`/`TERM_ARRIVING`

轮播间隔可通过模板行配置：
```text
rotate_ticks: 40
```

### 双语轮播（BossBar / ActionBar / Scoreboard 同步）

三块显示共用同一个时钟和同一个中英文切换周期 `runtime.hud.language-rotate-ticks`（默认 60），并且**先定语言、再轮播**：
同一语言有多行时，每轮到这种语言换下一行。因此某个状态的行数不同（如 `DEPARTING` 两行中文一行英文）、
或各模板的 `rotate_ticks` / `page_duration_ticks` 不同，都不会让横栏与侧边栏一块中文一块英文。
只有一种语言的状态仍按模板自己的周期轮播。

语言按行内容自动识别，不需要改模板：去掉 MiniMessage 标签与 `{占位符}` 后含汉字为第一语言，只有拉丁字母为第二语言；
都没有时看占位符名（`*_lang2`、`*_en_US` 为第二语言，`*_zh_CN` 为第一语言），其余视为通用行（两种语言下都可出现）。

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

线路与运营商字段取**列车当前所属的线路**：直通运转（停靠点 `CHANGE:<运营商>:<线路>`）换线后，`line*`、`operator`、`company*`
以及按线路绑定的模板都换成新线路；`route_*` 仍是交路本身。当前线路以列车的 `FTA_OPERATOR_CODE`/`FTA_LINE_CODE` 标签为准，
标签不全时为交路本身的线路（口径见 `RouteLineChanges`；代码不区分大小写，显示按主数据的写法）。

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
- `operator`：运营商 code（当前线路的运营商；未直通时即 RouteMetadata.operator）
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
终点口径统一由 `RouteTerminals` 定义，HUD、站牌、公开 API、列车命名共用同一套选站规则：

- `dest_eor`：End of Route（交路的最后一个节点，常为车库或折返线；缺失回退 `-`）
  - 车库显示为同代码车站名接「车库」：`SURC:D:LWN:1` → `林湾车库`，`_lang2` → `Lym Won Depot`，`_code` → `LWN`；
    没有同代码车站时用站码（`LWN车库` / `LWN Depot`）
  - 折返线、区间点显示它之前最近的车站：`SURC:OFL:MLU:2:004`（OFL 后折返）→ OFL，而不是车根本不去的 MLU
  - 例：`To {dest_eor}` → `To Central`
- `dest_eor_code`：End of Route code
  - 例：`{dest_eor_code}` → `CEN`
- `dest_eor_lang2`：End of Route 第二语言名（缺失为 `-`）
  - 例：`{dest_eor_lang2}` → `Central`
- `dest_eop`：End of Operation（退出营运前的最后一个车站：最后停靠的车站，回库途中只通过的车站与折返线上的 TERM 都不算；无则回退到 `dest_eor`）
  - RETURN（回库）线路：越过运营终点之前显示终点站名，之后显示 `回库`（`_code` 为 `OUT_OF_SERVICE`，`_lang2` 为 `Not in Service`）
  - 例：`To {dest_eop}` → `To HHU`
- `dest_eop_code`：End of Operation code
  - 例：`{dest_eop_code}` → `DEP`
- `dest_eop_lang2`：End of Operation 第二语言名（缺失为 `-`）
  - 例：`{dest_eop_lang2}` → `Depot`
- `route_pattern`：线路运行模式（i18n: enum.route-pattern-type.*，缺失为 `-`）
  - 例：`{route_pattern}` → `特快`
- `route_pattern_<locale>`：指定语言标签的运行模式（从 lang/<locale>.yml 读取）
  - 例：`{route_pattern_zh_CN}` → `特快`
  - 取数：取自交路缓存里的 `pattern_type`（按列车当前交路 ID），不读列车标签 `FTA_PATTERN`，交路重载后立即跟随，HUD 自己不缓存。
    `FTA_PATTERN` 是出车时写下的；折返复用换交路时运行时会同步改写它（车名的种别字母也取自同一处），但早先版本不改写，
    读标签会让靠复用接班的快速交路（如 MT-3）一直显示出车时那条交路的种别。
- `label_line`：语言文件里的“线路”标签
  - 例：`<dark_aqua>{label_line}</dark_aqua>` → `线路`
- `label_next`：语言文件里的“下一站”标签
  - 例：`<dark_aqua>{label_next}</dark_aqua>` → `下一站`

### 直通运转字段
前方下一次换线（不含已到达的换线站）；没有换线时文字为 `-`、`through_line_color` 为空、`through_line_color_tag` 为 `white`。
- `through_station` / `through_station_code` / `through_station_lang2`：换线站（列车在此以原线路到达、以新线路发车）
- `through_line` / `through_line_lang2` / `through_line_code` / `through_line_name` / `through_line_color` / `through_line_color_tag`：换线后的线路，含义同 `line*`
- `through_operator`：换线后线路的运营商 code
  - 例：`本列车自 {through_station} 起直通运行 <{through_line_color_tag}>{through_line}</{through_line_color_tag}>`

### ETA 字段
- `eta_status`：ETA 状态短文本（Arriving/3m/Delayed 5m 等）
  - 列车被扣停（信号、占用、授权等）满 1 分钟显示 `Delayed N m`，N 为已扣分钟数；被扣停时不显示 Arriving
  - 例：`ETA {eta_status}` → `ETA 3m`
- `eta_minutes`：ETA 分钟数（四舍五入；无 ETA 为 `-`）
  - 被扣停时按“已扣多久就估计还要多久”顺延（上限 5 分钟），扣停解除后回落
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
- `time_hhmm` / `time_HHmm`：当前时间（24h，HH:mm）
  - 例：`{time_hhmm}` → `20:10`
- `time_hhmmss` / `time_HHmmSS`：当前时间（24h，HH:mm:ss）
  - 例：`{time_hhmmss}` → `20:10:32`

### 综合示例
```text
<dark_aqua>{label_line}</dark_aqua> <white>{line}</white> <dark_gray>|</dark_gray>
<dark_aqua>{label_next}</dark_aqua> <white>{next_station}</white> <dark_gray>|</dark_gray>
<gold>{eta_status}</gold> <dark_gray>|</dark_gray> <aqua>{speed_kmh} {speed_unit}</aqua>
```

## 存储表
- `hud_templates`：模板主表（company + type + name 唯一）
- `hud_line_bindings`：线路绑定（line + type 唯一）

## Scoreboard（PLAYER_DISPLAY）模板规范
Scoreboard 使用 YAML 模板，支持“静态页 + list_page 生成器”。示例：

```yaml
lines: 10
page_duration_ticks: 60
pages:
  IN_TRIP_1:
    title: "<{line_color_tag}>▋</{line_color_tag}><white> {line}</white><gray> | </gray><white>{dest_eop}</white>"
    kind: list_page
    source: next_stops
    limit: 12
    header:
      - "<gray>Upcoming</gray>"
    row: "<white>{index}. {station}</white> <gray>{eta}</gray>"
    window:
      size: 3
      step: 3
      fixed: 3
      mode: chunk
      periodTicks: 120
      resetOnStop: true
    footer:
      - ""
      - "<gray>{time_hhmm}</gray>"
  ARRIVING_1:
    lines:
      - "<yellow>Arriving</yellow> <white>{next_station}</white>"
      - "<gray>Please prepare to exit.</gray>"
```

### 顶层字段说明
- `lines`：每页固定行数（最大 15）。
- `page_duration_ticks`：分页轮播间隔（ticks），用于单语状态与同一语言内的多页；中英文页按全局 `language-rotate-ticks` 与 BossBar/ActionBar 同步切换（见“双语轮播”）。
- `title`：Scoreboard 标题（MiniMessage，占位符同 BossBar/ActionBar；建议在 page 内单独配置）。
- `pages`：按 HUD 状态分组的页面。

### HUD State Key
Scoreboard 使用 HUD 状态名分组，`_n` 为页序号（排序轮播）：
- `DEFAULT_*`
- `IDLE_*`
- `AT_STATION_*`
- `AT_LAST_STATION_*`
- `ON_LAYOVER_*`
- `DEPARTING_*`
- `ARRIVING_*`
- `TERM_ARRIVING_*`
- `IN_TRIP_*`
- `OUT_OF_SERVICE_*`（只有写了才生效，见上文“状态模板”）

### 静态页
- `kind` 可省略或设为 `static`。
- `lines`：字符串列表（MiniMessage + `{placeholder}`）。
- `title`：可选，覆盖顶层标题（随页轮播）。

### list_page（页面生成器）
字段说明：
- `kind`: `list_page`
- `source`: 列表数据源（当前仅 `next_stops`）
- `limit`: 最大条目数（用于截断列表总长度）
- `row`: 每行模板（MiniMessage + item 占位符，可写为字符串或列表）
- `header` / `footer`: 可选，列表前后附加行
- `empty`: 可选，无数据时填充内容（默认 `-`）
- `window`: 窗口滚动配置

`window` 字段：
- `size`: 展示行数
- `step`: 每次滚动跨几站（chunk 通常等于 `size`，slide 通常为 `1`）
- `fixed`: 固定显示的前 N 条（不参与窗口滚动）
- `mode`: `chunk` 或 `slide`
- `periodTicks`: 滚动周期（ticks）
- `resetOnStop`: 到站推进或状态切换时重置窗口
- `windowOffset`: 运行时窗口偏移（从第几个 stop 开始展示），由系统按 `size/step` 自动计算

`row` 支持多行（列表形式）：会为每个 stop 输出多行，常用于“符号行 + 文本行”组合。

windowOffset 规则（用于“固定前三站 + 滚动后续”）：
- 窗口滚动只作用于“fixed 之后的列表”，固定行不参与滚动
- 当前展示区间：`stops[fixed + windowOffset .. fixed + windowOffset + size - 1]`
- `chunk` 模式：`windowOffset = pageIndex * step`（默认 step=size），超过最大 offset 回到 0
- `slide` 模式：`windowOffset = windowOffset + step`，超过最大 offset 回到 0

list-item 占位符（`next_stops`）：
- `index` / `idx`：序号（从 1 开始）
- `station` / `station_code` / `station_lang2`
- `eta` / `eta_minutes` / `eta_status`
- 行内的 `line*`（`line`、`line_lang2`、`line_code`、`line_name`、`line_color`、`line_color_tag`）取**该站所属的线路**：
  直通运转换线之后的各站显示新线路（与列车当前线路相同的站不覆盖），所以 `<{line_color_tag}>◘</{line_color_tag}>` 这类行首色块会在换线站起变色
