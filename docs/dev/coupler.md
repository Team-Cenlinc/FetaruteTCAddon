# 连挂与解挂（车钩扳手）

车钩扳手让玩家在现场拆开、连挂列车。调度管理的列车（受管列车）只动不在运营中的；普通 TrainCarts 列车不受这条限制。拆挂由插件自己发起，运行时监听不把它当事故：不隔离、不销毁、不全网先停车，拆挂后两车下一 tick 按现场车身重新占用。

## 使用

- 领取：`/fta coupler wrench`（`/fta coupler` 显示用法）。权限 `fetarute.coupler`，默认 OP，并入 `fetarute.admin`；不要求驾驶证。驾驶员、车掌值乘中的玩家不能用（驾驶侧拦下他们的交互）。
- 扳手是带 PDC 标记 `coupler_wrench` 的绊线钩（`WrenchItem`），物品模型 `fetarute:coupler/wrench` 给材质包用。拿着扳手右键车厢不会上车。
- 右键车厢时取玩家视线与车厢轴线（车厢中心 ± 半车长，沿 `MinecartMember#getOrientationForward`）的最近点（`WrenchAim`）：离某一端不到 1.5 格（短车厢按半车长的 45% 收窄）算点中那一端，其余是中部。

| 操作 | 效果 |
|---|---|
| 右键端车外端 | 开启或锁闭该端车钩 |
| 右键两节车厢的连接处 | 在此解挂；解挂处两端车钩随即锁闭 |
| Shift+右键车钩已开启的端车 | 把整列车慢速推向前方列车并连挂（对方相对一端的车钩也要开启） |
| 右键车厢中部 | 显示车厢序号、编组、两端车钩、能否操作 |

手持扳手时每半秒在 16 格内列车外端冒粒子（只给手持扳手的玩家看）：绿色开启、红色锁闭。

配置 `coupler:`：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 扳手能不能用；关掉后右键车厢不起作用，拆挂留下的同股道列车对照常维护 |
| `max-cars` | `0` | 连挂后最多几节；`0` 只受 TrainCarts 自己的上限 |
| `push-max-blocks` | `8` | 推车时两车空隙最多多少格 |
| `push-speed-blocks-per-second` | `1.0` | 推车速度 |

## 能不能动（`CouplerEligibility`）

受管列车（带 FTA 运行时标签）须同时：静止；没有驾驶员会话、没有车掌；不在停站计时、没挂发车门控；没有叫车标签、没有提前出车扣车；没绑时刻表的班次；并且是下面之一：

1. 终点待命车：在 `LayoverRegistry` 里，没有派车在进行；
2. 回库车：交路运营类型为 RETURN，且已过最后一个载客站（`RouteTerminals.outOfService`）。

其余受管列车一律拒绝，提示具体原因（语言键 `coupler.deny.<原因>`）。车库里没有“停放”状态（回库车到车库即销毁），车库里活着的车都已派了票，按运营中拒绝。非受管列车只要静止就能动。开闭车钩、解挂、推车三种操作用同一条判定。

## 车钩与编组（车厢级标签）

标签记在车厢自己身上（TrainCarts 的车厢级标签，随车厢走）：

- `FTA_COUPLER_FRONT` / `FTA_COUPLER_REAR` = `open`：车厢前端/后端车钩开启，前后按车厢自身朝向，列车调头不影响；不写 = 锁闭（`CouplerState`）。
- `FTA_CAR_CONSIST` = `<编组键>|<来源编组 ID>|<来源编组节数>`：车厢的来源编组，第一次用扳手时整列打上（`CarTags#stampOrigins`，编组键取列车的 `FTA_SPAWN_PATTERN`，没有时为 `?`）。

拆挂后按车头到车尾各节的来源重算列车的 `FTA_SPAWN_PATTERN`（`CarConsist#derive`）：连续同一来源、节数等于它整列的是一个完整来源编组；全是一个 → 原编组键；几个完整的拼成 → `A+B`；有不完整的写成 `A(3/6)` 这样，并打 `FTA_CONSIST_PARTIAL`。不完整编组只接回库票（`CouplerService#allowsLayoverDispatch`）。

用过扳手的列车（有车厢带上面任一标签）碰上别的车时，`CouplerLinkGate` 在 `GroupLinkEvent` 上看离得最近的两节端车：相对的两端车钩都开启才让 TrainCarts 挂上，否则取消。没用过扳手的列车保持 TrainCarts 原样（碰上即挂）。

