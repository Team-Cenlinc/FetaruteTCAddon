package org.fetarute.fetaruteTCAddon.display.pids.map;

import com.bergerkiller.bukkit.common.map.MapDisplayProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/**
 * 站台屏的展示框操作：找墙、铺地图、还原、认出屏幕地图。
 *
 * <p>只在主线程调用；查找实体只看已加载区块（{@code getNearbyEntities} 不会加载区块）。
 *
 * <p>一块屏幕的每个展示框放的是同一个地图物品（同一个地图 UUID），BKC 据此把相邻展示框拼成一块大显示，并按位置分配每块的画面。
 */
public final class PidsFrames {

  /** 地图物品上记屏幕 ID 的属性键。 */
  public static final String SCREEN_PROPERTY = "pidsScreen";

  private final Plugin plugin;

  public PidsFrames(Plugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 展示框所在的方块。 */
  public static PidsScreen.Position position(ItemFrame frame) {
    return new PidsScreen.Position(
        frame.getLocation().getBlockX(),
        frame.getLocation().getBlockY(),
        frame.getLocation().getBlockZ());
  }

  /**
   * 以某个展示框为中心、同一朝向的展示框，按所在方块索引。
   *
   * @param reach 各方向的查找半径（方块）
   */
  public static Map<PidsScreen.Position, ItemFrame> wallAround(ItemFrame center, int reach) {
    Map<PidsScreen.Position, ItemFrame> wall = new HashMap<>();
    for (ItemFrame frame :
        framesNear(center.getWorld(), position(center), reach, center.getFacing())) {
      wall.putIfAbsent(position(frame), frame);
    }
    return wall;
  }

  /** 展示框里没有物品。 */
  public static boolean isEmpty(ItemFrame frame) {
    return frame.getItem().getType().isAir();
  }

  /** 新建一个指向屏幕的地图物品；同一块屏幕的所有展示框共用这一件。 */
  public ItemStack mapItem(UUID screenId) {
    MapDisplayProperties properties = MapDisplayProperties.createNew(plugin, PidsDisplay.class);
    properties.set(SCREEN_PROPERTY, screenId.toString());
    return properties.getMapItem();
  }

  /** 把地图放进展示框，摆正并隐藏边框。 */
  public static void fill(Collection<ItemFrame> frames, ItemStack mapItem) {
    for (ItemFrame frame : frames) {
      frame.setItem(mapItem.clone(), false);
      frame.setRotation(Rotation.NONE);
      frame.setVisible(false);
    }
  }

  /** 取下地图并恢复边框。 */
  public static void restore(ItemFrame frame) {
    frame.setItem(null, false);
    frame.setVisible(true);
  }

  /** 屏幕在已加载区块里的展示框：按框里地图指向的屏幕认，而不是按位置（同一位置可能已换成别的东西）。 屏幕跨区块时只取已加载的那部分，其余等区块加载时再处理。 */
  public static List<ItemFrame> loadedFrames(PidsScreen screen) {
    World world = Bukkit.getWorld(screen.worldId());
    if (world == null) {
      return List.of();
    }
    Optional<UUID> id = Optional.of(screen.id());
    int reach = Math.max(screen.tileRows(), screen.tileCols()) + 1;
    return framesNear(world, screen.center(), reach, screen.facing().blockFace()).stream()
        .filter(frame -> screenIdOf(frame.getItem()).equals(id))
        .toList();
  }

  /** 地图物品指向的屏幕 ID；不是站台屏地图时为空。 */
  public static Optional<UUID> screenIdOf(ItemStack item) {
    if (item == null || item.getType() != Material.FILLED_MAP) {
      return Optional.empty();
    }
    MapDisplayProperties properties = MapDisplayProperties.of(item);
    if (properties == null
        || !PidsDisplay.class.getName().equals(properties.getMapDisplayClassName())) {
      return Optional.empty();
    }
    return parse(properties.get(SCREEN_PROPERTY, String.class));
  }

  static Optional<UUID> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /** 朝向一致的展示框。 */
  public static boolean faces(ItemFrame frame, PidsFacing facing) {
    return frame.getFacing() == facing.blockFace();
  }

  private static List<ItemFrame> framesNear(
      World world, PidsScreen.Position center, int reach, org.bukkit.block.BlockFace facing) {
    BoundingBox box =
        BoundingBox.of(
            new org.bukkit.util.Vector(center.x() + 0.5, center.y() + 0.5, center.z() + 0.5),
            reach,
            reach,
            reach);
    return world.getNearbyEntities(box, entity -> entity instanceof ItemFrame).stream()
        .map(ItemFrame.class::cast)
        .filter(frame -> frame.getFacing() == facing)
        .toList();
  }
}
