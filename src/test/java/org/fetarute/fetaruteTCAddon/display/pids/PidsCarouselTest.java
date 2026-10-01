package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.junit.jupiter.api.Test;

/** 轮播：主页 12 秒、宣传页 4 秒轮流出现；通过列车锁定安全提示页 15 秒；宣传页时长为 0 时不轮播。 */
class PidsCarouselTest {

  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");
  private static final PidsSettings.RenderSettings RENDER = PidsSettings.RenderSettings.DEFAULT;

  /** 一轮 48 秒（三张宣传页 ×（12 + 4））的起点：这一刻 HHU 的屏幕刚翻到主页。 */
  private static Instant roundStart() {
    long cycle = 48;
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
    assertEquals(
        Optional.of(PidsNotice.ORDER), page(carousel, screen, start.plusSeconds(12), false));
    assertEquals(
        Optional.of(PidsNotice.ORDER), page(carousel, screen, start.plusSeconds(15), false));
    assertEquals(Optional.empty(), page(carousel, screen, start.plusSeconds(16), false));
    assertEquals(
        Optional.of(PidsNotice.QUEUE), page(carousel, screen, start.plusSeconds(28), false));
    assertEquals(
        Optional.of(PidsNotice.DOORS), page(carousel, screen, start.plusSeconds(44), false));
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

    assertEquals(Optional.of(PidsNotice.PASSING), page(carousel, screen, start, true));
    assertEquals(
        Optional.of(PidsNotice.PASSING),
        page(carousel, screen, start.plusSeconds(14), false),
        "列车状态变了也锁满 15 秒");
    assertEquals(
        Optional.of(PidsNotice.QUEUE), page(carousel, screen, start.plusSeconds(28), false));
    assertEquals(
        Optional.of(PidsNotice.PASSING),
        page(carousel, UUID.randomUUID(), start.plusSeconds(5), true),
        "每块屏幕各自锁定");
  }

  @Test
  void zeroNoticeSecondsDisablesCourtesyPagesButKeepsTheSafetyPage() {
    PidsCarousel carousel = new PidsCarousel();
    PidsSettings.RenderSettings quiet = new PidsSettings.RenderSettings(20, 5, 30, 12, 0, 15);
    UUID screen = UUID.randomUUID();
    Instant at = roundStart().plusSeconds(13);

    assertEquals(Optional.empty(), carousel.page(screen, HHU, false, at, quiet));
    assertEquals(Optional.of(PidsNotice.PASSING), carousel.page(screen, HHU, true, at, quiet));
  }

  private static Optional<PidsNotice> page(
      PidsCarousel carousel, UUID screen, Instant at, boolean passing) {
    return carousel.page(screen, HHU, passing, at, RENDER);
  }
}
