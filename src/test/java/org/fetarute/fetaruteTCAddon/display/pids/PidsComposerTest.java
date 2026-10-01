package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsContent;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsFonts;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRenderer;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsViewBuilder;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 屏幕内容：未登记、尺寸不符、测试卡与到发页的判定，线路过滤与按世界时间取外观。 */
class PidsComposerTest {

  private static final Instant NOW = Instant.parse("2026-10-01T13:40:00Z");
  private static final UUID WORLD = UUID.randomUUID();
  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");

  @TempDir Path dir;
  private final PidsScreenRegistry registry = new PidsScreenRegistry();
  private boolean loaded = true;
  private OptionalLong worldTime = OptionalLong.of(6000);
  private Instant now = NOW;
  private List<PidsRow> rows = rows();

  /** 默认关掉宣传页轮播，免得固定时刻正好落在宣传页上；轮播另有用例。 */
  private PidsSettings settings = withNoticeSeconds(0);

  private PidsComposer composer;

  @BeforeEach
  void setUp() throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    lang.load(
        new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("lang/zh_CN.yml"),
            StandardCharsets.UTF_8));
    PidsLayoutRegistry layouts =
        new PidsLayoutRegistry(
            dir, getClass().getClassLoader()::getResourceAsStream, Logger.getLogger("test"));
    layouts.reload();
    PidsDirectory directory = new Directory();
    composer =
        new PidsComposer(
            registry,
            () -> loaded,
            layouts,
            station -> new PidsSnapshot(station, NOW, rows),
            new PidsViewBuilder(directory, new PidsVocabulary(key -> lang.getString(key, key))),
            directory,
            new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS)),
            key -> lang.getString(key, key),
            world -> worldTime,
            () -> settings,
            () -> now,
            ZoneOffset.UTC);
  }

  @Test
  void unknownScreensShowUnregisteredOnlyOnceTheRegistryIsLoaded() {
    Optional<PidsContent> content = composer.content(Optional.of(UUID.randomUUID()), 384, 128);
    assertEquals("屏幕未登记", card(content).title().primary());

    loaded = false;
    assertTrue(composer.content(Optional.of(UUID.randomUUID()), 384, 128).isEmpty(), "屏幕表未读入时不判定");
  }

  @Test
  void missingFramesShowTheSizeMismatch() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());

    PidsTestCard card = card(composer.content(Optional.of(screen.id()), 256, 128));

    assertEquals("屏幕尺寸不符", card.title().primary());
    assertEquals(List.of("展示框实际为 1×2，登记为 1×3"), card.lines());
    assertEquals(2, card.tileCols());
  }

  @Test
  void testCardListsLayoutStationAndScreenId() {
    PidsScreen screen = register(PidsScreen.Mode.TEST_CARD, Set.of());

    PidsTestCard card = card(composer.content(Optional.of(screen.id()), 384, 128));

    assertEquals("站台屏待配置", card.title().primary());
    assertEquals(
        List.of(
            "布局：站台屏 1×3（1×3）",
            "车站：新笛矢 · 壑湖（HHU）· 站台 3",
            "屏幕编号：" + PidsComposer.shortId(screen.id())),
        card.lines());
  }

  @Test
  void liveScreensFilterLinesAndKeepTheSameKeyWhileNothingChanges() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of("MT"));

    PidsContent first = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();
    PidsContent second = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();

    PidsView view = assertInstanceOf(PidsComposer.LiveKey.class, first.key()).view();
    assertEquals(List.of("MT"), view.rows().stream().map(row -> row.badge().code()).toList());
    assertEquals(first.key(), second.key(), "数据没变时不重绘");
    BufferedImage image = first.image().get();
    assertEquals(384, image.getWidth());
  }

  @Test
  void platformBoxListsTheScreensPlatformsByNumber() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    assertEquals(List.of("3"), platformBox(screen));

    // 同一块屏幕改绑成多个站台（多站台屏）
    PidsScreen many = screen.withBinding(screen.station(), Set.of("10", "4", "1"), Set.of(), NOW);
    registry.put(many);
    assertEquals(List.of("1", "4", "10"), platformBox(many), "按站台号数值排序");
  }

  private List<String> platformBox(PidsScreen screen) {
    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();
    return assertInstanceOf(PidsComposer.LiveKey.class, content.key()).view().platforms();
  }

  @Test
  void autoAppearanceFollowsTheWorldClock() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());

    assertEquals(PidsTheme.LIGHT, theme(screen), "白天");
    worldTime = OptionalLong.of(18000);
    assertEquals(PidsTheme.DARK, theme(screen), "夜里");
    worldTime = OptionalLong.empty();
    assertEquals(PidsTheme.DARK, theme(screen), "世界未加载时取深色");
  }

  @Test
  void platformScreensRotateCourtesyPagesWithTheSameBand() {
    settings = PidsSettings.defaults();
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    now = roundStart().plusSeconds(13);

    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();

    PidsNoticeView notice = assertInstanceOf(PidsNoticeView.class, content.key());
    assertEquals(PidsNotice.ORDER, notice.notice());
    assertEquals("先下后上", notice.title().primary());
    now = roundStart();
    PidsView main =
        assertInstanceOf(
                PidsComposer.LiveKey.class,
                composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key())
            .view();
    assertEquals(main.bandColors(), notice.bandColors(), "翻页时色带不变");
  }

  @Test
  void passingTrainsOnThisPlatformPinTheSafetyPage() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    rows = List.of(passing("5"));
    assertInstanceOf(
        PidsComposer.LiveKey.class,
        composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key(),
        "别的站台的通过车不锁");

    rows = List.of(passing("3"));
    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();

    assertEquals(
        PidsNotice.PASSING, assertInstanceOf(PidsNoticeView.class, content.key()).notice());
  }

  private static PidsSettings withNoticeSeconds(int seconds) {
    PidsSettings defaults = PidsSettings.defaults();
    PidsSettings.RenderSettings render = defaults.render();
    return new PidsSettings(
        defaults.configVersion(),
        defaults.enabled(),
        new PidsSettings.RenderSettings(
            render.checkIntervalTicks(),
            render.forceRefreshSeconds(),
            render.snapshotTtlSeconds(),
            render.horizonMinutes(),
            render.slideMainSeconds(),
            seconds,
            render.noticePinSeconds()),
        defaults.limits(),
        defaults.font(),
        defaults.layout(),
        defaults.appearance(),
        defaults.broadcast());
  }

  /** 这一刻 HHU 的屏幕刚翻回主页（一轮 48 秒）。 */
  private static Instant roundStart() {
    long base = NOW.getEpochSecond();
    return Instant.ofEpochSecond(base - Math.floorMod(base + PidsCarousel.offset(HHU, 48), 48));
  }

  private static PidsRow passing(String platform) {
    return new PidsRow(
        PidsRow.Status.ARRIVING,
        "WS",
        "SURC:WS:R1",
        "TPC",
        Optional.of("SURC:TPC"),
        platform,
        NOW.plusSeconds(20),
        OptionalLong.of(0),
        2,
        true,
        false,
        false,
        Optional.empty());
  }

  private PidsTheme theme(PidsScreen screen) {
    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();
    return assertInstanceOf(PidsComposer.LiveKey.class, content.key()).view().theme();
  }

  private PidsScreen register(PidsScreen.Mode mode, Set<String> lines) {
    PidsScreen screen =
        new PidsScreen(
            UUID.randomUUID(),
            WORLD,
            new PidsScreen.Position(0, 64, 0),
            PidsFacing.SOUTH,
            1,
            3,
            "platform-1x3",
            Optional.of(HHU),
            Set.of("3"),
            lines,
            PidsScreen.Appearance.AUTO,
            mode,
            NOW,
            NOW);
    registry.put(screen);
    return screen;
  }

  private static PidsTestCard card(Optional<PidsContent> content) {
    return assertInstanceOf(PidsTestCard.class, content.orElseThrow().key());
  }

  private static List<PidsRow> rows() {
    return List.of(row("WS", 60), row("MT", 120), row("WS", 300));
  }

  private static PidsRow row(String line, int seconds) {
    return new PidsRow(
        PidsRow.Status.EN_ROUTE,
        line,
        "SURC:" + line + ":R1",
        "TPC",
        Optional.of("SURC:TPC"),
        "3",
        NOW.plusSeconds(seconds),
        OptionalLong.of(0),
        2,
        false,
        false,
        false,
        Optional.empty());
  }

  private static final class Directory implements PidsDirectory {
    @Override
    public Optional<Names> stationName(String stationId) {
      return switch (stationId) {
        case "SURC:HHU" -> Optional.of(new Names("新笛矢 · 壑湖", "Neo Fueya - Hor Huu"));
        case "SURC:TPC" -> Optional.of(new Names("大港城", "The Port City"));
        default -> Optional.empty();
      };
    }

    @Override
    public Optional<LineStyle> line(String operatorCode, String lineCode) {
      return Optional.of(new LineStyle(lineCode, "MT".equals(lineCode) ? 0xD920D9 : 0x70DEEE));
    }

    @Override
    public Optional<RouteApi.OperationType> serviceType(String routeId) {
      return Optional.of(RouteApi.OperationType.LOCAL);
    }

    @Override
    public List<PidsView.LineChip> linesServing(PidsStationKey station) {
      return List.of();
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }
  }
}
