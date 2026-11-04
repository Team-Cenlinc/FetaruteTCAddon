# 站长

站长守在车站的站台上，管所辖股道上列车的接车与出站监视、发车信号、站台扣车、催关、清客与站台紧急停车。与信号员分工按真实车站来：信号员守信号楼（进路、区内扣车、改停股道、越站、取消车次），站长守站台（列车在站台上的那一段）。

站长是可选的增强：没人值守的股道照常自动办理；有人值守时站长只能把“放行”变成“暂不放行”，每一种等待都有时限，超时由系统代办。一切只在内存里，重启与重载即下岗，股道回到自动。

一次只办一列：一列车从接车到出站监视结束要占站长约 46 秒（接车 10、停站 21、出站 15），实验服的两道车站每小时 40 列、两向常同时到，一个人办不完。所以由系统排“当前这一列”，其他车自动办理、不提要求。

## 上岗与下岗

- `/fta stationmaster on`（简写 `/fta sm on`）：在附近最近的车站上岗（`TaskBoardSource#nearestStation`，与驾驶员任务板同一口径）。车站的股道取自调度图上该站的车站节点（`OP:S:站码:股道`），调度图里没有时不能上岗。
- `off` 下岗；离开车站（离所辖车站任一股道的车站牌子超过 `station-range-blocks`）超过 `grace-seconds` 秒、下线、死亡、换游戏模式、站长功能关闭、插件重载也下岗。
- `tasks`：站长任务板（`StationMasterBoard`），各站一格，写明股道数与在岗站长数，没人值守的排前面；点击在这一站上岗，人不在站上时只告诉去哪（与驾驶员任务板一样不传送）。
- `status`（站长或驾驶管理权限）：各站长的车站、所辖股道与发车信号、代发、扣车次数。
- 权限 `fetarute.stationmaster`（默认 OP，含在 `fetarute.drive.player` 打包节点里）。一名玩家同一时间只担任一个角色：`CrewDuty.Role.STATION_MASTER`，与驾驶员、车掌、信号员互斥。

## 分股道（按时刻表）

- 第一名站长管全站所有股道。
- 后来的人：有没人管的股道（分出它的人已下岗）时先全部接走；否则从没被分过的人（第一名站长，或分出他那份的人已下岗）剩下的股道里切一段（`TrackSplitter`），管得最多的先切，目标是全站工作量 ÷ 在岗人数。
- 工作量（`TrackWorkload`）：每条股道先算 1（无表线路的车、凌晨空窗也要有人照看），再加已发布的时刻表在接下来 `load-window-minutes` 分钟内于这条股道停站的车次数；DYNAMIC 停站按候选股道平分。车次的遍历与信号员中心工位共用 `TimetableRuns`。
- 只分股道号相连的一段（同一个岛式站台两侧的股道号通常相邻），原来的人至少留一条。每种分法的代价 = 工作量离目标差多少 + 分开后两人各自股道停站时段重叠的次数（一次重叠就是一列顾不上的车，与差一个车次同等看待）；代价相同取股道少的，再取股道号小的。
- 已分出去的那份不重切（第一名站长只剩一道时后来的人上不了岗）。下岗时分走的股道交还给分出它的人；那人已下岗时转为自动，等下一名站长接走，他手里分出来的那份从此也可以再切。

## 排车（当前这一列）

- 每秒按站牌（`EtaService#getBoard`，接下来 3 分钟、站台已定、运行中的车）记下要停到所辖股道上的来车。
- 手上没车、歇够了、人在车站时排下一列：按 `job.walk-blocks-per-second` 走到那条股道来得及的车里挑最早的；另一条股道的车只比它晚 30 秒以内就排那一列，几条股道轮着办。按实验服时刻表模拟（每列占 46 秒、换股道走 10 秒、歇 30 秒），繁忙的两道站每人约 20 列/时、两道各半，两列之间通常能歇 1–3 分钟。
- 一列办到出站监视结束才算办完，然后歇 `job.rest-seconds`（默认 30）。站长可以自己结束休息：歇着时按快捷栏 1（发车信号键）或 `/fta stationmaster next`，下一拍就排下一列。到站满 60 秒还没等发车信号的车（长时间折返、按表扣着）交回自动办理，不记分也不用歇，站长去接下一列；站长自己扣着的、正在清客的不交回。开着门的车不插队：上岗前已进站的车、歇着时到的车都自动办理。
- 站牌改了这一列的股道时跟着改；这一列从站牌上消失、又没开到股道附近，45 秒后不再等；列车不在了立即作废。作废的不记分，也不用歇。
- 站位与认车：`StationStopPoints` 算出这列车停稳后车头在哪（与驾驶员的发光停车标同一个点），`drive/hud/CrewMarks` 只给站长本人画一道发光标线；正进站、等发车信号时每节车厢罩一个发光的半透明罩子，一闪一闪。TrainCarts 自带的附件发光（`setFocused`）所有观看者都看得到，所以另画。

