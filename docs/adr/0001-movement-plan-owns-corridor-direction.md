# 规范行车计划拥有走廊方向

同一信号周期内，single conflict 方向由规范 `MovementPlanSnapshot` 唯一确定；当前位置、车尾保护和等待请求必须从该计划派生方向，不能按自己的短路径重新解释 traversal。规范计划对某个 single conflict 缺少方向或标记为 `UNKNOWN` 时，保护请求也保持无方向，不允许局部兜底。占用账本因此保留已有 `MOVEMENT_REQUIRED` 的角色与已知方向，只有新的硬授权在旧 claim 释放后才能建立不同方向；完全相同的 claim refresh 不推进 occupancy version，也不发布占用变化事件。队列心跳可以只更新 `lastSeen`，方向、优先级、稳定 entry order 与道岔签名不变时同样不推进版本。

选择这一方案是因为“每种请求独立推导 + 最后写入者覆盖”会让低权限保护请求反向覆盖出库授权并造成自锁。代价是合法折返必须显式结束旧授权并重新申请，不能依赖后续 retain 隐式翻转方向。
