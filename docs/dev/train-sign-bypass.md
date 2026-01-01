# 列车牌子旁路（FTA_BYPASS）

本插件提供一个运行时监听器：当列车带有 `FTA_BYPASS` tag 时，屏蔽绝大多数 TrainCarts 牌子对该列车的控制，仅保留：

- `destroy`：销毁列车（兜底清理）
- `switcher`：道岔控制（进路/物理执行）

该功能适用于“只想让列车按既定 destination/控制逻辑运行，但不希望被站台/发车/脚本等牌子干预”的场景。

## 行为说明

- 触发点：TrainCarts 触发 `SignActionEvent` 时（列车经过/进入牌子触发范围）
- 判定条件：列车 `TrainProperties` 匹配 tag `FTA_BYPASS`
- 执行方式：在 `EventPriority.LOWEST` 先行 `event.setCancelled(true)`（便于其他监听器感知取消），并在 `EventPriority.HIGHEST` 再次兜底取消（防止后续监听器反取消）
- 白名单：当牌子类型为 `destroy` 或 `switcher` 时不会被取消

注意：这是“全局旁路”——被取消后，不仅本插件的 waypoint/autostation/depot 牌子不会执行，TrainCarts 以及其他插件注册的牌子行为也会被跳过。

## 使用方法

给目标列车添加 `FTA_BYPASS` tag（由 TrainCarts 管理列车属性）：

- 可通过 TrainCarts 的命令为列车添加 tag（具体命令随版本可能略有差异）
- 或在列车保存配置中添加对应的 tags 字段

若要恢复正常牌子控制，移除该 tag 即可。

## 调试

当 `debug.enabled=true` 时，监听器会输出被屏蔽的牌子触发日志（包含列车名、触发 action、牌子类型与坐标），便于排查：

- TrainCarts 可能会对同一块牌子触发多次事件（例如 `member_enter` 会按每节车厢触发一次），因此理论上会出现重复日志。
- 监听器仅在 `member_enter` 且触发成员为列车“车头（head）”时输出一次，因此同一块牌子只会打印一次日志。

- 实现位置：`src/main/java/org/fetarute/fetaruteTCAddon/dispatcher/sign/TrainSignBypassListener.java`
