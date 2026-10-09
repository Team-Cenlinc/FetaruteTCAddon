# 车掌

调度列车上可以有一名车掌，与驾驶员或 ATO 搭档。车掌坐车尾驾驶室；有车掌时车门归车掌：停妥后开门，停站时可下到站台，停站时间到关门并监视，门全关后回座、确认出站信号开放、按发车铃。驾驶员（或 ATO）收到发车信号才起步。

## 上岗与离岗

- `/fta guard on`：坐在调度列车（`ManagedTrains.isFtaManaged`）车尾驾驶室的座位上执行。驾驶座的认定与驾驶员相同（`drive.yml` 的 `driver.cab-seat-names`，没有标记时按车厢位置）；车尾端（`CabSeats.End.TAIL`）才行，单节车坐另一头的驾驶室即可。一列车只有一名车掌；正在驾驶的玩家不能当车掌，车掌也不能同时驾驶（`drive.command.start.guard-on-duty`）。
- `/fta guard off` 结束值乘，`/fta guard seat` 传送入座，`/fta guard status` 查看，管理员 `/fta guard stop <玩家>` 撤下。车掌菜单（F）里“结束值乘”点两次也结束。
- 权限 `fetarute.drive.guard`（默认 OP，含在 `fetarute.drive.player` 打包节点里；考过车掌附注也给这个节点）。
- 值乘结束（含离线、死亡、列车不在了、漏乘、连续超时、换端没坐进车尾）时车掌开着的门随之关上，车门交还驾驶员或站台；站台的停站发现车门不归车掌了，剩下的开关门与发车按原来的方式进行。

## 车掌任务（`GuardTasks`、`GuardTask`）

- `/fta guard tasks [车站]` 打开车掌任务板（`TaskBoard.Kind.GUARD`，与驾驶员任务板同一套车次与筛选，名额分开算）：左键领取本班车掌，已领的车次红色标出领取人。一个车次一名车掌、一名玩家一个未结束的任务；值乘中、驾驶中或领着驾驶任务时不能领，领着车掌任务或在值乘时也不能领驾驶任务（同一时间只当一个角色）。
- 已领取的任务每秒推进（`GuardSessionManager#tickClaims`，判定在 `GuardTasks#step`）：对上列车后它停在接班站时，动作栏提示坐第几节车尾驾驶室，坐进去即自动上岗（不用再确认）；列车开过接班站、或还没对上列车而计划发车已过 10 分钟即作废。`/fta guard task goto` 在列车旁时直接坐进车掌那一端，`status`、`abandon` 查看与放弃（值乘中放弃即离岗）。也可以先 `/fta guard on`：上岗时列车就是任务的那一列（或正跑着那一班）就接上任务。
- 始发站（接班站序号 0）的终点站待命车：派车前（`DriveSessionManager#allowLayoverDispatch`，驾驶员与车掌各自判定，都放行才派）先扣着一列车等车掌（`GuardTasks#layover`），时限与驾驶员接车相同；通知车掌坐下一趟发车端另一头的驾驶室（分不出发车端时两头都行，`layoverCab`），坐好上岗后只派这一列；扣车期间（车掌已坐进去上岗、这一班还没开出时也一样，`pickupInterest`）这一班的其他候选车也不派，被扣的车不派去跑别的车次、也不给非表定的票（`heldForOther`）；车掌正在别的车上值乘或在驾驶时不扣车；到时限没上岗照常派车，任务作废。上岗被别的插件取消（`GuardDutyStartEvent`）时这一班作废，不每秒重试。开着接车传送时，离得远可用 `goto` 送到驾驶室旁。
- 任务那一趟结算时（终点站换车次、或值乘结束）任务随之结束，记下成绩；没做过作业就结束的按结束原因了结。带交班站的任务（插件派出）在列车到交班站停妥、车门还没开时交班（在交班站按开门键也是直接交班，不由车掌开门）（`EndReason.HANDOVER`，算开完），车门交还站台。插件派任务可关掉奖励（`TaskSpec#rewards`）。

