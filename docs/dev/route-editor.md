# Route Editor 交互

## 右键追加节点

当玩家手持由本插件生成的运行图编辑书时：

- 右键 `waypoint/autostation/depot` 牌子：追加对应 nodeId。
- 右键牌子对应的轨道：同样追加对应 nodeId。
- 右键 `[train]/[cart] switcher` 牌子或其轨道：追加 switcher 的 nodeId（形如 `SWITCHER:<world>:x:y:z`）。

若目标附近未找到可解析节点，会提示“附近未找到可解析的节点牌子”。

## 交互拦截

右键轨道时客户端可能会触发 `RIGHT_CLICK_AIR`，导致书本界面先弹出。
插件会在识别到轨道/牌子后主动取消交互，并在下一 tick 关闭书本界面以兜底。
