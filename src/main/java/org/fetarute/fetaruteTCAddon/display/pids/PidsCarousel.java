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
 * 站台屏轮播：主页与副页（宣传页、空位页）轮换，通过列车临近时锁定安全提示页。
 *
 * <ul>
 *   <li>每一段依次为：主页 {@code slide-main-seconds} → 副页 {@code
 *       slide-notice-seconds}。副页隔段轮换：本屏下一班列车有空位信息时，偶数段是空位页、奇数段是宣传页（三张轮流）；没有空位信息时每段都是宣传页。副页停留时间为 0
 *       时不轮播（空位页也不出现）。
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

  /** 主页之外的一页。 */
  public sealed interface Slide {

    /**
     * 宣传页或安全提示页。
     *
     * @param notice 哪一页
     */
    record Notice(PidsNotice notice) implements Slide {}

    /** 下一班列车的空位页。 */
    record Vacancy() implements Slide {}
  }

  /**
   * 此刻该显示的副页。
   *
   * @param screenId 屏幕
   * @param station 屏幕绑定的车站（决定翻页的错开量）
   * @param passingSoon 本屏的站台此刻有通过列车即将通过
   * @param vacancy 本屏下一班列车有空位信息（空位页可以出现）
   * @param now 当前时刻
   * @param render 轮播参数
   * @return 为空表示显示主页
   */
  public Optional<Slide> page(
      UUID screenId,
      PidsStationKey station,
      boolean passingSoon,
      boolean vacancy,
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
        return Optional.of(new Slide.Notice(PidsNotice.PASSING));
      }
      pinnedUntil.remove(screenId, pin);
    }
    if (render.slideNoticeSeconds() <= 0) {
      return Optional.empty();
    }
    long period = (long) render.slideMainSeconds() + render.slideNoticeSeconds();
    long cycle = period * COURTESY.size() * 2;
    long position = Math.floorMod(now.getEpochSecond() + offset(station, cycle), cycle);
    if (position % period < render.slideMainSeconds()) {
      return Optional.empty();
    }
    int segment = (int) (position / period);
    return Optional.of(
        vacancy && segment % 2 == 0
            ? new Slide.Vacancy()
            : new Slide.Notice(COURTESY.get(segment % COURTESY.size())));
  }

  /** 按车站错开的秒数：同站同时翻页，不同车站分散。 */
  static long offset(PidsStationKey station, long cycle) {
    return Math.floorMod(station.toString().hashCode(), cycle);
  }
}