## 公开 API（1.14.0）

- `FetaruteApi#guard()`（`GuardApi`，实现 `GuardApiImpl`）：`offersAt` 列可领车掌的车次、`assign` 派车掌任务（请求写法同驾驶任务，`TaskSpecResolver` 与驾驶任务共用车次与车站核对，可设交班站、关奖励）、`abandon`、`taskOf`/`dutyOf`/`guardOf`（任意线程读快照，`GuardSessionManager#refreshViews` 每 10 tick 与每次发事件前刷新）、`records`/`stats`（`mode = GUARD` 的记录与累计成绩）。
- 事件在车掌模块里当场发出（`GuardSessionManager#callEvent`）：领取（可取消，`GuardTasks#setBeforeClaim`）、上岗（可取消，新上岗结果 `CANCELLED`）、每站作业、发车信号（含站台代发）、紧急停车、异常报告、每趟成绩（只发按时刻表运行的）、任务结束（每个任务一次，`GuardTask#announceFinish`）、离岗。快照换算在 `GuardViews`。

## 站台与车掌

站台把停站交给车掌：与驾驶员控车时同一个 `DriverStationStop`，经 `ControlAuthority#beginStationStop` 交给车上的 `GuardLink`（`DriverControlRegistry` 里与驾驶员链路并列登记，按列车属性对象，换掉时按车名找回）。

- 自动运行（含 ATO 驾驶员）的车照样由站台对位停车、加等待动作，只把车门交给车掌；人工驾驶的车由驾驶员停车，车门同样归车掌（`driverOperatesDoors` 在有车掌时为假，驾驶员的左右门按钮提示 `drive.menu.deny.guard-doors`，驾驶员会话也不再回报车门）。
- 车掌会话每 tick 按站台推进到的阶段回报车门（关门动画放完前仍算开着），站台按它推进：等开门 → 停站（从开门起算）→ 等关门 → 等发车 → 放行。
- 等发车那一步依次过三道（`AutoStationSignAction#crewDeparture`）：出站门控、车掌的发车信号（`ControlAuthority#holdForGuard`，门控不放行时也问，车掌据此暂停计时）、ATO 驾驶员的确认发车（车掌放行之后才问）。人工驾驶放出出站许可等驾驶员起步；自动运行的车与自动运行同一放行动作，列车立即开走，停站阶段置为 `DEPART`，车掌会话在列车开出时结束这一站。
- 停站中途上岗：站台已开着、还没开始关的门交给车掌（`handOverOpenDoors`，与驾驶员中途接管同一套）。
- 站台兜底：车掌开不了门（列车没有车门动画）时，停妥 2 分钟后由站台接手开关门。

## 时限（`GuardStopWork`）

| 步骤 | 从何时起算 | 默认 | 超时 |
|---|---|---|---|
| 开门 | 停妥 | `open-doors-seconds` 30 | 代开站台侧的门，记一次超时 |
| 关门 | 停站时间到 | `close-doors-seconds` 15 | 代关，记一次超时 |
| 回座、出发确认、发车铃 | 门全关；只累计出站放行着的时间（相邻两次询问都放行着，中间那段才算） | `depart-signal-seconds` 15 | 传送回车掌座位、代发发车信号，记一次超时 |

- 异常情况报告（快捷栏 4 号或菜单）：选原因（夹人夹物 / 上下车拥挤 / 乘客求助 / 设备异常），当前这一步延长 `incident-extension-seconds`，每站最多 `incident-max-per-stop` 次；驾驶员动作栏同时显示。
- 一站有超时记一站；连续 `end-after-timeouts`（默认 3）站有超时，值乘结束（`TIMEOUTS`），完成一站不超时即清零。
- 出发确认：等发车那一步、出站放行时按下才算；确认在列车动之前一直有效。
- 发车铃一长：门全关、坐在自己的车掌座位、已确认才算发车信号；发了之后出站一放行就放行。

## 按钮与铃

