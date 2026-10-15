package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CoLocatedPairs;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CouplingOperations;

/**
 * 把两列车挂在一起。
 *
 * <p>被接上的那列（停着不动的一方）保留身份：车名、交路、进度、待命登记都沿用它的；挂过来的那列身份作废，进度、占用、待命登记由调度按车名清掉。
 * 受管列车按插件自己发起的联挂走（运行时监听不当事故处理，不全网先停车）；TrainCarts 联挂时会把一方的列车属性整份装进另一方并对调车名，
 * 所以联挂前先保存两边的标签，联挂后按保存的值改写。受管与非受管列车不挂在一起。只在服务器主线程使用。
 */
public final class TrainLinker {

  /** 联挂结果。 */
  public enum Outcome {
    /** 挂上了。 */
    LINKED,
    /** 列车无效或两节车是同一列车。 */
    INVALID,
    /** 一列受管、一列不受管：归属说不清，不挂。 */
    MIXED_OWNERSHIP,
    /** 合并后超过节数上限。 */
    TOO_LONG,
    /** TrainCarts 没有挂上（{@link Result#detail()} 写原因）。 */
    NOT_LINKED
  }

  /**
   * @param outcome 结果
   * @param merged 合并后的列车；没挂上时为空
   * @param detail TrainCarts 的联挂结果，或其他说明
   */
  public record Result(Outcome outcome, Optional<MinecartGroup> merged, String detail) {

    static Result of(Outcome outcome, String detail) {
      return new Result(outcome, Optional.empty(), detail);
    }
  }

  private final RuntimeDispatchService dispatch;
  private final CoLocatedPairs pairs;
  private final Optional<LayoverRegistry> layovers;
  private final Consumer<Runnable> nextTick;
  private final Consumer<String> debug;

  public TrainLinker(
      RuntimeDispatchService dispatch,
      CoLocatedPairs pairs,
      Optional<LayoverRegistry> layovers,
      Consumer<Runnable> nextTick,
      Consumer<String> debug) {
    this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    this.pairs = Objects.requireNonNull(pairs, "pairs");
    this.layovers = layovers == null ? Optional.empty() : layovers;
    this.nextTick = Objects.requireNonNull(nextTick, "nextTick");
    this.debug = debug == null ? message -> {} : debug;
  }

  /**
   * 把 {@code moving} 所在的列车挂到 {@code standing} 所在的列车上。两节车须是各自列车的端车，且轨道相连、几乎贴着（由调用方先推过去）。
   *
   * @param moving 挂过来那列的端车
   * @param standing 被接上那列的端车
   * @param maxCars 合并后最多几节
   */
  public Result link(MinecartMember<?> moving, MinecartMember<?> standing, int maxCars) {
    MinecartGroup movingGroup = moving == null ? null : moving.getGroup();
    MinecartGroup standingGroup = standing == null ? null : standing.getGroup();
    if (movingGroup == null
        || standingGroup == null
        || movingGroup == standingGroup
        || !movingGroup.isValid()
        || !standingGroup.isValid()) {
      return Result.of(Outcome.INVALID, "invalid");
    }
    boolean movingManaged = managed(movingGroup);
    boolean standingManaged = managed(standingGroup);
    if (movingManaged != standingManaged) {
      return Result.of(Outcome.MIXED_OWNERSHIP, "mixed");
    }
    if (maxCars > 0 && movingGroup.size() + standingGroup.size() > maxCars) {
      return Result.of(Outcome.TOO_LONG, "too-long");
    }
    CarTags.stampOrigins(movingGroup);
    CarTags.stampOrigins(standingGroup);
    Map<UUID, Map<String, String>> cars = new HashMap<>(CarTags.snapshot(movingGroup));
    cars.putAll(CarTags.snapshot(standingGroup));
    String movingName = movingManaged ? trainName(movingGroup) : "";
    String standingName = standingManaged ? trainName(standingGroup) : "";
    Map<String, String> standingTags =
        standingManaged ? trainLevelTags(standingGroup.getProperties()) : Map.of();

    MinecartGroupStore.LinkResult linked;
    CouplingOperations operations = dispatch.coupling().operations();
    try (CouplingOperations.Scope ignored = operations.begin(List.of(movingGroup, standingGroup))) {
      linked = MinecartGroupStore.link(moving, standing);
    }
    if (linked != MinecartGroupStore.LinkResult.SUCCESS) {
      return Result.of(Outcome.NOT_LINKED, String.valueOf(linked));
    }
    MinecartGroup merged = standing.getGroup();
    CarTags.restore(merged, cars);
    if (standingManaged) {
      adoptStandingIdentity(merged, standingName, standingTags);
      CarTags.rewriteConsist(merged);
      // 先让存活身份按合并后的车身占用，再放掉挂过来那列的占用：反过来会有一段车身下没有任何占用。
      dispatch.coupling().requestPhysicalRehydration(standingName);
      dispatch.refreshSignal(merged);
      retireMovingIdentity(movingName, standingName);
      refreshStandby(layovers, merged, standingName);
      nextTick.accept(
          () -> {
            if (merged.isValid()) {
              dispatch.refreshSignal(merged);
            }
          });
      debug.accept(
          "COUPLER_LINK survivor="
              + standingName
              + " retired="
              + movingName
              + " cars="
              + merged.size());
    } else {
      CarTags.rewriteConsist(merged);
    }
    return new Result(Outcome.LINKED, Optional.of(merged), String.valueOf(linked));
  }

