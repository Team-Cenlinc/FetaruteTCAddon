# RouteStop 特殊标记与 Sign 约定

本文描述调度 RouteStop 的扩展语法，以及配套的牌子（Sign）命名规则，便于实现动态站台、换线等高级调度能力。

## 1. RouteStop 元数据 DSL
每个 `RouteStop` 除了 `stationId` / `waypointNodeId` 外，还可在 metadata 或 notes 中携带 `action` 字段，推荐使用 `ACTION:PAYLOAD` 的冒号分段格式：
```
ACTION:PAYLOAD[:MORE]
```
例如 `CHANGE:SURN:LT`、`DYNAMIC:SURN:PTK:[1:3]`、`CRET SURN:D:DEPOT:1`。若参数较多，可继续拼接冒号片段。运行时由 `RouteStopActionResolver` 解析为调度意图，再由 `RuntimeDispatchService` 在授权边界内执行。

### 1.1 换线标记（CHANGE）
- 语法：`CHANGE:<OperatorCode>:<LineCode>`（例如 `CHANGE:SURN:LT`）。
- 含义：列车抵达当前 stop 后，调度层把对乘客显示的 operator/line（`FTA_OPERATOR_CODE`/`FTA_LINE_CODE` tags）改为新线路，但**不改变当前 Route 或 routeIndex**，也不改变管理归属。列车继续沿当前 route 运行。
- 典型场景：直通车在枢纽站起按 SURN-LT 线对乘客运营（列车继续按原 FTL 交路行驶、仍归 FTL 管理，HUD/PIDS 显示的线路信息变为 SURN-LT）。
- 行为：
  - 仅写入 `FTA_OPERATOR_CODE` 和 `FTA_LINE_CODE` tags
  - 不修改 `FTA_ROUTE_ID`/`FTA_ROUTE_CODE`/routeIndex
  - 与此前所属线路相同的 CHANGE 不算换线；缺少线路段或有空段的 CHANGE 不执行（运行时记 `CHANGE 解析失败`）
- **起步线路**：定义书**第一行（第一站之前）**写 `CHANGE:<OperatorCode>:<LineCode>` 表示列车**起步即按该线路对乘客运营**，例如交路 `MT-3N_DPExp` 在 NTA 起步、前半段实际在 WS 线上运营，到分界站 HHU 再 `CHANGE:SURC:MT`：
  ```
  CHANGE:SURC:WS
  STOP DYNAMIC:SURC:S:NTA:[1:3]
  ...
  STOP SURC:S:HHU:1
  CHANGE:SURC:MT
  ```
  - 存储上落在首站（sequence 0）的 notes 里，运行时与显示读同一处；解析定义书时，第一站之前的 `CHANGE` 行并入首站 notes（追加在首站已有动作行之后）。第一站之前只允许**一条** CHANGE；出现多条、或与首站下一行的旧写法 CHANGE 并存，视为歧义并报错（`command.route.define.change-ambiguous`）；第一站之前出现 `DYNAMIC`/`ACTION` 仍报 `command.route.define.action-first`。
  - CHANGE 是“抵达该站后”执行的，出车与折返复用没有“抵达”首站，所以**出车与折返复用都直接按它写线路标签**（`RouteLineChanges#entryLine`）：自动出库（`TrainCartsDepotSpawner`）、`/fta depot spawn`、折返复用（`dispatchLayover`）写下的 `FTA_OPERATOR_CODE`/`FTA_LINE_CODE` 就是起步线路，没有有效 CHANGE（未写、与交路同线、格式错误）时仍是交路自身线路，`FTA_ROUTE_CODE`/`FTA_ROUTE_ID` 不变。折返复用从中途下标入路时，入路站及之前的 CHANGE 一并算上，与显示口径一致。
  - 列车随后“抵达”首站时，标签已在目标线路上，运行时不再当成到站换线：不重复改写标签、不记 `CHANGE 移交成功`（标签与目标不一致时——例如没有经过出车入口的列车——仍按原样补写）。
  - 旧写法（CHANGE 写在首站下一行）继续接受，存储结果相同。`/fta route editor give`（按数据库停靠表渲染）与 `define` 后的归档书回显时，首站的 CHANGE 一律渲染成第一行、写在首站那一行之前，一读一写之后旧写法的书归一到新写法；`edit` 只把归档成书转回书与笔、保留原页面，旧归档书在下一次 `define` 后归一；中途站的 CHANGE 仍写在所属站之后。
  - 回显：`/fta route define` 与 `/fta route debug` 的停靠表解析结果里，首站的有效 CHANGE 显示为“起步线路=运营商:线路”，不显示成到站换线；中途站仍显示 `CHANGE:...`。格式错误的首站 CHANGE 运行时不执行，回显原样保留以便发现。