- 快捷栏（数据包层改写，背包本身不动）：1 开左门、2 开右门、3 关门、4 异常报告、6 出发确认、7 发车铃、9 紧急停车；5、8 空着。左右按车掌座位的朝向算（`DriveDoors` 与驾驶员共用，`DriveDoors.Cab`）。开门键对已开着的一侧无效，关门后再开门照样按开门键。选中格子后右键按下，按住右键时客户端连续发包只算一次；发车铃按住多久响多久。
- 贴图键：车门沿用驾驶台的 `fetarute:drive/panel/door_l_*`、`door_r_*`；其余是 `fetarute:drive/guard/<键>`（`door_close[_on]`、`report[_used]`、`confirm_stop|go|done`、`buzzer[_on]`、`emergency[_on]`、菜单的 `seat`），结束值乘沿用 `panel/end|end_confirm`。没装材质包时按染料区分，“亮起”加附魔光效。
- simulation 级人工驾驶的驾驶员收到发车信号后，要在 `ack-seconds`（默认 5）秒内按丢弃键回一短表示收到（`PendingAcks`，期限另留出认出一短要等的时间；等的期间转成 ATO 的不记），提示写在发车信号那一行（`drive.guard.driver.signal-ack`）；到时没回记一次漏确认（与漏确认信号同一项，扣 5 分）。车掌离岗时不再等。
- 铃（`BuzzerPress`）：按住约 0.8 秒（`buzzer-long-ticks`）为一长；一短之后 1 秒（`buzzer-double-ticks`）内再按一下为呼叫。车掌一长＝发车信号；一短＝收到；两短＝呼叫驾驶员。驾驶员的铃是丢弃键（Q，驾驶中本来不用）：一短收到、两短呼叫车掌。铃声（`sounds.buzzer`）只给这列车上的驾驶员与车掌听。
- 车掌菜单（F，27 格）：传送入座、呼叫驾驶员、异常报告、结束值乘。
- 离座：停稳且车门开着时 Shift 才放行（下到站台监视），行驶中、车门关着时拦下；在站台上右键自己的列车回到车掌座位。列车开走时车掌不在车上、离每节车厢都超过 48 格：漏乘，值乘结束（`LEFT_BEHIND`）。

## 紧急停车与监视

- 紧急停车（快捷栏 9 号，车掌阀）：只在列车行驶中有效（停着时不按发车铃列车就不会开）。人工驾驶的车把驾驶员的手柄拨到 EB（等同驾驶员自己拉 EB，不记防护介入、不扣驾驶员的分），由驾驶员停稳后缓解；自动运行（含 ATO）的车立即停住并扣着：扣着期间 `DriverControlRegistry#isDriverControlled` 为真，调度不替它起步、健康层不当它停滞，车掌停稳后按住发车铃一长声解除（`drive.guard.emergency.released`），或扣满 `emergency-hold-seconds`（默认 120）自动解除，解除时立即重算一次信号。车掌离岗时扣着的一并解除。驾驶员动作栏同时提示。
- 关门监视：关门动画放着时每 5 tick 采样一次（`GuardWatch#closingWatch`）：不在车上、离自己那节车厢不超过 `watch-radius-blocks`、视线水平方向与车身夹角不超过 `watch-angle-degrees`（朝车头或车尾都行）。合格的采样不少于 `watch-ratio` 算合格；动画放完时侧边栏的提示行告知结果，不合格也发到聊天框。
- 出站监视：车掌放行、列车开出这一站时开始，到车头走过“车长 + `departure-watch-extra-blocks`”（直线距离）或 `departure-watch-max-seconds` 为止，每 5 tick 采样一次（`GuardWatch#departureWatch`）：坐在车掌座位上，视线朝站台一侧或朝车后（车掌驾驶室面朝的方向），夹角不超过 60°。结束时告知结果。
- 两种监视的结果记在这一站的 `GuardStopWork` 里，供成绩使用。

## 终点站换端与座位预留

