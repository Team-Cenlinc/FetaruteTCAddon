# 2026-09-05 RVS—PPK 拥堵诊断与修复

## 判断与证据边界

最新日志支持“局部停滞导致后方持续排队”，不足以证明在线车数已经超过线路容量。修复前已通过真实调度入口复现：普通 PASS 到达时，下一动态站台暂不可用会使到达索引丢失；容量恢复后再次下发已经到达的 PASS 点。这种故障一辆进站车加一个临时站台占用条件即可触发。

现场日志中的明确等待边是 `SURC-MT-LP-4671 → SURC-MT-LP-0638`。0638 持有的 RVS NODE/EDGE 保护资源持续阻塞 4671。日志没有完整保留 0638 最初的停车原因，且旧节点触发日志不含列车名，因此不能把离线复现机制直接当成此次现场的唯一根因，也不能认定存在反向等待边。

诊断基线：Git `3ea0a376246618007fc67af147993b227bb670e8`，主源码与部署包 `sourceFingerprint=dbbfd1233f97`。日志副本为 5,214 行、2,407,762 字节，SHA-256 为 `67ded6b1b086baa08ef43facec2d701bb52c70a21563866cdd32cff6495a2e10`。日志中观察到在线峰值 18 列，首次长期停滞告警前最近快照为 7 列；这些数字不构成吞吐上限测量。

## 已修复的行为

| 问题 | 修复后的行为 | 回归入口 |
| --- | --- | --- |
| PASS 到达后选台失败，索引仍停在上一站 | 先提交到达索引与实际节点，再评估下一跳；容量恢复后目标是下一站台 | `waypointMemberEnterRecordsArrivalBeforeWaitingForDynamicCapacity` |
| 容量等待已撤队，资源释放却只唤醒队首，静止车还会被周期巡检跳过 | 单独登记候选站台 NODE；释放后安排下一 tick 完整重评估 | `platformReleaseWakesUnqueuedCapacityWaiterOnTheNextTick` |
| 提前选台时，受阻站台与当前位置之间还有 PASS | 分配器返回实际受阻目标索引，与当前窗口索引分别保存；资源释放仍能唤醒 | `platformReleaseWakesCapacityWaiterBeforeAnIntermediatePassNode` |
| 唤醒与实际执行之间站台再次被占用 | 重新核验现场占用并保持 STOP；下一次释放后再授权 | `capacityWakeupRechecksOccupancyWhenThePlatformIsTakenBeforeTheNextTick` |
| PASS 已到达，但缺图时沿用旧放行 | 保留到达事实，撤销旧授权并硬停车；图恢复后重新授权 | `waypointMemberEnterKeepsArrivalAndStopsWhenGraphIsUnavailable` |
| 前向路径不可构建时可能跳过到达提交 | 已到达索引保留，保持 STOP；恢复完整路径后继续下一跳 | `waypointMemberEnterKeepsArrivalWhenTheOutgoingPathCannotBeBuilt` |
| 动态实际股道被声明占位节点覆盖 | 在同一份进度快照中提交实际股道，例如 PPK 2 不再记成 PPK 1 | `dynamicStationArrivalRecordsTheActualPlatformInsteadOfThePlaceholder` |
| 到达入口交路缺失时只返回 | Waypoint 车头、普通推进和 AutoStation 到达均立即安全停车，不猜测新索引 | 三个 `*StopsWhenRouteDefinitionIsUnavailable` 用例 |
| 当前等待车授权失效，被诊断成前车受后车阻塞 | 单独使用 `CURRENT_TRAIN_AUTHORITY_FALLBACK`，另一车标记为实际 `blockerTrain` | `currentAuthorityFallbackDoesNotReverseWaiterAndBlocker` |
| 普通诊断预算吞掉首次到达、停因和解除 | 生产端去重的到达、STOP enter/transition/clear 逐次保留 | `preservesArrivalAndStopLifecycleBoundariesAfterObservationBudgetIsExhausted` |
| 资源真正释放或角色变化也被普通预算丢弃 | 逐次保留语义变化，稳定 claim refresh 不重复输出 | `preservesActualClaimChangesAfterBudgetExhaustionWithoutRepeatingStableRefreshes` |
| blocker 枚举顺序变化制造伪状态跃迁 | 对资源、owner 和角色去重排序；实际 owner 变化仍产生新事件 | `blockerEnumerationOrderDoesNotCreateANewStopLifecycle` |

到达流程统一使用 `RouteProgressRegistry.recordArrival`；不依赖 RVS/PPK 站名特判。初始化与交路移交继续使用已有恢复入口。STOP/TERM 的整组到齐、停站、居中和折返时序保持原有合同。

容量等待仍保留本车原有 NODE/EDGE/CONFLICT 与外车占用，不因更新进度就释放车尾保护。空闲站台仅因咽喉忙碌时，仍选台并进入普通 FIFO 授权；不把正常排队当成容量耗尽，也不靠清 claim、缩小安全间距或删除列车解决本次问题。

## 新增排障证据

