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
 * 站台屏轮播：主页（到发）与副页（宣传页、空位页）轮换，通过列车临近时锁定安全提示页。
 *
 * <ul>
 *   <li>每一段依次为：主页 {@code slide-main-seconds} → 副页 {@code
 *       slide-notice-seconds}。副页隔段轮换：本屏下一班列车有空位信息时，偶数段是空位页、奇数段是宣传页（三张轮流）；没有空位信息时每段都是宣传页。副页停留时间为 0
 *       时不轮播（空位页也不出现）。
 *   <li>本屏的下一班正在进站或停靠时不翻到宣传页：乘客这时抬头确认终点与站台。副页时段有空位信息就放空位页，没有就留在主页。
 *   <li>轮换按时钟计算，不依赖屏幕何时开始显示：同一车站的屏幕同时翻页，不同车站按站名错开，免得全服同一秒整页重发。
 *   <li>通过列车即将通过本屏的站台时显示安全提示页，并从最后一次看到它起锁定 {@code notice-pin-seconds}， 列车状态在两次检查之间变化也不会提早翻回主页。
 *   <li>主页上终点下面的英文与备注（{@link #remarks}）交替：英文停 {@code english-seconds}、备注停 {@code remark-seconds}。
 *       轮播的屏从每段主页的开头数起，翻回主页先写英文；不轮播的屏（车站统屏、副页停留为 0）按时钟交替，同样同站同步。
 *   <li>2×1 停站屏不轮播宣传页与空位页，停站多时按 {@link #stopPage} 翻页（换了一班车从第 1 页起）；通过列车临近时同样锁定安全提示页（竖排版式）。
 * </ul>
 *
 * <p>状态只有两样，都按屏幕记录：安全提示页的锁定时刻（过期即删），停站屏正在显示哪一班车、从何时起（换车即换）。
 */
public final class PidsCarousel {

  /** 轮流出现的宣传页。 */
  static final List<PidsNotice> COURTESY =
      List.of(PidsNotice.ORDER, PidsNotice.QUEUE, PidsNotice.DOORS);

  private final Map<UUID, Instant> pinnedUntil = new ConcurrentHashMap<>();
  private final Map<UUID, PageStart> stopPages = new ConcurrentHashMap<>();

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
   * @param arriving 本屏的下一班正在进站或停靠（不翻到宣传页）
   * @param now 当前时刻
   * @param render 轮播参数
   * @return 为空表示显示主页
   */
  public Optional<Slide> page(
      UUID screenId,
      PidsStationKey station,
      boolean passingSoon,
      boolean vacancy,
      boolean arriving,
      Instant now,
      PidsSettings.RenderSettings render) {
    if (pinned(screenId, passingSoon, now, render)) {
      return Optional.of(new Slide.Notice(PidsNotice.PASSING));
    }
    if (render.slideNoticeSeconds() <= 0) {
      return Optional.empty();
    }
    long period = period(render);
    long position = position(station, now, render);
    if (position % period < render.slideMainSeconds()) {
      return Optional.empty();
    }
    int segment = (int) (position / period);
    if (vacancy && (arriving || segment % 2 == 0)) {
      return Optional.of(new Slide.Vacancy());
    }
    return arriving
        ? Optional.empty()
        : Optional.of(new Slide.Notice(COURTESY.get(segment % COURTESY.size())));
  }

  /**
   * 此刻是否锁定在安全提示页：通过列车即将通过本屏的站台时锁定，从最后一次看到它起保持 {@code notice-pin-seconds}。
   *
   * <p>不轮播副页的屏（2×1 停站屏）也用它：安全提示优先于停站表。
   *
   * @param screenId 屏幕
   * @param passingSoon 本屏的站台此刻有通过列车即将通过
   * @param now 当前时刻
   * @param render 轮播参数
   */
  public boolean pinned(
      UUID screenId, boolean passingSoon, Instant now, PidsSettings.RenderSettings render) {
    Objects.requireNonNull(screenId, "screenId");
    if (passingSoon) {
      pinnedUntil.merge(
          screenId,
          now.plusSeconds(render.noticePinSeconds()),
          (old, next) -> next.isAfter(old) ? next : old);
    }
    Instant pin = pinnedUntil.get(screenId);
    if (pin == null) {
      return false;
    }
    if (now.isBefore(pin)) {
      return true;
    }
    pinnedUntil.remove(screenId, pin);
    return false;
  }

  /**
   * 此刻主页上是否轮到备注（终点下面那一格写备注，否则写英文）。
   *
   * @param station 屏幕绑定的车站（决定错开量）
   * @param now 当前时刻
   * @param render 轮播参数
   * @param rotating 这块屏轮播副页：从每段主页的开头数起，翻回主页先写英文
   * @return {@code remark-seconds} 为 0 时恒为 false
   */
  public static boolean remarks(
      PidsStationKey station, Instant now, PidsSettings.RenderSettings render, boolean rotating) {
    long remark = render.remarkSeconds();
    if (remark <= 0) {
      return false;
    }
    long english = render.englishSeconds();
    long cycle = english + remark;
    long elapsed =
        rotating && render.slideNoticeSeconds() > 0
            ? position(station, now, render) % period(render)
            : Math.floorMod(now.getEpochSecond() + offset(station, cycle), cycle);
    return elapsed % cycle >= english;
  }

  /**
   * 2×1 停站屏此刻显示第几页：每页停 {@code stop-page-seconds} 秒；换了一班车从第 1 页重新数起，乘客先看到近处的站。
   *
   * <p>每块屏从它看到这班车起算：同站几块屏在一次检查间隔（默认 1 秒）内看到同一班车，翻页最多差这么多。
   *
   * @param screenId 屏幕
   * @param train 这一班的标识（换了车就不相等）；没有车时传任意固定值
   * @param now 当前时刻
   * @param render 轮播参数
   * @param pages 总页数
   * @return 0 起的页号；只有一页时为 0
   */
  public int stopPage(
      UUID screenId, Object train, Instant now, PidsSettings.RenderSettings render, int pages) {
    Objects.requireNonNull(screenId, "screenId");
    Objects.requireNonNull(train, "train");
    PageStart start =
        stopPages.compute(
            screenId,
            (id, old) ->
                old != null && old.train().equals(train) ? old : new PageStart(train, now));
    if (pages <= 1) {
      return 0;
    }
    long elapsed = Math.max(0L, now.getEpochSecond() - start.since().getEpochSecond());
    return (int) ((elapsed / render.stopPageSeconds()) % pages);
  }

  /**
   * 停站屏正在显示的一班车与它开始显示的时刻。
   *
   * @param train 这一班的标识
   * @param since 开始显示的时刻
   */
  private record PageStart(Object train, Instant since) {}

  /** 一段（主页加副页）的秒数。 */
  private static long period(PidsSettings.RenderSettings render) {
    return (long) render.slideMainSeconds() + render.slideNoticeSeconds();
  }

  /** 此刻在一整轮（每张宣传页与空位页各轮到一次，共 {@code 2 × 宣传页数} 段）里的秒数，同站相同、不同车站错开。 */
  private static long position(
      PidsStationKey station, Instant now, PidsSettings.RenderSettings render) {
    long cycle = period(render) * COURTESY.size() * 2;
    return Math.floorMod(now.getEpochSecond() + offset(station, cycle), cycle);
  }

  /** 按车站错开的秒数：同站同时翻页，不同车站分散。 */
  static long offset(PidsStationKey station, long cycle) {
    return Math.floorMod(station.toString().hashCode(), cycle);
  }
}
