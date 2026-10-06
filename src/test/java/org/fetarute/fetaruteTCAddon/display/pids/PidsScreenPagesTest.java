package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.display.pids.PidsScreenPages.Outcome;
import org.fetarute.fetaruteTCAddon.display.pids.PidsScreenPages.Turn;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 组合翻页：哪些布局能组合、实际显示哪些、菜单增删与按时钟取页。 */
class PidsScreenPagesTest {

  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");

  @TempDir Path dir;
  private PidsLayoutRegistry layouts;

  @BeforeEach
  void setUp() throws IOException {
    // 一份自定义的 3×5 到发布局，用来测换主布局时保留其余翻页
    try (InputStream builtIn =
        getClass().getClassLoader().getResourceAsStream("pids/layouts/station-3x5.yml")) {
      Files.copy(builtIn, dir.resolve("board-b.yml"));
    }
    // 一份 3×5 的站台屏（带站台号组件，有自己的轮播），用来测不能加入组合翻页
    try (InputStream builtIn =
        getClass().getClassLoader().getResourceAsStream("pids/layouts/platform-1x3.yml")) {
      String yaml =
          new String(builtIn.readAllBytes(), StandardCharsets.UTF_8)
              .replace("  rows: 1\n  cols: 3\n", "  rows: 3\n  cols: 5\n");
      Files.writeString(dir.resolve("platform-wide.yml"), yaml);
    }
    layouts =
        new PidsLayoutRegistry(
            dir, getClass().getClassLoader()::getResourceAsStream, Logger.getLogger("test"));
    layouts.reload();
  }

  @Test
  void onlyLayoutsWithoutAPlatformWidgetCombine() {
    assertTrue(PidsScreenPages.combinable(layout("station-3x5")));
    assertTrue(PidsScreenPages.combinable(layout("status-3x5")));
    assertFalse(PidsScreenPages.combinable(layout("platform-1x3")), "站台屏有自己的轮播");
    assertFalse(PidsScreenPages.combinable(layout("platform-group-1x4")));
    assertFalse(PidsScreenPages.combinable(layout("platform-2x1")), "停站屏也有自己的轮播");
  }

  @Test
  void resolvedPagesSkipMissingMismatchedAndRepeatedLayouts() {
    assertEquals(
        List.of("station-3x5", "status-3x5"),
        ids(
            PidsScreenPages.resolve(
                screen(3, 5, "station-3x5", "gone", "platform-1x3", "status-3x5"), layouts)));
    assertEquals(
        List.of("station-3x5", "status-3x5"),
        ids(
            PidsScreenPages.resolve(
                screen(3, 5, "gone-3x5", "station-3x5", "status-3x5"), layouts)),
        "主布局退回内置车站统屏后，翻页里同一个布局不再重复");
    assertEquals(
        List.of("platform-1x3"),
        ids(PidsScreenPages.resolve(screen(1, 3, "platform-1x3", "platform-group-1x3"), layouts)),
        "主布局不能组合时只显示主布局");
    assertTrue(PidsScreenPages.resolve(screen(2, 2, "gone"), layouts).isEmpty(), "没有同尺寸布局");
  }

  @Test
  void togglingAddsAtTheEndAndRemovesAgain() {
    PidsScreenPages.Edit added =
        PidsScreenPages.togglePage(screen(3, 5, "station-3x5"), "status-3x5", layouts);
    assertEquals(Outcome.OK, added.outcome());
    assertEquals(List.of("station-3x5", "status-3x5"), added.layoutIds());

    PidsScreenPages.Edit removed =
        PidsScreenPages.togglePage(screen(3, 5, added.layoutIds()), "status-3x5", layouts);
    assertEquals(List.of("station-3x5"), removed.layoutIds());

    assertEquals(
        List.of("station-3x5"),
        PidsScreenPages.togglePage(screen(3, 5, "station-3x5", "gone"), "gone", layouts)
            .layoutIds(),
        "已删掉的布局也能从翻页里去掉");
  }

  @Test
  void togglingRefusesThePrimaryOtherSizesAndCarouselLayouts() {
    PidsScreen board = screen(3, 5, "station-3x5");
    assertEquals(
        Outcome.PRIMARY, PidsScreenPages.togglePage(board, "station-3x5", layouts).outcome());
    assertEquals(
        Outcome.PRIMARY,
        PidsScreenPages.togglePage(screen(3, 5, "gone-3x5"), "station-3x5", layouts).outcome(),
        "按实际显示的主布局认");
    assertEquals(
        Outcome.UNKNOWN_LAYOUT,
        PidsScreenPages.togglePage(board, "platform-1x3", layouts).outcome());
    assertEquals(
        Outcome.UNKNOWN_LAYOUT, PidsScreenPages.togglePage(board, "nope", layouts).outcome());
    PidsScreenPages.Edit refused =
        PidsScreenPages.togglePage(screen(1, 3, "platform-1x3"), "platform-group-1x3", layouts);
    assertEquals(Outcome.PRIMARY_NOT_COMBINABLE, refused.outcome(), "主布局有自己的轮播");
    assertEquals(List.of("platform-1x3"), refused.layoutIds(), "不成功时不变");
    assertEquals(
        Outcome.NOT_COMBINABLE,
        PidsScreenPages.togglePage(board, "platform-wide", layouts).outcome(),
        "点的布局有自己的轮播");
  }

