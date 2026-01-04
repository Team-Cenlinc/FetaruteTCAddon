# 节点牌子注册表与增量同步

本文描述 `waypoint` / `autostation` / `depot` 三类节点牌子的注册表（Registry）、冲突检测、拆牌清理，以及 `rail_nodes` 的持久化策略。

## 目标与边界

- 目标：把“节点牌子”稳定抽象为 `Node`，供调度层与 `/fta graph` 建图/诊断使用。
- 边界：这里只同步节点（`rail_nodes`）。区间距离/边（`rail_edges`）仍需要运维执行 `/fta graph build` 刷新。

## 建牌（注册）

当你放置/编辑一个符合 TrainCarts 牌子格式的节点牌子时（`[train]`/`[cart]` + `waypoint/autostation/depot` + `nodeId`）：

1) 解析：从牌子第 3/4 行解析 `nodeId`
2) 冲突检测：同一个 `nodeId` 不允许被多个方块位置占用
   - 冲突时会显示已占用的位置，并取消本次写入（牌子内容不会被改成“看起来有效但未注册”的状态）
3) 写入：
   - 内存注册表：`SignNodeRegistry`（按 `world + x,y,z` 键控）
   - 存储增量：`rail_nodes`（`upsert`）

## 拆牌（清理）

拆牌清理同时覆盖“TrainCarts destroy 回调”和“玩家拆方块”的两条链路：

- **TrainCarts destroy（首选）**：当牌子被正常移除时，TrainCarts 会回调 `SignAction#destroy()`，插件清理注册表并执行 `rail_nodes` 删除。
- **BlockBreakEvent 兜底**：监听 `BlockBreakEvent(MONITOR, ignoreCancelled=true)`，只在真正破坏成功后清理：
  - 玩家直接打掉牌子本体：按方块坐标命中注册表 → 清理
  - 玩家打掉“依附方块”导致牌子掉落：扫描相邻可能依附的牌子方块 → 清理

另外：TrainCarts 在拆牌时会先读一次 `getRailDestinationName()` 再调用 `destroy()` 清缓存，因此 `getRailDestinationName()` 必须在注册表缺失时也能从牌子文本回退解析。

## 非正常移除（WorldEdit / setblock 等）

WorldEdit、命令替换方块等场景可能不会触发 `destroy/BlockBreakEvent`，造成 **注册表/DB 残留**，进而出现“牌子已不在，但重放仍提示 conflict”。

为降低运维成本，插件在“发生冲突的建牌”时会做一次轻量自愈：

- 仅当冲突位置在 **同一世界** 且其所在 **chunk 已加载** 时，验证旧位置方块：
  - 若旧位置已不是节点牌子：自动清理残留（注册表 + `rail_nodes`），然后允许新牌子注册
  - 若旧位置仍是节点牌子但 `nodeId` 已改变：自动修复旧位置记录（更新注册表 + `rail_nodes`），解除对旧 `nodeId` 的占用
- 不会主动加载区块：避免因为一次建牌触发大量区块加载导致卡服

## 与调度图（RailGraph）快照的关系

- 建牌/拆牌会对 `rail_nodes` 做增量同步，并据此计算节点集合签名（`node_signature`），用于标记 `RailGraph` 快照是否 stale。
- 当 `node_signature` 不一致时，内存中的图快照会被清空，`/fta graph info` 会提示 stale。
- 在 stale 状态下执行 HERE build：命令会优先从存储加载旧图作为 merge base，避免只重建一个联通分量却误删其他分量。

## 运维排查建议

1) 打开调试日志：`config.yml` 中 `debug.enabled: true`，然后 `/fta reload`
2) 复现 conflict 时优先看提示里的位置，确认“占用方块”是否真的仍存在节点牌子
3) 若冲突位置区块未加载：先传送/靠近使其加载，再重试建牌触发自愈

## 手工测试清单

- 正常放置 → 正常打掉牌子本体 → 立刻重放同 `nodeId` 应可注册
- 墙牌/挂牌：打掉依附方块让牌子掉落 → 重放同 `nodeId` 应可注册
- WorldEdit/setblock 删除：在冲突位置 chunk 已加载时，重放同 `nodeId` 应触发自愈并放行
- 改牌文本：把同一块牌子从 `nodeId=A` 改成 `nodeId=B`，应释放 `A`；改成已存在的 `nodeId` 应提示 conflict 且本次写入被取消
