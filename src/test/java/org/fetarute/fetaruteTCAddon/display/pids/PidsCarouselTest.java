package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsCarousel.Signals;
import org.fetarute.fetaruteTCAddon.display.pids.PidsCarousel.Slide;
import org.fetarute.fetaruteTCAddon.display.pids.PidsCarousel.StopListPages;
import org.fetarute.fetaruteTCAddon.display.pids.PidsCarousel.StopListSlide;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.junit.jupiter.api.Test;

/**
 * 轮播（用例按主页 12 秒、副页 4 秒、英文与备注各 4 秒、停站屏每页 6 秒计，与默认值无关）：主页与副页轮流出现；有空位信息时副页隔段是空位页；
 * 下一班进站或停靠时不翻到宣传页；通过列车锁定安全提示页 15 秒；副页时长为 0 时不轮播。轮播的屏翻回主页先写英文。 2×1 停站屏依次翻停站表、后续列车与宣传页。
 */
class PidsCarouselTest {

  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");
  private static final PidsSettings.RenderSettings RENDER =
      new PidsSettings.RenderSettings(20, 5, 30, 12, 4, 15, 4, 4, 6);
  private static final List<PidsNotice> ALL = PidsNotice.courtesy();

  /** 一整轮 160 秒（全部五张宣传页与空位页各一次，十段 ×（12 + 4））的起点：这一刻 HHU 的屏幕刚翻到主页。 */
  private static Instant roundStart() {
    return cycleStart(160);
  }

  /** 按 {@code cycle} 秒轮换时 HHU 一轮的起点。 */
  private static Instant cycleStart(long cycle) {
    long offset = PidsCarousel.offset(HHU, cycle);
    long base = 1_790_000_000L;
    return Instant.ofEpochSecond(base - Math.floorMod(base + offset, cycle));
  }

  @Test
  void defaultsGiveTheMainPageMostOfTheTime() {
    PidsSettings.RenderSettings defaults = PidsSettings.RenderSettings.DEFAULT;

    assertEquals(20, defaults.slideMainSeconds());
    assertEquals(5, defaults.slideNoticeSeconds());
    assertEquals(6, defaults.englishSeconds());
    assertEquals(4, defaults.remarkSeconds());
    assertEquals(8, defaults.stopPageSeconds());
    assertEquals(
        List.of(
            PidsNotice.ORDER, PidsNotice.QUEUE, PidsNotice.DOORS, PidsNotice.CHECK, PidsNotice.GAP),
        defaults.notices());
  }

  @Test
  void mainPageAlternatesWithEachCourtesyPage() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();

