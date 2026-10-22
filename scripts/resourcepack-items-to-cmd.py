#!/usr/bin/env python3
"""把材质包里插件物品的定义改成按 custom_model_data 选模型的写法。

插件物品（车钩扳手、驾驶台、车掌）是原版物品，在 minecraft:custom_model_data 的第一个字符串里写标记
fetarute:<键>（见 docs/dev/resource-pack.md）。材质包要改写这些原版物品的定义，按标记选模型。

本脚本读材质包源目录里的 assets/fetarute/items/**/<键>.json（旧写法：插件写 item_model 时每个键一份定义），
把每份定义的 "model" 原样搬进 assets/minecraft/items/<原版物品>.json 的一个分支，对不上的照旧用原版模型。
模型放在哪个命名空间、怎么写（普通模型、组合、按条件切换）都不用改。

用法：
  python3 scripts/resourcepack-items-to-cmd.py <材质包源目录>
  python3 scripts/resourcepack-items-to-cmd.py <材质包源目录> --materials tripwire_hook

原版物品的定义文件已经存在、又不是本脚本生成的（例如材质包自己改写了 lime_dye）时不覆盖，报错退出，需要手工合并。
重复运行会覆盖上一次生成的文件。旧的 assets/fetarute/items/ 不再被用到，可以留着也可以删。
"""

import argparse
import json
import pathlib
import sys

NAMESPACE = "fetarute"

# 插件物品用到的原版物品：车钩扳手、驾驶台与车掌的染料、压力表与故障指示的玻璃板。
# 与代码里的 materialOf 同步（WrenchItem、HotbarItems、DriveMenuItems、DriveGaugeItems、GuardHotbar、GuardMenu）。
MATERIALS = [
    "tripwire_hook",
    "cyan_dye",
    "gray_dye",
    "light_blue_dye",
    "lime_dye",
    "orange_dye",
    "purple_dye",
    "red_dye",
    "yellow_dye",
    "gray_stained_glass_pane",
    "lime_stained_glass_pane",
    "red_stained_glass_pane",
    "yellow_stained_glass_pane",
]


def read_definitions(pack):
    root = pack / "assets" / NAMESPACE / "items"
    if not root.is_dir():
        sys.exit(f"找不到 {root}：材质包里没有插件物品的定义")
    definitions = {}
    for path in sorted(root.rglob("*.json")):
        key = path.relative_to(root).with_suffix("").as_posix()
        try:
            model = json.loads(path.read_text(encoding="utf-8"))["model"]
        except (ValueError, KeyError) as ex:
            sys.exit(f"{path} 读不出 model：{ex}")
        definitions[NAMESPACE + ":" + key] = model
    if not definitions:
        sys.exit(f"{root} 下没有定义")
    return definitions


def generated_by_us(path):
    try:
        model = json.loads(path.read_text(encoding="utf-8"))["model"]
    except (ValueError, KeyError):
        return False
    return (
        model.get("type") == "minecraft:select"
        and model.get("property") == "minecraft:custom_model_data"
        and all(
            str(case.get("when", "")).startswith(NAMESPACE + ":")
            for case in model.get("cases", [])
        )
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pack", type=pathlib.Path, help="材质包源目录（含 pack.mcmeta）")
    parser.add_argument(
        "--materials",
        nargs="+",
        default=MATERIALS,
        help="只改写这几种原版物品（默认插件用到的全部）",
    )
    args = parser.parse_args()

    definitions = read_definitions(args.pack)
    out_dir = args.pack / "assets" / "minecraft" / "items"
    targets = [out_dir / f"{material}.json" for material in args.materials]
    conflicts = [path for path in targets if path.exists() and not generated_by_us(path)]
    if conflicts:
        sys.exit(
            "这些原版物品的定义已经存在、不是本脚本生成的，需要手工合并：\n  "
            + "\n  ".join(str(path) for path in conflicts)
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    cases = [{"when": tag, "model": model} for tag, model in sorted(definitions.items())]
    for material, path in zip(args.materials, targets):
        definition = {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "cases": cases,
                "fallback": {"type": "minecraft:model", "model": "minecraft:item/" + material},
            }
        }
        path.write_text(json.dumps(definition, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(definitions)} 个标记写进 {len(targets)} 种原版物品的定义：{out_dir}")


if __name__ == "__main__":
    main()
