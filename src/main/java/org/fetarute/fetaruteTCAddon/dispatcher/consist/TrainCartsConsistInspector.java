package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableGroup;
import com.bergerkiller.bukkit.tc.controller.spawnable.SpawnableMember;
import com.bergerkiller.bukkit.tc.properties.SavedTrainProperties;
import com.bergerkiller.bukkit.tc.properties.standard.StandardProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 用 TrainCarts 的存车与出车写法解析编组，不出车。
 *
 * <p>节数与总长取自 {@link SpawnableGroup}（与出车同一个解析器，组合写法也认）；标签取各节车厢配置里的 {@code tags}，也就是出车后车身会继承的那一份。
 * 只能在主线程调用，见 {@link ConsistInspector}。
 */
public final class TrainCartsConsistInspector implements ConsistInspector {

  @Override
  public ConsistInspection inspect(String pattern) {
    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null || pattern == null || pattern.isBlank()) {
      return ConsistInspection.unresolved();
    }
    Optional<CompositeSpawnPattern.Parsed> parsed =
        CompositeSpawnPattern.parse(trainCarts, pattern);
    if (parsed.isEmpty()) {
      return ConsistInspection.unresolved();
    }
    SpawnableGroup group = parsed.get().group();
    Map<String, Set<String>> tags = new HashMap<>();
    for (SpawnableMember member : group.getMembers()) {
      StandardProperties.TAGS
          .readFromConfig(member.getConfig())
          .ifPresent(
              memberTags -> {
                for (String tag : memberTags) {
                  collectTag(tags, tag);
                }
              });
    }
    // 组合写法：每段都是存车才算存车；保有量上限只对单段写法有意义（组合写法的上限由各段自己的存车管）。
    boolean saved = true;
    for (String part : parsed.get().parts()) {
      saved &= trainCarts.getSavedTrains().getProperties(part) != null;
    }
    SavedTrainProperties single =
        parsed.get().parts().size() == 1
            ? trainCarts.getSavedTrains().getProperties(parsed.get().parts().get(0))
            : null;
    OptionalInt limit =
        single != null && single.getSpawnLimit() >= 0
            ? OptionalInt.of(single.getSpawnLimit())
            : OptionalInt.empty();
    return new ConsistInspection(
        saved, group.getMembers().size(), group.getTotalLength(), tags, limit);
  }

  @Override
  public boolean exceedsSpawnLimit(String pattern) {
    TrainCarts trainCarts = TrainCarts.plugin;
    if (trainCarts == null || pattern == null || pattern.isBlank()) {
      return false;
    }
    return CompositeSpawnPattern.parse(trainCarts, pattern)
        .map(parsed -> parsed.exceedsSpawnLimit(trainCarts))
        .orElse(false);
  }

  private static void collectTag(Map<String, Set<String>> tags, String tag) {
    if (tag == null) {
      return;
    }
    String trimmed = tag.trim();
    int eq = trimmed.indexOf('=');
    if (eq <= 0) {
      return;
    }
    String value = trimmed.substring(eq + 1).trim();
    if (value.isEmpty()) {
      return;
    }
    tags.computeIfAbsent(
            trimmed.substring(0, eq).trim().toUpperCase(Locale.ROOT), key -> new HashSet<>())
        .add(value);
  }
}
