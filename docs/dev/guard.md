# 车掌

调度列车上可以有一名车掌，与驾驶员或 ATO 搭档。车掌坐车尾驾驶室；有车掌时车门归车掌：停妥后开门，停站时可下到站台，停站时间到关门并监视，门全关后回座、确认出站信号开放、按发车铃。驾驶员（或 ATO）收到发车信号才起步。

## 上岗与离岗

- `/fta guard on`：坐在调度列车（`ManagedTrains.isFtaManaged`）车尾驾驶室的座位上执行。驾驶座的认定与驾驶员相同（`drive.yml` 的 `driver.cab-seat-names`，没有标记时按车厢位置）；车尾端（`CabSeats.End.TAIL`）才行，单节车坐另一头的驾驶室即可。一列车只有一名车掌；正在驾驶的玩家不能当车掌，车掌也不能同时驾驶（`drive.command.start.guard-on-duty`）。
- `/fta guard off` 结束值乘，`/fta guard seat` 传送入座，`/fta guard status` 查看，管理员 `/fta guard stop <玩家>` 撤下。车掌菜单（F）里“结束值乘”点两次也结束。
- 权限 `fetarute.drive.guard`（默认 OP，含在 `fetarute.drive.player` 打包节点里）。
- 值乘结束（含离线、死亡、列车不在了、漏乘、连续超时、换端没坐进车尾）时车掌开着的门随之关上，车门交还驾驶员或站台；站台的停站发现车门不归车掌了，剩下的开关门与发车按原来的方式进行。

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
- 铃（`BuzzerPress`）：按住约 0.8 秒（`buzzer-long-ticks`）为一长；一短之后 1 秒（`buzzer-double-ticks`）内再按一下为呼叫。车掌一长＝发车信号；一短＝收到；两短＝呼叫驾驶员。驾驶员的铃是丢弃键（Q，驾驶中本来不用）：一短收到、两短呼叫车掌。铃声（`sounds.buzzer`）只给这列车上的驾驶员与车掌听。
- 车掌菜单（F，27 格）：传送入座、呼叫驾驶员、异常报告、结束值乘。
- 离座：停稳且车门开着时 Shift 才放行（下到站台监视），行驶中、车门关着时拦下；在站台上右键自己的列车回到车掌座位。列车开走时车掌不在车上、离每节车厢都超过 48 格：漏乘，值乘结束（`LEFT_BEHIND`）。

## 紧急停车与监视

- 紧急停车（快捷栏 9 号，车掌阀）：只在列车行驶中有效（停着时不按发车铃列车就不会开）。人工驾驶的车把驾驶员的手柄拨到 EB（等同驾驶员自己拉 EB，不记防护介入、不扣驾驶员的分），由驾驶员停稳后缓解；自动运行（含 ATO）的车立即停住并扣着：扣着期间 `DriverControlRegistry#isDriverControlled` 为真，调度不替它起步、健康层不当它停滞，车掌停稳后按住发车铃一长声解除（`drive.guard.emergency.released`），或扣满 `emergency-hold-seconds`（默认 120）自动解除，解除时立即重算一次信号。车掌离岗时扣着的一并解除。驾驶员动作栏同时提示。
- 关门监视：关门动画放着时每 5 tick 采样一次（`GuardWatch#closingWatch`）：不在车上、离自己那节车厢不超过 `watch-radius-blocks`、视线水平方向与车身夹角不超过 `watch-angle-degrees`（朝车头或车尾都行）。合格的采样不少于 `watch-ratio` 算合格；动画放完时动作栏告知结果。
- 出站监视：车掌放行、列车开出这一站时开始，到车头走过“车长 + `departure-watch-extra-blocks`”（直线距离）或 `departure-watch-max-seconds` 为止，每 5 tick 采样一次（`GuardWatch#departureWatch`）：坐在车掌座位上，视线朝站台一侧或朝车后（车掌驾驶室面朝的方向），夹角不超过 60°。结束时告知结果。
- 两种监视的结果记在这一站的 `GuardStopWork` 里，供成绩使用。

## 终点站换端与座位预留

- 车掌坐在发车端的另一头（`GuardCabChange`）。发车端：有驾驶员（人工或 ATO）时跟驾驶员的换端判定（`GuardCabChange#fromDriver`：换端时按他要换到的那一端，不换端时按他坐的车厢）；只有车掌时，终点站待命按线路图预计（`TerminalCabEnd`，待命期间只查一次），其余时候车头端发车。单节车不换端。
- 放行前已知下一趟由车掌这一端发车：聊天栏告知换到另一头（`drive.guard.cab-change.announce`），不计时。派车放行、列车调头后车掌预留的座位不在车尾端：在换端时间预留（与驾驶员相同，`driver.cab-change` 段按车长算）内坐进车尾端驾驶室；超时、或人工驾驶的驾驶员先开了车，直接送进去，送不了值乘结束（`CAB_CHANGE`）。
- 换端途中车门关着也可以下车；在站台上右键要换到的那一端的车厢入座。菜单“传送入座”或 `/fta guard seat` 直接送过去（时间够时可选）。
- 一起传送：驾驶员被直接送进发车端（准备时间不足、换端超时、点了直接换端）时，车掌一起送进另一头（`GuardSessionManager#moveWithDriver`）；车掌被直接送过去时，驾驶员也在换端就一起送（`DriverSide#moveWithGuard`）。同一拍里先请坐在对方要去那一端的人下车，再分别入座，两人不会抢同一个座位。
- 座位预留：车掌值乘期间，别人（乘客、驾驶员）坐不进车掌预留的座位（TrainCarts `MemberBeforeSeatEnterEvent`，`GuardSessionManager#blocksSeat`）；换端开始时预留让出来，坐进另一头后预留改到新座位。左右车门的记录随车掌面朝的方向对调（`DriveDoors#followCab`）。
- 换端扣车：车上没有人工驾驶的驾驶员（只有车掌，或 ATO 驾驶员）时，从终点站待命起扣着列车（`GuardLink#cabHold`，按驾驶员控制处理）；派车放行时只调头、不发车（只有车掌时由 `GuardLink#takeTurnback` 取走调头标记），车掌坐进车尾端（或不必换）后解除并重算信号，交回自动运行发车。人工驾驶由驾驶员自己起步，驾驶员动作栏提示车掌换端与就位。

## 显示

- 车掌侧边栏（与驾驶员同一套 `DriveSidebar`，标题 `drive.guard.sidebar.title`）：列车、值乘（驾驶员 / ATO / 自动运行）、车站、作业与剩余秒数、出站（等发车时）、车门、超时站数。
- 动作栏：这一步要做的事（`GuardDisplay#prompt`）；按钮的结果停留 2 秒不被覆盖。
- 驾驶员那边：车掌上岗、离岗、发车信号、呼叫、应答、报告都在动作栏提示（`drive.guard.driver.*`）。
