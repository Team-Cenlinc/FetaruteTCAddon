# 规范行车计划拥有走廊方向

同一信号周期内，新 single conflict 方向由规范 `MovementPlanSnapshot` 唯一确定；当前位置、车尾保护和等待请求必须从该计划派生方向，不能按自己的短路径重新解释 traversal。列车位于两个 route waypoint 之间时，物理资源窗口从已验证的 `lastPassedGraphNode` 起算，但方向解析继续使用当前 canonical route leg 的上游语义锚点；该方向上下文不产生 NODE/EDGE/CONFLICT 占用。若车尾保护资源已经滑出当前前向计划，非硬请求可沿用同车在该资源上由上一份硬授权提交的已知方向；这是 committed Movement Plan 的历史证据，不是局部路径兜底。资源既不在当前计划内、也没有同车已知 claim 时仍保持 `UNKNOWN`。

占用账本保留已有 `MOVEMENT_REQUIRED` 的角色与已知方向，普通 refresh 不能建立相反方向。合法终点折返通过专用 Authority Handoff 在同一占用事务中暂时隐藏自身旧 claim、检查外部 claim/Gate Queue，并原子写入反向硬授权；拒绝时恢复旧状态。成功时只有与新窗口重叠的资源被替换，未获 rear-clear 证明的旧物理 footprint 转为内部 `PHYSICAL_FOOTPRINT` 硬占用，不发布虚假的 release，也不参加普通 `PROTECTIVE_RETAIN` 的跟驰放宽或 stale 清理。列车重命名迁移同一份 claim/queue/lock owner，禁止 `acquire(old) -> releaseByTrain(old) -> launch(new)`。完全相同的 claim refresh 不推进 occupancy version，也不发布占用变化事件。队列心跳可以只更新 `lastSeen`，方向、优先级、稳定 entry order 与道岔签名不变时同样不推进版本。

选择这一方案是因为“每种请求独立推导 + 最后写入者覆盖”会让低权限保护请求反向覆盖出库授权并造成自锁；先 release 再 acquire 又会给平交道口留下可被同步事件或其它列车插入的空窗。代价是合法折返必须走显式原子交接，不能依赖后续 retain 隐式翻转方向。
