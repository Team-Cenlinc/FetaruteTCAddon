# Route Editor 交互

## 右键追加节点

当玩家手持由本插件生成的运行图编辑书时：

- 右键 `waypoint/autostation/depot` 牌子：追加对应 nodeId。
- 右键牌子对应的轨道：同样追加对应 nodeId。
- 右键 `[train]/[cart] switcher` 牌子或其轨道：追加 switcher 的 nodeId（形如 `SWITCHER:<world>:x:y:z`）。

若目标附近未找到可解析节点，会提示“附近未找到可解析的节点牌子”。

## 书写规则

- 每行一个 stop 或 action；空行与 `#`、`//` 开头的行忽略。动作（`CHANGE`/`DYNAMIC`/`ACTION`）附着到上一个 stop 的备注。
- **起步线路**：第一站之前允许写一条 `CHANGE:<运营商>:<线路>`，表示列车起步即按该线路对乘客运营（详见 `docs/design/route-stop-markers.md` §1.1）：

  ```
  CHANGE:SURC:WS
  STOP DYNAMIC:SURC:S:NTA:[1:3]
  STOP SURC:S:HHU:1
  CHANGE:SURC:MT
  ```

  - 解析后并入首站备注；出车与折返复用都按它写线路标签，交路与管理归属不变。
  - 第一站之前出现 `DYNAMIC`/`ACTION` 报 `action-first`；出现多条 CHANGE，或与首站下一行的旧写法 CHANGE 并存，报 `change-ambiguous`。
  - 旧写法（CHANGE 写在首站下一行）继续接受，存储结果相同。
- **回显归一**：`/fta route editor give`（按数据库停靠表渲染）与 `define` 后归档的成书回显时，首站的 CHANGE 一律渲染成第一行（首站那一行之前），中途站的 CHANGE 写在所属站之后；读一遍再写回去，旧写法的书就归一到新写法。`/fta route editor edit` 只是把归档成书转回书与笔并保留原页面，不重新渲染，旧归档书在下一次 `define` 后归一。
- `/fta route define` 与 `/fta route debug` 的解析结果回显里，首站的有效 CHANGE 显示为“起步线路=运营商:线路”，不显示成到站换线。

## 交互拦截

右键轨道时客户端可能会触发 `RIGHT_CLICK_AIR`，导致书本界面先弹出。
插件会在识别到轨道/牌子后主动取消交互，并在下一 tick 关闭书本界面以兜底。
