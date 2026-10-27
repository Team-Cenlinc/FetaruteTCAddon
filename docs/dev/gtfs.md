# GTFS 静态包与 GTFS-Realtime

把插件里的线路、车站、股道与时刻表发布成标准 GTFS 静态包，并实时发布车次晚点、车辆位置与服务告警。代码在 `web/gtfs`（静态包）与 `web/gtfs/realtime`（实时源），共用 `web` 的 HTTP 服务、缓存与 ETag。设计调研见 `docs/design/gtfs-feeds.md`（不入库）。

## 入口与配置

| 路径 | Content-Type | 缓存 |
|---|---|---|
| `GET /v1/gtfs.zip` | `application/zip`（`Content-Disposition: attachment; filename="gtfs-{serverId}.zip"`） | 300 秒 |
| `GET /v1/gtfs-rt/trip-updates` | `application/x-protobuf` | 10 秒 |
| `GET /v1/gtfs-rt/vehicle-positions` | 同上 | 5 秒 |
| `GET /v1/gtfs-rt/alerts` | 同上 | 30 秒 |
| 三个 RT 端点加 `?format=json` | `application/json; charset=utf-8` | 同上 |

全部随 `web-api.enabled` 开关；地址、端口、令牌、JSON 接口的说明见 [`docs/dev/web-api.md`](web-api.md)。`/v1/meta` 另报 `gtfs.feedVersion` 与 `gtfs.generatedAt`，中转据此核对静态包与实时源是不是同一版；它与静态包走同一个缓存，到期先比输入指纹（约 1 ms），只有内容真变了才重建，所以重新发布时刻表后 meta 会报出新版本。

```yaml
web-api:
  gtfs:
    publisher-name: "Fetarute FTCA Universal"
    publisher-url: "https://www.fetarute.org"
```

- 坐标原点固定在西经 30°、赤道（`web/gtfs/GtfsGeo`），不设配置。
- 车站目录还没加载时 `/v1/gtfs.zip` 与 RT 回 `503`，不回报一个空包；时刻表服务还没起来时车站与线路照出，只是暂时没有车次（200）。
- JSON 接口的 `ETag` 与 304 复验照旧；GTFS 的 `ETag` 见下文「版本与缓存」。

## 线程与数据来源

生成全部在 web 线程（`FetaruteTCAddon-web-N`）完成，只读内存快照，不读库、不碰 Bukkit 世界/实体：

| 数据 | 来源 |
|---|---|
| 运营商、线路、车站、车站组 | `StationDirectory#snapshot()` |
| 已发布时刻表、计划股道、越站 | `TimetableService`（`publishedTimetables`、`plannedPlatform`、`skippedStops`） |
| 交路的线路归属与停靠表 | `RouteDefinitionCache#entries()` |
| 股道坐标 | `RailGraphService#snapshotAll()` 里 `STATION` 类型的节点 |
| 车次绑定、取消 | 公开 API 的 `TimetableApi#listAssignments` / `#cancellations` |
| 列车位置、速度、载客、停站剩余时间 | `TrainSnapshotStore`（`EtaRuntimeSampler` 在主线程采样） |
| 公告、线路运行状况 | `PidsService#bulletins()` / `#lineStatus` |
| 语言文件（告警文案） | `LocaleManager#text`，复用站台屏的 `pids.board.line-status.*` |

一次生成只取一次快照（`GtfsSnapshot` / `GtfsRealtimeSnapshot`），不会出现半新半旧的组合。静态包缓存 300 秒；期间所有请求共用同一份 zip。

## 静态包

### ID 规则

`{S}` 是 `config.yml` 的 `server-id`，没配时连同冒号一起省掉。几台服务器的 feed 被中转合并后仍不会撞号；**发布之后再补 `server-id` 会让所有消费者手里的 ID 全部换掉**，开启 GTFS 之前就要定死。

| 对象 | GTFS ID | 例 |
|---|---|---|
| 运营商 | `{S}:{OP}` | `survival:SURC` |
| 线路 | `{S}:{OP}:{LINE}` | `survival:SURC:WS` |
| 车站（`location_type=1`） | `{S}:{OP}:S:{CODE}` | `survival:SURC:S:HHU` |
| 股道（`location_type=0`） | `{S}:` + 节点 ID | `survival:SURC:S:HHU:3` |
| 站台未定 | `{S}:{OP}:S:{CODE}:DYN` | 见 `stops.txt` |
| 车次 | `{S}:{OP}:{时刻表所属线路}:{时刻表代码}:{车次号}`，直通拆段后一段起加 `~2`、`~3` | `survival:SURC:MT:SURC_Temporary:MT-3N_DPExp-012~2` |
| 服务 | `{S}:DAILY` | |