## 预期内的拆分与联挂（`dispatcher/runtime/coupling`）

TrainCarts 的拆分（`MinecartGroup#split`）与联挂（`MinecartGroupStore#link`）都是同步调用，成员移除、编组移除与联挂事件都在调用里发出。插件发起时用 `CouplingOperations#begin` 登记涉及的编组（按对象身份），调用返回即撤销；`RuntimeDispatchListener` 见到登记过的编组：

- `onMemberRemove`：不隔离、不排“意外拆分”检查；
- `onGroupLinkVeto` / `onGroupLink`：不否决、不触发全网 STOP_FIRST 重建；
- 编组移除（联挂时被并掉的那组）：不走按物理身份的移除，由发起方按车名清理。

拆挂后要求调度按现场车身重新占用：`CouplingRuntime#requestPhysicalRehydration` 清掉这列车的已水合标记（RDS 构造时以方法引用接入，不新增 RDS 方法），下一次信号检查走迟加载单车水合（`replaceTrainPhysicalFootprint`，保留其它列车的占用，现场重叠照样记）。

### 解挂（`TrainSplitter`）

在第 `at` 节之前拆开：含原车头车的一半（TrainCarts 的源编组）保留原身份。受管列车解下的一半：

- 取新车名：保留原车名前缀、按新运行 ID 重取 4 位序号，撞名换运行 ID 重取（最多 8 次）；取不到时报 `RENAME_FAILED`，它仍带着原车身份，调度按重复列车处理。
- 改写标签（`SplitTagPlan`）：换新 `FTA_TRAIN_NAME`、`FTA_RUN_ID`、`FTA_TRAIN_UID`；删掉时刻表交路、叫车、提前出车扣车、发车票、回滚墓碑、发车控制类瞬时标签与同股道列车对标签；交路身份与进度照搬。停在终点的照常登记为待命车。
- 两半下一 tick 刷新信号。

非受管列车照 TrainCarts 原样拆，拆下的一半不归调度管。

### 同股道列车对（`CoLocatedPairs`）

解挂后两车停在同一段轨道上，闭塞模型表示不了“同一段轨道上前后两列车”，两车会互相挡住。拆开后（等两车按现场车身重新占用，最多重试 6 次）取两车共占的资源登记成对，各记一个离开方向（自对方车身中心指向本车车身中心的水平方向）。`SimpleOccupancyManager` 经拆挂服务接入的 `CoLocationPolicy`（`setCoLocationPolicy`）判对方在共占资源上的占用：只有请求方这一段运动方向（首条有向边起止节点的位置差）与离开方向夹角余弦不小于 0.25 时不挡，其余照常；方向或位置取不到、策略出错时不豁免。效果：靠外的一列可以先开走，靠里的一列等对方出清。

- 发车票：交路第 0 个路径点走最短路到第 1 个的第一条边（与占用判定认行进方向的口径相同）朝对方开的一方不接票。不完整编组（待命登记标签快照里有 `FTA_CONSIST_PARTIAL`）只接回库票；拆挂后刷新留下那一半的待命登记快照。
- 维护：每秒把共占集收窄到当前的交集，为空或一方不在了即解除。
- 重启：对写在两车标签 `FTA_COLOCATED=<对方车名>|<x>,<z>`，每 30 秒按标签找回一次：两车互相记着且仍共占资源就恢复；两车都已占用却不再共占任何资源时删掉标签。

### 联挂（`TrainLinker`）

被接上的一列（停着不动的一方）保留身份：车名、交路、进度、待命登记都沿用它的；挂过来那列的身份作废（`handleTrainRemoved`）。先让存活身份当场按合并后的车身占用、再放掉作废身份的占用，中间不留没有占用的车身。TrainCarts 联挂时较大一方的列车属性会整份装进存活编组并对调车名，所以联挂前先保存两边的车厢级标签与被接上那列的列车级标签，联挂后改回车名、按保存的值改写；被接上的是待命车时刷新待命登记的标签快照。受管与非受管列车不连挂。

## 解编指令 DECP（`DecoupleService`）

交路备注 `DECP` 写在停车的那一站（写法见 `route-operations.md`）。解编协作者挂在交路节点监听上（与换模器用 `RouteNodeListener#andThen` 并成一个，不加 RDS 方法）：

