package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 内存中的公告表：启动时整表读入，命令修改时与数据库一起更新。
 *
 * <p>站台屏每次检查都要按车站取生效中的公告，所以不读库。
 */
public final class PidsBulletinBoard {

  /** 轮播顺序：重要的在前，同级按发布先后。 */
  public static final Comparator<PidsBulletin> ROTATION_ORDER =
      Comparator.comparing((PidsBulletin bulletin) -> !bulletin.important())
          .thenComparing(PidsBulletin::createdAt)
          .thenComparing(PidsBulletin::id);

  private final Map<UUID, PidsBulletin> bulletins = new ConcurrentHashMap<>();

  /** 整表替换。 */
  public void replaceAll(Collection<PidsBulletin> all) {
    bulletins.clear();
    all.forEach(this::put);
  }

  public void put(PidsBulletin bulletin) {
    bulletins.put(bulletin.id(), Objects.requireNonNull(bulletin, "bulletin"));
  }

  public void remove(UUID id) {
    bulletins.remove(id);
  }

  public Optional<PidsBulletin> find(UUID id) {
    return Optional.ofNullable(bulletins.get(id));
  }

  /** 全部公告，按发布先后。 */
  public List<PidsBulletin> all() {
    return bulletins.values().stream()
        .sorted(Comparator.comparing(PidsBulletin::createdAt).thenComparing(PidsBulletin::id))
        .toList();
  }

  /**
   * 某块屏幕此刻该轮播的公告，按 {@link #ROTATION_ORDER}。
   *
   * @param station 屏幕绑定的车站
   * @param screenLines 屏幕显示的线路代码；只在有公告限定了线路时才取，至多取一次
   * @param now 当前时刻
   */
  public List<PidsBulletin> active(
      PidsStationKey station, Supplier<? extends Collection<String>> screenLines, Instant now) {
    if (bulletins.isEmpty()) {
      return List.of();
    }
    List<PidsBulletin> active = new ArrayList<>();
    Collection<String> lines = null;
    for (PidsBulletin bulletin : bulletins.values()) {
      if (bulletin.status(now) != PidsBulletin.Status.ACTIVE
          || !bulletin.operatorCode().equals(station.operatorCode())) {
        continue;
      }
      if (lines == null && !bulletin.lines().isEmpty()) {
        lines = screenLines.get();
      }
      if (bulletin.appliesTo(station, lines == null ? List.of() : lines)) {
        active.add(bulletin);
      }
    }
    active.sort(ROTATION_ORDER);
    return active;
  }

  /**
   * 按完整编号或编号前缀（至少 4 位）找公告。
   *
   * @return 匹配的公告；多于一条表示前缀不唯一
   */
  public List<PidsBulletin> matchIdOrPrefix(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String prefix = raw.trim().toLowerCase(Locale.ROOT);
    try {
      return find(UUID.fromString(prefix)).stream().toList();
    } catch (IllegalArgumentException ignored) {
      // 不是完整编号，按前缀找
    }
    if (prefix.length() < 4) {
      return List.of();
    }
    return all().stream().filter(b -> b.id().toString().startsWith(prefix)).toList();
  }

  public int size() {
    return bulletins.size();
  }
}
