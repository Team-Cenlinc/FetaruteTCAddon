package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsContent;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRenderer;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsFollowingView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusSource;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatusViews;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsStopListView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVacancyView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsViewBuilder;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;

/**
 * 决定一块屏幕此刻显示什么。
 *
 * <ul>
 *   <li>地图物品不指向任何已知屏幕：测试卡“未注册”（屏幕表尚未成功读入时不判定，保持原画面）
 *   <li>展示框拼出的尺寸与记录不符（有展示框被挪走）：测试卡“尺寸不符”
 *   <li>测试卡模式或未绑定车站：测试卡，列出布局、识别出的车站与屏幕编号
 *   <li>线路运行状况屏（布局带状况表组件）：本站所属运营商各线路的运行状况（{@link PidsLineStatusViews}），不轮播宣传页
 *   <li>停站屏（布局带停站表组件）：本站台下一班的停站表（停站多时翻页）、后续列车页与宣传页依次轮换（{@link PidsCarousel#stopList}），
 *       不放空位页；通过列车临近时同样锁定安全提示页
 *   <li>其余：到发信息；站台屏与多站台屏按 {@link PidsCarousel} 轮播宣传页，通过列车临近时锁定安全提示页； 所有到发页的英文与备注按 {@link
 *       PidsCarousel#remarks} 轮换
 * </ul>
 *
 * <p>不碰 Bukkit：世界时间、文案、快照都由调用方注入，单元测试可直接驱动。
 */
public final class PidsComposer {

  /** 地图边长。 */
  static final int TILE = 128;

  private final PidsScreenRegistry registry;
  private final BooleanSupplier registryLoaded;
  private final PidsLayoutRegistry layouts;
  private final Function<PidsStationKey, PidsSnapshot> snapshots;
  private final PidsViewBuilder views;
  private final PidsDirectory directory;
  private final PidsRenderer renderer;
  private final Function<String, String> texts;
  private final Function<UUID, OptionalLong> worldTime;
  private final Supplier<PidsSettings> settings;
  private final InstantSource clock;
  private final ZoneId zone;
  private final PidsVocabulary vocabulary;
  private final PidsLineStatusSource lineStatuses;
  private final PidsLineStatusViews lineStatusViews;
  private final PidsCarousel carousel = new PidsCarousel();

