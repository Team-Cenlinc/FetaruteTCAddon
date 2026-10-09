package org.fetarute.fetaruteTCAddon.drive.seat;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.util.Collection;

/**
 * 读到的驾驶室座位（要逐节看座位附件的名字），连同读时的编组、节数与 tick：换端判定每拍都要用，尽头式待命可能持续几分钟，不必每 tick 重读。驾驶员会话与车掌会话共用。
 *
 * @param group 读时的编组
 * @param size 读时的节数
 * @param tick 读时的 tick
 * @param seats 驾驶室座位
 */
public record CabSeatsMemo(MinecartGroup group, int size, long tick, CabSeats seats) {

  /** 同一编组、同样节数时多久重读一次（tick）；挂上或摘下车厢时马上重读。 */
  public static final long REFRESH_TICKS = 20L;

  /**
   * 还能用就沿用，否则重读。
   *
   * @param memo 上次的；没有时为 {@code null}
   * @param cabNames 驾驶座名单
   */
  public static CabSeatsMemo refresh(
      CabSeatsMemo memo, MinecartGroup group, Collection<String> cabNames, long nowTick) {
    if (memo != null
        && memo.group() == group
        && memo.size() == group.size()
        && nowTick - memo.tick() < REFRESH_TICKS) {
      return memo;
    }
    return new CabSeatsMemo(group, group.size(), nowTick, SeatLocator.cabSeats(group, cabNames));
  }
}