- CHANGE 是**通知**，不是移交管理：交路、交路组、时刻表、调度（优先级、回收、折返）仍归交路自身的线路；换线只影响对乘客显示的线路。
- 显示口径（`RouteLineChanges` 是唯一定义，执行与显示共用同一套解析）：
  - 每站所属线路 = 该站及之前最后一个有效 CHANGE 的目标，没有时为交路自身线路；**换线站本身算新线路**（以原线路到达、以新线路发车，到站即改写标签）。首站的 CHANGE 是起步线路：首站就属于目标线路，车站目录的停靠线路只算目标线路（列车从来不是以交路自身线路到达首站的）。
  - 列车当前线路以标签为准，标签不全时为交路本身的线路（运行时执行 CHANGE 必然写标签，标签就是“通知是否发生”的事实）。
  - HUD：`{line}`/`{line_color}`/`{operator}`/`{company}` 与线路绑定的模板跟当前线路走；`{through_*}` 给出前方下一次换线的车站与线路；LCD 前方停靠列表里换线之后的各站按新线路着色。
  - 公开 API（1.7.0）：`RouteApi.StopInfo#lineChange` 标出换线站，`TrainApi.TrainSnapshot#operatorCode`/`lineCode` 为当前线路；
    停靠线路（`StationApi#linesServing`）换线之后的车站算新线路、换线站两条都算；站牌行的线路按列车到该站时所属的线路。时刻表仍是交路自身线路的。

### 1.2 动态站台标记（DYNAMIC）
- 语法：`DYNAMIC:<OperatorCode>:<S|D>:<NodeCode>[:Range]`，例如 `DYNAMIC:SURN:S:PTK:[1:3]` 表示 PTK 站 1-3 号站台任选其一。
  - 旧格式 `DYNAMIC:<OperatorCode>:<StationCode>[:Range]` 仍按 Station 解析。
  - Station 候选 NodeId 固定为 `Operator:S:Station:Track`；Depot 候选 NodeId 固定为 `Operator:D:Depot:Track`。
  - Range 省略表示本站/车库现有的全部数字股道；候选数量预算属于执行约束，不应被解释为声明只允许 1 道或 1..10 道。当前不同入口的实现差异见第 4 节。
- 行为（运行时选择顺序，满足“先空闲后可达”语义）：
  - 先筛选“空闲站台”：对应 NODE 资源未被其他列车占用（占用系统快照）。
  - 再做只读可达性检查；选择器不申请占用、不写 destination。
  - 已声明 DYNAMIC、但当前无法安全 materialize 时返回明确的 `BLOCKED`；容量耗尽、定义无效以及图/当前位置/占用证据缺失均属于该状态。禁止回退到 Route 占位节点或已占用站台。
  - 信号 tick / 推进点收到 `BLOCKED` 后保持停车，并撤回本车当前全部纯 queue entry；Layover 则在领取 ticket、换向与进路申请前终止本轮派发。
  - 站台全满时不会释放或绕过 NODE、EDGE、CONFLICT 等真实 claim；尽头站由已停靠列车优先出站，容量释放后进站列车再重新选择。
  - 若存在空闲且可达站台、只是咽喉或道岔暂时繁忙，仍先 materialize 站台，再由普通授权链保留 FIFO queue；不得把进路繁忙误判为容量耗尽。
  - 可写授权窗口与 Depot spawn gate 都到首个已 materialize 的 DYNAMIC 目标为止；更远处未解析的 DYNAMIC 在占位节点前截断，紧邻目标未解析时 fail-closed，原子联锁不得越界扩展，首个 TrainCarts destination 也不得使用 `fromTrack` 占位节点。
  - 选定后只覆盖该 stop 的“有效 NodeId”；普通 TrainCarts destination 必须等 Dispatcher 完成 `canEnter/acquire` 且无 hard blocker 后才写入。
  - DYNAMIC resolver / allocator 不控车、不 launch、不 stop、不 acquire。
  - TODO：将选定站台写回 MetaTag/事件（供 HUD/PIDS 精确显示）。

