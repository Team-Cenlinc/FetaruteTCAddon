# 调度上下文

本上下文描述 Fetarute 调度层如何把线路计划转换为资源授权、保护占用与可见信号，并保持 TrainCarts 控车状态一致。

## Language

**规范行车计划（Movement Plan）**：
列车在单个信号周期内的规范化有向路径快照，是展开路径、单线方向与道岔路径签名的唯一来源。
_Avoid_: 临时路径、当前位置路径

**前进授权（Movement Authority）**：
允许列车进入一组 `MOVEMENT_REQUIRED` 资源的硬许可；只有前进授权能够建立或改变当前 traversal 的已知方向。
授权边界来自规范行车计划中的有向选定路径：即使 `RailGraph` 是无向图，也不得把邻域 BFS 扫到的分支或远端物理边提升为本车授权。交叉、回库与支线进路通过双方选定路径实际共享的 EDGE、NODE、switcher 或桥链 single-section 资源互斥；仅处于同一非桥网格不能构成硬冲突。
_Avoid_: 保护占用、预测结果

**保护保留（Protective Retain）**：
对列车当前位置或车尾物理资源的尽力保留；它优先继承本周期规范行车计划的方向。当车尾资源已经滑出当前前向计划时，可沿用同车在该资源上由上一份硬授权提交的已知方向，但不能按局部路径重新推断，也不能改写已有前进授权。
_Avoid_: 前进授权、方向锁

**物理足迹（Physical Footprint）**：
折返授权交接后、列尾尚未被证明离开旧进路时保留的硬占用。它可以覆盖 edge、node 或 crossing/switcher conflict，不参加普通 Protective Retain 的跟驰放宽，也不能由 stale-retain 自愈提前释放。
同一列车连续折返时，每次 handoff 建立新的 traversal epoch 并封存旧 epoch；后续节点只推进最新方向，避免支路汇流节点伪造旧方向里程。跳点或路径外观测缺少有向子段证据时 fail-retain，倒退会回退净清界进度；已清界的一代也只能释放不再被其他代引用的资源。
_Avoid_: 前瞻保护、超时占用

**走廊方向（Corridor Direction）**：
列车在某个 single conflict 内当前获准的 traversal 方向；同一 claim 生命周期内保持稳定，换向必须先结束旧授权再重新申请。
_Avoid_: HUD 上下行方向、Minecart 朝向

**授权交接（Authority Handoff）**：
列车停稳折返时，把与新窗口重叠的旧方向 claim 原子替换为反向 Movement Plan 的硬授权；外部 blocker 存在时完整保留旧状态，成功时未获 rear-clear 证明的旧进站 edge/咽喉/道口继续作为 Physical Footprint，直到正常推进释放。列车显示名变化必须迁移同一份授权 owner，不能通过 `releaseByTrain` 重新取得。
_Avoid_: 普通 claim refresh、健康恢复清理

**扣停（Hold）**：
面向展示与估算的概念：列车处于非例行停车（信号、占用、授权、尾保、安全状态不可用等），或例行停站/门控已超出正常站内用时。
由 `EtaService#currentHold` 统一判定，ETA 顺延与公开 API 扣停事件共用；不参与控车。开始时刻跨运行时停车状态替换连续计算。
_Avoid_: 计划扣留（那是时刻表让早到列车在站等点，属于例行停车）、STOP 生命周期（那是控车侧状态，换原因就重置）