  /** 合并后的列车改回被接上那列的车名，并按联挂前保存的标签整份改写（TrainCarts 可能已把另一方的属性装了进来）。 */
  private void adoptStandingIdentity(
      MinecartGroup merged, String standingName, Map<String, String> standingTags) {
    TrainProperties properties = merged.getProperties();
    if (!standingName.equals(properties.getTrainName())) {
      try {
        properties.setTrainName(standingName);
      } catch (IllegalArgumentException ex) {
        debug.accept("COUPLER_LINK_RENAME_FAILED survivor=" + standingName + " error=" + ex);
      }
    }
    for (String key : trainLevelTags(properties).keySet()) {
      if (!standingTags.containsKey(key)) {
        TrainTagHelper.removeTagKey(properties, key);
      }
    }
    standingTags.forEach(
        (key, value) -> {
          if (!CouplerService.TAG_CO_LOCATED.equalsIgnoreCase(key)) {
            TrainTagHelper.writeTag(properties, key, value);
          }
        });
    TrainTagHelper.removeTagKey(properties, CouplerService.TAG_CO_LOCATED);
  }

  /** 挂过来那列的身份作废：解除它与被接上那列原有的同股道列车对，调度按车名清掉它的进度、占用与待命登记。 */
  private void retireMovingIdentity(String movingName, String standingName) {
    pairs.dissolve(movingName);
    pairs.dissolve(standingName);
    dispatch.handleTrainRemoved(movingName);
  }

  /** 列车是待命车时刷新待命登记里的标签快照（拆挂改了编组键，编组方案按快照认车）。 */
  static void refreshStandby(
      Optional<LayoverRegistry> layovers, MinecartGroup group, String trainName) {
    layovers.ifPresent(
        registry ->
            registry
                .get(trainName)
                .ifPresent(
                    candidate ->
                        registry.register(
                            trainName,
                            candidate.terminalKey(),
                            candidate.locationNodeId(),
                            candidate.readyAt() == null ? Instant.now() : candidate.readyAt(),
                            trainLevelTags(group.getProperties()))));
  }

  private boolean managed(MinecartGroup group) {
    TrainProperties properties = group.getProperties();
    return properties != null && dispatch.hasFtaRuntimeTag(properties);
  }

  private String trainName(MinecartGroup group) {
    TrainProperties properties = group.getProperties();
    return dispatch.resolveTrackedTrainName(properties).orElse(properties.getTrainName());
  }

  /** 列车级的 {@code 键=值} 标签（不含车厢级的车钩与来源编组）。 */
  static Map<String, String> trainLevelTags(TrainProperties properties) {
    Map<String, String> out = new LinkedHashMap<>();
    if (properties == null || !properties.hasTags()) {
      return out;
    }
    for (String tag : properties.getTags()) {
      if (tag == null) {
        continue;
      }
      int eq = tag.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = tag.substring(0, eq).trim();
      if (CarTags.CAR_KEYS.stream().noneMatch(key::equalsIgnoreCase)) {
        out.put(key, tag.substring(eq + 1).trim());
      }
    }
    return out;
  }
}