在 `route define` 书本语法中，DYNAMIC 可以：

- 作为独立 action 行附着到上一条 stop（推荐，语义最清晰）
- 或写在 stop 行末尾（便于编辑），例如：`STOP PTK DYNAMIC:SURN:PTK:[1:3]`
- 或作为 stop 目标的简写（语义：本站=PTK，站台动态选择），例如：`STOP DYNAMIC:SURN:PTK:[1:3]`

### 1.3 生成标记（CRET）
- 语法：`CRET <NodeId>` 或 `CRET DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`。
- 含义：明确列车在何处生成；调度层会在该 stop 触发生成/出库逻辑。
- 约束：整条路线仅允许一个 `CRET`，且必须为首个 stop（CRET 自身占一行 stop）。

### 1.4 销毁标记（DSTY）
- 语法：`DSTY <NodeId>` 或 `DSTY DYNAMIC:<OperatorCode>:<DepotCode>[:Range]`。
- 含义：列车抵达该 stop 后执行销毁/回收（例如进库后销毁实体）。
- 约束：整条路线仅允许一个 `DSTY`，且必须为最后 stop（DSTY 自身占一行 stop）。

## 2. Sign 牌子命名扩展
在 `dispatcher/sign` 体系内，保留以下扩展规则，方便与 RouteStop 标记联动：

| Sign 类型 | 格式 | 说明 |
| --- | --- | --- |
| Waypoint | `Operator:From:To:Track:Seq` | 已在 AGENTS.md 描述，可在 `Track` 字段附加 `#TAG` 说明，如 `LT1#DYNAMIC` 表示该轨可参与动态站台分配 |
| Station | `Operator:S:Station:Track` | 站点/站台类节点（用于停站/开关门等行为）；`Track` 可编号 1..n，对应 `DYNAMIC[1:n]` 的站台索引 |
| Station throat | `Operator:S:Station:Track:Seq` | 站咽喉（图节点/Waypoint；同站点下的多个咽喉用 Seq 区分） |
| Depot | `Operator:D:Depot:Track` | 车库节点（用于发车/回库等行为；同时用于与同名站点区分）；`Track` 可编号 1..n |
| Depot throat | `Operator:D:Depot:Track:Seq` | 车库咽喉（图节点/Waypoint；同车库下的多个咽喉用 Seq 区分） |

## 3. 与 Runtime 的关系
- RouteStop 标记先被解析为调度意图，不直接操作 TrainCarts、routeIndex 或 occupancy。
- 普通下一跳 destination 只在 Dispatcher 完成授权后写入；AutoStation/waypoint dwell、debug setup、spawn execution 等行为流程例外必须在代码里带明确 reason。
- 执行 `CHANGE` 只更新逻辑归属 tag；执行 `DYNAMIC` 只 materialize effective node，后续仍由授权路径决定是否写 `setDestination(...)` 交给 TrainCarts 寻路。
- PIDS/Scoreboard 读取 `RouteProgress` + RouteStop 列表即可展示未来停靠。执行 `DYNAMIC` 时，选定的站台节点应写入列车 MetaTag（如 `fta:current-platform=PTK2`）并广播事件，便于 UI 更新。
- `RouteProgress` 中需缓存 `routeId`、`sequence`、`nextStop` 等字段，跨服或列车重载时从 MetaTag 恢复，再继续应用 RouteStop 标记。

> 以上约定为第一版草案，后续可以逐步扩展 action 前缀或在 metadata 中使用结构化 JSON，以便解析器保持稳定。

## 4. DYNAMIC 候选集合扩展

