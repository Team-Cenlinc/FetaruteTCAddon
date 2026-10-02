package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;

/**
 * 一份时刻表触及的资源键集合。
 *
 * <p>键与 {@link TimetableConflictChecker} 投影占用时拼出来的键同一口径，两边不得各拼一套：作用域判定问的是"两份表会不会在同一个资源上相遇"，
 * 而"资源"的定义只能有一个。含 CREATE/RETURN 线路——出库回库的走行同样占资源。
 *
 * @param timetableId 时刻表 UUID；尚未落库的构建输入用调用方给的临时 id
 * @param displayCode 供报告显示的标识，形如 {@code company/operator/line/code}
 * @param resourceKeys 资源键集合
 */
public record TimetableFootprint(UUID timetableId, String displayCode, Set<String> resourceKeys) {

  public TimetableFootprint {
    Objects.requireNonNull(timetableId, "timetableId");
    displayCode = displayCode == null ? "" : displayCode;
    resourceKeys = resourceKeys == null ? Set.of() : Set.copyOf(resourceKeys);
  }

  /** 由 route 投影汇总足迹。 */
  public static TimetableFootprint of(
      UUID timetableId,
      String displayCode,
      Collection<TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index) {
    Set<String> keys = new LinkedHashSet<>();
    if (profiles != null) {
      for (TimetableConflictChecker.RouteProfile profile : profiles) {
        if (profile != null) {
          keys.addAll(TimetableConflictChecker.resourceKeysOf(profile, index));
        }
      }
    }
    return new TimetableFootprint(timetableId, displayCode, keys);
  }

  /** 与另一份足迹共用的资源数；0 表示不是邻表。 */
  public int sharedWith(TimetableFootprint other) {
    if (other == null) {
      return 0;
    }
    int shared = 0;
    for (String key : resourceKeys) {
      if (other.resourceKeys.contains(key)) {
        shared++;
      }
    }
    return shared;
  }

  /** 共用的资源键，供报告与诊断列出。 */
  public Set<String> sharedKeys(TimetableFootprint other) {
    Set<String> out = new LinkedHashSet<>();
    if (other == null) {
      return out;
    }
    for (String key : resourceKeys) {
      if (other.resourceKeys.contains(key)) {
        out.add(key);
      }
    }
    return out;
  }
}
