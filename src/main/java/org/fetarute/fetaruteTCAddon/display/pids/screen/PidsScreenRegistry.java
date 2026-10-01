package org.fetarute.fetaruteTCAddon.display.pids.screen;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的屏幕表：按 ID 与按展示框位置两种查找。
 *
 * <p>展示框保护在每次实体交互、伤害事件里都要判断“这是不是某块屏幕的展示框”，位置索引让这一步只是一次哈希查找， 不必解析展示框里地图物品的 NBT。
 */
public final class PidsScreenRegistry {

  /** 展示框位置：世界、方块坐标与朝向。 */
  public record FrameKey(UUID worldId, PidsScreen.Position position, PidsFacing facing) {
    public FrameKey {
      Objects.requireNonNull(worldId, "worldId");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(facing, "facing");
    }
  }

  private final Map<UUID, PidsScreen> byId = new ConcurrentHashMap<>();
  private final Map<FrameKey, UUID> byFrame = new ConcurrentHashMap<>();

  /** 整体替换（启动时从库读入）。 */
  public synchronized void replaceAll(Collection<PidsScreen> screens) {
    byId.clear();
    byFrame.clear();
    screens.forEach(this::put);
  }

  /** 新增或更新一块屏幕；位置变化时同步更新位置索引。 */
  public synchronized void put(PidsScreen screen) {
    Objects.requireNonNull(screen, "screen");
    remove(screen.id());
    byId.put(screen.id(), screen);
    for (PidsScreen.Position frame : screen.frames()) {
      byFrame.put(new FrameKey(screen.worldId(), frame, screen.facing()), screen.id());
    }
  }

  /** 移除一块屏幕。 */
  public synchronized Optional<PidsScreen> remove(UUID id) {
    PidsScreen removed = byId.remove(id);
    if (removed == null) {
      return Optional.empty();
    }
    for (PidsScreen.Position frame : removed.frames()) {
      byFrame.remove(new FrameKey(removed.worldId(), frame, removed.facing()), removed.id());
    }
    return Optional.of(removed);
  }

  public Optional<PidsScreen> find(UUID id) {
    return Optional.ofNullable(id).map(byId::get);
  }

  /** 占用这个展示框位置的屏幕。 */
  public Optional<PidsScreen> findByFrame(FrameKey key) {
    return Optional.ofNullable(byFrame.get(key)).map(byId::get);
  }

  /** 全部屏幕，按创建时刻排序。 */
  public List<PidsScreen> all() {
    return byId.values().stream()
        .sorted(Comparator.comparing(PidsScreen::createdAt).thenComparing(PidsScreen::id))
        .toList();
  }

  public int size() {
    return byId.size();
  }
}
