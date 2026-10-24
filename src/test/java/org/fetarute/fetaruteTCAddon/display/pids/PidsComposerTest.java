package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinBoard;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsContent;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsFonts;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRenderer;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsBulletinView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDivisionView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsFollowingView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsViewBuilder;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsYardView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.PidsYardZones;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardScope;
import org.fetarute.fetaruteTCAddon.signaller.console.ConsoleTarget;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxConsole;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;
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
  private int snapshotCalls;

  /** 运营商在大都会线、浦蓝线之外另有几条线路。 */
  private int extraLines;

  /** 目录认得运营商名（按运营商轮播的用例用；默认不认得，测试卡等写运营商代码）。 */
  private boolean operatorsNamed;

  private Function<PidsDirectory.OperatorLine, PidsLineStatus> statuses =
      line -> PidsLineStatus.of(PidsLineStatus.Condition.GOOD);

  /** 默认关掉宣传页轮播，免得固定时刻正好落在宣传页上；轮播另有用例。 */
  private PidsSettings settings = withSlides(12, 0);

  private PidsComposer composer;
  private final PidsBulletinBoard bulletins = new PidsBulletinBoard();

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
            station -> {
              snapshotCalls++;
              return new PidsSnapshot(station, NOW, rows);
            },
            new PidsViewBuilder(directory, new PidsVocabulary(key -> lang.getString(key, key))),
            directory,
            new PidsRenderer(PidsFonts.builtIn(PidsGlyphForm.ZH_HANS)),
            key -> lang.getString(key, key),
            world -> worldTime,
            () -> settings,
            () -> now,
            ZoneOffset.UTC,
            (line, at) -> statuses.apply(line),
            bulletins);
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
    settings = withSlides(12, 4);
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

  /** 2×1 停站屏也锁定安全提示页：通过车临近本站台时盖过停站表。 */
  @Test
  void stopListScreensStillPinTheSafetyPage() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    rows = List.of(passing("3"), row("MT", 120));

    PidsContent content = composer.content(Optional.of(screen.id()), 128, 256).orElseThrow();

    assertEquals(
        PidsNotice.PASSING, assertInstanceOf(PidsNoticeView.class, content.key()).notice());
    assertEquals(256, content.image().get().getHeight());
  }

  /** 各种屏幕什么时候判定能不能叫车（要排车源）：站台屏排满了班次不问、有空行才问；停站屏最下一行写提示，每次都问；车站统屏不写提示，从不问。 */
  @Test
  void eachKindOfScreenAsksForTheCallHintOnlyWhenItCanShowIt() {
    int[] asked = {0};
    composer.setCalls(
        screen ->
            new PidsViewBuilder.Calls(
                () -> {
                  asked[0]++;
                  return true;
                },
                Set.of()));
    PidsScreen platform = register(PidsScreen.Mode.LIVE, Set.of());
    PidsScreen stopList = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    PidsScreen station = registerPaged(PidsScreen.Mode.LIVE, "station-3x5");

    composer.content(Optional.of(platform.id()), 384, 128);
    assertEquals(0, asked[0], "站台屏三行都有车");

    rows = List.of(row("WS", 60));
    composer.content(Optional.of(platform.id()), 384, 128);
    assertEquals(1, asked[0], "站台屏有空行");

    composer.content(Optional.of(station.id()), 640, 384);
    assertEquals(1, asked[0], "车站统屏");

    composer.content(Optional.of(stopList.id()), 128, 256);
    assertEquals(2, asked[0], "停站屏");
  }

  /** 本站台第一班在本站终到：乘客不能上，照常翻到宣传页。 */
  @Test
  void aTerminatingTrainDoesNotHoldTheMainPage() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    now = roundStart().plusSeconds(13);
    PidsRow terminating = arriving("3");
    rows =
        List.of(
            new PidsRow(
                terminating.status(),
                terminating.lineName(),
                terminating.routeId(),
                terminating.destination(),
                terminating.destinationId(),
                terminating.platform(),
                terminating.expectedAt(),
                terminating.delaySeconds(),
                terminating.stopSequence(),
                false,
                true,
                false,
                terminating.trainName()),
            row("MT", 120));

    assertInstanceOf(
        PidsNoticeView.class,
        composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key());
  }

  /** 本站台的下一班在进站：轮到副页时也不翻到宣传页，留在主页。 */
  @Test
  void anArrivingTrainHereKeepsTheMainPage() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    now = roundStart().plusSeconds(13);
    rows = List.of(arriving("5"), row("MT", 120));
    assertInstanceOf(
        PidsNoticeView.class,
        composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key(),
        "别的站台的进站车不算");

    rows = List.of(arriving("3"), row("MT", 120));

    assertInstanceOf(
        PidsComposer.LiveKey.class,
        composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key());
  }

  /** 本站台的下一班要分行：车厢页时段（进站时也翻）放分行页，有座位数据时也先放它；文案取自语言文件，英文按单复数。 */
  @Test
  void aDividingTrainTakesTheCarPageEvenWhileArriving() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    now = roundStart().plusSeconds(13);
    PidsRow base = arriving("3");
    rows =
        List.of(
            new PidsRow(
                base.status(),
                base.lineName(),
                base.routeId(),
                base.destination(),
                base.destinationId(),
                base.platform(),
                base.expectedAt(),
                base.delaySeconds(),
                base.stopSequence(),
                false,
                false,
                false,
                base.trainName(),
                false,
                List.of(),
                List.of(new PidsRow.Car(4, 1), new PidsRow.Car(4, 1)),
                Optional.empty(),
                false,
                Optional.of(
                    new PidsRow.Division(
                        5,
                        "SURC:HHU",
                        List.of(
                            new PidsRow.Division.Part(1, false, Optional.of("SURC:TPC")),
                            new PidsRow.Division.Part(1, true, Optional.empty()))))),
            row("MT", 120));

    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();

    PidsDivisionView division =
        assertInstanceOf(PidsComposer.DivisionKey.class, content.key()).view();
    assertEquals(
        new Names("本次列车在新笛矢·壑湖分行", "This train divides at Neo Fueya - Hor Huu"), division.title());
    assertEquals(
        new Names("前 1 节开往大港城", "Front 1 car to The Port City"),
        division.segments().get(0).destination());
    assertEquals(
        new Names("后 1 节至新笛矢·壑湖止", "Rear 1 car ends at Neo Fueya - Hor Huu"),
        division.segments().get(1).destination());
    assertEquals(128, content.image().get().getHeight(), "画得出来");
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

  /** 2×1 停站屏：取本站台下一班可以上车的车（通过车不算）。 */
  @Test
  void stopListScreensShowTheNextRideableTrain() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    PidsRow passing = passing("3");
    // 通过车还远（运行中、未进站）：不锁安全提示页，停站表跳过它取下一班
    rows =
        List.of(
            new PidsRow(
                PidsRow.Status.EN_ROUTE,
                passing.lineName(),
                passing.routeId(),
                passing.destination(),
                passing.destinationId(),
                passing.platform(),
                passing.expectedAt(),
                passing.delaySeconds(),
                passing.stopSequence(),
                true,
                false,
                false,
                passing.trainName()),
            row("MT", 120));

    PidsContent content = composer.content(Optional.of(screen.id()), 128, 256).orElseThrow();

    PidsStopListView view = assertInstanceOf(PidsComposer.StopListKey.class, content.key()).view();
    assertEquals("MT", view.train().orElseThrow().badge().code());
    assertEquals(List.of("3"), view.platforms());
    BufferedImage image = content.image().get();
    assertEquals(128, image.getWidth());
    assertEquals(256, image.getHeight());
  }

  /** “确认终点”只在本站台有不同停站方式时轮到：只停一条线路时那一段改放下一张。 */
  @Test
  void theCheckPageOnlyRotatesOnMixedPlatforms() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    // 第四段副页：五张宣传页时轮到“确认终点”，去掉它后轮到“注意间隙”
    now = roundStart().plusSeconds(16 * 3 + 13);

    rows = List.of(row("MT", 120), row("WS", 300));
    assertEquals(PidsNotice.CHECK, noticeOn(screen, 384, 128));

    PidsScreen single = register(PidsScreen.Mode.LIVE, Set.of());
    rows = List.of(row("MT", 120), row("MT", 300));
    assertNotEquals(PidsNotice.CHECK, noticeOn(single, 384, 128));
  }

  /** 2×1：同一交路的下一班开走、后一班接上时，色牌、终点、停站都一样，仍从停站表第 1 页起。 */
  @Test
  void theNextTrainOfTheSameRouteStartsFromTheFirstPage() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    rows = List.of(named("a", 120), named("b", 600));
    now = NOW;
    composer.content(Optional.of(screen.id()), 128, 256);
    now = NOW.plusSeconds(8);
    assertInstanceOf(
        PidsComposer.FollowingKey.class,
        composer.content(Optional.of(screen.id()), 128, 256).orElseThrow().key());

    rows = List.of(named("b", 480));
    now = NOW.plusSeconds(9);

    PidsStopListView view =
        assertInstanceOf(
                PidsComposer.StopListKey.class,
                composer.content(Optional.of(screen.id()), 128, 256).orElseThrow().key())
            .view();
    assertEquals(0, view.page());
  }

  private static PidsRow named(String train, int seconds) {
    PidsRow row = row("MT", seconds);
    return new PidsRow(
        row.status(),
        row.lineName(),
        row.routeId(),
        row.destination(),
        row.destinationId(),
        row.platform(),
        row.expectedAt(),
        row.delaySeconds(),
        row.stopSequence(),
        false,
        false,
        false,
        Optional.of(train));
  }

  /** 2×1 停站屏：停站表之后是后续列车页，再是一张宣传页；下一班进站时只翻停站表。 */
  @Test
  void stopListScreensTurnToTheFollowingTrainsAndACourtesyPage() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    rows = List.of(row("MT", 120), row("WS", 300), row("MT", 600));
    now = NOW;
    assertInstanceOf(
        PidsComposer.StopListKey.class,
        composer.content(Optional.of(screen.id()), 128, 256).orElseThrow().key());

    now = NOW.plusSeconds(8);
    PidsContent following = composer.content(Optional.of(screen.id()), 128, 256).orElseThrow();
    PidsFollowingView view =
        assertInstanceOf(PidsComposer.FollowingKey.class, following.key()).view();
    assertEquals(List.of("WS", "MT"), view.trains().stream().map(t -> t.badge().code()).toList());
    assertEquals(256, following.image().get().getHeight());

    now = NOW.plusSeconds(16);
    assertFalse(noticeOn(screen, 128, 256).warning(), "之后是一张宣传页");

    PidsScreen held = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    rows = List.of(arriving("3"), row("MT", 300));
    now = NOW;
    composer.content(Optional.of(held.id()), 128, 256);
    now = NOW.plusSeconds(8);
    assertInstanceOf(
        PidsComposer.StopListKey.class,
        composer.content(Optional.of(held.id()), 128, 256).orElseThrow().key(),
        "下一班进站时留在停站表");
  }

  /** HHU 的一般公告：隔段与宣传页交替，公告那一段主页让出 8 秒；标签与色带同主页。 */
  @Test
  void platformScreensRotateBulletinsWithCourtesyPages() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    PidsBulletin bulletin = hhuBulletin(Set.of(), PidsBulletin.Level.NORMAL);
    bulletins.put(bulletin);
    now = roundStart();
    composer.content(Optional.of(screen.id()), 384, 128);

    now = roundStart().plusSeconds(9);
    PidsContent content = composer.content(Optional.of(screen.id()), 384, 128).orElseThrow();

    PidsBulletinView view = assertInstanceOf(PidsComposer.BulletinKey.class, content.key()).view();
    assertEquals("公告", view.label().primary());
    assertEquals("2 号出入口临时关闭", view.title().primary());
    assertEquals(1, view.pages());
    assertEquals(384, content.image().get().getWidth());
    now = roundStart().plusSeconds(28);
    assertEquals(PidsNotice.ORDER, noticeOn(screen, 384, 128), "下一段是宣传页");
  }

  /** 限定了线路的公告只上显示这些线路的屏幕。 */
  @Test
  void bulletinsForOtherLinesStayOff() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of("WS"));
    bulletins.put(hhuBulletin(Set.of("MT"), PidsBulletin.Level.IMPORTANT));
    now = roundStart().plusSeconds(13);

    assertFalse(noticeOn(screen, 384, 128).warning(), "副页照常放宣传页，主页也不让出时间");
    now = roundStart().plusSeconds(11);
    assertInstanceOf(
        PidsComposer.LiveKey.class,
        composer.content(Optional.of(screen.id()), 384, 128).orElseThrow().key());
  }

  /** 只放车站所属公司的公告：别家公司（运营商代码相同）发的公告不上本站的屏。 */
  @Test
  void bulletinsOfOtherCompaniesStayOff() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    PidsBulletin ours = hhuBulletin(Set.of(), PidsBulletin.Level.IMPORTANT);
    bulletins.put(
        new PidsBulletin(
            ours.id(),
            UUID.randomUUID(),
            ours.operatorCode(),
            ours.stations(),
            ours.lines(),
            ours.level(),
            ours.title(),
            ours.body(),
            ours.startsAt(),
            ours.endsAt(),
            ours.createdBy(),
            ours.createdAt(),
            ours.updatedAt()));
    now = roundStart().plusSeconds(13);

    assertFalse(noticeOn(screen, 384, 128).warning(), "放的是宣传页");
  }

  /** 2×1 停站屏：后续列车页之后是公告，分两页（中文、英文）各 8 秒。 */
  @Test
  void stopListScreensTurnToBulletinPages() {
    settings = withSlides(12, 4);
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "platform-2x1", 2, 1);
    rows = List.of(row("MT", 120), row("WS", 300));
    bulletins.put(hhuBulletin(Set.of(), PidsBulletin.Level.IMPORTANT));
    now = NOW;
    composer.content(Optional.of(screen.id()), 128, 256);
    now = NOW.plusSeconds(8);
    composer.content(Optional.of(screen.id()), 128, 256);

    now = NOW.plusSeconds(16);
    PidsBulletinView first =
        assertInstanceOf(
                PidsComposer.BulletinKey.class,
                composer.content(Optional.of(screen.id()), 128, 256).orElseThrow().key())
            .view();
    now = NOW.plusSeconds(24);
    PidsBulletinView second =
        assertInstanceOf(
                PidsComposer.BulletinKey.class,
                composer.content(Optional.of(screen.id()), 128, 256).orElseThrow().key())
            .view();

    assertEquals("重要公告", first.label().primary());
    assertEquals("1/2", first.pageLabel());
    assertEquals("2/2", second.pageLabel());
  }

  private static PidsBulletin hhuBulletin(Set<String> lines, PidsBulletin.Level level) {
    PidsBulletin sample = PidsBulletinFixtures.exitClosed();
    return PidsBulletinFixtures.bulletin(
        Set.of("HHU"),
        lines,
        level,
        sample.title(),
        sample.body(),
        Optional.empty(),
        Optional.empty());
  }

  private PidsNotice noticeOn(PidsScreen screen, int width, int height) {
    return assertInstanceOf(
            PidsNoticeView.class,
            composer.content(Optional.of(screen.id()), width, height).orElseThrow().key())
        .notice();
  }

  /** 主页与副页停留时间（秒）；其余取默认值。 */
  private static PidsSettings withSlides(int mainSeconds, int noticeSeconds) {
    PidsSettings defaults = PidsSettings.defaults();
    PidsSettings.RenderSettings render = defaults.render();
    return new PidsSettings(
        defaults.configVersion(),
        defaults.enabled(),
        new PidsSettings.RenderSettings(
            render.checkIntervalTicks(),
            render.snapshotTtlSeconds(),
            render.horizonMinutes(),
            mainSeconds,
            noticeSeconds,
            render.noticePinSeconds(),
            render.englishSeconds(),
            render.remarkSeconds(),
            render.stopPageSeconds()),
        defaults.pages(),
        defaults.limits(),
        defaults.font(),
        defaults.layout(),
        defaults.appearance(),
        defaults.broadcast());
  }

  /** 这一刻 HHU 的屏幕刚翻回主页（主页 12 秒、副页 4 秒，一轮十段 160 秒）。 */
  private static Instant roundStart() {
    long base = NOW.getEpochSecond();
    return Instant.ofEpochSecond(base - Math.floorMod(base + PidsCarousel.offset(HHU, 160), 160));
  }

  private static PidsRow arriving(String platform) {
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
        false,
        false,
        false,
        Optional.of("train"));
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

  @Test
  void lineStatusScreensListTheOperatorsLinesWithoutTheDepartureSnapshot() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of("WS"), "status-3x5", 3, 5);

    PidsContent first = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    PidsContent second = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();

    PidsLineStatusView view =
        assertInstanceOf(PidsComposer.LineStatusKey.class, first.key()).view();
    assertEquals(
        List.of("WS"), view.rows().stream().map(row -> row.line().code()).toList(), "按屏幕的线路过滤");
    assertTrue(view.roomy());
    assertEquals(first.key(), second.key(), "数据没变时不重绘");
    assertEquals(0, snapshotCalls, "状况屏不取到发快照");

    statuses = line -> PidsLineStatus.of(PidsLineStatus.Condition.MINOR_DELAYS);
    PidsContent delayed = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    assertNotEquals(first.key(), delayed.key(), "状况变了就重绘");
    assertEquals(640, delayed.image().get().getWidth());
  }

  @Test
  void lineStatusScreensCanBindOnlyAnOperator() {
    PidsScreen stationBound = register(PidsScreen.Mode.LIVE, Set.of(), "status-3x5", 3, 5);
    PidsScreen operatorOnly = stationBound.withOperator(HHU.operatorCode(), Set.of("WS"), NOW);
    registry.put(operatorOnly);

    PidsContent content = composer.content(Optional.of(operatorOnly.id()), 640, 384).orElseThrow();

    PidsLineStatusView view =
        assertInstanceOf(PidsComposer.LineStatusKey.class, content.key()).view();
    assertEquals(List.of("WS"), view.rows().stream().map(row -> row.line().code()).toList());
  }

  @Test
  void otherScreensWithOnlyAnOperatorShowTheTestCard() {
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of());
    PidsScreen operatorOnly = screen.withOperator(HHU.operatorCode(), Set.of(), NOW);
    registry.put(operatorOnly);

    card(composer.content(Optional.of(operatorOnly.id()), 384, 128));
  }

  @Test
  void combinedScreensAlternateDeparturesAndLineStatusByTheClock() {
    PidsScreen screen = registerPaged(PidsScreen.Mode.LIVE, "station-3x5", "status-3x5");
    // 到发 30 秒 + 线路运行状况 15 秒，一轮 45 秒，按站名错开
    long start = roundStart(45);

    now = Instant.ofEpochSecond(start + 29);
    PidsContent board = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    assertInstanceOf(PidsComposer.LiveKey.class, board.key());

    now = Instant.ofEpochSecond(start + 30);
    PidsContent status = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    PidsLineStatusView view =
        assertInstanceOf(PidsComposer.LineStatusKey.class, status.key()).view();
    assertEquals(List.of("MT", "WS"), view.rows().stream().map(row -> row.line().code()).toList());
    assertEquals(640, status.image().get().getWidth());

    now = Instant.ofEpochSecond(start + 45);
    assertInstanceOf(
        PidsComposer.LiveKey.class,
        composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key());
  }

  @Test
  void manyLinesShowOneStatusPagePerRoundSoDeparturesKeepTheirShare() {
    extraLines = 5;
    PidsScreen screen = registerPaged(PidsScreen.Mode.LIVE, "station-3x5", "status-3x5");
    long start = roundStart(45);

    List<Integer> pages = new ArrayList<>();
    for (int round = 0; round < 2; round++) {
      long at = start + 45L * round;
      now = Instant.ofEpochSecond(at + 29);
      assertInstanceOf(
          PidsComposer.LiveKey.class,
          composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key(),
          "每轮的到发时长不随线路数变");
      now = Instant.ofEpochSecond(at + 30);
      PidsLineStatusView view =
          assertInstanceOf(
                  PidsComposer.LineStatusKey.class,
                  composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key())
              .view();
      assertEquals(2, view.pages(), "7 条线路分两页");
      pages.add(view.page());
    }
    assertEquals(Set.of(0, 1), Set.copyOf(pages), "两页按轮轮换");
  }

  @Test
  void aStandaloneLineStatusScreenTurnsPagesByTheClock() {
    extraLines = 5;
    PidsScreen screen = register(PidsScreen.Mode.LIVE, Set.of(), "status-3x5", 3, 5);
    long start = roundStart(15);

    now = Instant.ofEpochSecond(start);
    int first = lineStatusPage(screen);
    now = Instant.ofEpochSecond(start + 14);
    assertEquals(first, lineStatusPage(screen), "每页停 15 秒");
    now = Instant.ofEpochSecond(start + 15);
    assertEquals(1 - first, lineStatusPage(screen), "7 条线路两页，到点翻页");
    now = Instant.ofEpochSecond(start + 30);
    assertEquals(first, lineStatusPage(screen));
  }

  /** 按运营商轮播：每家一页时 15 秒换一家，页头标出轮到第几家；每家只列自己的线路。 */
  @Test
  void aRotatingStatusScreenTakesTurnsBetweenOperatorsOfOneCompany() {
    operatorsNamed = true;
    PidsScreen screen =
        register(PidsScreen.Mode.LIVE, Set.of(), "status-3x5", 3, 5)
            .withRotation(List.of("SURC", "SURN"), NOW);
    registry.put(screen);
    long start = roundStart(15);

    now = Instant.ofEpochSecond(start);
    PidsLineStatusView first = statusView(screen);
    now = Instant.ofEpochSecond(start + 15);
    PidsLineStatusView second = statusView(screen);
    now = Instant.ofEpochSecond(start + 30);
    PidsLineStatusView third = statusView(screen);

    List<PidsLineStatusView> views = List.of(first, second);
    PidsLineStatusView surc =
        views.stream().filter(view -> view.rotationIndex() == 0).findFirst().orElseThrow();
    PidsLineStatusView surn =
        views.stream().filter(view -> view.rotationIndex() == 1).findFirst().orElseThrow();
    assertEquals("南城铁路", surc.operator().primary());
    assertEquals(List.of("MT", "WS"), surc.rows().stream().map(row -> row.line().code()).toList());
    assertEquals("北城铁路", surn.operator().primary());
    assertEquals(List.of("NL"), surn.rows().stream().map(row -> row.line().code()).toList());
    assertEquals(List.of("SURC", "SURN"), surc.rotation(), "页头按轮播顺序列出两家的代码");
    assertEquals(first.rotationIndex(), third.rotationIndex(), "两家各一页：一圈 30 秒");
    assertEquals(
        640,
        composer
            .content(Optional.of(screen.id()), 640, 384)
            .orElseThrow()
            .image()
            .get()
            .getWidth());
  }

  /** 只写线路代码的过滤是给屏幕所属运营商选的：别家照常显示全部线路，不会因为对不上被跳过。 */
  @Test
  void aBareLineFilterOnlyNarrowsTheScreensOwnOperator() {
    operatorsNamed = true;
    PidsScreen screen =
        register(PidsScreen.Mode.LIVE, Set.of("MT"), "status-3x5", 3, 5)
            .withRotation(List.of("SURC", "SURN"), NOW);
    registry.put(screen);
    long start = roundStart(15);

    now = Instant.ofEpochSecond(start);
    PidsLineStatusView first = statusView(screen);
    now = Instant.ofEpochSecond(start + 15);
    PidsLineStatusView second = statusView(screen);

    List<PidsLineStatusView> views = List.of(first, second);
    assertEquals(
        List.of(List.of("MT"), List.of("NL")),
        views.stream()
            .sorted(java.util.Comparator.comparingInt(PidsLineStatusView::rotationIndex))
            .map(view -> view.rows().stream().map(row -> row.line().code()).toList())
            .toList());
  }

  @Test
  void rotationIgnoresOperatorsOfAnotherCompany() {
    operatorsNamed = true;
    PidsScreen screen =
        register(PidsScreen.Mode.LIVE, Set.of(), "status-3x5", 3, 5)
            .withRotation(List.of("SURC", "FOO"), NOW);
    registry.put(screen);

    PidsLineStatusView view = statusView(screen);

    assertFalse(view.rotating(), "别的公司的运营商不轮");
    assertEquals("南城铁路", view.operator().primary());
  }

  private PidsLineStatusView statusView(PidsScreen screen) {
    return assertInstanceOf(
            PidsComposer.LineStatusKey.class,
            composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key())
        .view();
  }

  private int lineStatusPage(PidsScreen screen) {
    PidsLineStatusView view =
        assertInstanceOf(
                PidsComposer.LineStatusKey.class,
                composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key())
            .view();
    assertEquals(2, view.pages());
    return view.page();
  }

  /** 这一刻 HHU 的组合屏所在一轮的起点。 */
  private static long roundStart(long cycle) {
    long base = NOW.getEpochSecond();
    return base - Math.floorMod(base + PidsCarousel.offset(HHU, cycle), cycle);
  }

  @Test
  void combinedTestCardsListEveryLayoutInOrder() {
    PidsScreen screen = registerPaged(PidsScreen.Mode.TEST_CARD, "status-3x5", "station-3x5");

    PidsTestCard card = card(composer.content(Optional.of(screen.id()), 640, 384));

    assertEquals("布局：线路运行状况 3×5 + 车站统屏 3×5（3×5）", card.lines().get(0));
  }

  @Test
  void operatorOnlyCombinedScreensShowOnlyTheLineStatus() {
    PidsScreen screen =
        registerPaged(PidsScreen.Mode.LIVE, "station-3x5", "status-3x5")
            .withOperator(HHU.operatorCode(), Set.of(), NOW);
    registry.put(screen);
    long start = roundStart(15);

    for (long at = start; at < start + 45; at += 5) {
      now = Instant.ofEpochSecond(at);
      assertInstanceOf(
          PidsComposer.LineStatusKey.class,
          composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key(),
          "没绑车站时到发页不出现");
    }
    assertEquals(0, snapshotCalls, "不取到发快照");
  }

  /** 站场图屏：画面只随站场图变，不取到发快照；没接入调度时写“调度图未就绪”。 */
  @Test
  void yardScreensDrawTheYardAndKeepTheirContentWhileNothingChanges() {
    PidsScreen screen = registerPaged(PidsScreen.Mode.LIVE, "yard-3x5");
    PidsContent idle = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    assertEquals(
        Optional.of(new Names("调度图未就绪", "Rail graph not ready")),
        assertInstanceOf(PidsComposer.YardKey.class, idle.key()).view().message());

    YardPicture picture = YardPicture.unavailable(YardPicture.Problem.NOT_IN_GRAPH);
    List<YardScope> asked = new ArrayList<>();
    composer.setYards(
        (scope, world, widget) -> {
          asked.add(scope);
          return picture;
        });
    PidsContent first = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    PidsContent second = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();

    assertEquals(first.key(), second.key(), "站场图没变，画面不重发");
    assertEquals(List.of(YardScope.station(HHU), YardScope.station(HHU)), asked);
    assertEquals(640, first.image().get().getWidth());
    assertEquals(0, snapshotCalls, "不取到发快照");
  }

  @Test
  void yardScreensWithoutAStationShowTheTestCard() {
    PidsScreen screen =
        new PidsScreen(
            UUID.randomUUID(),
            WORLD,
            new PidsScreen.Position(0, 64, 0),
            PidsFacing.SOUTH,
            3,
            5,
            List.of("yard-3x5"),
            Optional.empty(),
            Optional.of("SURC"),
            Set.of(),
            Set.of(),
            PidsScreen.Appearance.AUTO,
            PidsScreen.Mode.LIVE,
            NOW,
            NOW);
    registry.put(screen);

    assertInstanceOf(
        PidsTestCard.class,
        composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key());
  }

  /** 绑了控制区的站场图屏：按控制区成员取站场图，标题写控制区名；控制区找不到时写说明，不取站场图。 */
  @Test
  void yardScreensBoundToAZoneDrawTheZone() {
    PidsScreen screen =
        registerPaged(PidsScreen.Mode.LIVE, "yard-3x5")
            .withOperator(HHU.operatorCode(), Set.of(), NOW)
            .withZone(Optional.of("north"), NOW);
    registry.put(screen);
    List<YardScope> asked = new ArrayList<>();
    composer.setYards(
        (scope, world, widget) -> {
          asked.add(scope);
          return YardPicture.unavailable(YardPicture.Problem.NOT_IN_GRAPH);
        });

    PidsYardView missing =
        assertInstanceOf(
                PidsComposer.YardKey.class,
                composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key())
            .view();
    assertEquals(new Names("NORTH", ""), missing.title());
    assertEquals(Optional.of(new Names("未找到绑定的控制区", "Control zone not found")), missing.message());
    assertTrue(asked.isEmpty(), "控制区找不到时不取站场图");

    YardScope scope =
        new YardScope(
            "SURC", List.of(YardArea.Member.station("HHU"), YardArea.Member.depot("HHD")));
    composer.setControlZones(
        (operator, code) ->
            operator.equals("SURC") && code.equals("NORTH")
                ? Optional.of(new PidsYardZones.Zone("北区", Optional.of("North"), scope))
                : Optional.empty());
    PidsYardView found =
        assertInstanceOf(
                PidsComposer.YardKey.class,
                composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key())
            .view();
    assertEquals(new Names("北区", "North"), found.title());
    assertEquals(List.of(scope), asked);
  }

  /** 信号员控制台：按屏幕所属运营商与控制区取控制台数据；内容标识含控制台数据的全部字段，值相同不重画、选中车一变就重画；去向站码换成站名。 */
  @Test
  void signalboxScreensKeyOnTheConsoleState() {
    PidsScreen screen =
        registerPaged(PidsScreen.Mode.LIVE, "signalbox-3x5").withZone(Optional.of("north"), NOW);
    registry.put(screen);
    List<String> asked = new ArrayList<>();
    SignalboxState[] current = {console(Optional.of("train-a"))};
    composer.setSignalboxConsole(
        new SignalboxConsole() {
          @Override
          public Optional<SignalboxState> state(String operator, String zone) {
            asked.add(operator + "/" + zone);
            return Optional.of(current[0]);
          }

          @Override
          public void press(
              Player player,
              Location console,
              String operator,
              String zone,
              ConsoleTarget target) {}
        });

    PidsContent first = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    PidsContent second = composer.content(Optional.of(screen.id()), 640, 384).orElseThrow();
    PidsComposer.SignalboxKey key = assertInstanceOf(PidsComposer.SignalboxKey.class, first.key());
    assertEquals(first.key(), second.key(), "控制台数据没变，画面不重发");
    assertEquals(List.of("SURC/NORTH", "SURC/NORTH"), asked);
    assertEquals(Optional.of(current[0]), key.view().state());
    assertEquals("新笛矢 · 壑湖", key.view().destinations().get("HHU"), "去向站码按运营商换成站名");
    assertTrue(key.view().placeholder().isEmpty());
    assertEquals(640, first.image().get().getWidth());

    current[0] = console(Optional.of("train-b"));
    assertNotEquals(
        first.key(),
        composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key(),
        "选中车变了要重画");
    current[0] = console(Optional.of("train-b"));
    assertEquals(
        composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key(),
        composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key(),
        "新取的数据值相同就不重画");
    assertEquals(0, snapshotCalls, "不取到发快照");
  }

  /** 中心工位上有人值守：控制台按信号员模块给的区取数据（在岗那位的临时小区）；不是工位的屏照旧按绑定的区。 */
  @Test
  void aStaffedWorkstationDrawsItsHoldersZone() {
    PidsScreen desk =
        registerPaged(PidsScreen.Mode.LIVE, "signalbox-3x5")
            .withZone(Optional.of("north"), NOW)
            .withWorkstation(true, NOW);
    registry.put(desk);
    List<String> asked = new ArrayList<>();
    composer.setSignalboxConsole(
        new SignalboxConsole() {
          @Override
          public Optional<SignalboxState> state(String operator, String zone) {
            asked.add(zone);
            return Optional.of(console(Optional.empty()));
          }

          @Override
          public void press(
              Player player,
              Location console,
              String operator,
              String zone,
              ConsoleTarget target) {}

          @Override
          public String zoneFor(java.util.UUID screen, String operator, String zone) {
            return screen.equals(desk.id()) ? "NORTH~2" : zone;
          }
        });

    composer.content(Optional.of(desk.id()), 640, 384).orElseThrow();

    assertEquals(List.of("NORTH~2"), asked);
  }

  @Test
  void theStationATrainWouldSkipIsNamedToo() {
    PidsScreen screen =
        registerPaged(PidsScreen.Mode.LIVE, "signalbox-3x5").withZone(Optional.of("north"), NOW);
    registry.put(screen);
    SignalboxState base = console(Optional.of("train-a"));
    SignalboxState page =
        new SignalboxState(
            base.mode(),
            base.signaller(),
            base.selected(),
            List.of(),
            base.buttons(),
            base.call(),
            false,
            base.notice(),
            base.preview(),
            base.pending(),
            base.signals(),
            base.platforms(),
            base.delegated(),
            Optional.of(new SignalboxState.TrainOps(false, Optional.of("HHU"), false)));
    composer.setSignalboxConsole(
        new SignalboxConsole() {
          @Override
          public Optional<SignalboxState> state(String operator, String zone) {
            return Optional.of(page);
          }

          @Override
          public void press(
              Player player,
              Location console,
              String operator,
              String zone,
              ConsoleTarget target) {}
        });

    PidsComposer.SignalboxKey key =
        assertInstanceOf(
            PidsComposer.SignalboxKey.class,
            composer.content(Optional.of(screen.id()), 640, 384).orElseThrow().key());

    assertEquals("新笛矢 · 壑湖", key.view().destinations().get("HHU"));
  }

  /** 控制台没绑控制区、信号员模块未接入或取数出错：只画站场图，列车表的位置写说明，不抛异常。 */
  @Test
  void signalboxScreensWithoutConsoleDataShowWhy() {
    PidsScreen unbound = registerPaged(PidsScreen.Mode.LIVE, "signalbox-3x5");
    PidsComposer.SignalboxKey noZone =
        assertInstanceOf(
            PidsComposer.SignalboxKey.class,
            composer.content(Optional.of(unbound.id()), 640, 384).orElseThrow().key());
    assertTrue(noZone.view().state().isEmpty());
    assertEquals(
        "未绑定控制区，请以 /fta signal console set <控制台> zone <控制区> 绑定",
        noZone.view().placeholder().orElseThrow().primary());

    PidsScreen bound = unbound.withZone(Optional.of("north"), NOW);
    registry.put(bound);
    PidsComposer.SignalboxKey notReady =
        assertInstanceOf(
            PidsComposer.SignalboxKey.class,
            composer.content(Optional.of(bound.id()), 640, 384).orElseThrow().key());
    assertEquals(
        new Names("控制台未就绪", "Signal box console not ready"),
        notReady.view().placeholder().orElseThrow());

    composer.setSignalboxConsole(
        new SignalboxConsole() {
          @Override
          public Optional<SignalboxState> state(String operator, String zone) {
            throw new IllegalStateException("boom");
          }

          @Override
          public void press(
              Player player,
              Location console,
              String operator,
              String zone,
              ConsoleTarget target) {}
        });
    PidsContent failing = composer.content(Optional.of(bound.id()), 640, 384).orElseThrow();
    assertTrue(
        assertInstanceOf(PidsComposer.SignalboxKey.class, failing.key()).view().state().isEmpty());
    assertEquals(640, failing.image().get().getWidth());
  }

  private static SignalboxState console(Optional<String> selected) {
    return new SignalboxState(
        SignalboxState.Mode.MANUAL,
        Optional.of("Sinapole"),
        selected,
        List.of(
            new SignalboxState.TrainRow(
                "train-a",
                "1234",
                Optional.empty(),
                Optional.of("HHU"),
                SignalboxState.RowStatus.WAITING,
                12,
                OptionalLong.of(70),
                0.5,
                false,
                false)),
        java.util.Map.of(SignalboxState.Button.SET_ROUTE, true),
        Optional.empty(),
        false,
        Optional.empty(),
        Set.of(),
        Set.of(),
        List.of(),
        List.of());
  }

  private PidsScreen registerPaged(PidsScreen.Mode mode, String... layouts) {
    PidsScreen screen =
        new PidsScreen(
            UUID.randomUUID(),
            WORLD,
            new PidsScreen.Position(0, 64, 0),
            PidsFacing.SOUTH,
            3,
            5,
            List.of(layouts),
            Optional.of(HHU),
            Optional.empty(),
            Set.of(),
            Set.of(),
            PidsScreen.Appearance.AUTO,
            mode,
            NOW,
            NOW);
    registry.put(screen);
    return screen;
  }

  private PidsScreen register(PidsScreen.Mode mode, Set<String> lines) {
    return register(mode, lines, "platform-1x3", 1, 3);
  }

  private PidsScreen register(
      PidsScreen.Mode mode, Set<String> lines, String layout, int tileRows, int tileCols) {
    PidsScreen screen =
        new PidsScreen(
            UUID.randomUUID(),
            WORLD,
            new PidsScreen.Position(0, 64, 0),
            PidsFacing.SOUTH,
            tileRows,
            tileCols,
            layout,
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

  private final class Directory implements PidsDirectory {
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
    public Optional<UUID> companyOfOperator(String operatorCode) {
      return "SURC".equals(operatorCode) || "SURN".equals(operatorCode)
          ? Optional.of(PidsBulletinFixtures.COMPANY)
          : Optional.empty();
    }

    @Override
    public Optional<Names> operatorName(String operatorCode) {
      if (!operatorsNamed) {
        return Optional.empty();
      }
      return switch (operatorCode) {
        case "SURC" -> Optional.of(new Names("南城铁路", "SURcentral"));
        case "SURN" -> Optional.of(new Names("北城铁路", "SURnorth"));
        default -> Optional.empty();
      };
    }

    @Override
    public List<PidsView.LineChip> linesServingPlatform(PidsStationKey station, String platform) {
      return List.of();
    }

    @Override
    public List<OperatorLine> operatorLines(String operatorCode) {
      if ("SURN".equals(operatorCode)) {
        return List.of(
            new OperatorLine(
                UUID.randomUUID(),
                "SURN",
                new PidsView.LineChip("NL", 0x3060C0, new Names("北环线", "North Loop")),
                LineApi.LineStatus.ACTIVE,
                Optional.empty()));
      }
      List<OperatorLine> lines = new ArrayList<>();
      lines.add(
          new OperatorLine(
              UUID.randomUUID(),
              "SURC",
              new PidsView.LineChip("MT", 0xD920D9, new Names("大都会线", "Metropolitan Line")),
              LineApi.LineStatus.ACTIVE,
              Optional.empty()));
      lines.add(
          new OperatorLine(
              UUID.randomUUID(),
              "SURC",
              new PidsView.LineChip("WS", 0x70DEEE, new Names("浦蓝线", "Waterside Line")),
              LineApi.LineStatus.ACTIVE,
              Optional.empty()));
      for (int i = 1; i <= extraLines; i++) {
        lines.add(
            new OperatorLine(
                UUID.randomUUID(),
                "SURC",
                new PidsView.LineChip("X" + i, 0x808080, new Names("线路 " + i, "Line " + i)),
                LineApi.LineStatus.ACTIVE,
                Optional.empty()));
      }
      return lines;
    }
  }
}