车次 ID 必须带时刻表所属的线路与时刻表代码：车次号只保证在一张表内唯一，时刻表代码只在线路内唯一；实服 WS 表里还跑着 IS 线的交路。不用 UUID：重新编表会换 UUID，代码基本不变，可读性也好。

### 坐标

一格一米，`+x` 向东、`+z` 向南；原点固定在西经 30°、赤道上的大西洋洋面：

```
lat =  0° - z / 6378137 × 180/π
lon = -30° + x / 6378137 × 180/π
```

CSV 保留 7 位小数（约 1 cm）；GTFS-RT 的经纬度是 float32。多个世界的方块坐标会重叠，这是已知取舍：消费者只需要相对位置。`y`（高度）GTFS 没有字段，不出。

原点**不是** `(0, 0)`：MobilityData 的 gtfs-validator 8.x 对 `|lat| ≤ 1` 且 `|lon| ≤ 1` 的停靠点报 `point_near_origin`（ERROR），原点在 (0, 0) 会让整包校验失败。坐标只表达相对位置，挪到一片没有真实公交数据的洋面不影响消费者；偏移量写死，不开放配置。

### 发布范围

- **线路**：ACTIVE 与 MAINTENANCE 进 `routes.txt`，PLANNING 不进。
- **车次**：只取生效中的已发布时刻表；`timetable.enabled=false` 时表只能查、不驱动行车，一个车次都不出。
- **交路阶段**：出库、回库交路只有在途中办客时才发布。判据是交路计划里至少有两个停车的车站（STOP/TERMINATE 且有站码）；纯走行、只停一站的车次不进包。
- **检修线路**：进 `routes.txt`（乘客仍能在图上看到这条线），但不出车次；全线停运由告警表达。
- **车站**：被车次停靠的车站，以及有非规划线路经过的车站，都发布（设计稿 Q4：没有时刻表的线路「线路和车站照常出」）；`…:DYN` 兜底停靠点只在车站真的有车次停靠时发布。
- **时区**：只发布时区与服务器时区相同的时刻表；GTFS 的时刻一律按 `agency_timezone` 解释，跨时区换算要处理夏令时，宁可不发布也不出错。
- **停靠点**：车站与股道两级；站咽喉、区间点、车库、道岔都不出。被停靠的车站发布**它的全部股道**，包括还没有车停的——动态站台的车进站前才选台，RT 的 `assigned_stop_id` 必须是静态包里有的停靠点。每条被停靠车站另有一行 `…:DYN` 兜底。
- **计划股道**：编表时排了计划股道就用它；没有排上时，动态站台用 `…:DYN`，固定站台直接用节点 ID。动态站台在时刻表里的节点是占位股道 `OP:S:CODE:fromTrack`，与真实的起始股道同名、图里也找得到，所以先看计划与交路上的 DYNAMIC 标记，不按节点名当成固定站台。
- **数据卫生**：车站缺坐标、站码查不到车站时，这个停靠点不出、整包继续；每类问题在日志里各记一次（每个 feed 版本只记一次）。

### 直通运转（`CHANGE`）

在换线站拆成两个 GTFS 车次：前一段挂原线路、到换线站为止；后一段挂新线路、从换线站起，ID 加 `~2`；两段用 `transfers.txt` 的 `transfer_type=4` 连起来，站点是换线站在静态包里的停靠点（计划股道或 `…:DYN`；规范要求 `location_type=0`）。两段的 `trip_headsign` 都写全程运营终点。

拆分后会不足两个停靠点的段不拆：相邻两站连续换线时中间只剩一站，去掉靠后的边界；末段只有一站时并回前一段——终点换线对乘客没有意义，一站的「车次」也没有行程可言。

### 上下客

整趟的第一个停靠点 `drop_off_type=1`（只上客），最后一个停靠点 `pickup_type=1`（只下客）；拆段边界两侧都是正常上下客（车上的乘客在换线站照常下车）。`timepoint=1`。

### 时刻与日历

