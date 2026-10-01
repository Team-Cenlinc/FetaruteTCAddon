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
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNoticeView;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsTestCard;
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
 *   <li>其余：到发信息；站台屏与多站台屏按 {@link PidsCarousel} 轮播宣传页，通过列车临近时锁定安全提示页
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
      ZoneId zone) {
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
  }

  /** 到发页的内容标识：布局与视图都相同才算没变。 */
  record LiveKey(PidsLayout layout, PidsView view) {}

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
    return Optional.of(live(screen, layout.get(), screen.station().get()));
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
    PidsView view =
        views.build(
            new PidsViewBuilder.Request(
                snapshot,
                now,
                zone,
                theme(screen),
                screen.platforms(),
                platformLabels,
                layout.rowCapacity(),
                layout.departures().map(d -> d.columns().platform().isPresent()).orElse(false)));
    if (PidsPlatformSelection.limit(layout).isPresent()) {
      Optional<PidsNotice> page =
          carousel.page(
              screen.id(), station, passingSoon(screen, snapshot), now, settings.get().render());
      if (page.isPresent()) {
        PidsNoticeView notice =
            new PidsNoticeView(
                view.theme(),
                page.get(),
                vocabulary.noticeTitle(page.get()),
                vocabulary.noticeBody(page.get()),
                view.bandColors());
        return new PidsContent(notice, () -> renderer.renderNotice(layout, notice));
      }
    }
    return new PidsContent(new LiveKey(layout, view), () -> renderer.render(layout, view));
  }

  /** 本屏的站台有通过列车即将通过（已按线路过滤）。 */
  private static boolean passingSoon(PidsScreen screen, PidsSnapshot snapshot) {
    return snapshot.rows().stream()
        .anyMatch(
            row ->
                row.passing()
                    && row.status() == PidsRow.Status.ARRIVING
                    && (screen.platforms().isEmpty() || row.mayUse(screen.platforms())));
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
