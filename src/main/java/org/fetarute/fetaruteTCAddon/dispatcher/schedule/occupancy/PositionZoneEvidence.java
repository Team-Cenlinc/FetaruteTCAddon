package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Set;

/**
 * 位置保持请求决定“当前边上的物理联锁区还要不要占住”时所依据的现场证据。
 *
 * <p>当前位置保护与停车保持都按“当前节点 + 朝目标方向的当前边”取资源，而当前边是整条算的：车头刚越过节点牌子几格，边末端的 {@code interlocking:*}
 * 交叠格就被一起扣住，哪怕车体离它还有二十几格。联锁区在准入里按物理资源算，谁占着谁就挡人，于是一条从旁边穿过交叠格的进路（例如回库车经正线岔口进库）
 * 会被一辆停着、根本没压到交叠格的车挡死，而这辆车往前又走不了，形成解不开的顶牛。
 *
 * <p>因此只有两件事同时成立时，当前边上的联锁区才跟随现场：列车已停稳（制动中车头仍可能越过授权终点压进去），且本轮整列足迹完整可读。
 * 此时车体没覆盖的联锁区不再由位置保持推出——列车要再往前走，必须重新以行车授权申请它们；覆盖到的照旧保持。其余情况一律按当前边整体保持（fail-retain）。
 *
 * <p>只作用于当前边派生的联锁区：节点、区间、道岔与单线资源不受影响；尾部保护由请求构造器另行加入，同一联锁区若也挂在尾部保护的边上照旧保持。
 *
 * @param followsLiveFootprint 列车已停稳且本轮整列足迹完整；为 {@code false} 时按当前边整体保持
 * @param coveredZones 车体实际覆盖的联锁区资源，仅在 {@code followsLiveFootprint} 时参与判定
 */
public record PositionZoneEvidence(
    boolean followsLiveFootprint, Set<OccupancyResource> coveredZones) {

  /** 没有停稳现场证据：当前边上的联锁区按边整体保持。 */
  public static final PositionZoneEvidence EDGE_WIDE = new PositionZoneEvidence(false, Set.of());

  public PositionZoneEvidence {
    coveredZones = coveredZones == null ? Set.of() : Set.copyOf(coveredZones);
  }

  /**
   * 列车已停稳且整列足迹完整时的证据。
   *
   * @param coveredZones 车体实际覆盖的联锁区资源
   * @return 当前边上只保留被车体覆盖的联锁区的证据
   */
  public static PositionZoneEvidence stationaryFootprint(Set<OccupancyResource> coveredZones) {
    return new PositionZoneEvidence(true, coveredZones);
  }

  /**
   * 当前边派生的资源是否仍由位置保持占住。
   *
   * @param resource 当前边派生的占用资源
   * @return 非联锁区资源恒为 {@code true}；联锁区在跟随现场时仅当被车体覆盖才为 {@code true}
   */
  public boolean retains(OccupancyResource resource) {
    return !followsLiveFootprint
        || !OccupancyResourceResolver.isInterlockingConflict(resource)
        || coveredZones.contains(resource);
  }
}
