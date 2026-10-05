package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.utils.StableCollections;

/**
 * 单个世界图快照的不可变联锁状态。
 *
 * <p>完整 coverage 时返回由物理足迹推导的精确 pair-zone；coverage 不完整时，expected edge universe 内的每条边都返回同一个稳定世界
 * sentinel，避免把缺失足迹误解释为“没有冲突”。
 */
public final class RailInterlockingState {

  private static final String INCOMPLETE_PREFIX = "interlocking:incomplete:";
  private static final RailInterlockingState UNAVAILABLE =
      new RailInterlockingState(
          Optional.empty(),
          Set.of(),
          RailInterlockingZoneIndex.from(new UUID(0L, 0L), Map.of()),
          null);

  private final Optional<UUID> worldId;
  private final Set<EdgeId> expectedEdges;
  private final RailInterlockingZoneIndex index;
  private final String incompleteSentinel;

  private RailInterlockingState(
      Optional<UUID> worldId,
      Set<EdgeId> expectedEdges,
      RailInterlockingZoneIndex index,
      String incompleteSentinel) {
    this.worldId = Objects.requireNonNull(worldId, "worldId");
    this.expectedEdges = Set.copyOf(expectedEdges);
    this.index = Objects.requireNonNull(index, "index");
    this.incompleteSentinel = incompleteSentinel;
  }

  /** 返回未启用联锁足迹的兼容状态；该状态不会为旧三参数图凭空添加资源。 */
  public static RailInterlockingState unavailable() {
    return UNAVAILABLE;
  }

