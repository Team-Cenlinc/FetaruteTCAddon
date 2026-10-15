package org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CoLocationPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 同股道列车对（见 {@link CoLocationPolicy}）的登记与判定。
 *
 * <p>一对列车拆分后同处一段轨道：各自记一个“离开方向”（从对方指向本车的水平方向）。占用请求的行进方向（路径第一条有向边从起点指向终点）与本车离开方向 夹角小于约 75°
 * 时算背向对方，对方在共用资源上的占用不挡它；方向、图节点位置或共用资源缺一样都按“照常挡”。
 *
 * <p>登记、更新与解除在主线程，判定可能在任何持占用锁的线程：内部用并发容器，判定只读。
 */
public final class CoLocatedPairs implements CoLocationPolicy {

  /** 行进方向与离开方向的夹角余弦至少这么大才算背向对方（约 75°）。 */
  static final double AWAY_COSINE = 0.25;

  /**
   * 一对列车。
   *
   * @param first 一方车名
   * @param second 另一方车名
   * @param worldId 所在世界
   * @param shared 两车共用的占用资源
   * @param awayFirst 第一方的离开方向（从对方指向本车，水平单位向量）
   * @param awaySecond 第二方的离开方向
   */
  public record Pair(
      String first,
      String second,
      UUID worldId,
      Set<OccupancyResource> shared,
      Vector awayFirst,
      Vector awaySecond) {

    public Pair {
      Objects.requireNonNull(first, "first");
      Objects.requireNonNull(second, "second");
      Objects.requireNonNull(worldId, "worldId");
      shared = Set.copyOf(Objects.requireNonNull(shared, "shared"));
      awayFirst = Objects.requireNonNull(awayFirst, "awayFirst").clone();
      awaySecond = Objects.requireNonNull(awaySecond, "awaySecond").clone();
    }

    /** 对方车名；不在这一对里时为空。 */
    public Optional<String> partnerOf(String trainName) {
      if (TrainNameNormalizer.sameLogicalTrain(trainName, first)) {
        return Optional.of(second);
      }
      if (TrainNameNormalizer.sameLogicalTrain(trainName, second)) {
        return Optional.of(first);
      }
      return Optional.empty();
    }

    /** 这一方的离开方向；不在这一对里时为空。 */
    public Optional<Vector> awayOf(String trainName) {
      if (TrainNameNormalizer.sameLogicalTrain(trainName, first)) {
        return Optional.of(awayFirst.clone());
      }
      if (TrainNameNormalizer.sameLogicalTrain(trainName, second)) {
        return Optional.of(awaySecond.clone());
      }
      return Optional.empty();
    }

    /** 换一份共用资源。 */
    public Pair withShared(Set<OccupancyResource> next) {
      return new Pair(first, second, worldId, next, awayFirst, awaySecond);
    }
  }

  private final ConcurrentMap<String, Pair> byMember = new ConcurrentHashMap<>();
  private final BiFunction<UUID, NodeId, Optional<Vector>> nodePositions;

  /**
   * @param nodePositions 世界 + 图节点 → 节点位置；查不到为空
   */
  public CoLocatedPairs(BiFunction<UUID, NodeId, Optional<Vector>> nodePositions) {
    this.nodePositions = Objects.requireNonNull(nodePositions, "nodePositions");
  }

  /** 登记（或更新）一对；共用资源为空时不登记，并解除这两车原有的对。 */
  public void register(Pair pair) {
    Objects.requireNonNull(pair, "pair");
    dissolve(pair.first());
    dissolve(pair.second());
    if (pair.shared().isEmpty()) {
      return;
    }
    byMember.put(key(pair.first()), pair);
    byMember.put(key(pair.second()), pair);
  }

  /** 解除这列车所在的对（另一方一并解除）；返回解除的对。 */
  public Optional<Pair> dissolve(String trainName) {
    Pair pair = byMember.remove(key(trainName));
    if (pair == null) {
      return Optional.empty();
    }
    byMember.remove(key(pair.first()), pair);
    byMember.remove(key(pair.second()), pair);
    return Optional.of(pair);
  }

  /** 这列车所在的对。 */
  public Optional<Pair> pairOf(String trainName) {
    return trainName == null ? Optional.empty() : Optional.ofNullable(byMember.get(key(trainName)));
  }

  /** 全部的对（每对一份）。 */
  public List<Pair> pairs() {
    Collection<Pair> values = byMember.values();
    return values.stream().distinct().toList();
  }

  /** 清空（插件停用时）。 */
  public void clear() {
    byMember.clear();
  }

  @Override
  public boolean exempts(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (byMember.isEmpty()) {
      return false;
    }
    Pair pair = byMember.get(key(request.trainName()));
    if (pair == null || !pair.shared().contains(resource)) {
      return false;
    }
    Optional<String> partner = pair.partnerOf(request.trainName());
    if (partner.isEmpty()
        || !TrainNameNormalizer.sameLogicalTrain(partner.get(), claim.trainName())) {
      return false;
    }
    Optional<Vector> away = pair.awayOf(request.trainName());
    Optional<Vector> direction = travelDirection(request, pair.worldId());
    if (away.isEmpty() || direction.isEmpty()) {
      return false;
    }
    return movingAway(direction.get(), away.get());
  }

  /** 行进方向是否背向对方：与离开方向的水平夹角余弦至少 {@link #AWAY_COSINE}。 */
  public static boolean movingAway(Vector direction, Vector away) {
    Vector flat = new Vector(direction.getX(), 0.0, direction.getZ());
    double length = flat.length();
    if (!(length > 1.0e-6) || !Double.isFinite(length)) {
      return false;
    }
    return flat.multiply(1.0 / length).dot(away) >= AWAY_COSINE;
  }

  /**
   * 两车的离开方向：从对方中心指向本车中心的水平单位向量。
   *
   * @return 第一方的离开方向；两车中心水平重合时为空
   */
  public static Optional<Vector> awayDirection(Vector selfCenter, Vector partnerCenter) {
    Vector flat =
        new Vector(
            selfCenter.getX() - partnerCenter.getX(),
            0.0,
            selfCenter.getZ() - partnerCenter.getZ());
    double length = flat.length();
    if (!(length > 1.0e-6) || !Double.isFinite(length)) {
      return Optional.empty();
    }
    return Optional.of(flat.multiply(1.0 / length));
  }

  /** 请求的行进方向：路径第一条有向边从起点指向终点；拿不到时为空。 */
  private Optional<Vector> travelDirection(OccupancyRequest request, UUID worldId) {
    Optional<DirectedTraversalContext> context = request.directedContext();
    if (context.isEmpty() || context.get().directedEdges().isEmpty()) {
      return Optional.empty();
    }
    DirectedTraversalContext.DirectedEdge edge = context.get().directedEdges().get(0);
    Optional<Vector> from = nodePositions.apply(worldId, edge.fromNode());
    Optional<Vector> to = nodePositions.apply(worldId, edge.toNode());
    if (from.isEmpty() || to.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(to.get().clone().subtract(from.get()));
  }

  private static String key(String trainName) {
    return TrainNameNormalizer.normalizeKey(trainName == null ? "" : trainName);
  }
}
