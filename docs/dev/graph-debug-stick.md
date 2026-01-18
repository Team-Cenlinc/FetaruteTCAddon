# Graph Debug Stick

## 获取道具

```
/fta graph debugstick give
```

## 交互

- 左键牌子或其对应轨道：两次选点后输出 edge 或最短路径摘要。
- 潜行左键：输出该节点信息（不进入两点选择流程）。
- 若点击轨道但附近没有可解析节点，会提示“无牌子”。

## 备注

debug 棍只读取内存中的调度图快照；若图未构建或过旧，请先执行 `/fta graph build`。
