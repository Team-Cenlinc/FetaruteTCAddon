package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.GroupLinkEvent;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CouplingOperations;

/**
 * 用过扳手的列车碰上别的车时看车钩：对着的两端都开着才让 TrainCarts 挂上，否则取消这次联挂。没用过扳手的列车保持 TrainCarts 原样（碰上即挂）；
 * 插件自己发起的联挂（扳手推车）已在发起前判过车钩，这里不管。只在服务器主线程使用。
 */
final class CouplerLinkGate implements Listener {

  private final Supplier<Optional<CouplingOperations>> operations;

  CouplerLinkGate(Supplier<Optional<CouplingOperations>> operations) {
    this.operations = operations;
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onGroupLink(GroupLinkEvent event) {
    MinecartGroup first = event.getGroup1();
    MinecartGroup second = event.getGroup2();
    if (first == null || second == null) {
      return;
    }
    if (operations.get().map(ops -> ops.involvesAny(first, second)).orElse(false)) {
      return;
    }
    if (!CouplerState.touched(first) && !CouplerState.touched(second)) {
      return;
    }
    if (!facingEndsOpen(first, second)) {
      event.setCancelled(true);
    }
  }

  /** 两列车离得最近的两节端车，对着的两端车钩是否都开着；位置取不到时按没开。 */
  static boolean facingEndsOpen(MinecartGroup first, MinecartGroup second) {
    MinecartMember<?> bestFirst = null;
    MinecartMember<?> bestSecond = null;
    double best = Double.MAX_VALUE;
    for (MinecartMember<?> a : new MinecartMember<?>[] {first.head(), first.tail()}) {
      for (MinecartMember<?> b : new MinecartMember<?>[] {second.head(), second.tail()}) {
        Optional<Vector> at = CarEnds.center(a);
        Optional<Vector> bt = CarEnds.center(b);
        if (at.isEmpty() || bt.isEmpty()) {
          continue;
        }
        double distance = at.get().distanceSquared(bt.get());
        if (distance < best) {
          best = distance;
          bestFirst = a;
          bestSecond = b;
        }
      }
    }
    if (bestFirst == null) {
      return false;
    }
    return facingOpen(bestFirst, bestSecond) && facingOpen(bestSecond, bestFirst);
  }

  private static boolean facingOpen(MinecartMember<?> member, MinecartMember<?> other) {
    return CarEnds.facingEnd(member, other)
        .map(end -> CouplerState.isOpen(member, end))
        .orElse(false);
  }
}
