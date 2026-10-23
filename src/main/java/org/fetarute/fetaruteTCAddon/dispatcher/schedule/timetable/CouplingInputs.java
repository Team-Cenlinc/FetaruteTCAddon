package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 编表认加解编要用、只能在主线程读的东西：各交路的出车编组写法（{@code spawn_train_pattern}，其次首站 CRET 指向的车库牌子），与组合写法（{@code
 * A+B}）各段的节数（用 TrainCarts 解析）。命令层在主线程收齐后交给编表线程，编表本身不碰 TrainCarts。
 *
 * @param patternByRoute 交路 → 出车编组写法；读不到的不在其中
 * @param partSizes 编组写法 → 各段节数（车头到车尾）；解析不出的不在其中
 */
public record CouplingInputs(
    Map<UUID, String> patternByRoute, Map<String, List<Integer>> partSizes) {

  /** 没有加解编输入。 */
  public static final CouplingInputs NONE = new CouplingInputs(Map.of(), Map.of());

  public CouplingInputs {
    patternByRoute = patternByRoute == null ? Map.of() : Map.copyOf(patternByRoute);
    Map<String, List<Integer>> sizes = new HashMap<>();
    if (partSizes != null) {
      partSizes.forEach((pattern, parts) -> sizes.put(pattern, List.copyOf(parts)));
    }
    partSizes = Map.copyOf(sizes);
  }

  /** 交路的出车编组写法。 */
  public Optional<String> pattern(UUID routeId) {
    return Optional.ofNullable(patternByRoute.get(routeId));
  }

  /** 编组写法各段的节数。 */
  public Optional<List<Integer>> sizes(String pattern) {
    return Optional.ofNullable(partSizes.get(pattern));
  }

  /** 两份合起来（联编几条线时）。 */
  public CouplingInputs merge(CouplingInputs other) {
    if (other == null || other == NONE) {
      return this;
    }
    Map<UUID, String> patterns = new HashMap<>(patternByRoute);
    patterns.putAll(other.patternByRoute);
    Map<String, List<Integer>> sizes = new HashMap<>(partSizes);
    sizes.putAll(other.partSizes);
    return new CouplingInputs(patterns, sizes);
  }
}