1. 到站回调记一条待执行，那一站多停 `decouple-seconds`（车站由 AutoStation 取走，路径点停车加在停车时长上）。之后每 2 tick 看一次：车停稳、停站计时开始后动手（停站计时一直没开始时停稳 3 秒；路径点停稳 1 秒）；60 秒内没停稳（有人值乘时 180 秒）、车已离站就放弃。表上这一班不拆时到站就放掉。
2. 切点（`DecoupleCut`）：车头＝运行前端，按车厢来源编组判，切点须在分界上、切下的全是完整来源编组；指定那头不行改拆另一头（节数相同），两头都不行就不拆、整列继续本交路并告警 `DECP_FAILED`。与编表推车型的 `CouplingPlanner.cut` 同一条规则。
3. 拆：走预期内拆分通道，没被指定的部分保留原身份。切车尾直接拆；切车头用 `TrainSplitter#splitKeepingRear`（先调头、拆、两半各调回来）。两车照常登记成同股道列车对，背向对方的一方先走。
4. 解下的车写 `FTA_DECP_HOLD=<交路>|<下标>|<站台节点>`，登记成本站待命车（主干停站结束才就绪）：
   - 按表运行（主干车绑着交路）：表上这一班生出子交路才拆（`TimetableService#splitPlanOf`），子交路接它（`pinSplit`：有班次的子交路首班只接它、它只接子交路的票；只回库的子交路直接绑上，回库票带走它）。拆不开时子交路记为落空（`splitFailed`），首班票到期照常登记取消。10 分钟没被接走同样落空，车成普通待命车。
   - GOTO：出一张目标交路的票，只给它接（候选过滤），120 秒没被接走撤票，车成普通待命车。发车计划跳过 GOTO 指向的交路（`StorageSpawnManager#selectSpawnRoutes`）。
   - IDLE：普通待命车。
   - RETURN：交给回收立即派回库（`ReclaimManager#returnCalledTrain`），派不出去 5 秒后再试，5 分钟后放手。
   - 等着被接走的车回收不收（`heldForCall`）。
5. 值乘的人（`DecoupleCrew`，驾驶员与车掌两方经 `DecoupleCrew.all` 合起，每次调用再取驾驶模块此刻的实现）：
   - 拆之前：驾驶员在这一站开过门（不开门的站台停稳即算）后，这一趟像到了终点一样在这一站结算（`DriverTaskManager#completeHere`，原因 `divide`）；车掌不在中途结算，只等这一站开过门。各方都好了才拆（`DecoupleService#dividesHere` 告诉驾驶侧这一站要拆）。结算后没能拆开（编组对不上等）时 `notSplit`：驾驶员从这一站接着记本趟。
   - 拆开后：在登记待命、出票之前交给值乘的人选择（`SplitChoices`），聊天栏三个按钮 `/fta drive split keep|switch|end`（车掌 `/fta guard split …`），说明写“前 4 节，开往南渡”（GOTO 写目标交路终点站，按表写子交路首班终点，IDLE 写本站待命，RETURN 写回库）。选择期间两段车都扣着：留下的一段经停站扣留（与联挂扣留并在 `setCouplingHold`），解下的一段不接票（`allowsLayoverDispatch`），回收也不收；扣到选择期限，最长 60 秒，过了结束值乘（防挂机），两段车都交给调度。选择期间驾驶员、车掌的座位可能随解下的一段分开，不按离座、列车不在处理。
   - keep：驾驶室随解下的一段分开时送进留下一段车头（车掌车尾）的驾驶室；驾驶员从这一站接着记本趟（新任务，来源 `continuation`，单独计分）。还没选就开车视为 keep。
   - switch：驾驶员交还留下的一段（结束原因 `SPLIT_SWITCH`，留下的一段由调度控车），送进解下一段发车端的驾驶室后开始驾驶（不另要任务，`SplitChoices.Switch` 放行接管检查）；那一段开出别的车次时记成新任务。车掌按做过的站结算后结束，送进解下一段车掌那一头重新上岗。期间解下的一段扣 15 秒。
   - end：结束值乘，两段车都交给调度。