实现日期：2026-10-12；研究基线：`9e247201`。本节记录第一版范围语法扩展的实现契约；实现与回归步骤见 [DYNAMIC 运维说明](../dev/dynamic-route-stops.md)。

### 4.1 语义与第一版写法

把最后一段从“单一连续范围”扩展为“允许作为目标的股道集合”：逗号取并集，冒号或连字符表示包含两端的闭区间。

| 表达 | 允许的股道 | 说明 |
| --- | --- | --- |
| `[1,3,5]` | 1、3、5 | 2、4 不能成为本次 DYNAMIC 的停靠目标 |
| `[1,2,3-5]` | 1、2、3、4、5 | 单值与区间混写 |
| `[1,3-5,8]` | 1、3、4、5、8 | 多段集合 |
| `[1:5]` | 1、2、3、4、5 | 保留已有闭区间写法 |
| `[1,3:5,8]` | 1、3、4、5、8 | 旧区间写法也可以作为集合项 |
| `3` / `[3]` | 3 | 保留单值写法 |
| 省略最后一段 | 本站/车库现有的全部数字股道 | 与显式空集合区分 |

示例：

```text
STOP DYNAMIC:SURN:S:PPK:[1,3,5]
TERM DYNAMIC:SURN:S:END:[1,3-5,8]
CRET DYNAMIC:SURN:D:DEPOT:[1,3,5]
DSTY DYNAMIC:SURN:D:DEPOT:[2,4-6]
```

集合仅限定目标节点，TrainCarts 仍可经由其他股道寻路；它不等于封锁 2、4 道。集合的书写顺序也不决定选台优先级：`[5,1,3]` 与 `[1,3,5]` 的资格相同，空闲、可达、预订、计划站台与方向优选仍由现有调度规则处理。所有允许目标不可用时保持 `BLOCKED`，即使集合外的 2、4 道空闲也不能改选它们。

### 4.2 解析与兼容规则

第一版语法：

```text
Selection := N | N ':' N | '[' Item (',' Item)* ']'
Item      := N | N ':' N | N '-' N
N         := 正的十进制 int
```

- 保留 `DYNAMIC:OP:S:NAME`、`DYNAMIC:OP:D:NAME` 及省略类型的旧 Station 格式；新集合语法共用同一个尾段解析器。
- 保留原有无方括号的单值与冒号区间。新列表及连字符区间统一要求方括号，减少可选写法。
- 重复值、重叠区间按集合去重；端点逆序按现有冒号区间行为归一化，例如 `[5:2]` 和 `[5-2]` 都表示 2..5。
- 空集合 `[]`、空项 `[1,,3]` / `[1,]`、未闭合括号、缺端点 `[1-]`、非数字、零、负数和 int 溢出都应明确拒绝。任何格式错误都不能退化为“不限股道”。
- 选择器内部不接受空白，使用 `[1,3,5]`；`[1, 3, 5]` 拒绝。当前书本解析使用 `split("\\s+")`，指令读取也会在空格处截断；第一版不扩展 token 边界。
- 定义书与归档书保留原始指令文本；诊断展示用统一格式化方法，显示完整集合，避免回显成 `[最小值:最大值]`。
- `*` 目前仅出现在部分诊断展示中，不是解析器支持的输入。不限范围仍通过省略尾段表达。
- 第一版不引入排除项（`!2`）、步长（`1-5/2`）、奇偶谓词、嵌套集合、字母股道或顺序优先级。需要允许奇数站台时显式写 `[1,3,5]`。

定义、保存与运行时的失败行为必须一致：

| 输入/证据 | 处理 |
| --- | --- |
| 未声明选择器 | 不限本站/车库的数字股道 |
| 有选择器但语法错误 | define 按原始行号报错，整次定义不写入；加载存量错误定义时记录 route、sequence、原文与原因并停用该交路，防止删站或回退选台 |
| 合法显式集合，但所有目标不存在 | 专用“没有有效股道”错误，禁止回退到不限范围 |
| 合法显式集合，部分目标不存在 | 保留现有部分缺失警告，只统计集合内股道 |
| 合法候选存在，但没有空闲可达目标 | `BLOCKED`，遵守现有容量等待规则 |
| 没有 DYNAMIC 声明 | `NOT_APPLICABLE`，沿用普通停靠逻辑 |