  /**
   * @param registry 屏幕表
   * @param registryLoaded 屏幕表是否已成功读入
   * @param layouts 布局目录
   * @param snapshots 按车站取快照
   * @param views 视图构建
   * @param directory 名称目录
   * @param renderer 渲染器
   * @param texts 按键取纯文本文案
   * @param worldTime 世界当天时刻（刻）；世界未加载为空
   * @param settings 当前配置
   * @param clock 时钟
   * @param zone 时钟时区
   * @param lineStatuses 线路运行状况
   */
  public PidsComposer(
      PidsScreenRegistry registry,
      BooleanSupplier registryLoaded,
      PidsLayoutRegistry layouts,
      Function<PidsStationKey, PidsSnapshot> snapshots,
      PidsViewBuilder views,
      PidsDirectory directory,
      PidsRenderer renderer,
      Function<String, String> texts,
      Function<UUID, OptionalLong> worldTime,
      Supplier<PidsSettings> settings,
      InstantSource clock,
      ZoneId zone,
      PidsLineStatusSource lineStatuses) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.registryLoaded = Objects.requireNonNull(registryLoaded, "registryLoaded");
    this.layouts = Objects.requireNonNull(layouts, "layouts");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.views = Objects.requireNonNull(views, "views");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.texts = Objects.requireNonNull(texts, "texts");
    this.worldTime = Objects.requireNonNull(worldTime, "worldTime");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zone = Objects.requireNonNull(zone, "zone");
    this.vocabulary = new PidsVocabulary(texts);
    this.lineStatuses = Objects.requireNonNull(lineStatuses, "lineStatuses");
    this.lineStatusViews = new PidsLineStatusViews(directory, vocabulary);
  }

  /** 到发页的内容标识：布局与视图都相同才算没变。 */
  record LiveKey(PidsLayout layout, PidsView view) {}

  /** 空位页的内容标识：渲染随布局（色牌样式、色带位置）而定。 */
  record VacancyKey(PidsLayout layout, PidsVacancyView view) {}

  /** 2×1 停站屏的内容标识：视图含当前页号。 */
  record StopListKey(PidsLayout layout, PidsStopListView view) {}

  /** 2×1 后续列车页的内容标识。 */
  record FollowingKey(PidsLayout layout, PidsFollowingView view) {}

  /** 线路运行状况屏的内容标识：视图含当前页号。 */
  record LineStatusKey(PidsLayout layout, PidsLineStatusView view) {}

  /**
   * @param screenId 地图物品上记的屏幕 ID
   * @param width 展示框拼出的宽度（像素）
   * @param height 展示框拼出的高度（像素）
   * @return 屏幕表尚未读入、无法判定时为空
   */
  public Optional<PidsContent> content(Optional<UUID> screenId, int width, int height) {
    int rows = Math.max(1, height / TILE);
    int cols = Math.max(1, width / TILE);
    Optional<PidsScreen> found = screenId.flatMap(registry::find);
    if (found.isEmpty()) {
      return registryLoaded.getAsBoolean()
          ? Optional.of(card(notice("unregistered", Map.of(), rows, cols)))
          : Optional.empty();
    }
    PidsScreen screen = found.get();
    if (screen.tileRows() != rows || screen.tileCols() != cols) {
      return Optional.of(
          card(
              notice(
                  "size-mismatch",
                  Map.of(
                      "actual", rows + "×" + cols,
                      "expected", screen.tileRows() + "×" + screen.tileCols()),
                  rows,
                  cols)));
    }
    Optional<PidsLayout> layout = layouts.resolve(screen.layoutId(), rows, cols);
    if (layout.isEmpty()) {
      return Optional.of(card(notice("no-layout", Map.of("size", rows + "×" + cols), rows, cols)));
    }
    if (screen.mode() == PidsScreen.Mode.TEST_CARD || screen.station().isEmpty()) {
      return Optional.of(card(testCard(screen, layout.get())));
    }
    Optional<PidsLayout.LineStatus> status = layout.get().lineStatus();
    if (status.isPresent()) {
      return Optional.of(lineStatus(screen, layout.get(), status.get(), screen.station().get()));
    }
    return Optional.of(live(screen, layout.get(), screen.station().get()));
  }

  /** 线路运行状况屏：线路少时用大行，多了用小行并翻页。 */
  private PidsContent lineStatus(
      PidsScreen screen, PidsLayout layout, PidsLayout.LineStatus widget, PidsStationKey station) {
    PidsLineStatusView view =
        lineStatusViews.build(
            new PidsLineStatusViews.Request(
                station,
                screen.lines(),
                theme(screen),
                clock.instant(),
                zone,
                widget.rowsPerPage(widget.roomy()),
                widget.rowsPerPage(widget.compact())),
            lineStatuses);
    return new PidsContent(
        new LineStatusKey(layout, view), () -> renderer.renderLineStatus(layout, view));
  }

  /** 屏幕的测试卡：布局、识别出的车站与站台、屏幕编号。 */
  public PidsTestCard testCard(PidsScreen screen, PidsLayout layout) {
    String station =
        screen
            .station()
            .map(
                key ->
                    format(
                        "pids.test-card.station",
                        Map.of(
                            "station", stationName(key),
                            "code", key.stationCode(),
                            "platforms", platforms(screen))))
            .orElseGet(() -> text("pids.test-card.station-none"));
    return new PidsTestCard(
        new Names(text("pids.test-card.title"), text("pids.test-card.title-secondary")),
        List.of(
            format(
                "pids.test-card.layout",
                Map.of(
                    "layout", layout.name(), "size", screen.tileRows() + "×" + screen.tileCols())),
            station,
            format("pids.test-card.screen", Map.of("id", shortId(screen.id())))),
        text("pids.test-card.hint"),
        screen.tileRows(),
        screen.tileCols());
  }

  /** 屏幕编号的短写：UUID 前 8 位。 */
  public static String shortId(UUID id) {
    return id.toString().substring(0, 8);
  }

  private PidsContent live(PidsScreen screen, PidsLayout layout, PidsStationKey station) {
    PidsSnapshot snapshot = snapshots.apply(station);
    if (!screen.lines().isEmpty()) {
      snapshot =
          new PidsSnapshot(
              snapshot.station(),
              snapshot.takenAt(),
              snapshot.rows().stream()
                  .filter(row -> screen.lines().contains(row.lineName().toUpperCase(Locale.ROOT)))
                  .toList());
    }
    Instant now = clock.instant();
    List<String> platformLabels =
        screen.platforms().stream().sorted(PidsPlatformNode.PLATFORM_ORDER).toList();
    boolean rotating =
        layout.stopList().isEmpty() && PidsPlatformSelection.limit(layout).isPresent();
    PidsViewBuilder.Request request =
        new PidsViewBuilder.Request(
            snapshot,
            now,
            zone,
            theme(screen),
            screen.platforms(),
            platformLabels,
            layout.rowCapacity(),
            layout.departures().map(d -> d.columns().platform().isPresent()).orElse(false),
            Optional.of(placement(screen)),
            PidsCarousel.remarks(station, now, settings.get().render(), rotating));
    Optional<PidsLayout.StopList> stopList = layout.stopList();
    if (stopList.isPresent()) {
      return stopList(screen, layout, stopList.get(), snapshot, request, now);
    }
    PidsView view = views.build(request);
    if (rotating) {
      Optional<PidsCarousel.Slide> slide =
          carousel.page(
              screen.id(),
              station,
              new PidsCarousel.Signals(
                  passingSoon(screen, snapshot),
                  views.hasVacancy(request),
                  arrivingHere(screen, snapshot),
                  courtesy(request)),
              now,
              settings.get().render());
      if (slide.isPresent() && slide.get() instanceof PidsCarousel.Slide.Notice page) {
        return notice(layout, view.theme(), view.bandColors(), page.notice());
      }
      Optional<PidsVacancyView> vacancy =
          slide.isPresent() ? views.vacancy(request) : Optional.empty();
      if (vacancy.isPresent()) {
        PidsVacancyView seats = vacancy.get();
        return new PidsContent(
            new VacancyKey(layout, seats), () -> renderer.renderVacancy(layout, seats));
      }
    }
    return new PidsContent(new LiveKey(layout, view), () -> renderer.render(layout, view));
  }

  /** 宣传页或安全提示页，色带与主页相同。 */
  private PidsContent notice(
      PidsLayout layout, PidsTheme theme, List<Integer> bandColors, PidsNotice which) {
    PidsNoticeView notice =
        new PidsNoticeView(
            theme, which, vocabulary.noticeTitle(which), vocabulary.noticeBody(which), bandColors);
    return new PidsContent(notice, () -> renderer.renderNotice(layout, notice));
  }

  /** 2×1 停站屏：下一班的停站表（停站多时翻页）、后续列车页、宣传页依次轮换；通过列车临近时锁定安全提示页。 */
  private PidsContent stopList(
      PidsScreen screen,
      PidsLayout layout,
      PidsLayout.StopList widget,
      PidsSnapshot snapshot,
      PidsViewBuilder.Request request,
      Instant now) {
    PidsStopListView full = views.stopList(request);
    int pages =
        full.train()
            .map(train -> widget.pages(train.stops().size(), full.note().isPresent()))
            .orElse(1);
    Object train =
        full.train().<Object>map(found -> List.of(found.id(), found.stops())).orElse(List.of());
    PidsCarousel.StopListSlide slide =
        carousel.stopList(
            screen.id(),
            new PidsCarousel.Signals(
                passingSoon(screen, snapshot),
                false,
                arrivingHere(screen, snapshot),
                courtesy(request)),
            new PidsCarousel.StopListPages(
                train, pages, widget.followingRows() > 0 && views.hasFollowing(request)),
            now,
            settings.get().render());
    return switch (slide) {
      case PidsCarousel.StopListSlide.Stops stops -> {
        PidsStopListView view = full.withPage(stops.page());
        yield new PidsContent(
            new StopListKey(layout, view), () -> renderer.renderStopList(layout, view));
      }
      case PidsCarousel.StopListSlide.Following ignored -> {
        PidsFollowingView following = views.following(request, widget.followingRows());
        yield new PidsContent(
            new FollowingKey(layout, following), () -> renderer.renderFollowing(layout, following));
      }
      case PidsCarousel.Slide.Notice page -> notice(
          layout, full.theme(), full.bandColors(), page.notice());
    };
  }

  /** 本屏轮换的宣传页：按配置的顺序；“确认终点”只在本屏站台有不同停站方式（多条线路、快慢车）时放。 */
  private List<PidsNotice> courtesy(PidsViewBuilder.Request request) {
    List<PidsNotice> configured = settings.get().render().notices();
    if (!configured.contains(PidsNotice.CHECK) || views.mixedServices(request)) {
      return configured;
    }
    return configured.stream().filter(notice -> notice != PidsNotice.CHECK).toList();
  }

  /** 屏幕所在世界与站在屏幕前看去的“向右”。 */
  static PidsViewBuilder.Placement placement(PidsScreen screen) {
    return new PidsViewBuilder.Placement(
        screen.worldId(), screen.facing().rightX(), screen.facing().rightZ());
  }

  /** 本屏的站台有通过列车即将通过（已按线路过滤）；原定走本站台、已改走别的股道的不算。 */
  private static boolean passingSoon(PidsScreen screen, PidsSnapshot snapshot) {
    return snapshot.rows().stream()
        .anyMatch(
            row ->
                row.passing()
                    && row.status() == PidsRow.Status.ARRIVING
                    && onThisPlatform(screen, row));
  }

  /**
   * 本屏站台停车的第一班（已按线路过滤）正在进站或停靠、乘客能上：这时不翻到宣传页。
   *
   * <p>本站终到、回库的车乘客不能上，不算；只看第一班，后面的车进站状态不影响。
   */
  private static boolean arrivingHere(PidsScreen screen, PidsSnapshot snapshot) {
    return snapshot.rows().stream()
        .filter(row -> !row.passing() && row.status() != PidsRow.Status.CANCELLED)
        .filter(row -> onThisPlatform(screen, row))
        .findFirst()
        .filter(
            row ->
                (row.status() == PidsRow.Status.ARRIVING || row.status() == PidsRow.Status.BOARDING)
                    && !row.terminating()
                    && !row.outOfService())
        .isPresent();
  }

  /** 这一行属于本屏的站台：统屏看全部；其余会停在本屏站台之一、且没有改去别的股道。 */
  private static boolean onThisPlatform(PidsScreen screen, PidsRow row) {
    return screen.platforms().isEmpty()
        || (row.mayUse(screen.platforms()) && !row.movedAwayFrom(screen.platforms()));
  }

  private PidsTheme theme(PidsScreen screen) {
    boolean dark =
        switch (screen.appearance()) {
          case LIGHT -> false;
          case DARK -> true;
          case AUTO -> {
            OptionalLong time = worldTime.apply(screen.worldId());
            yield time.isEmpty() || settings.get().appearance().darkAt(time.getAsLong());
          }
        };
    return dark ? PidsTheme.DARK : PidsTheme.LIGHT;
  }

  private PidsContent card(PidsTestCard card) {
    return new PidsContent(card, () -> renderer.renderTestCard(card));
  }

  private PidsTestCard notice(String kind, Map<String, String> values, int rows, int cols) {
    String prefix = "pids.test-card." + kind;
    return new PidsTestCard(
        new Names(text(prefix + ".title"), text(prefix + ".title-secondary")),
        List.of(format(prefix + ".detail", values)),
        text(prefix + ".hint"),
        rows,
        cols);
  }

  private String stationName(PidsStationKey key) {
    return directory.stationName(key.toString()).map(Names::primary).orElse(key.stationCode());
  }

  private String platforms(PidsScreen screen) {
    return screen.platforms().isEmpty()
        ? text("pids.test-card.all-platforms")
        : format(
            "pids.test-card.platforms", Map.of("platforms", String.join("、", screen.platforms())));
  }

  private String text(String key) {
    return texts.apply(key);
  }

  /** 纯文本文案的 {@code <占位符>} 替换。 */
  private String format(String key, Map<String, String> values) {
    String result = text(key);
    for (Map.Entry<String, String> entry : values.entrySet()) {
      result = result.replace("<" + entry.getKey() + ">", entry.getValue());
    }
    return result;
  }
}
