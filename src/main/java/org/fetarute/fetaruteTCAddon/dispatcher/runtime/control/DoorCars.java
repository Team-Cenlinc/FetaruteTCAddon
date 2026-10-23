package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;

/**
 * 一次停站中开关门的车厢：停车位置标写了 {@code door:} 时只开其中几节，否则全车开门。
 *
 * <p>到站时按行进方向从第一节数起选出车厢，记下车厢本身而不是序号：停站中整列调头（车厢序号翻转）也还是站台边的这几节。本类不依赖服务器对象。
 */
public final class DoorCars {

  /** 全车开门。 */
  public static final DoorCars ALL = new DoorCars(null, "*", null);

  /** 开门车厢的实体 UUID；全车开门时为 {@code null}。 */
  private final Set<UUID> carts;

  private final String spec;

  /** 按哪块停车位置标选的；没有标志、只有一节（{@link #only}）时为 {@code null}。 */
  private final StopMarkSign sign;

  private DoorCars(Set<UUID> carts, String spec, StopMarkSign sign) {
    this.carts = carts == null ? null : Set.copyOf(carts);
    this.spec = spec;
    this.sign = sign;
  }

  /**
   * 按停车位置标的 {@code door:} 选出开门的车厢。
   *
   * @param sign 本站选中的停车位置标；没有标志时为 {@code null}（全车开门）
   * @param cartsFromHead 各节车厢的实体 UUID，按行进方向从第一节排起；取不到实体的车厢为 {@code null}，不开门
   */
  public static DoorCars select(StopMarkSign sign, List<UUID> cartsFromHead) {
    if (sign == null) {
      return ALL;
    }
    if (sign.allDoors()) {
      return new DoorCars(null, "*", sign);
    }
    Set<UUID> chosen = new HashSet<>();
    for (int i = 0; i < cartsFromHead.size(); i++) {
      UUID cart = cartsFromHead.get(i);
      if (cart != null && sign.opensDoorsAt(i + 1)) {
        chosen.add(cart);
      }
    }
    return new DoorCars(chosen, sign.describeDoors(), sign);
  }

  /**
   * 停站中挂上来的车厢里开门的那几节。没有停车位置标（按车站牌子居中）时都开；对准的标志对得上挂上以后整列的节数时，照同一块标志、按整列从第一节数起的节数选；
   * 标志只对得上先到那列自己的节数时都不开（挂上来的多半在站台外）；只有一节（{@link #only}）时都不开。
   *
   * @param cartsFromHead 挂上以后各节车厢的实体 UUID，按行进方向从第一节排起
   * @param joined 挂上来的那几节
   */
  public DoorCars joined(List<UUID> cartsFromHead, Set<UUID> joined) {
    Set<UUID> chosen = new HashSet<>();
    boolean markFits = sign == null || sign.matches(cartsFromHead.size());
    for (int i = 0; i < cartsFromHead.size() && markFits; i++) {
      UUID cart = cartsFromHead.get(i);
      if (cart != null
          && joined.contains(cart)
          && (sign == null ? carts == null : carts == null || sign.opensDoorsAt(i + 1))) {
        chosen.add(cart);
      }
    }
    return new DoorCars(chosen, spec, sign);
  }

  /** 只留 {@code present} 里的车厢：全车开门的改成明确的这几节（停站中挂上来、没跟着开门的车厢不算在内）。 */
  public DoorCars restrictTo(Set<UUID> present) {
    Set<UUID> kept = new HashSet<>();
    for (UUID cart : present) {
      if (cart != null && includes(cart)) {
        kept.add(cart);
      }
    }
    return new DoorCars(kept, spec, sign);
  }

  /** 并上 {@code more} 的车厢（停站中挂上来、跟着开门的）；全车开门时还是全车。 */
  public DoorCars plus(DoorCars more) {
    if (carts == null || more == null) {
      return this;
    }
    if (more.carts == null) {
      return more;
    }
    Set<UUID> union = new HashSet<>(carts);
    union.addAll(more.carts);
    return new DoorCars(union, spec, sign);
  }

  /**
   * 只有这一节车厢（逐节判定开门侧时每节车单独播动画）。
   *
   * @param cart 车厢实体 UUID；为 {@code null} 时不含任何车厢
   */
  public static DoorCars only(UUID cart) {
    return new DoorCars(cart == null ? Set.of() : Set.of(cart), "1", null);
  }

  /** 是否全车开门。 */
  public boolean all() {
    return carts == null;
  }

  /** 一节都不开。 */
  public boolean none() {
    return carts != null && carts.isEmpty();
  }

  /** 这节车厢开不开门。 */
  public boolean includes(UUID cart) {
    return carts == null || (cart != null && carts.contains(cart));
  }

  /** 诊断输出，例如 {@code *}、{@code 1(1)}（写法与选中的节数）。 */
  public String summary() {
    return carts == null ? spec : spec + "(" + carts.size() + ")";
  }

  @Override
  public String toString() {
    return "DoorCars[" + summary() + "]";
  }
}
