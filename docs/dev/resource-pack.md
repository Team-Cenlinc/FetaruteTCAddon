# 插件物品的材质包（resource-pack）

插件发给玩家的物品（车钩扳手、驾驶台快捷栏与菜单、车掌快捷栏与菜单）都是原版物品，另在 `minecraft:custom_model_data` 的第一个字符串里写上标记 `fetarute:<键>`（`utils/PackModel`）。

- 没装材质包：显示原版物品（扳手是绊线钩，驾驶台、车掌按染料颜色区分，压力表与故障指示是彩色玻璃板），“亮起”的状态另加附魔光效。
- 装了材质包：材质包改写这些原版物品的定义（`assets/minecraft/items/<原版物品>.json`），按标记选自己的模型；对不上的照旧显示原版。

不用 `minecraft:item_model` 组件：它指向的定义在客户端不存在时（没装材质包，或材质包里没有这个键）显示紫黑方块。

素材不放本仓库，在服务器的私有材质包里；仓库只记标记与写法。

## 原版物品的定义怎么写

每种原版物品一个文件，按标记分支，对不上的用原版模型：

```json
{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:custom_model_data",
    "index": 0,
    "cases": [
      { "when": "fetarute:coupler/wrench", "model": { "type": "minecraft:model", "model": "fetarute:item/coupler/wrench" } }
    ],
    "fallback": { "type": "minecraft:model", "model": "minecraft:item/tripwire_hook" }
  }
}
```

插件用到的原版物品：`tripwire_hook`；`cyan_dye`、`gray_dye`、`light_blue_dye`、`lime_dye`、`orange_dye`、`purple_dye`、`red_dye`、`yellow_dye`；`gray_stained_glass_pane`、`lime_stained_glass_pane`、`red_stained_glass_pane`、`yellow_stained_glass_pane`。同一个标记会随状态落在不同的原版物品上（例如车门开着是绿色染料、关着是灰色），所以每个文件都列全部标记最省事。

和别的材质包一起用时：两个包改写同一种原版物品，只有排在上面的那个生效，需要把两边的 `cases` 合进一个文件。

## 从旧写法迁移（`scripts/resourcepack-items-to-cmd.py`）

旧写法是插件写 `item_model`，材质包每个键一份定义 `assets/fetarute/items/<键>.json`。脚本把这些定义里的 `model` 原样搬进上面那 13 个原版物品的文件（模型在哪个命名空间、怎么写都不用改）：

```bash
python3 scripts/resourcepack-items-to-cmd.py <材质包源目录>
```

- 原版物品的文件已经存在、又不是脚本生成的，不覆盖，报错退出（需要手工合并）；重复运行覆盖上一次生成的。
- `--materials` 只改写指定的几种（例如只做扳手：`--materials tripwire_hook`）。
- 旧的 `assets/fetarute/items/` 不再被用到，可以留着当脚本的输入。
- 以后加新键：在 `assets/fetarute/items/` 照旧加一份定义，再跑一次脚本。

## 标记一览

| 物品 | 原版物品 | 标记 |
|---|---|---|
| 车钩扳手 | 绊线钩 | `fetarute:coupler/wrench` |
| 驾驶台快捷栏（档位） | 绿／灰／橙／红色染料（牵引、惰行、制动、紧急制动） | `fetarute:drive/<档位小写>`，例如 `drive/p1`、`drive/n`、`drive/b4`、`drive/eb` |
| 驾驶台菜单按钮 | 各色染料 | `fetarute:drive/panel/<按钮>`，键名见 `MenuLayout#modelKey`（`reverser_f[_sel]`、`door_l_on|off`、`key_on|off`、受电开关、`mode_ato|manual`、`end[_confirm]` 等；有故障时去掉 `_on`／`_off` 加 `_fault`） |
| 驾驶台压力表、故障指示 | 彩色玻璃板 | 见 `GaugeView#modelKey`（`drive/panel/gauge_<表>_on|fault`，耐久条当指针）、`FaultPanelView#modelKey` |
| 车掌快捷栏与菜单 | 各色染料 | 车门沿用 `drive/panel/door_l_*`、`door_r_*`；其余 `fetarute:drive/guard/<键>`（`door_close[_on]`、`report[_used]`、`confirm_stop|go|done`、`buzzer[_on]`、`emergency[_on]`、`seat`），结束值乘沿用 `drive/panel/end|end_confirm` |

各物品对应的原版物品见 `WrenchItem`、`HotbarItems#materialOf`、`DriveMenuItems#materialOf`、`DriveGaugeItems`、`GuardHotbar#materialOf`、`GuardMenu#materialOf`；加了新的原版物品要同步脚本里的 `MATERIALS`。任务卡用原版书本，不写标记。
