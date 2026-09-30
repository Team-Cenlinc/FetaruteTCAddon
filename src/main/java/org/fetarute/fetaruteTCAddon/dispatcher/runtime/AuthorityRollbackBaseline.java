package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 一次硬授权 acquire 之前本车已经持有的资源：授权回滚只撤销这一拍新拿到的部分。
 *
 * <p>acquire 之后的最终校验、发布门、destination 提交等任一步失败都要回滚本拍授权。请求是从车头起算的整段窗口，里面有前几拍就授予、
 * 列车正压着或正沿着刹车驶入的资源；若回滚把请求里的硬授权全部放掉，这些也会一起放掉。典型情形在合流岔：
 * 一侧来车沿已持有授权刹车时，本拍因上一次停车记下的阻挡仍在而校验失败，回滚把它正要压上（或已压着）的合流岔节点与道岔冲突资源放掉、 当拍授给另一侧来车，后车开上岔把前车尾车撞断，并触发全网
 * STOP_FIRST 重建。
 *
 * <p>之前就持有的资源原样保留，由回滚之后的停车保持收缩到当前位置与列尾防护；这里不判断哪些"其实可以放"。 快照取本车全部 claim 而不只是本次请求里的，因为 acquire
 * 之后请求还可能被改写。
 *
 * @param heldBefore acquire 之前本车持有（任意角色）的资源
 */
record AuthorityRollbackBaseline(Set<OccupancyResource> heldBefore) {

  AuthorityRollbackBaseline {
    heldBefore = heldBefore == null ? Set.of() : Set.copyOf(heldBefore);
  }

  /**
   * 在 acquire 之前取快照。
   *
   * @param selfClaims 本车此刻在账本里的全部 claim
   * @return 快照
   */
  static AuthorityRollbackBaseline capture(Collection<OccupancyClaim> selfClaims) {
    Set<OccupancyResource> held = new HashSet<>();
    if (selfClaims != null) {
      for (OccupancyClaim claim : selfClaims) {
        if (claim != null && claim.resource() != null) {
          held.add(claim.resource());
        }
      }
    }
    return new AuthorityRollbackBaseline(held);
  }

  /** 回滚时能否释放这项资源：本拍之前已由本车持有的不放。 */
  boolean releasable(OccupancyResource resource) {
    return !heldBefore.contains(resource);
  }

  /**
   * 回滚保下之前已持有资源的诊断行；没有保下任何资源时为空（去重器据此清掉这辆车的记录）。
   *
   * @param trainName 列车名
   * @param reason 回滚原因
   * @param kept 回滚时因之前已持有而没有释放的资源
   * @return 诊断行
   */
  static Optional<String> traceLine(String trainName, String reason, List<OccupancyResource> kept) {
    if (kept == null || kept.isEmpty()) {
      return Optional.empty();
    }
    List<String> sorted = kept.stream().map(OccupancyResource::toString).sorted().toList();
    return Optional.of(
        "SMART_AUTHORITY_ROLLBACK_KEPT_HELD train="
            + trainName
            + " reason="
            + reason
            + " kept="
            + sorted.size()
            + " resources="
            + sorted);
  }
}
