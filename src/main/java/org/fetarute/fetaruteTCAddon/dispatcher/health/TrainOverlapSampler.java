package org.fetarute.fetaruteTCAddon.dispatcher.health;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LiveRailFootprintObservation;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;

/**
 * 在主线程采样静止受管列车的车厢位置与实时轨道足迹，供 {@link TrainOverlapDetector} 找叠在一起的车。
 *
 * <p>读整列足迹要走一遍轨道追踪，所以先只按车厢粗筛（{@link TrainOverlapDetector#mayOverlap}）：有一对车厢可能嵌进对方，才去读这两列的足迹。
 * 平时各车相距很远，一轮下来一份足迹都不读。读过的足迹按编组缓存，车厢没挪过就直接复用。
 *
 * <p>任何一列读不到（实体失效、车厢模型缺失、足迹不完整、TrainCarts 抛错）就跳过它——少一份采样只会让重叠晚一些被认定，不会误判； 本轮有过异常时打一行诊断，免得整个检测悄悄失效。
 */
final class TrainOverlapSampler implements Supplier<List<TrainOverlapDetector.Sample>> {

  /** 车厢挪动不超过这么多格就沿用上一轮读到的足迹。 */
  private static final double CACHE_POSITION_TOLERANCE_BLOCKS = 0.05;

  private final RuntimeDispatchService dispatchService;
  private final Consumer<String> debugLogger;

  /** 上一轮读到的足迹，按编组（同一对象）缓存；每轮只留本轮还在用的编组。 */
  private Map<MinecartGroup, CachedFootprint> footprintCache = new IdentityHashMap<>();

  TrainOverlapSampler(RuntimeDispatchService dispatchService, Consumer<String> debugLogger) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  private record Candidate(
      MinecartGroup group, String trainName, UUID worldId, List<TrainOverlapDetector.Cart> carts) {}

  private record CachedFootprint(
      List<TrainOverlapDetector.Cart> carts, Set<RailFootprintCell> cells) {}

  @Override
  public List<TrainOverlapDetector.Sample> get() {
    int failures = 0;
    Throwable firstFailure = null;
    List<Candidate> stationary = new ArrayList<>();
    for (MinecartGroup group : MinecartGroupStore.getGroups()) {
      try {
        Candidate candidate = candidate(group);
        if (candidate != null) {
          stationary.add(candidate);
        }
      } catch (RuntimeException | LinkageError failure) {
        failures++;
        firstFailure = firstFailure == null ? failure : firstFailure;
      }
    }
    boolean[] near = new boolean[stationary.size()];
    for (int i = 0; i < stationary.size(); i++) {
      for (int j = i + 1; j < stationary.size(); j++) {
        Candidate first = stationary.get(i);
        Candidate second = stationary.get(j);
        if (first.worldId().equals(second.worldId())
            && TrainOverlapDetector.mayOverlap(first.carts(), second.carts())) {
          near[i] = true;
          near[j] = true;
        }
      }
    }
    Map<MinecartGroup, CachedFootprint> nextCache = new IdentityHashMap<>();
    List<TrainOverlapDetector.Sample> samples = new ArrayList<>();
    for (int i = 0; i < stationary.size(); i++) {
      if (!near[i]) {
        continue;
      }
      Candidate candidate = stationary.get(i);
      try {
        CachedFootprint cached = footprintCache.get(candidate.group());
        Set<RailFootprintCell> cells =
            cached != null && samePositions(cached.carts(), candidate.carts())
                ? cached.cells()
                : readFootprint(candidate.group());
        if (cells != null) {
          nextCache.put(candidate.group(), new CachedFootprint(candidate.carts(), cells));
          samples.add(
              new TrainOverlapDetector.Sample(
                  candidate.trainName(), candidate.worldId(), cells, candidate.carts()));
        }
      } catch (RuntimeException | LinkageError failure) {
        failures++;
        firstFailure = firstFailure == null ? failure : firstFailure;
      }
    }
    footprintCache = nextCache;
    if (failures > 0) {
      debugLogger.accept(
          "HEALTH_TRAIN_OVERLAP_SAMPLE_ERRORS count=" + failures + " first=" + firstFailure);
    }
    return samples;
  }

  /** 读整列实时足迹；读不到返回 null。 */
  private static Set<RailFootprintCell> readFootprint(MinecartGroup group) {
    LiveRailFootprintObservation observation =
        new TrainCartsRuntimeHandle(group).observeLiveRailFootprint();
    return observation != null && observation.available() ? observation.cells().orElse(null) : null;
  }

  private static boolean samePositions(
      List<TrainOverlapDetector.Cart> previous, List<TrainOverlapDetector.Cart> current) {
    if (previous.size() != current.size()) {
      return false;
    }
    for (int i = 0; i < previous.size(); i++) {
      TrainOverlapDetector.Cart a = previous.get(i);
      TrainOverlapDetector.Cart b = current.get(i);
      if (Math.abs(a.x() - b.x()) > CACHE_POSITION_TOLERANCE_BLOCKS
          || Math.abs(a.y() - b.y()) > CACHE_POSITION_TOLERANCE_BLOCKS
          || Math.abs(a.z() - b.z()) > CACHE_POSITION_TOLERANCE_BLOCKS) {
        return false;
      }
    }
    return true;
  }

  /** 静止的受管列车才是候选；返回 null 表示不参与。 */
  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
      justification = "TrainCarts 的 MinecartMember#getEntity 泛型契约保证返回该成员对应的 CommonMinecart")
  private Candidate candidate(MinecartGroup group) {
    if (group == null || !group.isValid() || group.isEmpty() || group.isMoving()) {
      return null;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null || !dispatchService.hasCompleteFtaRouteIdentity(properties)) {
      return null;
    }
    String trainName =
        dispatchService.resolveTrackedTrainName(properties).orElse(properties.getTrainName());
    if (trainName == null || trainName.isBlank() || group.getWorld() == null) {
      return null;
    }
    List<TrainOverlapDetector.Cart> carts = new ArrayList<>();
    for (MinecartMember<?> member : group) {
      Entity entity =
          member == null || member.getEntity() == null ? null : member.getEntity().getEntity();
      if (entity == null
          || !entity.isValid()
          || member.getProperties() == null
          || member.getProperties().getModel() == null) {
        return null;
      }
      double halfLength = member.getProperties().getModel().getCartLength() / 2.0;
      Location location = entity.getLocation();
      if (!(halfLength > 0.0)
          || !Double.isFinite(halfLength)
          || !Double.isFinite(location.getX() + location.getY() + location.getZ())) {
        return null;
      }
      carts.add(
          TrainOverlapDetector.Cart.at(
              location.getX(), location.getY(), location.getZ(), halfLength));
    }
    if (carts.isEmpty()) {
      return null;
    }
    return new Candidate(group, trainName.trim(), group.getWorld().getUID(), List.copyOf(carts));
  }
}