- `SMART_ROUTE_ARRIVAL`：规范列车名、交路、前后索引与实际 `arrivedNode`，证明到达已提交；重复到达状态不重复输出。
- `SMART_STOP_LIFECYCLE`：首次停因、原因变化、解除条件与解除事件。前向 DYNAMIC 选择无可用目标时，detail 包含各候选首个拒绝原因；节点占用含 owner/role。候选拒绝证据不进入真实授权 blocker 集合。
- `SMART_RESOURCE_LIFECYCLE`：真实账本资源创建、释放和 owner/角色/方向变化，保留前后状态；重复刷新由占用生产端抑制。
- 健康告警包含 `train`；通用节点触发包含 TrainCarts 车名与事件类型，跨改名时需关联规范 owner。
- 恢复目标与另一辆证据车按 `blockerTrain`、`evidenceWaiter`、`evidenceFollower`、`counterpartTrain` 区分。只有真实 blocker 快照才能支持等待边。

普通 signal/resource snapshot 观察日志仍受预算限制。本次保留到达、停车与资源变化边界，未增加逐轨道、逐 tick 的全量物理审计；候选拒绝详情与账本释放事件也不能代替车尾清空证据。

## 验证方式

新增缺陷回归先确认失败，再实现修复；动态容量用例使用真实 `SimpleOccupancyManager`、`RouteProgressRegistry`、`SignalEventBus`、`SignalEvaluator` 与重评估调度器，验证等待、释放、下一 tick 重新授权及原有保护资源保留。改名与推进/移除清理、无关资源释放、唤醒前重新占用也有覆盖。既有运行时、健康监控、动态站台与占用测试继续作为兼容性验证。

仓库全量验证命令：

```sh
./gradlew spotlessApply clean check --offline --no-daemon --console=plain
```

本地全量验证已通过：1,653 项测试，0 失败、0 错误、0 跳过；SpotBugs 主代码与测试代码均为 0 问题。原始独立诊断夹具的两项缺陷断言与两项正常对照由 2 失败 / 2 通过变为 4 项全部通过。原日志是历史证据，不会因源码修改而变成“现场已恢复”的证明。

9 月 5 日候选包为 `build/libs/FetaruteTCAddon-0.0.2.jar`，`sourceFingerprint=a52e152cc5db`，SHA-256 为 `6c2e3d390523af62003af12de50cace8f1f85a369e2372f2087df2234c7b5757`。这是未提交工作区构建；包内 Git `3ea0a37` 仅表示基线，不表示修复已经提交或部署。

### 2026-09-12 复验与停止条件

本轮核实上述修复已存在，未新增业务逻辑或扩展修复范围。重新执行 `./gradlew spotlessApply clean check --offline --no-daemon --console=plain`，1,653 项正式测试全部通过，SpotBugs 主代码与测试代码均为 0 问题。原临时独立夹具已不在，本轮没有重新运行此前的 4 项临时探针，其历史结果不能作为本轮新证据。

重新打包后的当前候选仍位于 `build/libs/FetaruteTCAddon-0.0.2.jar`，源码指纹保持 `a52e152cc5db`，buildId 为 `build-3ea0a37-src-a52e152cc5db-260912`，SHA-256 为 `f723a717fa1204b106f52302e9eb44fe949755da94aeb28f6262379793a93d17`。

停止条件以已复现缺陷及其回归清单为界：代码验收通过后停止修改，不继续推测性排查或增加架构。当前代码验收已完成；实服行车验收仍待下面的现场证据，本轮未提交或部署。

本次不改变配置键、Sign 语法、数据库结构、发车计划或线路车数。编译与本地测试通过后，真实 Paper / TrainCarts / TCCoasters 行车仍需以下验收。

## 现场验收

1. 用 `/fta info` 保存候选包的 sourceFingerprint，并确认服务器实际加载了该包。不要只比较 Git SHA；未提交构建也可能有不同源码指纹。
2. 在受控场景中让下一动态站台暂时占用，运行一列 RVS → PASS → PPK 进站车。确认 `SMART_ROUTE_ARRIVAL` 已把索引推进到 PASS，随后是带候选原因的 STOP；用 `/fta train debug <train>` 与 `/fta occupancy dump` 留下同一时刻的状态。
3. 让占用车正常离站。关联站台 NODE 的 `SMART_RESOURCE_LIFECYCLE event=release`，确认释放后下一 tick 开始完整重评估，进站车得到新 token，destination 指向所选 PPK 站台，并出现真实位置推进。调度器预算耗尽时允许后续 tick 继续处理；恢复动作“已执行”不能代替真实推进。
4. 使用 `/fta occupancy queue`、`/fta occupancy dump` 和列车诊断关联车尾清空、保护资源释放、后车重新授权。禁止通过手工释放现场 claim 伪造成功。
5. 确认上述故障不再发生后，再在相同路线和运行条件下对比发车间隔，记录站台占用时间、共享咽喉占用时间、后车等待时间及 TPS/MSPT，区分实际容量瓶颈与异常停滞。

SPB—JBS 等其他热点在原日志中仍有实际推进；解锁计划随物理进度变化而撤回不一定是缺陷。本次未据此改动方向推断、联锁范围或销毁策略，不能声称全网吞吐已完成实服验证。
