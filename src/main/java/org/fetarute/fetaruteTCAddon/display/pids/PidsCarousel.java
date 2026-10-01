package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;

/**
 * 站台屏轮播：主页与宣传页轮换，通过列车临近时锁定安全提示页。
 *
 * <ul>
 *   <li>一轮依次为：主页 {@code slide-main-seconds} → 宣传页 {@code slide-notice-seconds}，三张宣传页轮流出现； 宣传页停留时间为
 *       0 时不轮播。
 *   <li>轮换按时钟计算，不依赖屏幕何时开始显示：同一车站的屏幕同时翻页，不同车站按站名错开，免得全服同一秒整页重发。
 *   <li>通过列车即将通过本屏的站台时显示安全提示页，并从最后一次看到它起锁定 {@code notice-pin-seconds}， 列车状态在两次检查之间变化也不会提早翻回主页。
 * </ul>
 *
 * <p>只有锁定时刻是状态，按屏幕记录，过期即删。
 */
public final class PidsCarousel {

  /** 轮流出现的宣传页。 */
  static final List<PidsNotice> COURTESY =
      List.of(PidsNotice.ORDER, PidsNotice.QUEUE, PidsNotice.DOORS);

  private final Map<UUID, Instant> pinnedUntil = new ConcurrentHashMap<>();

  /**
   * 此刻该显示的宣传页或安全提示页。
   *
   * @param screenId 屏幕
   * @param station 屏幕绑定的车站（决定翻页的错开量）
   * @param passingSoon 本屏的站台此刻有通过列车即将通过
   * @param now 当前时刻
   * @param render 轮播参数
   * @return 为空表示显示主页
   */
  public Optional<PidsNotice> page(
      UUID screenId,
      PidsStationKey station,
      boolean passingSoon,
      Instant now,
      PidsSettings.RenderSettings render) {
    Objects.requireNonNull(screenId, "screenId");
    if (passingSoon) {
      pinnedUntil.merge(
          screenId,
          now.plusSeconds(render.noticePinSeconds()),
          (old, next) -> next.isAfter(old) ? next : old);
    }
    Instant pin = pinnedUntil.get(screenId);
    if (pin != null) {
      if (now.isBefore(pin)) {
        return Optional.of(PidsNotice.PASSING);
      }
      pinnedUntil.remove(screenId, pin);
    }
    if (render.slideNoticeSeconds() <= 0) {
      return Optional.empty();
    }
    long period = (long) render.slideMainSeconds() + render.slideNoticeSeconds();
    long cycle = period * COURTESY.size();
    long position = Math.floorMod(now.getEpochSecond() + offset(station, cycle), cycle);
    if (position % period < render.slideMainSeconds()) {
      return Optional.empty();
    }
    return Optional.of(COURTESY.get((int) (position / period)));
  }

  /** 按车站错开的秒数：同站同时翻页，不同车站分散。 */
  static long offset(PidsStationKey station, long cycle) {
    return Math.floorMod(station.toString().hashCode(), cycle);
  }
}
