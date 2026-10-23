package org.fetarute.fetaruteTCAddon.utils;

import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

/**
 * 插件物品给材质包认的标记：写在物品的 {@code minecraft:custom_model_data} 第一个字符串里（{@code fetarute:<键>}），不改物品本身的模型。
 *
 * <p>没装材质包时显示原版物品（绊线钩、染料、玻璃板等）；材质包改写这些原版物品的定义（{@code assets/minecraft/items/<物品>.json}），
 * 按这个字符串选自己的模型，对不上的照旧显示原版。不用 {@code minecraft:item_model}：它指向的定义在客户端不存在时（没装材质包）显示紫黑方块。 材质包的写法见
 * {@code docs/dev/resource-pack.md}。
 */
public final class PackModel {

  /** 标记的命名空间。 */
  public static final String NAMESPACE = "fetarute";

  private PackModel() {}

  /**
   * 给物品写上标记（覆盖已有的 custom_model_data 字符串）。
   *
   * @param meta 物品元数据
   * @param key 键（{@code fetarute:} 之后的部分，例如 {@code coupler/wrench}、{@code drive/p1}）
   */
  public static void mark(ItemMeta meta, String key) {
    Objects.requireNonNull(meta, "meta");
    Objects.requireNonNull(key, "key");
    CustomModelDataComponent data = meta.getCustomModelDataComponent();
    data.setStrings(List.of(NAMESPACE + ":" + key));
    meta.setCustomModelDataComponent(data);
  }
}
