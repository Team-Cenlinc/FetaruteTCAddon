# 多模车（多受电方式）

一列车可以有几种受电方式，按交路备注在途中换模，例如有轨段 5 米网、干线段 6 米网，电力加内燃双源，接触网加超级电容，第三轨加接触网。

各模式**性能一样**：控车曲线、编表走行时分、到站预测都不按模式区分。换模只影响表现——受电弓升降、手动驾驶的启动流程与车上系统（电制动、受电中断、超级电容），
以及涉及内燃的换模在停车点多停的时间。

## 车上：受电方式标签

- `FTA_TRAIN_POWER`：写一个是单模车（与原来一样）；写多个（逗号、加号、斜杠或空白分隔）即多模车，如 `ptg6,diesel`，**第一个是默认模式**，
  最多 3 种（驾驶台菜单第三排只有 3 个受电方式按钮，`/fta train config set --power` 超过 3 种时拒绝）。
- `FTA_TRAIN_POWER_ACTIVE`：多模车当前用的受电方式，换模时改写，随车走（重启、改名照认）；没记或记的不在列表里时取第一个。
- `/fta train config set --power ptg6,diesel` 写标签并回到默认模式；`/fta train config show` 的受电方式一栏多模车写成 `ptg6,diesel → diesel`（箭头后是当前模式）。
- 读取：`TrainPower#of`（当前模式）、`#supplies`、`#multiMode`；`PowerSupply#parseList`、`#selfPowered`（内燃、超级电容不靠外部供电）。

## 交路：POWER 备注

- 写在停靠点（含 PASS 路径点）之后：`POWER ptg6`，存为 `POWER:ptg6`，与 CHANGE 同为动作行（`RouteStopDirectives` 白名单、`FtaRouteCommand.ACTION_PREFIXES`）。
- 含义：从这一点起应当用这种受电方式（`RoutePowerModes#modeAt`：此前含此点最后一条认得出的 POWER）。交路到这里还没写过 POWER 时沿用列车当前的模式，
  所以建议在首站写明出发模式。
- 电对电（受电弓高度、受电弓与第三轨、接触网与超级电容）**经过即换**；涉及内燃（启停发动机）**只在停车时换**：写在不停车的路径点上的推迟到其后第一个停车点。
- 涉及内燃的那一站停站多加 `RoutePowerModes.DIESEL_SWITCH_SECONDS`（25 秒）：编表（`TimetableTimingCalculator#resolveDwellSeconds`）、
  到站预测（`RouteStopPlan#plannedDwellSec`）、现场停站（AutoStation 在晚点压缩之后加，不会被压掉；区间停车点同样）同一口径（`RoutePowerModes#switchDwellSeconds`）。
  交路首站不加；交路开头还不知道列车用什么时，换成内燃按要换算、换成电力按不用换算。改了 POWER 后需重新编排时刻表。
- 校验：受电方式认不出时 define 与 validate 报错；涉及内燃写在 PASS 上、写了 POWER 而首站没写时 validate 提示（不拦保存）。
- 推算一遍出整份停靠表的加时（`RoutePowerModes#switchDwellPlan`），到站预测按停靠表缓存，编表连续查同一份停靠表时只推算一次；
  备注里没有 POWER 字样的停靠点不走指令解析。

## 运行时：换模器

`dispatcher.power.PowerModeSwitcher`（单模车一律不动）：

- 出车、终点接续（`SimpleTicketAssigner#addDispatchObserver`）：按交路起点（区间生成按入路下标）应当用的模式换好，在站内，内燃也能换。
- 到达或经过交路节点（`RuntimeDispatchService#setRouteNodeListener`，紧接 CHANGE 之后）：换成这一点起应当用的模式；涉及内燃而这里不停车时推迟。
  车站停站多停的秒数由 AutoStation 向换模器取走（`takeStationDwell`），区间停车点由回调返回值加到停车时长上；
  这个秒数按交路与节点下标记，同一节点的重复到达事件不清掉它，车开到别的节点才作废。
- 车上没有交路要的模式：照旧跑，记 `POWER_MODE_SKIP reason=unsupported:<模式>`（同车同原因只记一次）。换模记 `POWER_MODE_SWITCH from= to= index= reason=dispatch|node`。
- 受电弓（只做多模车）：按当前模式升起对应的一组（`ptg5`/`ptg6`）、降下其余，**出车即升弓**；已是这个状态的不动，所以每个节点都可以调用，
  附件重新挂载（模型回到默认姿态）后下一次经过节点时补上；出车时附件多半还没挂载，出车后 20 tick 再按当前模式补一次。当前用超级电容时不动受电弓（充电升弓归站台 animate 牌子，发车降弓归 `SupercapPantograph`）。
  共用的升降与"升没升"判断在 `dispatcher.sign.action.Pantographs`（看队尾同名动画、没有时看当前动画；TrainCarts 放完动画仍保留它作为当前动画）。

## 手动驾驶

有人驾驶的车升降弓归驾驶会话，换模器不动受电弓。每经过一个交路节点，换模器都把这一段要求的受电方式交给会话
（`DriveSessionManager#powerSectionReached`；交路还没写 POWER、车上没有这种方式时交空，清掉上一段的要求，免得换交路后残留）。

- **电对电**（两级都一样）：经过换模点由调度直接换，驾驶会话跟着换（`DriveSessionManager#powerSwitchedByDispatch`），
  受电接通着时降下原来的弓、升起新的，提示"已切换为……受电"。
- **涉及内燃**：standard 级同样由调度在停车点直接换；simulation 级由驾驶员**停车后在驾驶台菜单第三排**换（会话记下这一段要求的方式，
  动作栏与侧边栏提示）。没换而当前方式要靠接触网或第三轨时没电（牵引封锁，`DriveSession#noLinePower`）；内燃、超级电容在哪都能走。
- **驾驶台菜单第三排最后三格**是车上的第 1、2、3 种受电方式（单模车是玻璃板）：当前用的亮着，点另一种换过去（菜单只在停稳时打开，内燃也能换）；
  simulation 级受电按新方式重新接通（耗时同启动流程的受电一步），期间不能牵引，standard 级直接换。这一段要求的方式在说明里标出。
  在交路上换成别的方式时，经过下一个节点会被调度按交路要求换回（simulation 级涉及内燃的除外）。贴图沿用受电开关那一套（`powerKey`）。
- **侧边栏**：多模车多一行"受电"，写当前方式的简称，这一段要求换别的方式时后面跟"→ 要换成的方式"。
- 右键不用于换模（右键是警惕、信号与 ATO 发车的确认键）。
- 启动流程：`TrainSetup#switchSupply`（冷车只换方式；接通着时调度换模直接换、驾驶员在 simulation 级换模重新受电；换成电力时主断随辅助电源的状态接通，
  换成非电力时没有主断）。调度换模不等正在接通的系统：那一步作废，一键启动接着进行。重新受电期间驾驶员离座，受电与其后的系统一并断开。
- 车上系统：`CabSystems#vehicleChanged`（电制动、恒功率拐点）、`CabFaults#setElectric`（换成非电力时解除受电中断、主断跳闸）；换到超级电容时补上储能（按满电）。
- 自由驾驶（FREE）没有交路，不知道区段要求，不换模。
