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
 * 站台屏轮播：主页（到发）与副页（宣传页、公告、空位页）轮换，通过列车临近时锁定安全提示页。
 *
 * <ul>
 *   <li>每一段依次为：主页 {@code slide-main-seconds} → 副页 {@code
 *       slide-notice-seconds}。副页隔段轮换：本屏下一班列车有空位信息时，偶数段是空位页、奇数段是宣传页或公告；没有空位信息时每段都是宣传页或公告。宣传页按 {@link
 *       Signals#courtesy()} 的顺序轮流。一段副页放什么在副页开始时定下，之后段内列车状况变了也不换（进站、通过除外）；轮到公告的段在段首就定下（要从主页借时间）。
 *       副页停留时间为 0 时不轮播（空位页、公告页也不出现）。
 *   <li>公告（{@link Signals#bulletins()}）：有重要公告时轮到宣传页的段都放公告（全部公告依次轮流），宣传页暂停；只有一般公告时公告与宣传页隔次交替。 公告页每页停
 *       {@code bulletin-seconds}，两页的连着放；这些时间从本段主页里扣，一段的总长不变，同站各屏照样同时翻页。
 *   <li>本屏的下一班正在进站或停靠时不翻到宣传页：乘客这时抬头确认终点与站台。副页时段有空位信息就放空位页，没有就留在主页。
 *   <li>轮换按时钟计算，不依赖屏幕何时开始显示：同一车站的屏幕同时翻页，不同车站按站名错开，免得全服同一秒整页重发。
 *   <li>通过列车即将通过本屏的站台时显示安全提示页，并从最后一次看到它起锁定 {@code notice-pin-seconds}， 列车状态在两次检查之间变化也不会提早翻回主页。
 *   <li>主页上终点下面的英文与备注（{@link #remarks}）交替：英文停 {@code english-seconds}、备注停 {@code remark-seconds}。
 *       轮播的屏从每段主页的开头数起，翻回主页先写英文；不轮播的屏（车站统屏、副页停留为 0）按时钟交替，同样同站同步。
 *   <li>2×1 停站屏（{@link #stopList}）一页一页往下翻：停站表各页 → 后续列车 → 宣传页，换了一班车从停站表第 1 页起；下一班进站或停靠时只翻停站表。
 *       通过列车临近时同样锁定安全提示页（竖排版式）。
 * </ul>
 *
 * <p>状态都按屏幕记录：安全提示页的锁定时刻（过期即删），站台屏这一段副页放什么，停站屏正在显示哪一班车的哪一页、从何时起、下一张宣传页轮到第几张。
 */
public final class PidsCarousel {

  /** 屏幕久未刷新（没人看）时，停站屏一次最多补翻几页；再落后就从此刻起算。 */
  private static final int MAX_CATCH_UP = 32;

  private final Map<UUID, Instant> pinnedUntil = new ConcurrentHashMap<>();
  private final Map<UUID, SideSlot> sideSlots = new ConcurrentHashMap<>();
  private final Map<UUID, StopListState> stopLists = new ConcurrentHashMap<>();

  /** 主页之外的一页。 */
  public sealed interface Slide {

    /**
     * 宣传页或安全提示页（停站屏也用）。
     *
     * @param notice 哪一页
     */
    record Notice(PidsNotice notice) implements Slide, StopListSlide {}

    /** 下一班列车的空位页。 */
    record Vacancy() implements Slide {}

    /**
     * 公告的一页（停站屏也用）。
     *
     * @param key 公告的标识（{@link BulletinInfo#key()}）
     * @param page 第几页（0 起）
     */
    record Bulletin(Object key, int page) implements Slide, StopListSlide {

      public Bulletin {
        Objects.requireNonNull(key, "key");
      }
    }
  }

  /**
   * 本屏可轮播的一条公告。
   *
   * @param key 标识：内容改过就不相等
   * @param important 重要公告
   * @param pages 在本屏布局上排成几页（至少 1）
   */
  public record BulletinInfo(Object key, boolean important, int pages) {

    public BulletinInfo {
      Objects.requireNonNull(key, "key");
      pages = Math.max(1, pages);
    }
  }

  /** 2×1 停站屏的一页：停站表、后续列车或宣传页（含安全提示页 {@link Slide.Notice}）。 */
  public sealed interface StopListSlide {

    /**
     * 停站表。
     *
     * @param page 第几页（0 起）
     */
    record Stops(int page) implements StopListSlide {}

    /** 后续列车页。 */
    record Following() implements StopListSlide {}
  }

  /**
   * 本屏此刻的状况。
   *
   * @param passingSoon 本屏的站台此刻有通过列车即将通过
   * @param vacancy 本屏下一班列车有空位信息（空位页可以出现；停站屏不用）
   * @param arriving 本屏的下一班正在进站或停靠（不翻到宣传页）
   * @param courtesy 本屏轮换的宣传页，按顺序；为空时不放宣传页
   * @param bulletins 本屏可轮播的公告，按轮播顺序（重要的在前）
   */
  public record Signals(
      boolean passingSoon,
      boolean vacancy,
      boolean arriving,
      List<PidsNotice> courtesy,
      List<BulletinInfo> bulletins) {

    public Signals {
      courtesy = List.copyOf(courtesy);
      bulletins = List.copyOf(bulletins);
    }

    /** 没有公告。 */
    public Signals(
        boolean passingSoon, boolean vacancy, boolean arriving, List<PidsNotice> courtesy) {
      this(passingSoon, vacancy, arriving, courtesy, List.of());
    }

    /** 公告排成几页；不在清单里（已撤下）时为 0。 */
    int pagesOf(Object key) {
      return bulletins.stream()
          .filter(bulletin -> bulletin.key().equals(key))
          .mapToInt(BulletinInfo::pages)
          .findFirst()
          .orElse(0);
    }
  }

  /**
   * 停站屏这一班要翻的页。
   *
   * @param train 这一班的标识（换了车就不相等）；没有车时传任意固定值
   * @param stopPages 停站表的页数（至少 1）
   * @param following 有后续列车页
   */
  public record StopListPages(Object train, int stopPages, boolean following) {

    public StopListPages {
      Objects.requireNonNull(train, "train");
    }
  }

  /**
   * 此刻该显示的副页。
   *
   * @param screenId 屏幕
   * @param station 屏幕绑定的车站（决定翻页的错开量）
   * @param signals 本屏此刻的状况
   * @param now 当前时刻
   * @param render 轮播参数
   * @return 为空表示显示主页
   */
  public Optional<Slide> page(
      UUID screenId,
      PidsStationKey station,
      Signals signals,
      Instant now,
      PidsSettings.RenderSettings render) {
    if (pinned(screenId, signals.passingSoon(), now, render)) {
      return Optional.of(new Slide.Notice(PidsNotice.PASSING));
    }
    if (render.slideNoticeSeconds() <= 0) {
      return Optional.empty();
    }
    long period = period(render);
    long position = position(station, now, render);
    long segment = Math.floorDiv(position, period);
    SideSlot slot =
        sideSlots.compute(
            screenId,
            (id, old) ->
                old != null && old.segment() == segment
                    ? old
                    : side(segment, signals, render, period));
    long main = period - slot.seconds();
    long elapsed = Math.floorMod(position, period);
    if (elapsed < main) {
      return Optional.empty();
    }
    if (signals.arriving()) {
      return signals.vacancy() ? Optional.of(new Slide.Vacancy()) : Optional.empty();
    }
    if (slot.provisional()) {
      SideSlot decided = side(segment, signals, render, period);
      slot = new SideSlot(segment, decided.slide(), slot.seconds(), decided.pages(), false);
      sideSlots.put(screenId, slot);
    }
    SideSlot shown = slot;
    return shown
        .slide()
        .map(
            slide ->
                slide instanceof Slide.Bulletin bulletin
                    ? new Slide.Bulletin(
                        bulletin.key(),
                        (int)
                            Math.min(
                                shown.pages() - 1L, (elapsed - main) / render.bulletinSeconds()))
                    : slide);
  }

  /**
   * 第 {@code segment} 段副页放什么、停多久：有空位信息时偶数段放空位页；其余段按段号轮到宣传页或公告（有空位页时只数奇数段）。 公告停“页数 × {@code
   * bulletin-seconds}”，至多占到这一段只剩 1 秒主页。
   *
   * <p>公告要从主页借时间，段首就定下；其余为暂定，只决定主页多长，到副页开始时再按当时的状况重定（段内空位信息出现等照旧生效）。
   */
  private static SideSlot side(
      long segment, Signals signals, PidsSettings.RenderSettings render, long period) {
    if (signals.vacancy() && segment % 2 == 0) {
      return new SideSlot(
          segment, Optional.of(new Slide.Vacancy()), render.slideNoticeSeconds(), 1, true);
    }
    long turn = signals.vacancy() ? segment / 2 : segment;
    Optional<Slide> slide = courtesyOrBulletin(turn, signals);
    if (slide.isPresent() && slide.get() instanceof Slide.Bulletin bulletin) {
      int pages = Math.max(1, signals.pagesOf(bulletin.key()));
      long seconds = Math.min((long) pages * render.bulletinSeconds(), period - 1);
      return new SideSlot(segment, slide, seconds, pages, false);
    }
    return new SideSlot(segment, slide, render.slideNoticeSeconds(), 1, true);
  }

  /**
   * 第 {@code turn} 次轮到宣传页或公告时放什么：有重要公告时只放公告（全部公告依次轮流），只有一般公告时公告与宣传页交替， 没有宣传页可放时只放公告；没有公告时宣传页依次轮流。
   *
   * @return 公告时为第 1 页；什么都没有时为空
   */
  private static Optional<Slide> courtesyOrBulletin(long turn, Signals signals) {
    List<BulletinInfo> bulletins = signals.bulletins();
    List<PidsNotice> courtesy = signals.courtesy();
    if (!bulletins.isEmpty()) {
      boolean important = bulletins.stream().anyMatch(BulletinInfo::important);
      if (important || courtesy.isEmpty()) {
        return Optional.of(firstPage(bulletins.get((int) Math.floorMod(turn, bulletins.size()))));
      }
      long round = Math.floorDiv(turn, 2);
      return Optional.of(
          Math.floorMod(turn, 2) == 0
              ? firstPage(bulletins.get((int) Math.floorMod(round, bulletins.size())))
              : new Slide.Notice(courtesy.get((int) Math.floorMod(round, courtesy.size()))));
    }
    if (courtesy.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Slide.Notice(courtesy.get((int) Math.floorMod(turn, courtesy.size()))));
  }

  private static Slide.Bulletin firstPage(BulletinInfo bulletin) {
    return new Slide.Bulletin(bulletin.key(), 0);
  }

  /**
   * 站台屏这一段副页放什么。
   *
   * @param segment 段号
   * @param slide 这一段的副页；为空表示留在主页；公告时为第 1 页
   * @param seconds 副页停多久（本段主页为一段的秒数减去它）
   * @param pages 公告的页数；其余为 1
   * @param provisional 暂定：副页开始时再按当时的状况重定（停留时间不变）
   */
  private record SideSlot(
      long segment, Optional<Slide> slide, long seconds, int pages, boolean provisional) {}

  /**
   * 2×1 停站屏此刻显示哪一页：停站表每页、后续列车页各停 {@code stop-page-seconds}，宣传页停 {@code slide-notice-seconds}，公告每页停
   * {@code bulletin-seconds}，一页一页往下翻。宣传页与公告的轮换规则同站台屏（{@link #courtesyOrBulletin}），一轮放一张（公告的几页连着放）。
   *
   * <p>换了一班车从停站表第 1 页起，乘客先看到近处的站。同站几块屏在一次检查间隔（默认 1 秒）内看到同一班车，翻页最多差这么多。
   * 下一页是什么在翻页时按当时的状况定：有后续列车才放后续列车页，有可放的宣传页才放宣传页，列车状况中途变了不会让正在显示的一页跳走。 下一班进站或停靠时立即回到停站表第 1
   * 页、只翻停站表；通过列车临近时锁定安全提示页。宣传页按屏幕依次轮换，换车不重来。
   *
   * @param screenId 屏幕
   * @param signals 本屏此刻的状况（空位信息不用）
   * @param pages 这一班要翻的页
   * @param now 当前时刻
   * @param render 轮播参数
   */
  public StopListSlide stopList(
      UUID screenId,
      Signals signals,
      StopListPages pages,
      Instant now,
      PidsSettings.RenderSettings render) {
    if (pinned(screenId, signals.passingSoon(), now, render)) {
      return new Slide.Notice(PidsNotice.PASSING);
    }
    return stopLists
        .compute(screenId, (id, old) -> advance(old, signals, pages, now, render))
        .slide();
  }

  /** 停站屏翻到此刻：换车、进站或停站表变短时回到第 1 页，否则把到时的页依次翻过去。 */
  private static StopListState advance(
      StopListState old,
      Signals signals,
      StopListPages pages,
      Instant now,
      PidsSettings.RenderSettings render) {
    int turn = old == null ? 0 : old.turn();
    StopListState first = new StopListState(pages.train(), FIRST_PAGE, now, turn);
    if (old == null
        || !old.train().equals(pages.train())
        || (signals.arriving() && !(old.slide() instanceof StopListSlide.Stops))
        || (old.slide() instanceof StopListSlide.Stops stops
            && stops.page() >= pages.stopPages())) {
      return first;
    }
    StopListState state = old;
    for (int step = 0; step < MAX_CATCH_UP; step++) {
      Instant end = state.since().plusSeconds(seconds(state.slide(), render));
      if (now.isBefore(end)) {
        return state;
      }
      state = next(state, end, signals, pages, render);
    }
    return new StopListState(state.train(), state.slide(), now, state.turn());
  }

  /** 这一页之后的一页，从 {@code since} 起显示。 */
  private static StopListState next(
      StopListState state,
      Instant since,
      Signals signals,
      StopListPages pages,
      PidsSettings.RenderSettings render) {
    StopListSlide slide = state.slide();
    boolean extras = !signals.arriving();
    if (slide instanceof StopListSlide.Stops stops && stops.page() + 1 < pages.stopPages()) {
      return state.showing(new StopListSlide.Stops(stops.page() + 1), since);
    }
    if (slide instanceof StopListSlide.Stops && extras && pages.following()) {
      return state.showing(new StopListSlide.Following(), since);
    }
    if (slide instanceof Slide.Bulletin bulletin
        && extras
        && bulletin.page() + 1 < signals.pagesOf(bulletin.key())) {
      return state.showing(new Slide.Bulletin(bulletin.key(), bulletin.page() + 1), since);
    }
    if (!(slide instanceof Slide.Notice || slide instanceof Slide.Bulletin)
        && extras
        && render.slideNoticeSeconds() > 0) {
      Optional<Slide> side = courtesyOrBulletin(state.turn(), signals);
      if (side.isPresent() && side.get() instanceof StopListSlide next) {
        return new StopListState(state.train(), next, since, state.turn() + 1);
      }
    }
    return state.showing(FIRST_PAGE, since);
  }

  /**
   * 一页停多久：宣传页停 {@code slide-notice-seconds}，公告每页停 {@code bulletin-seconds}，停站表与后续列车页停 {@code
   * stop-page-seconds}。
   */
  private static long seconds(StopListSlide slide, PidsSettings.RenderSettings render) {
    return switch (slide) {
      case Slide.Notice ignored -> render.slideNoticeSeconds();
      case Slide.Bulletin ignored -> render.bulletinSeconds();
      default -> render.stopPageSeconds();
    };
  }

  /** 此刻是否锁定在安全提示页：通过列车即将通过本屏的站台时锁定，从最后一次看到它起保持 {@code notice-pin-seconds}。 */
  private boolean pinned(
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
            ? Math.floorMod(position(station, now, render), period(render))
            : Math.floorMod(now.getEpochSecond() + offset(station, cycle), cycle);
    return elapsed % cycle >= english;
  }

  /** 停站表第 1 页。 */
  private static final StopListSlide FIRST_PAGE = new StopListSlide.Stops(0);

  /**
   * 停站屏正在显示的一页。
   *
   * @param train 这一班的标识
   * @param slide 这一页
   * @param since 这一页开始显示的时刻
   * @param turn 下一张宣传页轮到清单里的第几张
   */
  private record StopListState(Object train, StopListSlide slide, Instant since, int turn) {

    /** 同一班车翻到另一页。 */
    StopListState showing(StopListSlide next, Instant from) {
      return new StopListState(train, next, from, turn);
    }
  }

  /** 一段（主页加副页）的秒数。 */
  private static long period(PidsSettings.RenderSettings render) {
    return (long) render.slideMainSeconds() + render.slideNoticeSeconds();
  }

  /**
   * 按车站错开后的时钟秒数：除以一段的秒数得段号、取余得段内位置。同站相同、不同车站错开；错开量在全部宣传页与空位页各轮到一次 （{@code 2 × 宣传页种数}
   * 段）的范围里取，与本屏轮换几张无关，同站各屏轮换的宣传页不同也同时翻页。
   */
  private static long position(
      PidsStationKey station, Instant now, PidsSettings.RenderSettings render) {
    return now.getEpochSecond() + offset(station, period(render) * PidsNotice.courtesyCount() * 2);
  }

  /** 按车站错开的秒数：同站同时翻页，不同车站分散。 */
  static long offset(PidsStationKey station, long cycle) {
    return Math.floorMod(station.toString().hashCode(), cycle);
  }
}
