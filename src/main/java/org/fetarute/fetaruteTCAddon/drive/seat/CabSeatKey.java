package org.fetarute.fetaruteTCAddon.drive.seat;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一个具体的座位：哪节车厢（按车厢实体，不按编组次序）的第几个座位。整列调头会翻转车厢序号（{@link SeatBinding#memberIndex()}），这个键不受影响，
 * 用来记住驾驶员确认过的驾驶座。
 *
 * @param member 车厢实体的 UUID
 * @param seatIndex 座位序号（见 {@link SeatBinding#seatIndex()}）
 */
public record CabSeatKey(UUID member, int seatIndex) {

  public CabSeatKey {
    Objects.requireNonNull(member, "member");
  }

  /**
   * 座位绑定对应的具体座位。
   *
   * @return 绑定的车厢已不在编组里时为空
   */
  public static Optional<CabSeatKey> of(MinecartGroup group, SeatBinding seat) {
    if (group == null || seat == null || seat.memberIndex() >= group.size()) {
      return Optional.empty();
    }
    return Optional.of(
        new CabSeatKey(group.get(seat.memberIndex()).getEntity().getUniqueId(), seat.seatIndex()));
  }
}
