package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 车辆交路与加解编的关系：这条交路由哪一班主干班次在途中解编生出（{@code DECP}），以及在哪一班主干班次上联挂并入（{@code COPL}）。
 *
 * <p>普通交路两者都为空：从车库出库、回车库收尾。由解编开始的交路没有出库走行，起点是解编站；以联挂结束的交路没有回库走行，终点是联挂站，
 * 车并进跑那一班主干班次的车里。交路之间的父子关系按班次认：主干班次所在的交路就是父交路 / 并入的交路。
 *
 * @param splitFromTripId 由这一班主干班次解编生出；普通交路为空
 * @param joinsTripId 在这一班主干班次上联挂并入；普通交路为空
 */
public record DutyCoupling(Optional<UUID> splitFromTripId, Optional<UUID> joinsTripId) {

  /** 普通交路。 */
  public static final DutyCoupling NONE = new DutyCoupling(Optional.empty(), Optional.empty());

  public DutyCoupling {
    splitFromTripId = splitFromTripId == null ? Optional.empty() : splitFromTripId;
    joinsTripId = joinsTripId == null ? Optional.empty() : joinsTripId;
  }

  /** 由解编开始。 */
  public boolean startsBySplit() {
    return splitFromTripId.isPresent();
  }

  /** 以联挂结束。 */
  public boolean endsByJoin() {
    return joinsTripId.isPresent();
  }

  /** 班次主键换了（临时主键 → 最终主键）：引用跟着换；映射不到的保持原值。 */
  public DutyCoupling remap(Function<UUID, UUID> tripIds) {
    if (this == NONE || (splitFromTripId.isEmpty() && joinsTripId.isEmpty())) {
      return this;
    }
    return new DutyCoupling(
        splitFromTripId.map(id -> Optional.ofNullable(tripIds.apply(id)).orElse(id)),
        joinsTripId.map(id -> Optional.ofNullable(tripIds.apply(id)).orElse(id)));
  }
}
