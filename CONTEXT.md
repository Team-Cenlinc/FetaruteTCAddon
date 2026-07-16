# 调度上下文

本上下文描述 Fetarute 调度层如何把线路计划转换为资源授权、保护占用与可见信号，并保持 TrainCarts 控车状态一致。

## Language

**规范行车计划（Movement Plan）**：
列车在单个信号周期内的规范化有向路径快照，是展开路径、单线方向与道岔路径签名的唯一来源。
_Avoid_: 临时路径、当前位置路径

**前进授权（Movement Authority）**：
允许列车进入一组 `MOVEMENT_REQUIRED` 资源的硬许可；只有前进授权能够建立或改变当前 traversal 的已知方向。
_Avoid_: 保护占用、预测结果

**保护保留（Protective Retain）**：
对列车当前位置或车尾物理资源的尽力保留；它继承规范行车计划的方向，但不能改写已有前进授权。
_Avoid_: 前进授权、方向锁

**走廊方向（Corridor Direction）**：
列车在某个 single conflict 内当前获准的 traversal 方向；同一 claim 生命周期内保持稳定，换向必须先结束旧授权再重新申请。
_Avoid_: HUD 上下行方向、Minecart 朝向
