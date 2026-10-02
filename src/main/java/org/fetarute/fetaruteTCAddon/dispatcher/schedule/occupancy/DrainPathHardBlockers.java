package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 解析出清路径上由其他列车持有的实体或 switcher 硬 blocker。
 *
 * <p>NODE、EDGE、抽象 switcher conflict 与物理 interlocking zone 属于出清路径硬资源；single conflict
 * 仍由方向互斥和队列规则判定。软性的 {@link ClaimRole#UNLOCK_RESERVATION}
 * 只表达等待者的队列意图，不能反向阻止已经位于冲突区内的列车出清。调用方只能传入已经完成证明的精确抽象 switcher conflicts 作为忽略集合。
 */
public final class DrainPathHardBlockers {

  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";

  private DrainPathHardBlockers() {}

  /**
   * 返回请求路径上第一个由其他逻辑列车持有的硬 blocker。
   *
   * @param request 当前出清请求
   * @param claims 当前 claim 快照
   * @param ignoredSwitcherConflicts 已完成证明、可忽略的精确抽象 switcher conflicts
   * @return 首个外车硬 blocker；没有则为空
   */
  public static Optional<OccupancyClaim> firstExternalClaim(
      OccupancyRequest request,
      Collection<OccupancyClaim> claims,
      Set<OccupancyResource> ignoredSwitcherConflicts) {
    if (request == null || claims == null || claims.isEmpty()) {
      return Optional.empty();
    }
    Set<OccupancyResource> ignored = new LinkedHashSet<>();
    if (ignoredSwitcherConflicts != null) {
      for (OccupancyResource resource : ignoredSwitcherConflicts) {
        if (resource != null
            && resource.kind() == ResourceKind.CONFLICT
            && resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)) {
          ignored.add(resource);
        }
      }
    }
    Set<OccupancyResource> hardPathResources = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (isHardPathResource(resource) && !ignored.contains(resource)) {
        hardPathResources.add(resource);
      }
    }
    for (OccupancyClaim claim : claims) {
      if (claim == null
          || claim.resource() == null
          || claim.role() == ClaimRole.UNLOCK_RESERVATION
          || !hardPathResources.contains(claim.resource())
          || TrainNameNormalizer.sameLogicalTrain(request.trainName(), claim.trainName())) {
        continue;
      }
      return Optional.of(claim);
    }
    return Optional.empty();
  }

  private static boolean isHardPathResource(OccupancyResource resource) {
    if (resource == null) {
      return false;
    }
    if (resource.kind() == ResourceKind.NODE || resource.kind() == ResourceKind.EDGE) {
      return true;
    }
    return resource.kind() == ResourceKind.CONFLICT
        && (resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)
            || OccupancyResourceResolver.isInterlockingConflict(resource));
  }
}
