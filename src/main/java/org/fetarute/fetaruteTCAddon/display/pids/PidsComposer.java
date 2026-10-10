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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinBoard;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayoutRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsContent;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsBulletinTypesetter;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsRenderer;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreenRegistry;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsBulletinView;
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
 *   <li>测试卡模式：测试卡，列出布局、识别出的车站（或运营商）与屏幕编号
 *   <li>线路运行状况屏（布局带状况表组件）：屏幕所属运营商各线路的运行状况（{@link PidsLineStatusViews}），不轮播宣传页；线路多时分页； 可以不绑车站、只绑运营商
 *   <li>组合翻页（屏幕除主布局外还选了布局，见 {@link PidsScreenPages}）：按时钟轮流显示各布局，每个布局照它自己的规则显示，
 *       线路运行状况分页时每轮放一页、按轮轮换；线路运行状况屏单独用时也按这套时钟翻页。只绑运营商的屏幕只轮流显示线路运行状况
 *   <li>其余屏未绑定车站：测试卡
 *   <li>停站屏（布局带停站表组件）：本站台下一班的停站表（停站多时翻页）、后续列车页与宣传页依次轮换（{@link PidsCarousel#stopList}），
 *       不放空位页；通过列车临近时同样锁定安全提示页
 *   <li>其余：到发信息；站台屏与多站台屏按 {@link PidsCarousel} 轮播宣传页，通过列车临近时锁定安全提示页； 所有到发页的英文与备注按 {@link
 *       PidsCarousel#remarks} 轮换
 *   <li>公告：站台屏、多站台屏与停站屏轮播本屏适用的公告（{@link PidsBulletinBoard#active}），按本屏布局排版分页； 车站统屏与线路运行状况屏不放公告
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
  private final PidsBulletinBoard bulletins;

  /** 叫车：本屏能不能叫车、哪些车是叫来的。未接入时一律不叫车。 */
  private volatile Function<PidsScreen, PidsViewBuilder.Calls> calls =
      screen -> PidsViewBuilder.Calls.NONE;

  /** 公告按布局排好的版：同一条公告、同一布局只排一次。 */
  private final Map<TypesetKey, PidsBulletinTypesetter.Result> typeset = new ConcurrentHashMap<>();

  /** 排版缓存的上限：超过时整个清空（公告改过、布局重载后旧条目不再用到）。 */
  private static final int TYPESET_CACHE_LIMIT = 512;

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
   * @param bulletins 公告表
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
      PidsLineStatusSource lineStatuses,
      PidsBulletinBoard bulletins) {
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
    this.bulletins = Objects.requireNonNull(bulletins, "bulletins");
  }

  /** 没有公告。 */
  /**
   * 接入叫车：站台屏空行写叫车提示、叫来的车状态写“叫车”。
   *
   * @param calls 屏幕 → 叫车显示；null 恢复默认（不叫车）
   */
  public void setCalls(Function<PidsScreen, PidsViewBuilder.Calls> calls) {
    this.calls = calls == null ? screen -> PidsViewBuilder.Calls.NONE : calls;
  }

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
    this(
        registry,
        registryLoaded,
        layouts,
        snapshots,
        views,
        directory,
        renderer,
        texts,
        worldTime,
        settings,
        clock,
        zone,
        lineStatuses,
        new PidsBulletinBoard());
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

  /** 公告页的内容标识：视图含当前页的排版。 */
  record BulletinKey(PidsLayout layout, PidsBulletinView view) {}

  /** 排版缓存的键：公告内容版本与布局编号、尺寸（重要与否已含在内容版本里）。布局与文案随站台屏服务一起重建，不必比较整个布局。 */
  private record TypesetKey(String revision, String layoutId, int tileRows, int tileCols) {}

  /**
   * 本屏可轮播的一条公告与它在本屏布局上的排版。
   *
   * @param bulletin 公告
   * @param layout 排版结果
   */
  private record Posting(PidsBulletin bulletin, PidsBulletinTypesetter.Result layout) {

    PidsCarousel.BulletinInfo info() {
      return new PidsCarousel.BulletinInfo(
          bulletin.revision(), bulletin.important(), layout.pages().size());
    }
  }

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
    List<PidsLayout> pages = PidsScreenPages.resolve(screen, layouts);
    if (pages.isEmpty()) {
      return Optional.of(card(notice("no-layout", Map.of("size", rows + "×" + cols), rows, cols)));
    }
    if (screen.mode() == PidsScreen.Mode.TEST_CARD) {
      return Optional.of(card(testCard(screen, pages)));
    }
    // 到发页要绑车站：只绑运营商的屏幕只显示其中的线路运行状况
    List<PidsLayout> shown =
        screen.station().isPresent()
            ? pages
            : pages.stream().filter(layout -> layout.lineStatus().isPresent()).toList();
    if (shown.isEmpty() || screen.operatorCode().isEmpty()) {
      return Optional.of(card(testCard(screen, pages)));
    }
    if (shown.size() == 1 && shown.get(0).lineStatus().isEmpty()) {
      return Optional.of(live(screen, shown.get(0), screen.station().orElseThrow()));
    }
    return Optional.of(paged(screen, shown));
  }

  /**
   * 按时钟轮流显示各布局（见 {@link PidsScreenPages#turn}）；线路运行状况分几页时第几轮放第几页。
   *
   * <p>同一车站的屏幕同时翻页；只绑运营商的屏幕按运营商代码错开。到发页只在绑了车站时出现。
   */
  private PidsContent paged(PidsScreen screen, List<PidsLayout> layouts) {
    PidsSettings.PageSettings timing = settings.get().pages();
    Instant now = clock.instant();
    List<Integer> seconds =
        layouts.stream()
            .map(
                layout ->
                    layout.lineStatus().isPresent()
                        ? timing.lineStatusSeconds()
                        : timing.boardSeconds())
            .toList();
    String operator = screen.operatorCode().orElseThrow();
    PidsScreenPages.Turn turn =
        PidsScreenPages.turn(
            seconds, screen.station().map(PidsStationKey::toString).orElse(operator), now);
    PidsLayout shown = layouts.get(turn.page());
    Optional<PidsLayout.LineStatus> widget = shown.lineStatus();
    if (widget.isEmpty()) {
      return live(screen, shown, screen.station().orElseThrow());
    }
    return lineStatus(
        shown,
        new PidsLineStatusViews.Request(
            operator,
            screen.station(),
            screen.lines(),
            theme(screen),
            now,
            zone,
            widget.get().rowsPerPage(widget.get().roomy()),
            widget.get().rowsPerPage(widget.get().compact()),
            turn.round()));
  }

  /** 线路运行状况的一页：线路少时用大行，多了用小行并分页。 */
  private PidsContent lineStatus(PidsLayout layout, PidsLineStatusViews.Request request) {
    PidsLineStatusView view = lineStatusViews.build(request, lineStatuses);
    return new PidsContent(
        new LineStatusKey(layout, view), () -> renderer.renderLineStatus(layout, view));
  }

  /** 屏幕的测试卡：布局（组合翻页时依次列出）、识别出的车站与站台、屏幕编号。 */
  public PidsTestCard testCard(PidsScreen screen, List<PidsLayout> layouts) {
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
            .or(
                () ->
                    screen
                        .operatorCode()
                        .map(
                            operator ->
                                format(
                                    "pids.test-card.operator",
                                    Map.of("operator", operatorName(operator), "code", operator))))
            .orElseGet(() -> text("pids.test-card.station-none"));
    return new PidsTestCard(
        new Names(text("pids.test-card.title"), text("pids.test-card.title-secondary")),
        List.of(
            format(
                "pids.test-card.layout",
                Map.of(
                    "layout",
                    layoutNames(layouts),
                    "size",
                    screen.tileRows() + "×" + screen.tileCols())),
            station,
            format("pids.test-card.screen", Map.of("id", shortId(screen.id())))),
        text("pids.test-card.hint"),
        screen.tileRows(),
        screen.tileCols());
  }

  /** 布局名称，组合翻页时按显示顺序以“ + ”相连。 */
  public static String layoutNames(List<PidsLayout> layouts) {
    return layouts.stream().map(PidsLayout::name).collect(Collectors.joining(" + "));
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
    boolean rotating = layout.stopList().isEmpty() && PidsPlatformSelection.hasCarousel(layout);
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
            PidsCarousel.remarks(station, now, settings.get().render(), rotating),
            callsOf(screen, layout));
    Optional<PidsLayout.StopList> stopList = layout.stopList();
    if (stopList.isPresent()) {
      return stopList(screen, layout, stopList.get(), snapshot, request, now);
    }
    PidsView view = views.build(request);
    if (rotating) {
      List<Posting> postings = postings(screen, layout, station, now);
      Optional<PidsCarousel.Slide> slide =
          carousel.page(
              screen.id(),
              station,
              new PidsCarousel.Signals(
                  passingSoon(screen, snapshot),
                  views.hasVacancy(request),
                  arrivingHere(screen, snapshot),
                  courtesy(request),
                  postings.stream().map(Posting::info).toList()),
              now,
              settings.get().render());
      if (slide.isPresent() && slide.get() instanceof PidsCarousel.Slide.Notice page) {
        return notice(layout, view.theme(), view.bandColors(), page.notice());
      }
      if (slide.isPresent() && slide.get() instanceof PidsCarousel.Slide.Bulletin page) {
        Optional<PidsContent> bulletin =
            bulletin(layout, view.theme(), view.bandColors(), postings, page);
        if (bulletin.isPresent()) {
          return bulletin.get();
        }
        return new PidsContent(new LiveKey(layout, view), () -> renderer.render(layout, view));
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
    PidsStationKey station = screen.station().orElseThrow();
    List<Posting> postings = postings(screen, layout, station, now);
    PidsCarousel.StopListSlide slide =
        carousel.stopList(
            screen.id(),
            new PidsCarousel.Signals(
                passingSoon(screen, snapshot),
                false,
                arrivingHere(screen, snapshot),
                courtesy(request),
                postings.stream().map(Posting::info).toList()),
            new PidsCarousel.StopListPages(
                train, pages, widget.followingRows() > 0 && views.hasFollowing(request)),
            now,
            settings.get().render());
    if (slide instanceof PidsCarousel.Slide.Bulletin page) {
      Optional<PidsContent> bulletin =
          bulletin(layout, full.theme(), full.bandColors(), postings, page);
      if (bulletin.isPresent()) {
        return bulletin.get();
      }
      slide = new PidsCarousel.StopListSlide.Stops(0);
    }
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
      case PidsCarousel.Slide.Bulletin ignored -> throw new IllegalStateException("公告页已在上面处理");
    };
  }

  /**
   * 本屏可轮播的公告：本屏车站、线路适用且生效中的，按轮播顺序，各自按本屏布局排好版。
   *
   * <p>屏幕显示的线路：设了线路过滤取过滤清单，否则取停靠所选站台的线路（未选站台时取停靠本站的线路）；只在有公告限定了线路时才去查。
   *
   * <p>只放车站所属公司发布的公告：运营商代码按名称目录认公司，代码属于多家公司（或目录还没建好）时一律不放，免得别家公司的公告上了本站的屏。 副页停留为
   * 0（不轮播）时不放公告，也就不必排版。
   */
  private List<Posting> postings(
      PidsScreen screen, PidsLayout layout, PidsStationKey station, Instant now) {
    if (settings.get().render().slideNoticeSeconds() <= 0) {
      return List.of();
    }
    List<PidsBulletin> active = bulletins.active(station, () -> screenLines(screen, station), now);
    if (active.isEmpty()) {
      return List.of();
    }
    Optional<UUID> company = directory.companyOfOperator(station.operatorCode());
    if (company.isEmpty()) {
      return List.of();
    }
    return active.stream()
        .filter(bulletin -> bulletin.companyId().equals(company.get()))
        .map(bulletin -> new Posting(bulletin, typeset(layout, bulletin)))
        .toList();
  }

  /**
   * 公告在某布局上的排版；发布时检查长度也用它，与站台屏显示同一份缓存。
   *
   * @param layout 布局
   * @param bulletin 公告
   */
  public PidsBulletinTypesetter.Result typeset(PidsLayout layout, PidsBulletin bulletin) {
    if (typeset.size() > TYPESET_CACHE_LIMIT) {
      typeset.clear();
    }
    return typeset.computeIfAbsent(
        new TypesetKey(bulletin.revision(), layout.id(), layout.tileRows(), layout.tileCols()),
        key ->
            renderer.typesetBulletin(
                layout,
                vocabulary.bulletinLabel(bulletin.important()),
                new Names(bulletin.title().primary(), bulletin.title().secondary()),
                new Names(bulletin.body().primary(), bulletin.body().secondary())));
  }

  /** 布局轮播公告：有自己轮播的站台屏、多站台屏与停站屏（{@link PidsPlatformSelection#hasCarousel}）；车站统屏与线路运行状况屏不放。 */
  public static boolean showsBulletins(PidsLayout layout) {
    return PidsPlatformSelection.hasCarousel(layout);
  }

  /** 屏幕显示的线路代码（大写）。 */
  /** 叫车显示；只有站台屏（单站台、多站台、2×1）写叫车提示，车站统屏右键照样能叫、但不写提示。 */
  private PidsViewBuilder.Calls callsOf(PidsScreen screen, PidsLayout layout) {
    PidsViewBuilder.Calls result;
    try {
      result = calls.apply(screen);
    } catch (RuntimeException ex) {
      return PidsViewBuilder.Calls.NONE;
    }
    if (result == null) {
      return PidsViewBuilder.Calls.NONE;
    }
    return PidsPlatformSelection.hasCarousel(layout)
        ? result
        : new PidsViewBuilder.Calls(false, result.calledTrains());
  }

  private Set<String> screenLines(PidsScreen screen, PidsStationKey station) {
    if (!screen.lines().isEmpty()) {
      return screen.lines();
    }
    List<PidsView.LineChip> chips =
        screen.platforms().isEmpty()
            ? directory.linesServing(station)
            : screen.platforms().stream()
                .flatMap(platform -> directory.linesServingPlatform(station, platform).stream())
                .toList();
    return chips.stream()
        .map(chip -> chip.code().toUpperCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  /**
   * 公告的一页。
   *
   * @return 公告已撤下（本屏清单里没有）时为空，调用方改回主页
   */
  private Optional<PidsContent> bulletin(
      PidsLayout layout,
      PidsTheme theme,
      List<Integer> bandColors,
      List<Posting> postings,
      PidsCarousel.Slide.Bulletin page) {
    Optional<Posting> found =
        postings.stream().filter(p -> p.bulletin().revision().equals(page.key())).findFirst();
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Posting posting = found.get();
    List<PidsBulletinTypesetter.Page> pages = posting.layout().pages();
    int index = Math.min(page.page(), pages.size() - 1);
    PidsBulletin bulletin = posting.bulletin();
    PidsBulletinView view =
        new PidsBulletinView(
            theme,
            bulletin.important(),
            vocabulary.bulletinLabel(bulletin.important()),
            new Names(bulletin.title().primary(), bulletin.title().secondary()),
            pages.get(index),
            index,
            pages.size(),
            bandColors);
    return Optional.of(
        new PidsContent(
            new BulletinKey(layout, view), () -> renderer.renderBulletin(layout, view)));
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

  private String operatorName(String operatorCode) {
    return directory.operatorName(operatorCode).map(Names::primary).orElse(operatorCode);
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