## 发车信号

- 站台（AutoStation）车门开了就向站长登记这一站（`StationMasterGate#stopOpened`：股道、乘务员还要多久能关门、车门归不归乘务员），停站会话结束时注销（`stopEnded`）。
- 车门归乘务员（车掌或驾驶员）的车可以提前发：从提前关门的窗口起（`closeOpensAt`），清完客、门还开着也算“站台作业完毕”，不挡关门；车门关好、出站放行时站台一问就放行（`Stop#presignalledAt`），乘务员不用多等。发完还能扣车，再按不重复记；发完 60 秒车还不走（出站一直不开、门一直不关）也交回自动，站长去接下一列。窗口之前按下告诉还要几秒。车门归站台的车没有这个窗口。
- 车门关好、出站门控放行后，站台问 `holdsDeparture`。不是当前这一列（或在别的车站停站）时放行。是当前这一列时开始等信号，Boss 栏与动作栏倒数；等满 `signal-timeout-seconds`（默认 10 秒）由系统代发，记一笔代发。
- 出站门控没放行时（闭塞、按表扣车、站长扣车）站台报 `departureBlocked`，发车信号的等待到此作废，放行后重新等、重新倒数：关着和扣着的时间不算站长超时，也不会一放行就代发。已发过的信号不作废。
- 停站会话没放行就结束（会话被接管、转入待命，车还停着）不算出站：这一列不用办了，不记分、不用歇。
- `/fta stationmaster signal`：给身旁的当前这一列发车信号。车门还开着或出站未放行时不算。身旁的车在等信号时这一按总是给真车，不拿去答模拟事件。
- 自动运行、ATO、乘务员三条路径都在出站门控之后问；乘务员路径把它并进车掌看到的“出站放行”，车掌要等站长的信号来了才能确认、按发车铃（真实车站的先后：站台作业完毕 → 车掌 → 驾驶员）。只差站长时站台记在停站对象上（`DriverStationStop#awaitingStationMaster`），车掌、驾驶员的提示写“等站长”，不写等出站信号；站长发信号时车上的乘务员在动作栏收到提示。

## 站台扣车

- `/fta stationmaster hold`：扣住身旁的当前这一列；`release` 放行，放行后立即重评。
- 经 `StationStopCoordinator#setStationMaster` 成为出站门控的第四个扣车来源，与按表扣车、联挂等候、信号员扣车合并取较晚者，同样对健康检查豁免（`holdingForSchedule`）。
- 最长 `max-hold-seconds`（不超过 150 秒，与按表扣车同一上限），到时自动放行并提醒站长。站长下岗时他扣的车全部放行。

## 催关与提前关门

- 乘务员（车掌、驾驶员）管门的车，可在提前关门的窗口里关门，关好即结束停站（窗口算法见 `autostation.md`「停站流程」，车掌一侧见 `guard.md`）。
- `/fta stationmaster close`：催身旁的当前这一列关门。车门归乘务员时，窗口已到就提醒车上的驾驶员与车掌（`stationmaster.crew.close-request`），未到只告诉站长还要几秒；车门归站台的车本来就在停站结束前开始关（晚点时停站已压到下限），不必催。

## 快捷栏与侧边栏