尤其不能用单个 `Optional.empty()` 同时决定“不需要 DYNAMIC”和“DYNAMIC 写错了”：声明检测与解析结果必须共同决定是否允许普通目标回退。

### 4.3 统一模型与调用方式

复用 `dispatcher.route.DynamicStopMatcher` 作为 DYNAMIC 解析与匹配入口，把 `DynamicSpec` 内的单区间表示替换为不可变的 `TrackSelection` 值模型。它明确区分“不限股道”与“显式非空集合”，内部采用排序、合并后的闭区间列表，避免解析大区间时逐个分配整数。

归一化后必须满足：区间端点为正 int，区间升序，区间彼此不重叠且不相邻；单值用长度为 1 的区间表示。不限范围是明确状态，不能由空列表冒充；显式集合不能为空。合并相邻区间时用 long 或安全比较，避免 `Integer.MAX_VALUE + 1` 溢出。

`[5,1,3]` 与 `[1,3,5]`、`[1,2,3-5]` 与 `[1:5]` 分别具有相同的集合值；集合的相等性不依赖原始书写顺序、分隔符或重复项。原始文本留在 notes 中用于归档，归一化值用于候选资格及缓存证据比较。

| 接口职责 | 实现契约 |
| --- | --- |
| 判断资格 `contains(track)` | 精确集合成员判断，显式 `[1,3,5]` 对 2、4 返回 false |
| 声明代表值 `firstDeclaredTrack()` | 显式集合取最小允许值；不限范围返回 `OptionalInt.empty()` |
| `formatSelector()` | 返回 `Optional<String>`：不限范围为 empty，单值为 `3`，连续区间为 `[1-5]`，离散集合为 `[1,3-5]` |
| `matches(nodeId, spec)` | 复用既有运营商、S/D、站名与四段节点检查，再调用集合成员判断 |
| `candidateNodes(spec, graph)` | 从实际图节点筛出符合集合的候选，按数字股道号排序 |

统一格式化必须可重新解析，满足 `parse(format(selection)) == selection`；不限范围在完整 payload 中直接省略尾段。人类可读日志可以显示“全部”，但不得把这个显示标签或 `*` 当成可持久化的选择器。

`toPlaceholderNodeId()` 对显式集合取最小允许股道，例如 `[3,5]` 的代表节点为 `OP:S:NAME:3`；不限范围可以保留原有 `:1` 声明占位约定。占位节点不证明节点存在或进路可用，仍不得直接用于授权或 TrainCarts destination。

不建议在 `fromTrack/toTrack` 旁再添加可选列表、让调用方自行决定读哪个字段；集合的最小值和最大值只能描述包围区间，不能作为资格依据。项目内的区间循环、边界比较与格式化应全部迁移到共享模型，再删除旧的私有解析器及 record。

### 4.4 已确认的影响范围

| 入口 | 研究基线中的实现 | 本版处理内容 |
| --- | --- | --- |
| `DynamicStopMatcher` | `DynamicSpec`、区间解析、`matches`、`candidateNodes` | 共享集合模型、成员判断、图候选枚举与格式化 |
| `FtaRouteCommand` | 私有 `DynamicNodeSpec` / `parseDynamicNodeSpec` / `parseTrackRange` | 复用共享解析；定义、部分缺失警告、可达性检查与 debug 回显保留集合空洞 |
| `RuntimeDispatchService` | 私有 `DynamicStopSpec` / `parseDynamicStopSpec` / `dynamicCandidateTracks` | 推进点与信号 tick 使用相同资格；保留授权预判与拒绝原因 |
| `DynamicPlatformAllocator` | 共用 `candidateNodes`，但日志仍拼接区间端点 | 选台、暂定、预订证据与计划偏好均受集合限制，日志输出完整集合 |
| `RouteIndexResolver` / `matchesStop` | 先做普通或同站容错，再检查 DYNAMIC | 检查已有容错是否绕过明确的目标集合；2、4 道不得被当作该受限停靠的到达 |
| `AutoStationSignAction.findStop` | 可直接按 stationId、精确节点或站码回退匹配 | 停站、开门动作也验证该 DYNAMIC 目标的集合，不能仅收紧索引解析器 |
| `TrainCartsDepotSpawner` | 私有 `TrackRange`；解析失败保留默认 1..99 | 出库候选使用共享匹配；错误表达不得放宽为默认范围 |
| `SimpleTicketAssigner` | 私有 Depot 范围解析；解析失败直接返回匹配成功 | Depot 池别名、负载统计及实际候选使用相同集合；错误表达返回不匹配 |
| `CallCatalog` / `CallService` | 逐个展开 from..to，以及端点比较 | 叫车方向目录、站台屏与预指定站台保留集合空洞 |
| `RouteDefinitionCache` / `RouteApiImpl` 等 | 从端点或 `toPlaceholderNodeId()` 构造代表节点 | 统一代表值，避免对离散集合制造错误的声明节点 |
| `TimetablePlatformPlanner` / `RouteStopPlan` | 已经共用 `candidateNodes` | 编表排台与 ETA 自动使用相同候选，增加集合回归 |
| `FtaGraphCommand` / `RouteNodeUsage` | 已经复用 `matchesStop` 或 `matches` | 图校验及节点使用诊断正确识别集合成员 |