- 车掌坐在发车端的另一头（`GuardCabChange`）。发车端：有驾驶员（人工或 ATO）时跟驾驶员的换端判定（`GuardCabChange#fromDriver`：换端时按他要换到的那一端，不换端时按他坐的车厢）；只有车掌时，终点站待命按线路图预计（`TerminalCabEnd`，待命期间只查一次），其余时候车头端发车。单节车不换端。
- 放行前已知下一趟由车掌这一端发车：聊天栏告知换到另一头（`drive.guard.cab-change.announce`），不计时。派车放行、列车调头后车掌预留的座位不在车尾端：在换端时间预留（与驾驶员相同，`driver.cab-change` 段按车长算）内坐进车尾端驾驶室；超时、或人工驾驶的驾驶员先开了车，直接送进去，送不了值乘结束（`CAB_CHANGE`）。
- 换端途中车门关着也可以下车；在站台上右键要换到的那一端的车厢入座。菜单“传送入座”或 `/fta guard seat` 直接送过去（时间够时可选）。
- 一起传送：驾驶员被直接送进发车端（准备时间不足、换端超时、点了直接换端）时，车掌一起送进另一头（`GuardSessionManager#moveWithDriver`）；车掌被直接送过去时，驾驶员也在换端就一起送（`DriverSide#moveWithGuard`）。同一拍里先请坐在对方要去那一端的人下车，再分别入座，两人不会抢同一个座位。
- 座位预留：车掌值乘期间，别人（乘客、驾驶员）坐不进车掌预留的座位（TrainCarts `MemberBeforeSeatEnterEvent`，`GuardSessionManager#blocksSeat`）；换端时预留只让给这列车的驾驶员（乘客照样坐不进），坐进另一头后预留改到新座位。左右车门的记录随车掌面朝的方向对调（`DriveDoors#followCab`）。
- 正线原地折返（没经过待命的调头）：开始换端时列车还停着，同样扣住（撤掉调头后排上的发车动作），车掌坐进车尾端后重算信号发车；已经开动了才开始换端的直接送进去。
- 换端扣车：车上没有人工驾驶的驾驶员（只有车掌，或 ATO 驾驶员）时，从终点站待命起扣着列车（`GuardLink#cabHold`，按驾驶员控制处理）；派车放行时只调头、不发车（只有车掌时由 `GuardLink#takeTurnback` 取走调头标记），车掌坐进车尾端（或不必换）后解除并重算信号，交回自动运行发车。人工驾驶由驾驶员自己起步，驾驶员动作栏提示车掌换端与就位。

## 成绩、奖励与记录

- 只有做过作业的站才记（`GuardLink.Settled#worked`）：车门放行过、或有超时由站台代做；越站、开门前就结束的停站不计成绩、奖励与考试站数。
- 按车次分趟（`GuardTrip`）：每站作业结算后记进这一趟（站开始时列车跑的车次，按时刻表的列车分配查）；没在停站时每秒看一次车次，换了（终点站折返开下一趟）就把做过作业的这一趟按开完结算；值乘结束时按结束原因结算手上这一趟。站结算时先进 `GuardLink` 的队列，同一拍里下一站开始也不会丢；作业记录到结算时才折成成绩（出站监视离站后还在采样）。
- 计分（`GuardScore`，满分 100，评级阈值与驾驶员共用 `ScoreRules#gradeOf`）：开门超时、关门超时、发车铃超时、开错一侧车门各 5 分；停站时间未到就关门、关门监视不合格、出站监视不合格各 2 分（没采到样的不判）；异常情况报告只记次数不扣分。成绩单每项扣分一行（`drive.guard.sheet.*`），全无扣分时一行说明。
- 终态：开完一趟为完成；车掌自己结束、离线、死亡、游戏模式不允许为放弃；管理员撤下、列车不在了、功能关闭为中断；连续超时、漏乘、换端没坐进车尾为未完成（最高 D）。
- 奖励（`DriveRewards#guard`，单价、评级系数、货币与发放方式沿用 `rewards` 段）：每完成一站作业按驾驶员人工停站单价乘 `guard.reward-per-stop-ratio`（默认 1.0），里程（值乘期间列车车头走过的距离）按驾驶员每公里单价乘 `guard.reward-per-km-ratio`（默认 0.5），再乘评级系数；车掌搭 ATO 也全额。完成、放弃、中断的按做过的站发，未完成的不发（成绩单后说明）。
- 记录：写进 `drive_task_records`，`mode` 为 `GUARD`（`DriveTaskRecord#MODE_GUARD`），明细见 `GuardRecordCodec`；`/fta drive records` 照样列出，驾驶排行与驾驶员的汇总（`/fta drive top`、API 的统计）不算车掌的记录。
- 不按时刻表运行的列车没有车次：整段值乘算一趟，只给成绩，不记录、不发奖励（与驾驶员接管不按表运行的列车不记任务一致）。