6. 站牌与站台屏（`DivisionForecast`）：解编服务每秒按交路进度给每列运行中的车预测下一个分行站（此刻停着的这一站只在还没拆时算；路径点上的解编、按表时表上这一班不拆、按现场编组切不开的不算），两段车头在前、各自分行后开往的车站（`DivisionDestinations`：留下的一段是本交路运营终点，解下的一段见上；不载客时为空）。主线程整份换上，`EtaApi.BoardRow#division` 按车名、交路与停靠序号挂上（分行站不在本站之前）。站台屏的车厢页时段先放分行页（再是空位页），到发行备注在末班之后写“分行”（本站分行时“本站分行”）与解下那一段的去向。折返站上按下一趟列出的来车，开出下一趟之前不带分行。
7. 重启：待命登记只在内存里，解下的车照搬了主干的交路与进度。信号检查见到 `FTA_DECP_HOLD`、车还停在记下的那一站就重新登记成待命车（`DetachedHold#restore`）；车被派上别的交路后标记作废、见到就删。重启后只给它接的票与子交路的对应都没了，它按普通待命车处理。

## 联挂指令 COPL（`CoupleService`）

交路备注 `COPL FROM:<交路>[:WAIT:<秒>]` 写在主干的联挂站（写法见 `route-operations.md`）：回程支线车与主干挂成一列，沿用主干的身份。`CouplingIndex` 由交路缓存建（交路变了重建）：哪条主干在第几站挂哪条回程支线（同线同组按交路码找、终点须是联挂站），主干那一站是不是写死的股道。

先到的车在站台等，后到的车开进同一条股道挂上（按表时回程支线车先到）：

1. 等：回程支线车终到联挂站、登记成待命车后，主干会来就扣住它（`FeederHold`：发车票不接、回收不收）——按表时等表上那一班主干（`TimetableService#joinTrunkOf`，主干跑过那一班、那一班取消或 15 分钟后放手），否则有主干正开往本站时等 WAIT。主干正常到了联挂站（没在进站时挂上）：回程支线车在别的股道等它的放掉；回程支线车正开往本站时停站扣留最多 WAIT（`TrunkWait`，经 `StationStopCoordinator#setCouplingHold`，不超过发车扣留上限 150 秒）。那一站多停 `couple-seconds`。
2. 选台（`DynamicPlatformAllocator.CouplingPartners`，经停靠协调器接入）：后到的车遇 DYNAMIC 优先选对方停着的那条股道，对方的占用与预订对它不算外部占用。主干是 DYNAMIC 停靠、回程支线车还没到但正开往本站时，主干先不选台（`defer`，分配结果为阻塞 `coupling-partner-pending`），在站外等它最多 WAIT，过了照常选台。主干停的是写死的股道时，回程支线车选台优先停那条。
3. 站外：后到的车照常被对方的占用挡在站台外。停稳一秒、交路还没到联挂站、驶向对方那条股道（交路下一个目标或 TrainCarts 目的地）、离对方不超过 `approach-max-blocks`（沿调度图）、车头朝着对方、合并后不超过节数上限，才开始进站。
4. 进站：后到的车登记调车中（调度让出控制，照常排进路、写目的地），这一对之间的占用互相不挡（与同股道列车对并成一个 `CoLocationPolicy`）。调度把进路排进对方的资源或排到那条股道后（10 秒内没排进来就放弃，5 秒后可再试），由 `ApproachAction` 推着走：以 `approach-speed-blocks-per-second` 起步，沿轨道（`PushTargetFinder`，每 4 tick 看一次，近了每 tick）看得见对方、空隙小于 6 格后降到 `push-speed-blocks-per-second`，空隙不超过 0.25 格停车（`ApproachRules`）。看不见对方时（道岔还没扳到）照常往前走，但不超过“图上距离 + 对方车身 + 8 格”；先碰到别的列车、对方动了、推不动、超时都停车放弃。推的期间 AutoStation 不处理它。
5. 挂上（`CouplerService#linkInService`）：把主干当作被接上的一方交给 `TrainLinker`，主干保留车名、交路、进度与编组对象，回程支线车的身份作废。回程支线车先到时，挂上后对合并的列车重新触发那一站的车站牌子（`SignAction.executeAll`），照常记到站、开门、停站、发车，这一站不再多停 `couple-seconds`（进站已经用掉了）。主干先到时它本来就在停站。
6. 退路：没开动就放弃的，5 秒后可再试；开动后失败的，两车登记成同股道列车对（背离对方的一方先走），双方的等候都放掉，各走各的。插件停用时进行中的进站全部停车放弃。
7. 值乘的人（`CoupleCrew`，驾驶员与车掌两方合起）：进站与挂车由调度控车。开始进站前（`yieldBeforeApproach`，同步办完）：后到的车上、回程支线车上的驾驶员把这一趟在这里结算（原因 `couple`）后结束驾驶（`COUPLING`），回程支线车上的车掌结束值乘（它的身份挂上后作废）。挂上后（回程支线车先到时在重新触发车站牌子之后）：主干原有的驾驶员不在车头那一端时换到车头，车掌不在车尾时换到车尾；合并的列车没人驾驶时，把让出控制的驾驶员（原在主干上的优先）送回发车端驾驶室，从这一站接着记车次。开动后失败时告诉他们不再交还；没开动就放弃、等着再试的，3 分钟没挂上同样不再交还。