- `stop_sequence` 直接用车次在交路上的节点下标（`TimetableStop#stopSequence`）：GTFS 只要求递增、不要求连续，这样 RT 的 `stop_sequence` 与插件所有 API 是同一个数。
- `arrival_time` / `departure_time` = 起点发车当日秒数 + 站间偏移，小时允许超过 24（跨零点写 `24:xx:xx`）。口径是**起点发车所在的日历日**，与公开 API 的 `serviceDate` 一致；RT 的 `start_date` 直接用它，不做换算。
- `agency_timezone` 取服务器时区；GTFS 用「服务日正午减 12 小时」作零点，插件用 `atStartOfDay`，两者只在夏令时切换日不同（`Asia/Shanghai` 没有夏令时）。
- `calendar.txt` 只有一行 `DAILY`，七天都是 1，`start_date` 取生成日前 1 天、`end_date` 取后 30 天。静态包每 300 秒检查一次：到期先取一份新输入快照算内容指纹（`GtfsSnapshotHash`），指纹没变只续期、不重新生成；变了才重建，日期窗口随之往后滚。指纹没变也最多续期 30 分钟，之后强制重建一次，兜住将来漏加进指纹的输入。真正让旧包作废的是内容变化，由 `feed_version` 标出。
- 没有 `calendar_dates.txt`、`frequencies.txt`、`shapes.txt`（P3 再做）。

### 换乘

- 车站组成员两两互转（双向）：有步行秒数时 `transfer_type=2` + `min_transfer_time`（取**目标成员**上配的秒数），没有时 `transfer_type=0`。两端车站都发布了才写。
- 直通运转拆段用 `transfer_type=4`。

### 版本与缓存

`feed_version = {S}-{内容摘要前 12 位}`（没配服务器 ID 时只有摘要）。摘要覆盖运营商、线路、停靠点、车次、停靠时刻与换乘（含发布方与语言）；**日历起止日期与生成时刻不进摘要，版本号里也不放日期**：同一份内容隔天还是同一个版本，只有内容变了才换。

zip 的条目顺序固定、条目时刻写死（`ZipEntry#setTimeLocal`），同一个 feed 两次生成逐字节一致；`ETag` 按 `feed_version` 加日历起点算：内容不变、日期窗口滚动时 zip 也变了，只按版本算会被 304 挡住，复验的消费者手里的日历会一直不续期直到过期。

| 表 | 说明 |
|---|---|
| `agency.txt` | 每个运营商一行；`agency_url` 统一取发布方地址 |
| `stops.txt` | 车站、股道、`…:DYN` |
| `routes.txt` | 线路；`route_type` 按 `LineServiceType` 映射（METRO→1、LRT→0、REGIONAL/COMMUTER/EXPRESS→2）；颜色去掉 `#`，文字色按相对亮度取黑或白 |
| `trips.txt` | 拆段后的每一段 |
| `stop_times.txt` | 只列停车的车站；通过站（PASS）不列 |
| `calendar.txt` | 一行 `DAILY` |
| `feed_info.txt` | 发布方、`zh`/`en`、日历起止、`feed_version`、`feed_contact_url` |
| `translations.txt` | `secondaryName` 作为 `en` 译文写入 `agency_name`、`stop_name`、`route_long_name`、`trip_headsign`；没有英文名或与中文相同的项不写 |
| `transfers.txt` | 车站组换乘与直通拆段；没有换乘时不写 |

## GTFS-Realtime

三种 feed 的报文头 `feed_version` 与 `/v1/meta` 的 `gtfs.feedVersion` 相同。ID 全部引用静态包：车次 `trip_id`、停靠点 `stop_id`、线路 `route_id`、运营商 `agency_id`。

### TripUpdates

| 内容 | 来源 |
|---|---|
| 车次身份 | 绑定车次的时刻表、车次号、`serviceDate` → `trip_id` + `start_date` + `start_time`（与静态 `stop_times` 首站发车一致） |
| 直通运转的段 | 运行时进度下标 ≥ 换线站下标时用 `~2` 那一段 |
| 上一站实际偏差 | `TrainAssignment#currentDelaySeconds` → 上一站的 `departure.delay` |
| 停站中的车 | 运行时 `dwellRemainingSec` → 上一站 `departure.time = max(now + 剩余秒数, 表定发车)`（按表等点时不会早于表定开出）；既没有停站剩余也没有偏差时不写上一站 |
| 下一站预计 | `projectedDelaySeconds` → `arrival.time` / `departure.time`（静态时刻 + 偏差） |
| 动态选台 | 只认**已选定**的股道（`EtaService#placedStopNode`；未选台时车次绑定给的是与起始股道同名的占位股道，不算）。实际股道与静态 `stop_id` 不同时只写 `stop_time_properties.assigned_stop_id`、不写 `stop_id`（规范首选；两者都写时必须相同，而实际股道不在这一趟的静态 `stop_times` 里） |
| 越站 / 半途取消 | 该站 `schedule_relationship=SKIPPED`（越站来自 `TimetableService#skippedStops`）；拆段后某一段一站都不再停时，这一段整段 `CANCELED` |
| 整趟取消 | `TripDescriptor.schedule_relationship=CANCELED`，拆段的每一段各一条 |
| 直通车跑在其中一段 | 只有车所在的那一段按运行中发布（按分段 `trip_id` + 日期认），其它段照常按取消登记发布 |
| 一条站点更新都给不出 | 不发这趟车（未取消的车次至少要有一条站点更新）；消费者按静态时刻显示 |
| 回库车过了 EOP | 不发布（Q10） |