## 车掌附注（驾驶证的车掌考法）

- 车掌不是更高一级的驾驶资格，是驾驶证上的附注（`LicenseClass#endorsement`，即 `exam: guard` 的一项）：不占准驾等级的级数（`LicenseConfig#levelOf` 为 0），驾驶证成书与 `/fta license` 里在“准驾等级”之后单列“附注”一栏，只写名字（“车掌”）；提示句子里称“车掌附注”（`LicenseService#displayName`，`drive.license.endorsement-name`）。考过后的下一步提示用 `drive.license.next-endorsement`。

- `drive.yml` 的 `license.classes.guard`：考法 `exam: guard`（`LicenseClass.Exam.GUARD`），不要求先有驾驶证，考过给 `fetarute.drive.guard`。默认做满 `exam-stops`（3）站、及格 `min-points`（70），`allow-wrong-door: false`，报名前要做完 `training-runs`（1）次练习。
- 车掌功能关着或不可用时不能报名考试或练习（`drive.license.exam.guard-unavailable`）；正在驾驶、值乘、领着驾驶或车掌任务时也不能（`LicenseService#crewBusy`）。
- 考试由系统指派：报名（`/fta license exam guard [车站]`）时与路考一样在报名的车站（不写站码时取附近的车站）挑一班车，派一个来源为 `exam` 的车掌任务（`GuardSessionManager#assign`）。车掌在交班站停妥即交班、不做那一站的作业，所以交班站排在第 `exam-stops + 1` 个停车站。挑车跳过马上要开走、已经晚点、已有车掌任务或车掌的车次。报名时说明考哪一班（线路、开往、站台、发车时刻，车次号只作附注，`TaskBoardSource#label`）、考试内容、讲评方式、及格条件、开始方式，并发一本《FTCA 车掌手册》（`DriverHandbook#guard`，`/fta handbook guard` 再领）。列车到站后坐进车尾驾驶室即上岗（与车掌任务板领的任务同一套），考试期间临时挂上车掌权限。任务没上岗就了结（列车没等到、已开走、放弃）时考试不计成绩、可以马上重考（`LicenseService#onGuardTaskFinished`）。
- 练习不派车：玩家先坐上要练的那列调度列车（中途站停站中，或在终点站待命），再输入 `/fta license practice guard`；从这一站起（终点站待命车从它下一趟的始发站起）派一个来源为 `training` 的车掌区间任务（`TaskBoardSource#intervalSpec`），交班站同样排在第 `exam-stops + 1` 个停车站。要求列车在 `training.routes` 里（为空不限）、晚点不超过 `training.drill-max-delay-seconds`、这一趟没有车掌。逐站讲评与考试相同，夹人夹物演练在 `training.drill` 打开时也安排（没按时处置只提醒）；做满站数后按考试标准讲评并计一次练习，临时权限留到值乘到交班站（`guardPracticeJudged`）；中途结束不计次。练习的这一趟不发奖励、不写记录（`GuardTrip#practiced`）。
- 考官只认考试或练习派的那一班（`LicenseService#onExamDuty`：车掌任务在值乘中、来源对得上），另上别的车做的站不算。
- 考官（`GuardExaminer`，驾驶证服务实现）：车掌每做完一站作业（出站监视采完）交给它，逐站讲评开门、关门、关门监视、发车信号、出站监视（`GuardExam#review`）；哪一站超时当场不及格，开错车门按 `allow-wrong-door`，做满站数后按车掌成绩判定（`GuardExam#judge`）。值乘中途结束：连续超时、漏乘、换端没坐进车尾为不及格，其余不计成绩（`GuardExam#ended`）。不及格进入 `retry-cooldown-minutes` 冷却，并在下一拍结束这次值乘（`EXAM`）；及格当场发证，值乘照常继续。
- 夹人夹物演练（`license.classes.<id>.drill-seconds`，车掌考法默认 15，0 为不演练）：报名时在第二站到最后一站里随机排一站（只考一站时就在这一站，`GuardExam#drillStop`）；车掌在那一站按下关门时考官（`GuardExaminer#drillDue`）让站台报告夹人夹物，车掌要在时限内再开车门并报告“夹人夹物”（先后不限，`GuardDrill`），处置完成告知用时，没按时处置当场不及格（`GuardExam#drillMissed`，下一拍结束值乘）。列车晚点超过 `training.drill-max-delay-seconds` 时顺延到下一站，顺延到考完也没演练就不演练；考试中只演练一次。
- 考试中做的这一趟不发奖励（`GuardTrip#examined`），记录照写。