  /**
   * 从世界级 expected edge universe 与足迹构建状态。
   *
   * @param worldId 图所属世界
   * @param expectedEdges 图快照中的全部区间
   * @param footprintsByEdge 已捕获的区间足迹
   * @return 完整或 fail-closed sentinel 状态
   */
  public static RailInterlockingState from(
      UUID worldId, Set<EdgeId> expectedEdges, Map<EdgeId, RailEdgeFootprint> footprintsByEdge) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(expectedEdges, "expectedEdges");
    Objects.requireNonNull(footprintsByEdge, "footprintsByEdge");
    Set<EdgeId> canonicalExpected = new HashSet<>();
    for (EdgeId edge : expectedEdges) {
      if (edge != null) {
        canonicalExpected.add(InterlockingZoneInfo.canonical(edge));
      }
    }
    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.from(worldId, canonicalExpected, footprintsByEdge);
    String sentinel = index.coverage().complete() ? null : INCOMPLETE_PREFIX + worldId;
    return new RailInterlockingState(Optional.of(worldId), canonicalExpected, index, sentinel);
  }

  /**
   * 从已校验格式和 Edge 签名的稀疏持久化快照恢复状态。
   *
   * @param worldId 图所属世界
   * @param expectedEdges 当前图快照的全部区间
   * @param coverage 自动发现覆盖状态
   * @param zones 只包含真实联锁区局部坐标的记录
   * @return 不含普通轨道方块的不可变状态
   */
  public static RailInterlockingState fromSnapshot(
      UUID worldId,
      Set<EdgeId> expectedEdges,
      RailInterlockingCoverage coverage,
      Map<String, InterlockingZoneInfo> zones) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(expectedEdges, "expectedEdges");
    Objects.requireNonNull(coverage, "coverage");
    Objects.requireNonNull(zones, "zones");
    Set<EdgeId> canonicalExpected = canonicalEdges(expectedEdges);
    RailInterlockingZoneIndex index =
        RailInterlockingZoneIndex.fromZones(canonicalExpected, coverage, zones);
    String sentinel = coverage.complete() ? null : INCOMPLETE_PREFIX + worldId;
    return new RailInterlockingState(Optional.of(worldId), canonicalExpected, index, sentinel);
  }

  /** 创建一个已知世界、已知 Edge universe 但证据尚未认证的 fail-closed 状态。 */
  public static RailInterlockingState incomplete(UUID worldId, Set<EdgeId> expectedEdges) {
    Objects.requireNonNull(worldId, "worldId");
    Set<EdgeId> canonicalExpected = canonicalEdges(expectedEdges);
    return fromSnapshot(
        worldId,
        canonicalExpected,
        new RailInterlockingCoverage(canonicalExpected.size(), 0, false),
        Map.of());
  }

  /**
   * 使用新的 edge universe 与足迹重建同一世界的联锁状态。
   *
   * <p>该方法供图合并等只持有旧状态、没有独立 world id 的路径使用。旧三参数图产生的 unavailable 兼容态不会被猜测为任意世界，也不会凭空启用联锁。
   *
   * @param expectedEdges 新图快照中的全部区间
   * @param footprintsByEdge 新图快照中的区间足迹
   * @return 同一世界重建后的状态；兼容态仍返回 unavailable
   */
  public RailInterlockingState rebuild(
      Set<EdgeId> expectedEdges, Map<EdgeId, RailEdgeFootprint> footprintsByEdge) {
    Objects.requireNonNull(expectedEdges, "expectedEdges");
    Objects.requireNonNull(footprintsByEdge, "footprintsByEdge");
    return worldId
        .map(id -> RailInterlockingState.from(id, expectedEdges, footprintsByEdge))
        .orElse(UNAVAILABLE);
  }

  /**
   * 删除已经不在图中的 Edge 与其 Zone，而不重新引入全线轨迹。
   *
   * <p>从完整快照删除 Edge 不会产生新的物理冲突，因此可保持完整；旧状态本就不完整时继续返回新的 world sentinel。
   */
  public RailInterlockingState retainEdges(Set<EdgeId> retainedEdges) {
    Objects.requireNonNull(retainedEdges, "retainedEdges");
    if (worldId.isEmpty()) {
      return UNAVAILABLE;
    }
    Set<EdgeId> retained = canonicalEdges(retainedEdges);
    if (!expectedEdges.containsAll(retained)) {
      throw new IllegalArgumentException("不能从旧联锁快照保留未知 Edge");
    }
    if (!coverage().complete()) {
      return incomplete(worldId.orElseThrow(), retained);
    }
    Map<String, InterlockingZoneInfo> retainedZones = new java.util.TreeMap<>();
    exactZones()
        .forEach(
            (key, zone) -> {
              if (retained.contains(zone.firstEdge()) && retained.contains(zone.secondEdge())) {
                retainedZones.put(key, zone);
              }
            });
    return fromSnapshot(
        worldId.orElseThrow(),
        retained,
        new RailInterlockingCoverage(retained.size(), retained.size(), true),
        retainedZones);
  }

  /**
   * 参与联锁计算的逐边足迹（完整、当前格式），供合并图时沿用没有重扫的区间。
   *
   * <p>cell 索引不可用（由持久化 Zone 恢复、或 {@link #incomplete}）时为空：此时没有可沿用的足迹。
   */
  public Map<EdgeId, RailEdgeFootprint> participatingFootprints() {
    Map<EdgeId, RailEdgeFootprint> footprints = new java.util.HashMap<>();
    footprintCellsByEdge()
        .forEach(
            (edge, cells) ->
                footprints.put(
                    edge,
                    new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, cells)));
    return footprints;
  }

  /**
   * 同样的足迹与 Zone，但整体按不完整发布。
   *
   * <p>用于无法证明完整 Edge universe 的局部合并：联锁照旧走世界 sentinel，但已经测到的足迹留在索引里，写库时不会被抹掉。
   */
  public RailInterlockingState withCoverageMarkedIncomplete() {
    if (worldId.isEmpty() || incompleteSentinel != null) {
      return this;
    }
    return new RailInterlockingState(
        worldId,
        expectedEdges,
        index.withCoverageIncomplete(),
        INCOMPLETE_PREFIX + worldId.orElseThrow());
  }

  /** 返回状态所属世界；旧兼容态没有可安全推断的世界。 */
  public Optional<UUID> worldId() {
    return worldId;
  }

  /** 返回该状态是否由一个已知世界的足迹快照构建，而不是旧图兼容态。 */
  public boolean available() {
    return worldId.isPresent();
  }

  /** 返回该状态声明覆盖的规范化 Edge universe。 */
  public Set<EdgeId> expectedEdges() {
    return expectedEdges;
  }

  /** 返回指定区间需要共同申请的联锁区键。 */
  /**
   * 返回覆盖该方块的全部图区间。
   *
   * <p>供"车体此刻是否还压在某个 NODE/EDGE 上"的判定使用——这是 Phase 4 把尾部保护回收扩到 NODE/EDGE 的前置条件：CONFLICT
   * 是抽象互斥键可以直接放，NODE/EDGE 对应物理空间， 车体还压着时释放就是 co-occupancy，必须有实测覆盖作证。
   *
   * <p><b>空集合有两种含义</b>，必须先用 {@link #cellCoverageAvailable()} 区分： 索引可用时是"确实没覆盖"，不可用时是"无从判断"。
   */
  public Set<EdgeId> edgesForCell(RailFootprintCell cell) {
    return index.edgesForCell(cell);
  }

  /** cell→edge 反向索引是否可用；由持久化快照重建的状态不可用，调用方须 fail-closed。 */
  public boolean cellCoverageAvailable() {
    return index.cellCoverageAvailable();
  }

  /**
   * 逐边足迹（由 cell→edge 索引反转而得），供持久化写出。
   *
   * <p>索引不可用时返回空 Map。调用方**必须**先用 {@link #cellCoverageAvailable()} 区分
   * "确实没有"与"无从判断"——把后者当成前者写进库，会用一份空足迹覆盖掉库里原有的好数据。
   */
  public Map<EdgeId, Set<RailFootprintCell>> footprintCellsByEdge() {
    return index.footprintCellsByEdge();
  }

  public Set<String> zoneKeysForEdge(EdgeId edgeId) {
    if (edgeId == null) {
      return Set.of();
    }
    EdgeId canonical = InterlockingZoneInfo.canonical(edgeId);
    if (!expectedEdges.contains(canonical)) {
      return Set.of();
    }
    if (incompleteSentinel != null) {
      return Set.of(incompleteSentinel);
    }
    return index.zoneKeysForEdge(canonical);
  }

  /**
   * 观察完整列车车体当前覆盖的稀疏物理联锁区。
   *
   * <p>普通轨道方块不进入常驻空间索引，因此“没有命中 Zone”是完整且合法的 clear 结果。只有 catalog 不可用、coverage
   * 不完整、输入为空或包含坏坐标时才返回不完整；调用方必须在这种情况下保持既有物理 claim。
   *
   * @param liveCells 本轮完整车体的临时轨道方块集合
   * @return 本轮实际覆盖的 Zone，或不可用于释放的证据状态
   */
  public LiveZoneObservation observeLiveCells(Collection<RailFootprintCell> liveCells) {
    if (!available() || !coverage().complete() || liveCells == null || liveCells.isEmpty()) {
      return LiveZoneObservation.incomplete();
    }
    Set<String> occupiedZoneKeys = new HashSet<>();
    for (RailFootprintCell cell : liveCells) {
      if (cell == null) {
        return LiveZoneObservation.incomplete();
      }
      occupiedZoneKeys.addAll(index.zoneKeysForCell(cell));
    }
    return LiveZoneObservation.complete(occupiedZoneKeys);
  }

  /** 返回精确联锁区详情；incomplete sentinel 不伪造几何详情。 */
  public Optional<InterlockingZoneInfo> zoneInfo(String zoneKey) {
    return index.zoneInfo(zoneKey);
  }

  /** 返回精确 pair-zone 数量；incomplete sentinel 不计入精确联锁区。 */
  public int exactZoneCount() {
    return index.zones().size();
  }

  /** 返回现场观察所需的稀疏 Zone 方块总数；普通轨道方块不计入。 */
  public int indexedZoneCellCount() {
    return index.indexedZoneCellCount();
  }

  /** 返回同时属于多个精确 Zone 的局部检测方块数。 */
  public int multiZoneCellCount() {
    return index.multiZoneCellCount();
  }

  /** 返回稳定资源键到精确联锁区证据的不可变映射；incomplete sentinel 不伪造几何记录。 */
  public Map<String, InterlockingZoneInfo> exactZones() {
    return index.zones();
  }

  /**
   * 判断另一个状态是否向每条预期区间投影完全相同的 occupancy 资源。
   *
   * <p>该比较刻意包含 available 状态、世界与 expected edge universe；即使两个 incomplete 状态当前都返回同一类
   * sentinel，也不能在边集合变化时视为可无门控热切换。
   *
   * @param other 待比较状态
   * @return 是否可在不重投影 active claims 的情况下安全沿用资源键
   */
  public boolean sameResourceProjection(RailInterlockingState other) {
    if (other == null
        || !worldId.equals(other.worldId)
        || !expectedEdges.equals(other.expectedEdges)) {
      return false;
    }
    return expectedEdges.stream()
            .allMatch(edge -> zoneKeysForEdge(edge).equals(other.zoneKeysForEdge(edge)))
        && exactZones().equals(other.exactZones());
  }

  /** 返回当前世界的足迹覆盖状态。 */
  public RailInterlockingCoverage coverage() {
    return index.coverage();
  }

  private static Set<EdgeId> canonicalEdges(Collection<EdgeId> edges) {
    Set<EdgeId> canonical = new HashSet<>();
    if (edges != null) {
      edges.stream()
          .filter(Objects::nonNull)
          .map(InterlockingZoneInfo::canonical)
          .forEach(canonical::add);
    }
    return Set.copyOf(canonical);
  }

  /**
   * 一次完整车体对稀疏联锁区的观察结果。
   *
   * @param complete catalog 与车体证据是否足以安全收缩既有物理 claim
   * @param occupiedZoneKeys 当前车体实际覆盖的 Zone；完整空集合表示车体位于普通轨道。按字符串自然序排列，下游据此生成的资源顺序因而与 JVM 无关（{@code
   *     Set.copyOf} 的顺序每个 JVM 随机一次）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "构造器已经 StableCollections 复制为不可修改的有序视图；SpotBugs 只认得 Set.copyOf，看不穿这层复制。")
  public record LiveZoneObservation(boolean complete, Set<String> occupiedZoneKeys) {

    public LiveZoneObservation {
      Objects.requireNonNull(occupiedZoneKeys, "occupiedZoneKeys");
      occupiedZoneKeys = StableCollections.copySorted(occupiedZoneKeys, Comparator.naturalOrder());
      if (!complete && !occupiedZoneKeys.isEmpty()) {
        throw new IllegalArgumentException("不完整现场观察不能携带可用于释放的 Zone 结论");
      }
    }

    private static LiveZoneObservation complete(Set<String> occupiedZoneKeys) {
      return new LiveZoneObservation(true, occupiedZoneKeys);
    }

    private static LiveZoneObservation incomplete() {
      return new LiveZoneObservation(false, Set.of());
    }
  }
}