没有实时记录的车次，消费者按静态时刻显示，这是规范的默认语义。取消登记取现在前后各 3 小时。

### VehiclePositions

| 字段 | 来源 |
|---|---|
| `vehicle.id` / `label` | 列车名 / 车次号（没绑车次时用列车名） |
| `trip` | 绑了车次：`trip_id` + `start_date`；没绑：只填乘客线路 `route_id`；两者都没有时不填 |
| `position` | 车头实际坐标与朝向（Q9 选 A，见下），速度取采样速度（格/秒即米/秒） |
| `current_stop_sequence` / `stop_id` / `current_status` | 正在停站为 `STOPPED_AT`，车头已到下一站节点为 `INCOMING_AT`，其余 `IN_TRANSIT_TO`；只报这一段静态 `stop_times` 里有的停靠（没有车站的停车点不算），`stop_id` 只在已选台时写实际股道 |
| `occupancy_percentage` / `occupancy_status` | 各节车座位与在座（`TrainLoad`）：空车 `EMPTY`，空位过半年 `MANY_SEATS_AVAILABLE`，有空位 `FEW_SEATS_AVAILABLE`，坐满 `FULL`。车辆模型没有站位容量，100% 表示座位坐满，不代表不能再上车 |
| `multi_carriage_details` | P3 |

**Q9 车头坐标**：`EtaRuntimeSampler` 在主线程采样时顺手记下车头的 `x/y/z` 与沿轨道的运动向量（`RailTracker#getMotionVector`，`TrainRuntimeSnapshot.Position`），HTTP 侧再按上面的投影换成经纬度，`bearing = atan2(dx, −dz)` 归一到 `[0, 360)`（+x 向东为 90°，−z 向北为 0°）；从没动过时不写。不用实体 yaw：TrainCarts 写进实体的是模型朝向，与轨道方向差 90°，车厢反挂、换端时还会再差 180°。不在上一节点与下一节点之间插值：弯道和道岔区会偏离轨道，停站时位置也不在站台上。

回库越过运营终点的车不发布（Q10）；出库途中、还没到当前交路第一个载客站（`RouteTerminals#beforeService`，与回库的 `outOfService` 对称：停车且是车站本体的停靠点，咽喉不算）的车也不发布位置，纯走行交路整趟都不发布——绑了车次的照常发布到站预测，乘客要等的正是它的首站到达；`block_id` 不出（Q11）：插件的交路在终点折返时都是本站终到，写成可以不下车反而误导。

### ServiceAlerts

| 来源 | 映射 |
|---|---|
| 站台屏公告 | 编号作 id；`informed_entity` 按公告的线路/车站逐对成组（只影响某条线时写 `(agency, route)`；线路与车站同时限定时写 `(agency, route, stop)` 取交集，不能把运营商选择器单独放进去——那会变成全网告警）；无线路也无车站时整家运营商；`active_period` 取 `starts_at`/`ends_at`；标题、正文中英双语；`IMPORTANT`→`WARNING`、`NORMAL`→`INFO`；cause/effect 填 UNKNOWN；**不输出 `created_by`** |
| 线路运行状况 | 只出影响乘车的四种：SUSPENDED→`NO_SERVICE`（整条线，cause=MAINTENANCE）、PART_SUSPENDED→`NO_SERVICE`（只标这条线在断开处前后两站 `(agency, route, stop)`；一个都标不出时整条不发）、SEVERE_DELAYS→`SIGNIFICANT_DELAYS`、CANCELLATIONS→`REDUCED_SERVICE`。id 取 `alert:line-status:{OP}:{LINE}:{状况}`，状况变了就换一条；文案复用站台屏的中英文案 |

