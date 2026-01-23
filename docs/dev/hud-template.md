# HUD 模板系统（Template Service）

本模块用于统一管理 HUD 文本模板（MiniMessage + `{placeholder}`），并按线路绑定模板供 BossBar/ActionBar/Scoreboard 等展示层复用。

## 模板类型
- `BOSSBAR`：车上 BossBar 标题（单行，支持 MiniMessage）。
- `ANNOUNCEMENT`：ActionBar 广播（预留）。
- `PLAYER_DISPLAY`：Scoreboard LCD（预留）。
- `STATION_DISPLAY`：站牌显示（预留）。

## 渲染顺序
1) `{placeholder}` 替换
2) MiniMessage 解析
3) Adventure Component 输出

## BossBar 模板默认值
语言文件中可提供默认模板：
- `display.hud.bossbar.template`

当线路未绑定模板时，BossBar 会回退到该默认值（或 config 中的 `runtime.hud.bossbar.template`）。

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

## BossBar 占位符
BossBar 支持以下占位符（模板中使用 `{xxx}`）：

### 基础字段（线路/站点）
- `line`：线路显示名（优先 Line.name，其次 line code）；缺失为 `-`
  - 例：`{line}` → `S2`
- `line_code`：线路 code（优先 Line.code，其次 RouteMetadata.lineId）
  - 例：`{line_code}` → `S2`
- `line_name`：线路名称（Line.name）
  - 例：`{line_name}` → `东湾快线`
- `line_color`：线路颜色（Line.color，形如 `#RRGGBB`；缺失为 `""`）
  - 例：`<# {line_color}>` → `<#2BC4FF>`（模板内自行包裹）
- `operator`：运营商 code（RouteMetadata.operator）
  - 例：`{operator}` → `SURN`
- `route_code`：班次/route code（RouteMetadata.serviceId）
  - 例：`{route_code}` → `EXP-01`
- `route_name`：班次/route 展示名（Route.name，来自 RouteMetadata.displayName）
  - 例：`{route_name}` → `机场直达`
- `route_id`：RouteId 原始值（如 `OP:LINE:ROUTE`）
  - 例：`{route_id}` → `SURN:NE:EXP-01`
- `next_station`：下一站（由 NodeId 推断站名）；缺失为 `-`
  - 例：`Next: {next_station}` → `Next: Central`
- `dest_eor`：End of Route（最后一个站点，PASS 也算；缺失回退 `-`）
  - 例：`To {dest_eor}` → `To Central`
- `dest_eop`：End of Operation（最后一个 STOP/TERM；无则回退到 `dest_eor`）
  - 例：`To {dest_eop}` → `To Depot`
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

### 信号/占用字段
- `signal_status`：中文信号提示（通行/注意/停车）
  - 例：`Signal {signal_status}` → `Signal 注意`
- `signal_aspect`：枚举值（PROCEED/PROCEED_WITH_CAUTION/CAUTION/STOP/UNKNOWN）
  - 例：`{signal_aspect}` → `PROCEED`

### 运行状态字段
- `service_status`：营运状态（营运中/待命）
  - 例：`{service_status}` → `待命`
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
