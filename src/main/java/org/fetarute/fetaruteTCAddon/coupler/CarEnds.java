package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.Optional;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * 车厢两端的几何：哪一端朝着某处、某一端挨着同列车的哪一节、端部位置。前后按车厢自身朝向（{@link MinecartMember#getOrientationForward()}），
 * 列车调头不影响。只在服务器主线程使用。
 */
final class CarEnds {

  /** 车厢模型缺失时的半车长（格）。 */
  private static final double FALLBACK_HALF_LENGTH = 0.5;

  private CarEnds() {}

  /** 车厢中心；实体不可用时为空。 */
  static Optional<Vector> center(MinecartMember<?> member) {
    Entity entity =
        member == null || member.getEntity() == null ? null : member.getEntity().getEntity();
    if (entity == null || !entity.isValid()) {
      return Optional.empty();
    }
    return Optional.of(entity.getLocation().toVector());
  }

  /** 车厢自身朝向（单位向量）；取不到时为空。 */
  static Optional<Vector> forward(MinecartMember<?> member) {
    try {
      Vector forward = member == null ? null : member.getOrientationForward();
      if (forward == null || !(forward.lengthSquared() > 0.0)) {
        return Optional.empty();
      }
      return Optional.of(forward.clone().normalize());
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  /** 半车长（格）。 */
  static double halfLength(MinecartMember<?> member) {
    try {
      double length = member.getProperties().getModel().getCartLength();
      return length > 0.0 && Double.isFinite(length) ? length / 2.0 : FALLBACK_HALF_LENGTH;
    } catch (RuntimeException ex) {
      return FALLBACK_HALF_LENGTH;
    }
  }

  /** 车钩长度（格）。 */
  static double couplerLength(MinecartMember<?> member) {
    try {
      double length = member.getCartCouplerLength();
      return length >= 0.0 && Double.isFinite(length) ? length : 0.0;
    } catch (RuntimeException ex) {
      return 0.0;
    }
  }

  /** 两节车挂着时中心之间的距离：各自半车长加各自车钩长。 */
  static double coupledDistance(MinecartMember<?> first, MinecartMember<?> second) {
    return halfLength(first) + couplerLength(first) + halfLength(second) + couplerLength(second);
  }

  /** 自 {@code self} 看，{@code other} 在车厢的哪一端：朝向前方为前端。纯计算。 */
  static CouplerState.End facing(Vector forward, Vector self, Vector other) {
    return forward.dot(other.clone().subtract(self)) >= 0.0
        ? CouplerState.End.FRONT
        : CouplerState.End.REAR;
  }

  /** 自 {@code member} 看，{@code other} 在它的哪一端；位置或朝向取不到时为空。 */
  static Optional<CouplerState.End> facingEnd(MinecartMember<?> member, MinecartMember<?> other) {
    Optional<Vector> forward = forward(member);
    Optional<Vector> self = center(member);
    Optional<Vector> there = center(other);
    if (forward.isEmpty() || self.isEmpty() || there.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(facing(forward.get(), self.get(), there.get()));
  }

  /** 车厢这一端朝外的方向（单位向量）。 */
  static Vector outward(Vector forward, CouplerState.End end) {
    Vector unit = forward.clone().normalize();
    return end == CouplerState.End.FRONT ? unit : unit.multiply(-1.0);
  }

  /** 这一端挨着同列车的哪一节；是列车的外端时为空。 */
  static Optional<MinecartMember<?>> neighbourAt(MinecartMember<?> member, CouplerState.End end) {
    MinecartGroup group = member == null ? null : member.getGroup();
    Optional<Vector> forward = forward(member);
    Optional<Vector> center = center(member);
    if (group == null || forward.isEmpty() || center.isEmpty()) {
      return Optional.empty();
    }
    int index = group.indexOf(member);
    for (int candidate : new int[] {index - 1, index + 1}) {
      if (candidate < 0 || candidate >= group.size()) {
        continue;
      }
      MinecartMember<?> neighbour = group.get(candidate);
      Optional<Vector> other = center(neighbour);
      if (other.isPresent() && facing(forward.get(), center.get(), other.get()) == end) {
        return Optional.of(neighbour);
      }
    }
    return Optional.empty();
  }

  /** 这一端的端部位置（车厢中心沿朝向偏半车长）。 */
  static Optional<Vector> endPoint(MinecartMember<?> member, CouplerState.End end) {
    Optional<Vector> forward = forward(member);
    Optional<Vector> center = center(member);
    if (forward.isEmpty() || center.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        center.get().clone().add(outward(forward.get(), end).multiply(halfLength(member))));
  }
}