### 编码与调试

protobuf 由 `GtfsRealtimeProtobuf` 手写编码（只用到 varint、长度前缀与 float32 三种线格式），字段号以官方 `gtfs-realtime.proto` 为准，插件 jar 不引入任何 protobuf 运行时依赖。单测用只在测试期引入的 `org.mobilitydata:gtfs-realtime-bindings:0.2.0` 反解码核对。

`?format=json` 用同一份 `GtfsRealtimeFeed` 模型输出字段名与 proto 原名的 JSON，方便排查；不承诺与官方 JSON 映射逐字节一致。JSON 与 protobuf 各自缓存，缓存键带格式。

`ETag` 按「静态包版本 + 报文头生成时刻 + 实体内容」算：报文头时间戳是表示的一部分，强校验必须随它变化，否则 304 会让条件请求的消费者一直拿着旧时间戳判断新鲜度（RFC 9110 §8.8.1）。同一个缓存有效期内重复请求仍可拿到 304；过了缓存周期重新生成，时间戳变了就回 200。静态包换版本时三种 RT 的 ETag 一起变。

单条记录算不出来（例如某列车的绑定数据异常）时只跳过这一条并记一条告警，其余实体照常发布；同一条告警一分钟内不重复，不会按缓存周期刷屏。整体生成失败（快照抛异常、报文头都拼不出来）按 `503` 处理，不缓存。

静态包与 zip 的单飞重建只对缓存未命中加锁：命中缓存不取锁；一次生成期间其它请求等这一次结果。到期时先比输入指纹：实服规模下 hash 约
0.2 ms、取快照约 1 ms，远低于完整构建（0.15–1 秒），所以内容没变的 5 分钟检查几乎不占 web 线程。`stop()` 最多等 1 秒，超时的生成线程是守护线程，不会阻止服务器停用。重载后改了 `server-id` 时旧缓存立即作废：包内 ID 的 `{S}` 前缀与 zip 下载文件名必须一起换，不能等 300 秒。

## 排障

| 现象 | 检查 |
|---|---|
| `/v1/gtfs.zip` 回 503 | 车站目录是否加载（`/fta station list`）、`web-api.enabled` 是否打开。数据源暂时不可用但已有旧包时继续返回旧包；没有旧包才 503，服务起来后下一次请求就能出包 |
| 包很小 / 没有车次 | 时刻表是否发布且 `timetable.enabled=true`；线路是否 ACTIVE；日志里搜 `GTFS 导出跳过`（每个 feed 版本只记一次，最多 40 条） |
| `stop_id` 找不到 | 先 `/fta graph build` 重建调度图（股道坐标来自图）；`/v1/meta` 的 `gtfs.feedVersion` 看是否换了新包 |
| 校验器警告 | 没车停的股道（`stop_without_stop_time`）、没有 shapes、落在海上的虚构坐标，都是预期内的 |
| 中转对不上静态包 | RT 报文头 `feed_version` 必须等于 `/v1/meta` 的 `gtfs.feedVersion`；缓存窗口内重新发布时刻表时最多 300 秒对不上 |

手动校验可以用 MobilityData 的 gtfs-validator 跑一遍实服导出的包；导出后先看 `feed_info.txt` 的 `feed_version` 与 `calendar.txt` 的日期窗口。

## 测试

- `GtfsFeedBuilderTest`：直通拆段、跨零点 `24:xx`、动态站台与计划股道、出入库纯走行过滤、检修/规划线路、上下客、换乘、英文译文、`feed_version` 与 zip 逐字节稳定、相邻换线不拆出一站段。
- `GtfsRealtimeProtobufTest`：用官方绑定反解码核对三类实体、负偏差、绝对时刻、`assigned_stop_id`、`SKIPPED`/`CANCELED`、多语言文本。
- `GtfsRealtimeBuilderTest`：运行中车次、换段、越站、停站剩余时间、整趟/半途取消、车辆位置与载客、只带线路的车、公告与线路状况。
- `WebApiGtfsTest`：zip 的 Content-Type/文件名词/缓存、RT 的两种格式与 400、meta 的版本、数据源为空时 503。

## 未做（P3）

shapes、frequencies（无时刻表线路）、逐站预测（需要给站牌行加车次身份）、未发车车次的晚点、`multi_carriage_details`、分合编（`DECP`/`COPL`）、多世界坐标。
