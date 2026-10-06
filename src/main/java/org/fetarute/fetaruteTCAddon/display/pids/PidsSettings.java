package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsNotice;

/**
 * 站台 PIDS 的全局策略配置快照，解析自 {@code pids.yml}。
 *
 * <p>只承载运维可调的全局策略：渲染节奏、数量上限、字体、默认布局、外观与广播策略。每块屏幕与车站、站台、线路过滤的绑定属于实例数据，存放在数据库中，不在此处。
 *
 * <p>解析不抛异常：缺失或无效的值回退为默认值并输出警告，使旧版本或手工编辑过的文件也能安全加载。
 *
 * @param configVersion 配置文件版本号（模板升级时由 {@code ConfigUpdater} 写入）
 * @param enabled 总开关；关闭后不加载站台屏、不播报
 * @param render 渲染与翻页节奏
 * @param pages 组合翻页（一块屏幕轮流显示几个布局）各页的停留时间
 * @param limits 数量上限
 * @param font 字体
 * @param layout 默认布局预设
 * @param appearance 深浅外观
 * @param broadcast 播报策略
 */
public record PidsSettings(
    int configVersion,
    boolean enabled,
    RenderSettings render,
    PageSettings pages,
    LimitSettings limits,
    FontSettings font,
    LayoutSettings layout,
    AppearanceSettings appearance,
    BroadcastSettings broadcast) {

  /** 内置模板的配置版本；模板升级时同步修改。 */
  public static final int EXPECTED_CONFIG_VERSION = 4;

  /** 一个游戏日的刻数。 */
  private static final int TICKS_PER_DAY = 24000;

  /** 校验并固化各分组，调用方不会拿到 {@code null} 分组。 */
  public PidsSettings {
    Objects.requireNonNull(render, "render");
    Objects.requireNonNull(pages, "pages");
    Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(font, "font");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(appearance, "appearance");
    Objects.requireNonNull(broadcast, "broadcast");
  }

  /** 返回全部取默认值的配置，与内置模板 {@code pids.yml} 一致。 */
  public static PidsSettings defaults() {
    return new PidsSettings(
        EXPECTED_CONFIG_VERSION,
        true,
        RenderSettings.DEFAULT,
        PageSettings.DEFAULT,
        LimitSettings.DEFAULT,
        FontSettings.DEFAULT,
        LayoutSettings.DEFAULT,
        AppearanceSettings.DEFAULT,
        BroadcastSettings.DEFAULT);
  }

  /**
   * 解析配置。缺失或无效的键回退为默认值，并通过 {@code logger} 输出警告。
   *
   * @param config 已加载的 {@code pids.yml}
   * @param logger 警告出口
   */
  public static PidsSettings parse(FileConfiguration config, Logger logger) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(logger, "logger");
    Reader reader = new Reader(config, logger);

    RenderSettings renderDefault = RenderSettings.DEFAULT;
    RenderSettings render =
        new RenderSettings(
            reader.positiveInt("render.check-interval-ticks", renderDefault.checkIntervalTicks()),
            reader.positiveInt("render.snapshot-ttl-seconds", renderDefault.snapshotTtlSeconds()),
            reader.positiveInt("render.horizon-minutes", renderDefault.horizonMinutes()),
            reader.positiveInt("render.slide-main-seconds", renderDefault.slideMainSeconds()),
            reader.nonNegativeInt(
                "render.slide-notice-seconds", renderDefault.slideNoticeSeconds()),
            reader.positiveInt("render.notice-pin-seconds", renderDefault.noticePinSeconds()),
            reader.positiveInt("render.english-seconds", renderDefault.englishSeconds()),
            reader.nonNegativeInt("render.remark-seconds", renderDefault.remarkSeconds()),
            reader.positiveInt("render.stop-page-seconds", renderDefault.stopPageSeconds()),
            reader.notices("render.notices", renderDefault.notices()),
            reader.positiveInt("render.bulletin-seconds", renderDefault.bulletinSeconds()));

    PageSettings pages =
        new PageSettings(
            reader.positiveInt("pages.board-seconds", PageSettings.DEFAULT.boardSeconds()),
            reader.positiveInt(
                "pages.line-status-seconds", PageSettings.DEFAULT.lineStatusSeconds()));

    LimitSettings limits =
        new LimitSettings(
            reader.positiveInt("limits.max-screens", LimitSettings.DEFAULT.maxScreens()));

    FontSettings font = parseFont(config, reader);

    LayoutSettings layoutDefault = LayoutSettings.DEFAULT;
    LayoutSettings layout =
        new LayoutSettings(
            reader.text("layout.platform", layoutDefault.platform()),
            reader.text("layout.station", layoutDefault.station()));

    AppearanceSettings appearance = parseAppearance(config, reader);

    BroadcastSettings broadcastDefault = BroadcastSettings.DEFAULT;
    BroadcastSettings broadcast =
        new BroadcastSettings(
            reader.bool("broadcast.enabled", broadcastDefault.enabled()),
            reader.positiveInt("broadcast.range-blocks", broadcastDefault.rangeBlocks()),
            reader.nonNegativeInt("broadcast.dedupe-seconds", broadcastDefault.dedupeSeconds()),
            reader.nonNegativeInt(
                "broadcast.arriving-lead-seconds", broadcastDefault.arrivingLeadSeconds()),
            new BroadcastTriggers(
                reader.bool("broadcast.trigger-arriving", true),
                reader.bool("broadcast.trigger-passing", true),
                reader.bool("broadcast.trigger-cancelled", true),
                reader.bool("broadcast.trigger-delayed", true),
                reader.bool("broadcast.trigger-platform-changed", true)),
            reader.bool("broadcast.channel-text", broadcastDefault.channelText()),
            reader.bool("broadcast.channel-sound", broadcastDefault.channelSound()));

    return new PidsSettings(
        config.getInt("config-version", EXPECTED_CONFIG_VERSION),
        reader.bool("enabled", true),
        render,
        pages,
        limits,
        font,
        layout,
        appearance,
        broadcast);
  }

  private static FontSettings parseFont(FileConfiguration config, Reader reader) {
    boolean detect = reader.bool("font.detect-glyphs", FontSettings.DEFAULT.detectGlyphs());
    String raw = config.getString("font.cjk-glyphs");
    if (raw == null || raw.isBlank()) {
      return new FontSettings(FontSettings.DEFAULT.cjkGlyphs(), detect);
    }
    Optional<PidsGlyphForm> parsed = PidsGlyphForm.fromConfig(raw);
    if (parsed.isEmpty()) {
      reader.warnInvalid("font.cjk-glyphs", raw, FontSettings.DEFAULT.cjkGlyphs().fileSuffix());
      return new FontSettings(FontSettings.DEFAULT.cjkGlyphs(), detect);
    }
    return new FontSettings(parsed.get(), detect);
  }

  private static AppearanceSettings parseAppearance(FileConfiguration config, Reader reader) {
    AppearanceSettings fallback = AppearanceSettings.DEFAULT;
    AppearanceMode mode = fallback.mode();
    String rawMode = config.getString("appearance.mode");
    if (rawMode != null && !rawMode.isBlank()) {
      Optional<AppearanceMode> parsed = AppearanceMode.fromConfig(rawMode);
      if (parsed.isPresent()) {
        mode = parsed.get();
      } else {
        reader.warnInvalid("appearance.mode", rawMode, "mc-time");
      }
    }
    int darkFrom = config.getInt("appearance.dark-from-tick", fallback.darkFromTick());
    int lightFrom = config.getInt("appearance.light-from-tick", fallback.lightFromTick());
    boolean validRange =
        darkFrom >= 0
            && darkFrom < TICKS_PER_DAY
            && lightFrom >= 0
            && lightFrom < TICKS_PER_DAY
            && darkFrom != lightFrom;
    if (!validRange) {
      reader.warnInvalid(
          "appearance.dark-from-tick / light-from-tick",
          darkFrom + " / " + lightFrom,
          fallback.darkFromTick() + " / " + fallback.lightFromTick());
      darkFrom = fallback.darkFromTick();
      lightFrom = fallback.lightFromTick();
    }
    return new AppearanceSettings(mode, darkFrom, lightFrom);
  }

  /**
   * 渲染与翻页节奏。
   *
   * @param checkIntervalTicks 每隔多少 tick 比对一次将显示的文本，变化才重绘
   * @param snapshotTtlSeconds 同一车站到发快照的缓存时间（秒）
   * @param horizonMinutes 预测窗口（分钟）
   * @param slideMainSeconds 主页（到发）停留时间（秒）
   * @param slideNoticeSeconds 宣传页停留时间（秒）
   * @param noticePinSeconds 通过列车临近时锁定安全页的时长（秒）
   * @param englishSeconds 主页上终点下面写英文停留多少秒（与备注交替）
   * @param remarkSeconds 主页上终点下面写备注（末班车、直通、经由）停留多少秒；0 不显示备注
   * @param stopPageSeconds 2×1 停站屏停站多、分页时每页停留多少秒（后续列车页也停这么久）
   * @param notices 轮换哪几张宣传页、按什么顺序；为空时不放宣传页（空位页、公告与安全提示页照常）
   * @param bulletinSeconds 公告页每页停留多少秒；站台屏上从轮到公告那一段的主页时间里扣
   */
  public record RenderSettings(
      int checkIntervalTicks,
      int snapshotTtlSeconds,
      int horizonMinutes,
      int slideMainSeconds,
      int slideNoticeSeconds,
      int noticePinSeconds,
      int englishSeconds,
      int remarkSeconds,
      int stopPageSeconds,
      List<PidsNotice> notices,
      int bulletinSeconds) {

    /** 公告页每页的默认停留时间：读完三行中文与两行英文。 */
    static final int DEFAULT_BULLETIN_SECONDS = 8;

    /**
     * 内置默认值：主页（到发）占八成时间，副页停到读得完标题与一行英文；英文是常态、备注是补充，英文停得更久；2×1 每页 6～7 站按一站一秒多扫一遍；全部宣传页按声明顺序轮换；公告每页 8
     * 秒。
     */
    public static final RenderSettings DEFAULT = new RenderSettings(20, 5, 30, 20, 5, 15, 6, 4, 8);

    public RenderSettings {
      notices = List.copyOf(notices);
    }

    /** 公告页停留取默认。 */
    public RenderSettings(
        int checkIntervalTicks,
        int snapshotTtlSeconds,
        int horizonMinutes,
        int slideMainSeconds,
        int slideNoticeSeconds,
        int noticePinSeconds,
        int englishSeconds,
        int remarkSeconds,
        int stopPageSeconds,
        List<PidsNotice> notices) {
      this(
          checkIntervalTicks,
          snapshotTtlSeconds,
          horizonMinutes,
          slideMainSeconds,
          slideNoticeSeconds,
          noticePinSeconds,
          englishSeconds,
          remarkSeconds,
          stopPageSeconds,
          notices,
          DEFAULT_BULLETIN_SECONDS);
    }

    /** 宣传页取默认（全部，按声明顺序），公告页停留取默认。 */
    public RenderSettings(
        int checkIntervalTicks,
        int snapshotTtlSeconds,
        int horizonMinutes,
        int slideMainSeconds,
        int slideNoticeSeconds,
        int noticePinSeconds,
        int englishSeconds,
        int remarkSeconds,
        int stopPageSeconds) {
      this(
          checkIntervalTicks,
          snapshotTtlSeconds,
          horizonMinutes,
          slideMainSeconds,
          slideNoticeSeconds,
          noticePinSeconds,
          englishSeconds,
          remarkSeconds,
          stopPageSeconds,
          PidsNotice.courtesy());
    }
  }

  /**
   * 组合翻页：一块屏幕选了几个布局（如车站统屏与线路运行状况）时按时钟轮流显示，同一车站的屏幕同时翻页。
   *
   * @param boardSeconds 到发页每轮停留多少秒
   * @param lineStatusSeconds 线路运行状况每轮停留多少秒；线路多、分几页时每轮放其中一页、按轮轮换，单独的线路运行状况屏每页也停这么久
   */
  public record PageSettings(int boardSeconds, int lineStatusSeconds) {

    /** 内置默认值：乘客走到屏前多半是来看到发的，到发占一轮的三分之二；状况一页 15 秒读得完五条线路。 */
    public static final PageSettings DEFAULT = new PageSettings(30, 15);
  }

  /**
   * 数量上限。
   *
   * @param maxScreens 全服屏幕数量的安全上限；单个车站的屏幕数量不设固定上限
   */
  public record LimitSettings(int maxScreens) {

    /** 内置默认值。 */
    public static final LimitSettings DEFAULT = new LimitSettings(200);
  }

  /**
   * 字体设置。
   *
   * @param cjkGlyphs 中文行使用的字形版本；开启按内容判断时为判断不出时的版本。英文行固定用拉丁版
   * @param detectGlyphs 按每段文字的内容选简体、繁体或日文字形
   */
  public record FontSettings(PidsGlyphForm cjkGlyphs, boolean detectGlyphs) {

    /** 内置默认值：按内容判断，判断不出时用简体中文字形。 */
    public static final FontSettings DEFAULT = new FontSettings(PidsGlyphForm.ZH_HANS, true);

    /** 保证字形版本不为 {@code null}。 */
    public FontSettings {
      Objects.requireNonNull(cjkGlyphs, "cjkGlyphs");
    }
  }

  /**
   * 默认布局预设。
   *
   * @param platform 站台屏默认布局预设标识
   * @param station 车站统屏默认布局预设标识
   */
  public record LayoutSettings(String platform, String station) {

    /** 内置默认值。 */
    public static final LayoutSettings DEFAULT = new LayoutSettings("platform-1x3", "station-3x5");

    /** 保证预设标识不为 {@code null}。 */
    public LayoutSettings {
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(station, "station");
    }
  }

  /** 外观模式。 */
  public enum AppearanceMode {
    /** 按屏幕所在世界的 MC 时间切换：白天浅色，夜晚深色。 */
    MC_TIME("mc-time"),
    /** 固定浅色。 */
    LIGHT("light"),
    /** 固定深色。 */
    DARK("dark");

    private final String configValue;

    AppearanceMode(String configValue) {
      this.configValue = configValue;
    }

    /** 返回写入配置文件的取值。 */
    public String configValue() {
      return configValue;
    }

    /**
     * 按配置取值解析外观模式，忽略大小写，并容忍用下划线代替连字符。
     *
     * @param raw 配置中的原始文本
     * @return 对应模式；无法识别时为空
     */
    public static Optional<AppearanceMode> fromConfig(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
      for (AppearanceMode mode : values()) {
        if (mode.configValue.equals(normalized)) {
          return Optional.of(mode);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * 深浅外观设置。
   *
   * @param mode 外观模式
   * @param darkFromTick 世界时间进入夜间（深色）的刻，范围 0 至 23999
   * @param lightFromTick 世界时间进入白天（浅色）的刻，范围 0 至 23999
   */
  public record AppearanceSettings(AppearanceMode mode, int darkFromTick, int lightFromTick) {

    /** 内置默认值：按 MC 时间切换，12500 起夜间，23000 起白天。 */
    public static final AppearanceSettings DEFAULT =
        new AppearanceSettings(AppearanceMode.MC_TIME, 12500, 23000);

    /** 保证外观模式不为 {@code null}。 */
    public AppearanceSettings {
      Objects.requireNonNull(mode, "mode");
    }

    /**
     * 判断在给定的世界时间应使用深色外观。
     *
     * <p>固定模式忽略时间；{@code MC_TIME} 模式下夜间窗口为 {@code [darkFromTick, lightFromTick)}，窗口跨越午夜零点时同样成立。
     *
     * @param timeOfDay 世界一天内的时间（刻）；超出一天的值会被取模
     * @return 为真表示使用深色
     */
    public boolean darkAt(long timeOfDay) {
      return switch (mode) {
        case LIGHT -> false;
        case DARK -> true;
        case MC_TIME -> {
          long t = Math.floorMod(timeOfDay, (long) TICKS_PER_DAY);
          yield darkFromTick < lightFromTick
              ? t >= darkFromTick && t < lightFromTick
              : t >= darkFromTick || t < lightFromTick;
        }
      };
    }
  }

  /**
   * 播报触发开关。进站、通过走 ActionBar，取消、严重晚点走聊天。
   *
   * @param arriving 列车即将进站
   * @param passing 有列车通过
   * @param cancelled 班次取消
   * @param delayed 严重晚点（达到 {@link
   *     org.fetarute.fetaruteTCAddon.display.Lateness#SEVERELY_LATE_SECONDS}）
   * @param platformChanged 站台变更（走聊天）
   */
  public record BroadcastTriggers(
      boolean arriving,
      boolean passing,
      boolean cancelled,
      boolean delayed,
      boolean platformChanged) {}

  /**
   * 播报策略。全站广播：离玩家最近的已加载屏幕只用来认车站，全站各站台的事件都播报，每条带站台号。
   *
   * @param enabled 播报总开关
   * @param rangeBlocks 玩家离最近的已加载站台屏不超过这个距离（方块）才算在站内
   * @param dedupeSeconds 同一条播报对同一玩家只播一次：仍有效时一直记着，取消与严重晚点失效后再记这么久（秒）；进站与通过固定再记 2 分钟
   * @param arrivingLeadSeconds 进站播报的提前量（秒）：预计到达前这么久即播报，进站状态的列车随时播报
   * @param triggers 各类触发的开关
   * @param channelText 是否启用文字通道（ActionBar 与聊天）
   * @param channelSound 是否启用提示音（声源为来源屏幕）
   */
  public record BroadcastSettings(
      boolean enabled,
      int rangeBlocks,
      int dedupeSeconds,
      int arrivingLeadSeconds,
      BroadcastTriggers triggers,
      boolean channelText,
      boolean channelSound) {

    /** 内置默认值：全部触发与通道开启。 */
    public static final BroadcastSettings DEFAULT =
        new BroadcastSettings(
            true, 32, 600, 30, new BroadcastTriggers(true, true, true, true, true), true, true);

    /** 保证触发开关不为 {@code null}。 */
    public BroadcastSettings {
      Objects.requireNonNull(triggers, "triggers");
    }
  }

  /** 带回退与警告的取值辅助，只在解析期使用。 */
  private static final class Reader {
    private final FileConfiguration config;
    private final Logger logger;

    private Reader(FileConfiguration config, Logger logger) {
      this.config = config;
      this.logger = logger;
    }

    private boolean bool(String path, boolean fallback) {
      return config.getBoolean(path, fallback);
    }

    private int positiveInt(String path, int fallback) {
      int value = config.getInt(path, fallback);
      if (value > 0) {
        return value;
      }
      warnInvalid(path, String.valueOf(value), String.valueOf(fallback));
      return fallback;
    }

    private int nonNegativeInt(String path, int fallback) {
      int value = config.getInt(path, fallback);
      if (value >= 0) {
        return value;
      }
      warnInvalid(path, String.valueOf(value), String.valueOf(fallback));
      return fallback;
    }

    /** 宣传页清单：按键找宣传页，未知的键（含安全提示页）跳过并警告，重复的只留第一次；没写这个键时取默认。 */
    private List<PidsNotice> notices(String path, List<PidsNotice> fallback) {
      if (!config.contains(path)) {
        return fallback;
      }
      if (!config.isList(path)) {
        warnInvalid(
            path,
            String.valueOf(config.get(path)),
            fallback.stream().map(PidsNotice::key).toList().toString());
        return fallback;
      }
      LinkedHashSet<PidsNotice> notices = new LinkedHashSet<>();
      for (String key : config.getStringList(path)) {
        Optional<PidsNotice> notice = PidsNotice.courtesy(key);
        if (notice.isPresent()) {
          notices.add(notice.get());
        } else {
          logger.warning("pids.yml 的 " + path + " 里没有宣传页 " + key + "，已跳过");
        }
      }
      return List.copyOf(notices);
    }

    private String text(String path, String fallback) {
      String value = config.getString(path);
      if (value == null || value.isBlank()) {
        return fallback;
      }
      return value.trim();
    }

    private void warnInvalid(String path, String value, String fallback) {
      logger.warning("pids.yml 的 " + path + " 配置无效: " + value + "，已回退为 " + fallback);
    }
  }
}