    assertEquals(Optional.empty(), page(carousel, screen, start, false));
    assertEquals(Optional.empty(), page(carousel, screen, start.plusSeconds(11), false));
    assertEquals(notice(PidsNotice.ORDER), page(carousel, screen, start.plusSeconds(12), false));
    assertEquals(notice(PidsNotice.ORDER), page(carousel, screen, start.plusSeconds(15), false));
    assertEquals(Optional.empty(), page(carousel, screen, start.plusSeconds(16), false));
    assertEquals(notice(PidsNotice.QUEUE), page(carousel, screen, start.plusSeconds(28), false));
    assertEquals(notice(PidsNotice.DOORS), page(carousel, screen, start.plusSeconds(44), false));
    assertEquals(notice(PidsNotice.CHECK), page(carousel, screen, start.plusSeconds(60), false));
    assertEquals(notice(PidsNotice.GAP), page(carousel, screen, start.plusSeconds(76), false));
    assertEquals(Optional.empty(), page(carousel, screen, start.plusSeconds(80), false), "下一段");
  }

  /** 宣传页按配置的顺序轮换，配置里没有的不出现。 */
  @Test
  void courtesyPagesFollowTheConfiguredList() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Signals two = new Signals(false, false, false, List.of(PidsNotice.GAP, PidsNotice.ORDER));

    assertEquals(
        notice(PidsNotice.GAP), carousel.page(screen, HHU, two, start.plusSeconds(12), RENDER));
    assertEquals(
        notice(PidsNotice.ORDER), carousel.page(screen, HHU, two, start.plusSeconds(28), RENDER));
    assertEquals(
        notice(PidsNotice.GAP), carousel.page(screen, HHU, two, start.plusSeconds(44), RENDER));
  }

  /** 同站的屏同时翻页，轮换的宣传页不同（有的站台没有“确认终点”）也一样。 */
  @Test
  void screensOfTheSameStationFlipTogether() {
    PidsCarousel carousel = new PidsCarousel();
    Signals withoutCheck =
        new Signals(
            false, false, false, List.of(PidsNotice.ORDER, PidsNotice.QUEUE, PidsNotice.DOORS));
    Instant main = roundStart().plusSeconds(16);
    Instant side = roundStart().plusSeconds(28);

    assertEquals(
        page(carousel, UUID.randomUUID(), side, false),
        page(carousel, UUID.randomUUID(), side, false));
    assertEquals(
        Optional.empty(), carousel.page(UUID.randomUUID(), HHU, withoutCheck, main, RENDER));
    assertTrue(carousel.page(UUID.randomUUID(), HHU, withoutCheck, side, RENDER).isPresent());
  }

  @Test
  void passingTrainsPinTheSafetyPage() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();

    assertEquals(notice(PidsNotice.PASSING), page(carousel, screen, start, true));
    assertEquals(
        notice(PidsNotice.PASSING),
        page(carousel, screen, start.plusSeconds(14), false),
        "列车状态变了也锁满 15 秒");
    assertEquals(notice(PidsNotice.QUEUE), page(carousel, screen, start.plusSeconds(28), false));
    assertEquals(
        notice(PidsNotice.PASSING),
        page(carousel, UUID.randomUUID(), start.plusSeconds(5), true),
        "每块屏幕各自锁定");
  }

  /** 下一班进站或停靠时：副页时段有空位信息放空位页，没有就留在主页；通过车的安全提示页照旧优先。 */
  @Test
  void anArrivingTrainKeepsCourtesyPagesAway() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Optional<Slide> vacancy = Optional.of(new Slide.Vacancy());

    assertEquals(
        Optional.empty(),
        carousel.page(
            screen, HHU, new Signals(false, false, true, ALL), start.plusSeconds(12), RENDER));
    assertEquals(
        vacancy,
        carousel.page(
            screen, HHU, new Signals(false, true, true, ALL), start.plusSeconds(28), RENDER),
        "原本是宣传页的一段也放空位页");
    assertEquals(
        notice(PidsNotice.PASSING),
        carousel.page(
            screen, HHU, new Signals(true, true, true, ALL), start.plusSeconds(28), RENDER));
  }

  /** 轮播的屏从每段主页的开头数起：翻回主页先写英文 4 秒，再写备注 4 秒。 */
  @Test
  void rotatingScreensStartEachMainPageInEnglish() {
    Instant start = roundStart();

    assertFalse(PidsCarousel.remarks(HHU, start, RENDER, true));
    assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(3), RENDER, true));
    assertTrue(PidsCarousel.remarks(HHU, start.plusSeconds(4), RENDER, true));
    assertTrue(PidsCarousel.remarks(HHU, start.plusSeconds(7), RENDER, true));
    assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(8), RENDER, true));
    assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(16), RENDER, true), "下一段主页又从英文起");
    assertTrue(PidsCarousel.remarks(HHU, start.plusSeconds(20), RENDER, true));
  }

  /** 不轮播的屏（车站统屏）按时钟交替，英文与备注可以不等长。 */
  @Test
  void otherScreensAlternateByTheClockWithTheirOwnLengths() {
    PidsSettings.RenderSettings uneven =
        new PidsSettings.RenderSettings(20, 5, 30, 12, 4, 15, 6, 4, 6);
    Instant start = cycleStart(10);

    assertFalse(PidsCarousel.remarks(HHU, start, uneven, false));
    assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(5), uneven, false));
    assertTrue(PidsCarousel.remarks(HHU, start.plusSeconds(6), uneven, false));
    assertTrue(PidsCarousel.remarks(HHU, start.plusSeconds(9), uneven, false));
    assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(10), uneven, false));
  }

  @Test
  void zeroRemarkSecondsNeverShowsRemarks() {
    PidsSettings.RenderSettings off =
        new PidsSettings.RenderSettings(20, 5, 30, 12, 4, 15, 4, 0, 6);
    Instant start = roundStart();

    for (int second = 0; second < 16; second++) {
      assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(second), off, true));
      assertFalse(PidsCarousel.remarks(HHU, start.plusSeconds(second), off, false));
    }
  }

  @Test
  void zeroNoticeSecondsDisablesCourtesyPagesButKeepsTheSafetyPage() {
    PidsCarousel carousel = new PidsCarousel();
    PidsSettings.RenderSettings quiet =
        new PidsSettings.RenderSettings(20, 5, 30, 12, 0, 15, 4, 4, 6);
    UUID screen = UUID.randomUUID();
    Instant at = roundStart().plusSeconds(13);

    assertEquals(
        Optional.empty(),
        carousel.page(screen, HHU, new Signals(false, true, false, ALL), at, quiet));
    assertEquals(
        notice(PidsNotice.PASSING),
        carousel.page(screen, HHU, new Signals(true, true, false, ALL), at, quiet));
  }

  /** 有空位信息时副页隔段是空位页，五张宣传页仍各出现一次。 */
  @Test
  void vacancyAlternatesWithTheCourtesyPages() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Signals seats = new Signals(false, true, false, ALL);
    Optional<Slide> vacancy = Optional.of(new Slide.Vacancy());

    assertEquals(Optional.empty(), carousel.page(screen, HHU, seats, start, RENDER));
    assertEquals(vacancy, carousel.page(screen, HHU, seats, start.plusSeconds(12), RENDER));
    assertEquals(
        notice(PidsNotice.ORDER), carousel.page(screen, HHU, seats, start.plusSeconds(28), RENDER));
    assertEquals(vacancy, carousel.page(screen, HHU, seats, start.plusSeconds(44), RENDER));
    assertEquals(
        notice(PidsNotice.QUEUE), carousel.page(screen, HHU, seats, start.plusSeconds(60), RENDER));
    assertEquals(
        notice(PidsNotice.DOORS), carousel.page(screen, HHU, seats, start.plusSeconds(92), RENDER));
    assertEquals(
        notice(PidsNotice.CHECK),
        carousel.page(screen, HHU, seats, start.plusSeconds(124), RENDER));
    assertEquals(
        notice(PidsNotice.GAP), carousel.page(screen, HHU, seats, start.plusSeconds(156), RENDER));
  }

  /** 没有可轮换的宣传页时副页时段留在主页，空位页照常。 */
  @Test
  void anEmptyCourtesyListLeavesOnlyTheVacancyPage() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();

    assertEquals(
        Optional.empty(),
        carousel.page(
            UUID.randomUUID(),
            HHU,
            new Signals(false, false, false, List.of()),
            start.plusSeconds(12),
            RENDER));
    assertEquals(
        Optional.of(new Slide.Vacancy()),
        carousel.page(
            UUID.randomUUID(),
            HHU,
            new Signals(false, true, false, List.of()),
            start.plusSeconds(12),
            RENDER));
  }

  /** 一段副页放什么在这一段开始时定下：段内空位信息、可放的宣传页变了也不换，下一段才按新的状况。 */
  @Test
  void aSidePageStaysForItsWholeSegment() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Signals before = new Signals(false, false, false, ALL);
    Signals after = new Signals(false, true, false, List.of(PidsNotice.GAP));

    Optional<Slide> first = carousel.page(screen, HHU, before, start.plusSeconds(28), RENDER);
    assertEquals(notice(PidsNotice.QUEUE), first);
    assertEquals(first, carousel.page(screen, HHU, after, start.plusSeconds(31), RENDER), "段内不换");
    assertEquals(
        Optional.of(new Slide.Vacancy()),
        carousel.page(screen, HHU, after, start.plusSeconds(44), RENDER),
        "下一段按新的状况");
  }

  /** 宣传页按连续的段号轮换：四张时每十六段各四次，相邻两段不重复。 */
  @Test
  void courtesyPagesRotateEvenlyWithoutRepeats() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Signals four =
        new Signals(
            false,
            false,
            false,
            List.of(PidsNotice.ORDER, PidsNotice.QUEUE, PidsNotice.DOORS, PidsNotice.GAP));
    List<Optional<Slide>> shown = new ArrayList<>();

    for (int segment = 0; segment < 16; segment++) {
      shown.add(carousel.page(screen, HHU, four, start.plusSeconds(16L * segment + 12), RENDER));
    }

    for (PidsNotice notice : four.courtesy()) {
      assertEquals(4, Collections.frequency(shown, notice(notice)), notice::toString);
    }
    for (int i = 1; i < shown.size(); i++) {
      assertNotEquals(shown.get(i - 1), shown.get(i), "相邻两段不重复");
    }
  }

  /** 2×1：停站表每页 6 秒 → 后续列车 6 秒 → 宣传页 4 秒，一轮 22 秒；下一轮换下一张宣传页。 */
  @Test
  void stopListScreensTurnStopsThenFollowingThenCourtesy() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = Instant.ofEpochSecond(1_790_000_002L);
    Signals signals = new Signals(false, false, false, List.of(PidsNotice.ORDER, PidsNotice.QUEUE));
    StopListPages pages = new StopListPages("A", 2, true);

    assertEquals(stops(0), carousel.stopList(screen, signals, pages, start, RENDER));
    assertEquals(stops(0), carousel.stopList(screen, signals, pages, start.plusSeconds(5), RENDER));
    assertEquals(stops(1), carousel.stopList(screen, signals, pages, start.plusSeconds(6), RENDER));
    assertEquals(
        new StopListSlide.Following(),
        carousel.stopList(screen, signals, pages, start.plusSeconds(12), RENDER));
    assertEquals(
        new Slide.Notice(PidsNotice.ORDER),
        carousel.stopList(screen, signals, pages, start.plusSeconds(18), RENDER));
    assertEquals(
        stops(0), carousel.stopList(screen, signals, pages, start.plusSeconds(22), RENDER));
    assertEquals(
        new Slide.Notice(PidsNotice.QUEUE),
        carousel.stopList(screen, signals, pages, start.plusSeconds(40), RENDER),
        "下一轮换下一张");
  }

  /** 换了一班车从停站表第 1 页起；只有一页、没有后续列车时停站表之后直接是宣传页。 */
  @Test
  void aNewTrainRestartsAndMissingPagesAreSkipped() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = Instant.ofEpochSecond(1_790_000_002L);
    Signals signals = new Signals(false, false, false, List.of(PidsNotice.GAP));

    carousel.stopList(screen, signals, new StopListPages("A", 2, true), start, RENDER);
    assertEquals(
        stops(0),
        carousel.stopList(
            screen, signals, new StopListPages("B", 2, true), start.plusSeconds(8), RENDER),
        "换了车从第 1 页起");
    StopListPages single = new StopListPages("C", 1, false);
    carousel.stopList(screen, signals, single, start, RENDER);
    assertEquals(
        new Slide.Notice(PidsNotice.GAP),
        carousel.stopList(screen, signals, single, start.plusSeconds(6), RENDER));
    assertEquals(
        stops(0), carousel.stopList(screen, signals, single, start.plusSeconds(10), RENDER));
  }

  /** 下一班进站或停靠时只翻停站表；没有宣传页可放、副页停留为 0 时也只翻停站表与后续列车。 */
  @Test
  void stopListScreensStayOnTheStopsWhileTheTrainIsIn() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = Instant.ofEpochSecond(1_790_000_002L);
    StopListPages pages = new StopListPages("A", 2, true);
    UUID arriving = UUID.randomUUID();
    Signals in = new Signals(false, false, true, ALL);

    carousel.stopList(arriving, in, pages, start, RENDER);
    assertEquals(stops(0), carousel.stopList(arriving, in, pages, start.plusSeconds(12), RENDER));
    assertEquals(stops(1), carousel.stopList(arriving, in, pages, start.plusSeconds(18), RENDER));

    PidsSettings.RenderSettings quiet =
        new PidsSettings.RenderSettings(20, 5, 30, 12, 0, 15, 4, 4, 6);
    UUID silent = UUID.randomUUID();
    Signals out = new Signals(false, false, false, ALL);
    carousel.stopList(silent, out, pages, start, quiet);
    assertEquals(
        new StopListSlide.Following(),
        carousel.stopList(silent, out, pages, start.plusSeconds(12), quiet));
    assertEquals(stops(0), carousel.stopList(silent, out, pages, start.plusSeconds(18), quiet));
  }

  /** 2×1：一页显示期间后续列车出现不让这一页跳走，到时再按新的状况翻；下一班进站时立即回到停站表第 1 页。 */
  @Test
  void stopListPagesDoNotJumpWhenTheRoundChanges() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = Instant.ofEpochSecond(1_790_000_002L);
    Signals out = new Signals(false, false, false, ALL);
    StopListPages alone = new StopListPages("A", 2, false);
    StopListPages withFollowing = new StopListPages("A", 2, true);

    carousel.stopList(screen, out, alone, start, RENDER);
    assertEquals(stops(1), carousel.stopList(screen, out, alone, start.plusSeconds(6), RENDER));
    assertEquals(
        stops(1),
        carousel.stopList(screen, out, withFollowing, start.plusSeconds(8), RENDER),
        "后续列车出现时，正在显示的一页不跳");
    assertEquals(
        new StopListSlide.Following(),
        carousel.stopList(screen, out, withFollowing, start.plusSeconds(12), RENDER));
    assertEquals(
        stops(0),
        carousel.stopList(
            screen,
            new Signals(false, false, true, ALL),
            withFollowing,
            start.plusSeconds(14),
            RENDER),
        "进站时立即回到第 1 页");
  }

  /** 停站屏也锁定安全提示页：锁满 15 秒，之后照常翻页。 */
  @Test
  void stopListScreensPinTheSafetyPage() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = Instant.ofEpochSecond(1_790_000_002L);
    StopListPages pages = new StopListPages("A", 2, true);

    assertEquals(
        new Slide.Notice(PidsNotice.PASSING),
        carousel.stopList(screen, new Signals(true, false, false, ALL), pages, start, RENDER));
    assertEquals(
        new Slide.Notice(PidsNotice.PASSING),
        carousel.stopList(
            screen, new Signals(false, false, false, ALL), pages, start.plusSeconds(14), RENDER));
    assertEquals(
        stops(0),
        carousel.stopList(
            screen, new Signals(false, false, false, ALL), pages, start.plusSeconds(15), RENDER));
  }

  private static StopListSlide stops(int page) {
    return new StopListSlide.Stops(page);
  }

  private static Optional<Slide> notice(PidsNotice notice) {
    return Optional.of(new Slide.Notice(notice));
  }

  private static Optional<Slide> page(
      PidsCarousel carousel, UUID screen, Instant at, boolean passing) {
    return carousel.page(screen, HHU, new Signals(passing, false, false, ALL), at, RENDER);
  }
}
