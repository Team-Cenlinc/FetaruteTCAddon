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
- 推车动作要真实的 TrainCarts 编组（单测里 `MinecartGroup` 无法构造或 mock），靠实验服验证。