“同站容错”需要在每个索引匹配候选上验证对应 stop 的资格，不能在找到一个宽松匹配后直接返回。含固定节点与内联 DYNAMIC 的旧定义也需要回归：例如 `CRET <固定Depot> DYNAMIC:<Station>`，不能因为 notes 出现 DYNAMIC 就把固定出库点身份整体改写成 Station。

车站身份匹配和股道资格不是同一个判定：按 Station/Depot key 查找潜在折返或回库交路时可以保留同站筛选，真正接受当前 stop 的到达、触发停站动作、复用原地列车或授予目标进路时必须校验实际节点。不要把所有 Station key 查询都改成精确股道，也不要把“交路属于同站”作为集合资格已经成立的证据。

本版统一了研究基线中的两个差异：

- 未声明范围时，共享 matcher 与运行时标记为不限范围，但 `FtaRouteCommand` 的私有校验模型仍使用 1..10，Depot spawner 使用 1..99；本版统一解析并按实际节点匹配。
- 共享 `candidateNodes` 的安全预算是 20；显式区间先截取前 20 个号码、再过滤不存在的节点，无范围扫描也会提前停止。推进点、命令校验及叫车目录的区间展开却没有相同预算。第一版不新增分批选台状态机、不提高既有昂贵评估预算，也不把 20 解释为允许的最大股道号。候选必须先按完整集合筛实际节点、稳定排序，再执行既有预算；超过预算时明确诊断，不能用静默截断后的子集反推“声明中没有其他股道”。超过 20 个实际候选的完整评估及跨 tick 续跑作为独立改进，不宣称本轮解决该既有限制。

### 4.5 持久化、交付顺序与验证

范围表达继续存放在 `RouteStop.notes` 和 Depot 池的字符串配置中，本扩展不需要 SQL schema 迁移，也不需要改变 Station/Depot Sign 的单股道 NodeId。包含新语法的定义只能交给已支持集合的插件版本；旧版本的 Depot 解析有放宽范围行为，不能假定回滚时会安全拒绝新表达。

本版按以下顺序实现并验证：

1. 建立共享集合值模型与解析/匹配回归，固定旧格式、无范围、空洞、逆序及错误表达的语义。
2. 迁移书本定义与回显、运行时两条选台路径、到站匹配和占位值；清理重复解析器。
3. 迁移 Depot 出库/池匹配与叫车入口，验证编表、ETA、图校验和公开 API 使用相同声明。
4. 将本提案转为已实现说明，更新 `docs/dev/dynamic-route-stops.md`、JavaDoc 与必要的语言提示。

验收至少包含：