## 推车（`ShuntPusher`）

Shift+右键车钩已开启的端车：

1. 沿轨道找前方的车（`PushTargetFinder`）：从端车中心朝开启的一端用 TrainCarts 的 `TrackWalkingPoint` 每 0.25 格走一步（弯道、坡道照走，道岔按此刻的开向），碰到的第一节别的列车的车厢就是对方；它须是那列车的端车，空隙不超过 `push-max-blocks`。
2. 两车都要能动、同为受管或同为非受管、对方相对一端车钩开启、合起来不超过 `max-cars`、被推的列车除了等待动作（停站留下的 `WaitAction`）没有别的动作在执行。受管列车还要求推车路径守得住（`CouplerService#pushPathGuarded`）：沿途轨道方块经 `LiveRailFootprintResolver` 映射出的联锁区（道岔、交叉）都已被两车之一占着、没有别的列车占着；两车之间是一段被两端车身堵住的轨道，别的列车只能经联锁区进来。联锁目录不全或读不到图时不推。
3. 被推的一列清掉等待动作，让开启的端车成为车头（必要时调头；TrainCarts 调头只反转车厢顺序与行进方向，不改车厢朝向，车钩前后不受影响），限速抬到推车速度，挂上推车动作（`GroupAction`，逐 tick 定速）。两列车都登记为调车中（`ShuntingRegistry`）：`PluginControlAuthority` 把它们当作人工控制，调度不写限速、不发车、不刹车，发车票与回收也不接它们。
4. 空隙小于 0.25 格时停车，等列车停稳后还原限速、撤销调车登记，按联挂流程挂上；挂上后接口两端车钩锁闭。推过“起始空隙 + 1 格”仍没接上、空隙比起始时大出 0.5 格（往反方向推）、40 tick 几乎没动或超时、任一方不在了，都停车报失败，调回原朝向、补回等待动作，受管列车按现场车身重新占用。看门狗每半秒查一次：推车动作被别处清掉、列车被卸载、或过了期限（推车超时再加 100 tick）动作仍没收尾时照样收尾，调车登记一定撤销。

## 测试

- `CouplingOperationsTest`：登记只在范围内有效、按对象身份认、嵌套范围只撤自己加的。
- `CoLocatedPairsTest`：离开方向判定、豁免只在朝离开方向时生效、信息缺失不豁免；`SimpleOccupancyManager` 集成：配对前互挡，配对后靠外一列能开走、靠里一列仍被挡。
- `SplitTagPlanTest`、`CouplerEligibilityTest`、`CouplerPairTagTest`、`CarConsistTest`、`WrenchAimTest`、`CarEndsTest`：纯计算部分。
- `CouplingBypassListenerTest`：监听器的四个入口（联挂否决、联挂、成员移除、编组移除）对登记过的编组跳过事故处理、对没登记的照旧（入口按对象接收编组，不必初始化 TrainCarts 静态类型）。
- `CouplingIndexTest`：联挂索引；`ApproachRulesTest`：进站每一拍的判定与速度；`TimetableJoinRuntimeTest`：按表时谁等谁；`DynamicPlatformAllocatorTest#aCouplingPartnerPlatformIsSelectableOnlyForItsPartner`。联挂进站与重新到站要真实的 TrainCarts 编组，靠实验服验证。
- `DecoupleCutTest`：切点规则；`TimetableSplitRuntimeTest`：按表时子交路首班等主干、只接解下的车、拆不开就落空；`StorageSpawnManagerTest#decoupleTargetsAreNotSpawnedOnTheirOwn`。
- `SplitChoicesTest`：分行后结算、限时选择、改驾三步的状态与两段车扣到几点；`CrewCompositionTest`：驾驶员与车掌合起后的判定、扣留与通知。
- 推车动作要真实的 TrainCarts 编组（单测里 `MinecartGroup` 无法构造或 mock），靠实验服验证。