  @Test
  void screensWithoutAStationGetNoDeparturePages() {
    PidsScreen status = operatorOnly("status-3x5");

    PidsScreenPages.Edit refused = PidsScreenPages.togglePage(status, "station-3x5", layouts);
    assertEquals(Outcome.NEED_STATION, refused.outcome());
    assertEquals(List.of("status-3x5"), refused.layoutIds(), "不成功时不变");
    assertTrue(
        PidsScreenPages.candidates(status, layouts).isEmpty(), "没绑车站时菜单不列到发布局（另一个可组合的只有到发布局）");
    assertEquals(
        List.of("status-3x5"),
        ids(PidsScreenPages.resolve(operatorOnly("status-3x5", "station-3x5"), layouts)),
        "已存着的到发翻页显示时跳过");
    assertEquals(
        List.of("status-3x5", "station-3x5"),
        PidsScreenPages.togglePage(screen(3, 5, "status-3x5"), "station-3x5", layouts).layoutIds(),
        "绑了车站就能加");
  }

  @Test
  void choosingAPrimaryKeepsTheOtherPages() {
    PidsScreen combined = screen(3, 5, "station-3x5", "status-3x5");

    assertEquals(
        List.of("station-3x5", "status-3x5"),
        PidsScreenPages.withPrimary(combined, layout("station-3x5"), layouts),
        "再点一次主布局不丢翻页");
    assertEquals(
        List.of("board-b", "status-3x5"),
        PidsScreenPages.withPrimary(combined, layout("board-b"), layouts));
    assertEquals(
        List.of("status-3x5"),
        PidsScreenPages.withPrimary(combined, layout("status-3x5"), layouts),
        "翻页里的布局改成主布局后不再重复");
    assertEquals(
        List.of("platform-1x3"),
        PidsScreenPages.withPrimary(
            screen(1, 3, "platform-group-1x3"), layout("platform-1x3"), layouts));
  }

  @Test
  void candidatesAreTheOtherCombinableLayoutsOfTheSameSize() {
    assertEquals(
        List.of("status-3x5", "board-b"),
        ids(PidsScreenPages.candidates(screen(3, 5, "station-3x5"), layouts)));
    assertTrue(PidsScreenPages.candidates(screen(1, 3, "platform-1x3"), layouts).isEmpty());
  }

  @Test
  void eachLayoutShowsOncePerRoundFollowingTheClock() {
    List<Integer> seconds = List.of(30, 15);
    long base = Instant.parse("2026-10-05T12:00:00Z").getEpochSecond();
    long shifted = base + PidsCarousel.offset(HHU, 45);
    long start = base - Math.floorMod(shifted, 45);
    long round = Math.floorDiv(shifted, 45);

    assertEquals(new Turn(0, round), turn(seconds, start));
    assertEquals(new Turn(0, round), turn(seconds, start + 29));
    assertEquals(new Turn(1, round), turn(seconds, start + 30));
    assertEquals(new Turn(1, round), turn(seconds, start + 44));
    assertEquals(new Turn(0, round + 1), turn(seconds, start + 45), "一轮 30 + 15 秒");
    assertEquals(
        new Turn(0, Math.floorDiv(start + 20 + PidsCarousel.offset(HHU, 15), 15)),
        PidsScreenPages.turn(List.of(15), HHU.toString(), at(start + 20)),
        "只有一个布局时每 15 秒一轮");
  }

  private static Turn turn(List<Integer> seconds, long epochSecond) {
    return PidsScreenPages.turn(seconds, HHU.toString(), at(epochSecond));
  }

  private static Instant at(long epochSecond) {
    return Instant.ofEpochSecond(epochSecond);
  }

  private PidsLayout layout(String id) {
    return layouts.find(id).orElseThrow();
  }

  private static List<String> ids(List<PidsLayout> pages) {
    return pages.stream().map(PidsLayout::id).toList();
  }

  /** 只绑运营商、不绑车站的 3×5 屏幕。 */
  private static PidsScreen operatorOnly(String... layoutIds) {
    Instant now = Instant.EPOCH;
    return new PidsScreen(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PidsScreen.Position(0, 64, 0),
        PidsFacing.SOUTH,
        3,
        5,
        List.of(layoutIds),
        Optional.empty(),
        Optional.of(HHU.operatorCode()),
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.LIVE,
        now,
        now);
  }

  private static PidsScreen screen(int rows, int cols, String... layoutIds) {
    return screen(rows, cols, List.of(layoutIds));
  }

  private static PidsScreen screen(int rows, int cols, List<String> layoutIds) {
    Instant now = Instant.EPOCH;
    return new PidsScreen(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PidsScreen.Position(0, 64, 0),
        PidsFacing.SOUTH,
        rows,
        cols,
        layoutIds,
        Optional.of(HHU),
        Optional.empty(),
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.LIVE,
        now,
        now);
  }
}
