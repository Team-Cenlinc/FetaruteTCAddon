package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.junit.jupiter.api.Test;

/** 轮播：主页 12 秒、副页 4 秒轮流出现；有空位信息时副页隔段是空位页；通过列车锁定安全提示页 15 秒；副页时长为 0 时不轮播。 */
class PidsCarouselTest {

  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");
  private static final PidsSettings.RenderSettings RENDER = PidsSettings.RenderSettings.DEFAULT;

  /** 一整轮 96 秒（六段 ×（12 + 4））的起点：这一刻 HHU 的屏幕刚翻到主页。 */
  private static Instant roundStart() {
    long cycle = 96;
    long offset = PidsCarousel.offset(HHU, cycle);
    long base = 1_790_000_000L;
    return Instant.ofEpochSecond(base - Math.floorMod(base + offset, cycle));
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

  @Test
  void zeroNoticeSecondsDisablesCourtesyPagesButKeepsTheSafetyPage() {
    PidsCarousel carousel = new PidsCarousel();
    PidsSettings.RenderSettings quiet = new PidsSettings.RenderSettings(20, 5, 30, 12, 0, 15);
    UUID screen = UUID.randomUUID();
    Instant at = roundStart().plusSeconds(13);

    assertEquals(Optional.empty(), carousel.page(screen, HHU, false, true, at, quiet));
    assertEquals(notice(PidsNotice.PASSING), carousel.page(screen, HHU, true, true, at, quiet));
  }

  /** 有空位信息时副页隔段是空位页，三张宣传页仍各出现一次。 */
  @Test
  void vacancyAlternatesWithTheCourtesyPages() {
    PidsCarousel carousel = new PidsCarousel();
    Instant start = roundStart();
    UUID screen = UUID.randomUUID();
    Optional<PidsCarousel.Slide> vacancy = Optional.of(new PidsCarousel.Slide.Vacancy());

    assertEquals(Optional.empty(), carousel.page(screen, HHU, false, true, start, RENDER));
    assertEquals(vacancy, carousel.page(screen, HHU, false, true, start.plusSeconds(12), RENDER));
    assertEquals(
        notice(PidsNotice.QUEUE),
        carousel.page(screen, HHU, false, true, start.plusSeconds(28), RENDER));
    assertEquals(vacancy, carousel.page(screen, HHU, false, true, start.plusSeconds(44), RENDER));
    assertEquals(
        notice(PidsNotice.ORDER),
        carousel.page(screen, HHU, false, true, start.plusSeconds(60), RENDER));
    assertEquals(
        notice(PidsNotice.DOORS),
        carousel.page(screen, HHU, false, true, start.plusSeconds(92), RENDER));
  }

  private static Optional<PidsCarousel.Slide> notice(PidsNotice notice) {
    return Optional.of(new PidsCarousel.Slide.Notice(notice));
  }

  private static Optional<PidsCarousel.Slide> page(
      PidsCarousel carousel, UUID screen, Instant at, boolean passing) {
    return carousel.page(screen, HHU, passing, false, at, RENDER);
  }
}