- 在岗时快捷栏换成站台作业按钮（数据包层改写，背包本身不动，与车掌同一套）：1 发车信号、2 扣车/放行、3 催关、9 站台紧急停车；其余几格空着。当前这一列可以发信号（在等、或到了提前发的窗口）、扣着车、紧急停车扣着车时对应按钮亮起。贴图见 `resource-pack.md`。
- F 打开菜单（`StationMasterMenu`，三排）：呼叫信号员、本班情况、下岗（点两次）。岗位菜单实现 `drive/inventory/CrewMenuHolder`，物品保护放行这个窗口。
- 按键判定与驾驶员、车掌相同：时刻按客户端时钟读（`ClientClock`），按住时每 4 tick 的重发与同一次右键的连发都只算一下（`UseClicks#fresh`），网络延迟、抖动、丢包重传不会把一下算成两下。
- Boss 栏只发给本人：当前这一列到了哪一步（来车倒数、接车、停站、清客、等发车信号、扣车、紧急停车、出站）或还要歇几秒；可以发信号时变绿。侧边栏（`DriveSidebar`）与驾驶员同一写法，一行只写一件事、值都很短，免得把侧边栏撑宽（`LocaleManagerTest` 限制每个值不超过 12 个字符；所辖股道连着三条以上写成一段，如 1-4；值乘只写车掌、驾驶员、ATO 或自动运行，不写玩家名）：所辖、列车、停靠、作业、车门、值乘、实时评分（`StationMasterScore#evaluate`）；发车、代发、监视不合格的明细在 F 菜单的本班情况里。车门由站台报（`StationMasterGate#doorsClosed`），只用于显示。每 5 tick 刷新。
- 日常作业提示只走动作栏（`StationMasterMessenger#notice`）不进聊天，免得刷屏。
- 物品保护（`DriveListener`）：服务器上手里还是真实物品，所以攻击实体、撞车、拆展示框都拦下。站长要走动，压力板与绊线放行；选中空格时可以用手开门，手里的物品照样用不了。优先级更低的别的插件仍能在拦下前看到真实物品。
- 手持信号：看得见站长的玩家看到他手里选中的那一格（`StationMasterHotbar#showHand`，每次刷新经 `sendEquipmentChange` 发给跟踪他的玩家），选中第 1 格就是举着发车信号，选中空格就是空手；下岗时换回真实物品。换格的一瞬间别人可能先看到真实物品，最多 5 tick。
- 驾驶会话管理器是唯一的数据包监听；驾驶之外占快捷栏的岗位都实现 `drive/inventory/CrewHotbar`（车掌、站长），监听按登记顺序问它们，物品保护（`isProtected`）与乘客计分板让位（`usesCrewSidebar`）也按它判。
- 第一次上岗、第一次接车、第一次等发车信号、第一次清客、第一次模拟事件各提示一次（玩家持久数据 `stationmaster_tip_*`），`/fta drive tutorial reset` 一并重置。

## 接车与出站监视

- 只判当前这一列，每 5 tick 采一次样：它任一节车厢开到股道的车站牌子附近（`watch.approach-range-blocks`）还在动算接车，放行之后算出站，停在站台上时不采样。
- 到位：站在这列车停稳后的车身范围内（车头到车尾，两端各放宽 4 格）、离轨道中心不超过 `watch.side-range-blocks` 格，或离它某节车厢不超过这么远（弯道站台、量不出车长的单节车）；站位还量不出时按离车站牌子的距离算。标线只是推荐位置，画在停稳后的车头：车掌在车尾一带往前看，站长在车头往后看，正好各盯一头；没车掌时列车开出每节都从站长身边过。岛式站台两侧的车头常在两端，站中间两边都算到位，不必每列跑一个车长。
- 看着：这列车任一节车厢在视线 `watch.view-angle-degrees` 度以内。不判“最近一节”：车从身边过时最近一节在正侧面，顺着车身看反而不算。
- 动作栏实时显示：请站到标线、请面向列车、目视中。
- 接车在列车登记停站时结束（不停站开过的车在离开股道附近时结束），出站在列车离开股道附近或满 `watch.departure-watch-max-seconds` 时结束；合格采样不少于 `watch.watch-ratio` 算合格。接车不合格、而且一次也没到位，记接车不在岗（不另记监视不合格）。一次也没采到样的不判。

## 站台紧急停车

- 快捷栏 9：对身旁（`train-range-blocks`）进出所辖股道（离它最近的股道归这名站长）、还在动的车里离得最近的一列紧急停车，不限于当前这一列（安全动作不受排车限制），车上的驾驶员与车掌都收到提醒。
  - 自动运行（含 ATO）的车：按紧急制动减速度刹停（`drive/driver/EmergencyBrakeAction`，与车掌紧急停车同一口径），停住后由站长扣着：`DriverControlRegistry#setPlatformHold` 让扣着的车算驾驶员控制，调度不替它起步、健康层不当它停滞。
  - 人工驾驶的车：把驾驶员的手柄拨到 EB（`DriveSessionManager#platformEmergency`），由驾驶员停稳后缓解，站长一侧不扣。