| 场景 | 期望 |
| --- | --- |
| `[1,3,5]`，图上 1..5 均存在 | 只产生 1、3、5 三个候选；缺失警告不提 2、4 |
| 1、3、5 占用，2、4 空闲 | `BLOCKED`，不选集合外股道、不回退占位节点 |
| 计划/叫车预指定 2 或 4 | 不可越过集合限制；在其他合法空闲候选中按现有规则处理 |
| 已选 3 道，随后经过 waypoint 或信号 tick | 继续沿用 3 道，保持 materialization 粘性 |
| 实际到达 2、4 道，路由占位节点为同站 1 道 | 同站容错不能把受限停靠误判为完成 |
| 站点 UUID 或站码匹配成功，但实际在集合外 | AutoStation 不将它识别为该受限 stop 的停车/开门事件 |
| `STOP` / `TERM` / `PASS`、`CRET` / `DSTY` 以及内联动作 | 同一末段表达有相同资格含义，原有行为类型不变 |
| `[1,2,3-5]`、`[1,3:5]`、重复和重叠项 | 并集正确、数字排序、无重复 |
| `[3,5]` 编辑、归档、再 define | 保持 3、5 两个允许值，不补进 4 |
| 空集合、尾逗号、缺括号、超大端点 | 明确失败；不放宽为全站/全库，不发生整数溢出或巨量展开 |
| 旧 `[1:3]`、单值、旧 Station 格式、无范围及五段咽喉 | 保持合法旧语义，五段咽喉仍不匹配行为节点 |
| `[5,1,3]` 改写为 `[1,3,5]`，或加入重复项 | 集合证据相等，不因文本排列变化重新选择站台 |
| 选定 3 道后，定义改为 `[1,5]` | 走既有定义失效流程；不得继续接受 3 道，也不得强制释放真实轨道 claim |
| `[101,103,105]` 或 `[1:2147483647]` | 股道号不受 20 限制；仅筛图中实际候选，不逐个展开整个数值空间 |

实现验证统一运行 `./gradlew spotlessApply clean check`；手动服务器验证使用 1..5 道测试站，依次占满 1、3、5 道，确认列车在站外等待，释放 3 道后确认只下发 3 道 destination。

### 4.6 第一版交付边界

第一版交付的是“同一选择器在所有目标资格入口有相同含义”。它扩展允许集合并统一解析、匹配、回显与必要的缓存证据；不修改股道全局封锁、动态选台优先策略或 TrainCarts 寻路，也不增加排除 DSL、计划站台字段或公开 API 字段。

代表节点只用于保持现有 Route/API 声明结构。计划、暂定及缓存 materialization 都不能赋予集合外节点资格；合法的既有 materialization 继续沿用，真正改变集合导致目标失效时走已有生命周期清理。任何清理都不能以选择器变化为由提前释放列车实际持有的 NODE/EDGE/CONFLICT claim。

验收要同时证明候选资格、到站事实和停站行为一致；仅通过末段解析单测或仅演示“选到 3 道”都不足以交付。第一版需覆盖所有启用的入口，不能让 Station 支持集合而 Depot、叫车或编表仍按 min..max 理解。

### 4.7 排班容量与计划落实

站台组容量不再只比较车辆数量与物理股道总数：已知图的受限停靠按实际允许集合做瞬时二分匹配，同车辆重叠到发要求取交集。固定 Depot 带另一个 Station 的内联 DYNAMIC 不改变 Depot 身份。

同车辆先后使用不同股道时，旧停留的安全间隔残留与新停留分别限制其他车辆，不把两者取空交集而漏掉真实争用。本车新停留不受自己已离开股道的间隔阻挡；真正时间相交的到发、待命仍合并为一次停留。

瞬时 Hall 条件不能证明一辆车可在整段停留保持同一股道。计划排台因此继续按整个 Visit 检查占用与交集；显式选择器出现未排定的连续停留时，编表入口在保存之前拒绝结果。既有无选择器的未排台兜底保留。排台仍为确定性贪心规则，保守失败不代表不存在全局更优方案。

合法物化目标的稳定性与内部预订分开：后车遇到物理前方等台者时只让出预订优先权，仍等待原目标；不得因抢占预订而改写目的地，也不得释放真实占用。

生产图快照在创建时建立本站四段节点索引。候选枚举只筛本站节点，快照替换自动更新索引；没有索引能力的测试/兼容图实现保留扫描回退。超过 20 实际候选时，route validate 与运行时诊断明确说明预算。