## 显示

- 车掌的提示全在侧边栏（与驾驶员同一套 `DriveSidebar`，标题 `drive.guard.sidebar.title`），不用动作栏：车厢里的乘客信息（`ActionBarTrainHudManager`）与报站（`PidsAnnouncer`）都走动作栏，车掌也会被它们覆盖。乘客的计分板 HUD（`ScoreboardTrainHudManager`）对驾驶员与值乘中的车掌都让位（`DriveSessionManager#usesCrewSidebar`）：车掌也坐在车上，它不让位就会把计分板换成乘客的，`DriveSidebar` 被换掉后让给对方、不再抢回。自上而下：
  - 提示（`GuardSession#showNotice`）：按钮的结果、超时代做、监视结果、演练结果，停留 3 秒，有新提示时下一拍就刷新；超时代做、监视不合格、紧急停车与解除、演练处置完成、驾驶员呼叫另发到聊天框（`drive.guard.alert`）。
  - 演练：考试演练进行中时剩余秒数与再开门、报告两项是否已做。
  - 列车、值乘（驾驶员 / ATO / 自动运行）、车站（停站时带开门方向）或下一站、作业（写明右键哪一格与剩余秒数，`GuardDisplay#stepRow`）、出站（等发车时）、车门、超时站数、换端。
- 上岗前（任务已领、列车到站）的“请坐第几节”、他人坐车掌预留座位的提醒仍用动作栏：那时还没有车掌侧边栏。
- 车掌提示（`GuardTip`，`GuardTips` 记录与弹出）：第一次上岗、开门、关门与关门监视、回座、等候出站信号、出发确认、发车铃、出站监视、终点换端时，各在聊天框与屏幕下方说明一次要做什么；每名玩家每种只出一次（玩家持久数据 `guard_tip_*`），两条之间至少隔 3 秒。与驾驶员的驾驶提示同一套做法，`/fta drive tutorial reset` 一并重置。第一次上岗那条附《车掌手册》按钮。
- 驾驶员那边：车掌上岗、离岗、发车信号、呼叫、应答、报告都在动作栏提示（`drive.guard.driver.*`）。车上有车掌时（`DriverLink#guardAboard`，DSM 每拍按 `DriverControlRegistry#guardOperatesDoors` 更新），驾驶员的车站提示开关门那几步写“车门由车掌开关”、等发车写“等候车掌发车信号”，都不要驾驶员动手（`DriverStationHint.Kind.GUARD_DOORS`/`GUARD_SIGNAL`），开关门的驾驶提示与练习教练也不再教驾驶员开关门；驾驶员第一次与车掌同车时出一次驾驶提示 `guard-aboard`，讲清车门归车掌、等一长声再起步、simulation 级要按一下 Q 键回一短、连按两下 Q 键呼叫车掌。