- 车停稳后站长再按一次解除，或从按下起扣满 `emergency-hold-seconds`（默认 120，含刹车的那几秒）自动解除；解除时立即重算一次信号。站长下岗时他扣着的一并解除。
- 只记次数，不扣分（宁停勿过）。

## 清客

- 当前这一列本站之后不再载客时（`PassengerClearing`：本站是交路的最后一个载客站，且之后还有停靠点——回库、空驶——或终点之后销毁；终点折返待命、接着开下一趟的不算）发车信号前先清客。改停到同一站另一股道的照样清。
- 车门一开，车上还有乘客（不含值乘的驾驶员与车掌）时就车内广播请全体乘客下车（聊天、标题与提示音），乘客趁停站下车；从广播起等 `clearing.seconds`（默认 20），乘客自己下完即清完，到时限仍在车上的送到本站站台（`DriverTaskManager#platformLocation`）。停站结束时还没清完的车等清完才开始等发车信号。
- 只清当前这一列，站长人在不在车旁都照清；自动办理的车不清客，与没人值守时一样。

## 呼叫信号员

- F 菜单：就身旁的车（身旁的当前这一列优先，其次身旁别的车）弹出选原因的对话框（`SignallerCallDialog`，与驾驶员、车掌同一个），按 `SignallerLink#call` 呼叫那列车所在或正接近的信号楼，呼叫者岗位记为 `CallerRole.STATION_MASTER`；结果在聊天里告诉站长，两次呼叫至少隔 5 秒。信号员的回复照常发给站长本人。

## 值守中的模拟事件

- 只考判断，不改变列车运行：有题在等处置时，按下的发车信号、扣车、催关、紧急停车键只算答题，不真的发信号、扣车或紧急停车。身旁有别的真车要办时（别的车在进站要紧急停车、停着要解除紧急停车）这一按给真车，题目接着等；出题用的那列车只算答题。
- 题目都以当前这一列的名义：进站时出“乘客越线”（应站台紧急停车）；停站、还没关门等信号、没在清客时出“夹人夹物”或“乘客赶车”（都应扣车）。`events.answer-seconds`（默认 15）内没处置算没按时处置。用的车不在了题目作废。
- 出题：上岗满 60 秒后，按 `events.mean-interval-seconds` 取指数分布，两题至少隔 `events.min-gap-seconds`；人不在车站、手上没车、当前这一列在等发车信号或被扣着时等 5 秒再看。考试与练习中不常驻出题，只出考官要的那一道（`StationMasterService#drill`）。

## 成绩、记录与奖励

- 下岗时结算（`drive/crew/StationMasterShift`、`StationMasterScore`，`drive/session/StationMasterSettlement`）：满分 100，超时代发每次 5 分，接车不在岗、接车或出站监视不合格、扣车到上限、模拟事件处置错误或没按时处置每次 2 分；紧急停车只记次数。值守时长不加分。
- 一列车也没经手（亲手发与代发都没有）的班次不结算。记录写进 `drive_task_records`（mode `STATION_MASTER`，交路代码一栏是站码，明细见 `StationMasterRecordCodec`），不进驾驶排行与驾驶员汇总，也不进 `DriveApi#records`；`/fta drive records` 的终态写下岗原因。
- 奖励：亲手发的发车信号次数（模拟事件处置正确一次折半次）× `drive.yml` 的 `rewards.station-master-per-train-ratio`（默认 0.5）× 驾驶员人工停站单价 × 评级系数，经验与钱币各一份（`DriveRewards#perTrain`，与信号员同一算法）。
- 考试的这一班写记录、不发奖励；练习的这一班两样都不做。信号员与站长的结算共用 `drive/session/SettlementHost`（发奖励与报奖励的 `payPerTrain`、`tellPaid`）。

## 操作许可证（站长资格）

- 第四个岗位：`LicenseClass.Exam.STATION_MASTER`、`Post.STATION_MASTER`，默认资格 `station-master`（不要求别的资格，先练习 1 次，考试亲手发车 5 列、70 分及格，考过给 `fetarute.stationmaster`）。`drive.yml` 的 `license.classes.station-master`（`exam-trains`）。
- 报名（`/fta license exam station-master`）后在时限内到车站上岗；考官（`drive/license/StationMasterExams`，站长服务经 `StationMasterExaminer` 报事件）亲手发满列数、处置完那一道模拟事件（亲手发到第 1–N 列之间随机安排）后按扣分判定（`StationMasterExam`：超时代发、监视不合格、模拟事件处置不当或没按时处置每次 10 分，低于及格线当场不及格）。考试中下岗不计成绩。考官的那一道题只在当前这一列没有等发车信号时出。判定落地（发证、冷却、报结果）与信号员共用 `LicenseService#settleExam`。
- 练习（`/fta license practice station-master`）与考试同一做法，不发证、不冷却；做满列数并处置对模拟事件（错了再来一次）计一次练习。
- 《FTCA 站长手册》（`/fta handbook station-master`，报名时没有就发一本）。证面第一页增加站长一栏；四个岗位都取得时第一页放不下取得日期，日期另印第二页（证面版式升到 3）。

