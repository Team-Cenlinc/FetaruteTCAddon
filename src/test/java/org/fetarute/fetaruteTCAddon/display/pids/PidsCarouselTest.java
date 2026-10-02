package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.junit.jupiter.api.Test;

/**
 * 轮播（用例按主页 12 秒、副页 4 秒、英文与备注各 4 秒、停站屏每页 6 秒计，与默认值无关）：主页与副页轮流出现；有空位信息时副页隔段是空位页；
 * 下一班进站或停靠时不翻到宣传页；通过列车锁定安全提示页 15 秒；副页时长为 0 时不轮播。轮播的屏翻回主页先写英文。
 */
class PidsCarouselTest {

  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");
  private static final PidsSettings.RenderSettings RENDER =
      new PidsSettings.RenderSettings(20, 5, 30, 12, 4, 15, 4, 4, 6);

  /** 一整轮 96 秒（六段 ×（12 + 4））的起点：这一刻 HHU 的屏幕刚翻到主页。 */
  private static Instant roundStart() {
    return cycleStart(96);
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
    assertEquals(Optional.empty(), page(carousel, screen, start.plusSeconds(48), false), "下一轮");
  }

  @Test
  void screensOfTheSameStationFlipTogether() {
    PidsCarousel carousel = new PidsCarousel();
    Instant at = roundStart().plusSeconds(13);

    assertEquals(
        page(carousel, UUID.randomUUID(), at, false), page(carousel, UUID.randomUUID(), at, false));
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
    Optional<PidsCarousel.Slide> vacancy = Optional.of(new PidsCarousel.Slide.Vacancy());

    assertEquals(
        Optional.empty(),
        carousel.page(screen, HHU, false, false, true, start.plusSeconds(12), RENDER));
    assertEquals(
        vacancy,
        carousel.page(screen, HHU, false, true, true, start.plusSeconds(28), RENDER),
        "原本是宣传页的一段也放空位页");
    assertEquals(
        notice(PidsNotice.PASSING),
        carousel.page(screen, HHU, true, true, true, start.plusSeconds(28), RENDER));
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

  /** 停站屏每页 6 秒；换了一班车从第 1 页重新数起。 */
  @Test
  void stopListPagesTurnEveryStopPageSecondsAndRestartForANewTrain() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = Instant.ofEpochSecond(1_790_000_003L);

    assertEquals(0, carousel.stopPage(screen, "A", start, RENDER, 3));
    assertEquals(0, carousel.stopPage(screen, "A", start.plusSeconds(5), RENDER, 3));
    assertEquals(1, carousel.stopPage(screen, "A", start.plusSeconds(6), RENDER, 3));
    assertEquals(2, carousel.stopPage(screen, "A", start.plusSeconds(12), RENDER, 3));
    assertEquals(0, carousel.stopPage(screen, "A", start.plusSeconds(18), RENDER, 3));
    assertEquals(0, carousel.stopPage(screen, "B", start.plusSeconds(20), RENDER, 3), "换了车从第 1 页起");
    assertEquals(1, carousel.stopPage(screen, "B", start.plusSeconds(26), RENDER, 3));
    assertEquals(
        0, carousel.stopPage(UUID.randomUUID(), "A", start.plusSeconds(7), RENDER, 1), "只有一页不翻");
  }

  /** 安全提示页的锁定单独可查：停站屏不轮播副页，也要锁。 */
  @Test
  void thePassingPinIsAvailableWithoutTheCarousel() {
    PidsCarousel carousel = new PidsCarousel();
    UUID screen = UUID.randomUUID();
    Instant start = roundStart();

    assertFalse(carousel.pinned(screen, false, start, RENDER));
    assertTrue(carousel.pinned(screen, true, start, RENDER));
    assertTrue(carousel.pinned(screen, false, start.plusSeconds(14), RENDER), "锁满 15 秒");
    assertFalse(carousel.pinned(screen, false, start.plusSeconds(15), RENDER));
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

    assertEquals(Optional.empty(), carousel.page(screen, HHU, false, true, false, at, quiet));
    assertEquals(
        notice(PidsNotice.PASSING), carousel.page(screen, HHU, true, true, false, at, quiet));
  }

  /** 有空位信息时副页隔段是空位页，三张宣传页仍各出现一次。 */
  @Test
  void vacancyAlternatesWithTheCourtesyPages() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Optional<PidsCarousel.Slide> vacancy = Optional.of(new PidsCarousel.Slide.Vacancy());

    assertEquals(Optional.empty(), carousel.page(screen, HHU, false, true, false, start, RENDER));
    assertEquals(
        vacancy, carousel.page(screen, HHU, false, true, false, start.plusSeconds(12), RENDER));
    assertEquals(
        notice(PidsNotice.QUEUE),
        carousel.page(screen, HHU, false, true, false, start.plusSeconds(28), RENDER));
    assertEquals(
        vacancy, carousel.page(screen, HHU, false, true, false, start.plusSeconds(44), RENDER));
    assertEquals(
        notice(PidsNotice.ORDER),
        carousel.page(screen, HHU, false, true, false, start.plusSeconds(60), RENDER));
    assertEquals(
        notice(PidsNotice.DOORS),
        carousel.page(screen, HHU, false, true, false, start.plusSeconds(92), RENDER));
  }

  private static Optional<PidsCarousel.Slide> notice(PidsNotice notice) {
    return Optional.of(new PidsCarousel.Slide.Notice(notice));
  }

  private static Optional<PidsCarousel.Slide> page(
      PidsCarousel carousel, UUID screen, Instant at, boolean passing) {
    return carousel.page(screen, HHU, passing, false, false, at, RENDER);
  }
}