练习中有教练（`StationMasterService#coach`）：当前这一列每到一步（来车、进站、停站、清客、可发信号、等信号、已发信号、扣车、紧急停车、出站）和办完可以歇时，在聊天里说一句该做什么，同一步不重复；模拟事件出题时另说一句该按哪个键。考试没有教练。文案在 `stationmaster.coach.*`。

## 配置（`config.yml` 的 `station-master` 段）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 关闭后不能上岗，已在岗的下岗 |
| `station-range-blocks` | 64 | 离所辖车站任一股道的车站牌子该格数以内算在车站 |
| `train-range-blocks` | 12 | 离列车任一节车厢该格数以内算在车旁 |
| `grace-seconds` | 30 | 离开车站后该秒数内未返回即下岗 |
| `signal-timeout-seconds` | 10 | 等发车信号的时限（3–120） |
| `max-hold-seconds` | 150 | 扣车上限（不超过 150） |
| `load-window-minutes` | 60 | 分股道统计的时间窗 |
| `job.rest-seconds` | 30 | 办完一列后至少歇几秒才排下一列 |
| `job.walk-blocks-per-second` | 5.0 | 估算走到股道要多久的步速，来不及的车不排 |
| `emergency-hold-seconds` | 120 | 站台紧急停车停住后扣着的上限（10–600） |
| `watch.approach-range-blocks` | 64 | 列车离股道车站牌子该格数以内、还在动，算进站或出站中 |
| `watch.side-range-blocks` | 12 | 站在车身范围内、离轨道中心该格数以内算到位 |
| `watch.view-angle-degrees` | 60 | 列车任一节车厢在视线该度数以内算看着 |
| `watch.watch-ratio` | 0.7 | 合格采样比例 |
| `watch.departure-watch-max-seconds` | 20 | 出站监视最多看几秒 |
| `clearing.enabled` / `clearing.seconds` | true / 20 | 清客与清客时限（5–120） |
| `events.enabled` / `mean-interval-seconds` / `min-gap-seconds` / `answer-seconds` | true / 300 / 90 / 15 | 模拟事件 |

## 代码位置

- `dispatcher/runtime/control/StationMasterGate`：零依赖 SPI（站台登记、发车信号、扣车）。
- `stationmaster/StationMasterService`：值守、分股道、发车信号、扣车、催关、每秒巡检；`StationMasterWorld` 隔开服务器对象，测试用假实现。
- `stationmaster/TrackWorkload`、`TrackSplitter`：分股道。
- `stationmaster/StationMasterModule`：建服务、每秒巡检、监听下线、死亡与换游戏模式；`FetaruteTCAddon#initStationMaster` 接到在岗登记与调度。
- `stationmaster/StationMasterHotbar`（`CrewHotbar` 实现）、`StationMasterMenu`（F 菜单）、`StationMasterBoard`（任务板）、`StationMasterDisplay`（快捷栏样子、Boss 栏与侧边栏内容）、`PassengerClearing`（清客的站）。
- `drive/hud/CrewMarks`：只画给本人的站位标线与车厢罩子。
- `command/FtaStationMasterCommand`：命令。
- `drive/crew/StationMasterShift`、`StationMasterScore`、`StationMasterRecordCodec`；`drive/session/StationMasterSettlement`；`drive/license/StationMasterExam`、`StationMasterExams`。

## 当班体力

`drive/crew/CrewSustain`：驾驶员、车掌、站长、信号员当班时（领了任务还没开始的不算，见 `DriveSessionManager#isOnShift`），生存与冒险模式每秒喂饱、按 `config.yml` 的 `crew.sustain.health-per-second`（默认 1，半颗心）回血。不是无敌，一击打死照样下岗。`crew.sustain.enabled` 关闭。

## 未做

- 公开 API 事件（上岗、下岗）。
- 提前关门帮列车赶回的秒数没有单独记分。
