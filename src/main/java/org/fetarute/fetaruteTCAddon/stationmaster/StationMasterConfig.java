package org.fetarute.fetaruteTCAddon.stationmaster;

import java.time.Duration;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator;

/**
 * 站长配置（{@code config.yml} 的 {@code station-master} 段）。
 *
 * @param enabled 是否启用；关闭后不能上岗，已在岗的下岗
 * @param stationRangeBlocks 离所辖股道（车站牌子）该格数以内算在车站
 * @param trainRangeBlocks 离列车任一节车厢该格数以内（水平）算在这列车旁
 * @param graceSeconds 离开车站后该秒数内未返回即下岗
 * @param signalTimeoutSeconds 列车等发车信号超过该秒数由系统代发
 * @param maxHoldSeconds 扣车最长秒数，到时自动放行
 * @param loadWindowMinutes 分股道时统计接下来该分钟数内的表定停站
 * @param emergencyHoldSeconds 站台紧急停车停住后扣着的最长秒数
 * @param job 系统怎么给站长排车
 * @param watch 接车与出站监视
 * @param clearing 清客
 * @param events 值守中的模拟事件
 */
public record StationMasterConfig(
    boolean enabled,
    int stationRangeBlocks,
    int trainRangeBlocks,
    int graceSeconds,
    int signalTimeoutSeconds,
    int maxHoldSeconds,
    int loadWindowMinutes,
    int emergencyHoldSeconds,
    Job job,
    Watch watch,
    Clearing clearing,
    Events events) {

  /** 扣车上限的上限：与按表扣车、信号员扣车同一个上限（{@link StationStopCoordinator#HOLD_CEILING}）。 */
  private static final int HOLD_CEILING_SECONDS =
      (int) StationStopCoordinator.HOLD_CEILING.toSeconds();

  /**
   * 系统怎么给站长排车：一次只办一列，办完至少歇一会儿。
   *
   * @param restSeconds 办完一列后至少歇该秒数才排下一列
   * @param walkBlocksPerSecond 估算站长走到股道要多久的步速（格/秒），来不及走到的车不排
   */
  public record Job(int restSeconds, double walkBlocksPerSecond) {

    public static final Job DEFAULT = new Job(30, 5.0);

    public Job {
      restSeconds = clamp(restSeconds, 0, 600);
      walkBlocksPerSecond =
          Double.isFinite(walkBlocksPerSecond)
              ? Math.max(1.0, Math.min(20.0, walkBlocksPerSecond))
              : 5.0;
    }

    static Job from(ConfigurationSection section) {
      if (section == null) {
        return DEFAULT;
      }
      return new Job(
          section.getInt("rest-seconds", DEFAULT.restSeconds()),
          section.getDouble("walk-blocks-per-second", DEFAULT.walkBlocksPerSecond()));
    }

    /** 办完一列后至少歇多久。 */
    public Duration rest() {
      return Duration.ofSeconds(restSeconds);
    }
  }

  /**
   * 接车与出站监视。
   *
   * @param approachRangeBlocks 列车任一节车厢离股道的车站牌子该格数以内、还在动，算进站或出站中
   * @param sideRangeBlocks 站长站在这列车车身范围内、离轨道中心不超过该格数算到位
   * @param viewAngleDegrees 这列车任一节车厢在视线该度数以内算在看着
   * @param watchRatio 合格的采样不少于该比例算监视合格
   * @param departureWatchMaxSeconds 出站监视最多看该秒数
   */
  public record Watch(
      int approachRangeBlocks,
      int sideRangeBlocks,
      int viewAngleDegrees,
      double watchRatio,
      int departureWatchMaxSeconds) {

    public static final Watch DEFAULT = new Watch(64, 12, 60, 0.7, 20);

    public Watch {
      approachRangeBlocks = clamp(approachRangeBlocks, 8, 256);
      sideRangeBlocks = clamp(sideRangeBlocks, 2, 64);
      viewAngleDegrees = clamp(viewAngleDegrees, 1, 90);
      watchRatio = Double.isFinite(watchRatio) ? Math.max(0.0, Math.min(1.0, watchRatio)) : 0.7;
      departureWatchMaxSeconds = clamp(departureWatchMaxSeconds, 5, 120);
    }

    static Watch from(ConfigurationSection section) {
      if (section == null) {
        return DEFAULT;
      }
      return new Watch(
          section.getInt("approach-range-blocks", DEFAULT.approachRangeBlocks()),
          section.getInt("side-range-blocks", DEFAULT.sideRangeBlocks()),
          section.getInt("view-angle-degrees", DEFAULT.viewAngleDegrees()),
          section.getDouble("watch-ratio", DEFAULT.watchRatio()),
          section.getInt("departure-watch-max-seconds", DEFAULT.departureWatchMaxSeconds()));
    }
  }

  /**
   * 清客：在岗时，本站之后不再载客的车（回库、接空驶段或销毁）发车前确认车上没有乘客。
   *
   * @param enabled 是否清客
   * @param seconds 广播请乘客下车后等该秒数，仍在车上的送到本站站台
   */
  public record Clearing(boolean enabled, int seconds) {

    public static final Clearing DEFAULT = new Clearing(true, 20);

    public Clearing {
      seconds = clamp(seconds, 5, 120);
    }

    static Clearing from(ConfigurationSection section) {
      if (section == null) {
        return DEFAULT;
      }
      return new Clearing(
          section.getBoolean("enabled", DEFAULT.enabled()),
          section.getInt("seconds", DEFAULT.seconds()));
    }
  }

  /**
   * 值守中的模拟事件：只考判断，不改变列车运行。
   *
   * @param enabled 是否出题
   * @param meanIntervalSeconds 平均隔该秒数出一题（指数分布）
   * @param minGapSeconds 两题至少隔该秒数
   * @param answerSeconds 该秒数内没处置算漏处置
   */
  public record Events(
      boolean enabled, int meanIntervalSeconds, int minGapSeconds, int answerSeconds) {

    public static final Events DEFAULT = new Events(true, 300, 90, 15);

    public Events {
      meanIntervalSeconds = clamp(meanIntervalSeconds, 30, 3600);
      minGapSeconds = clamp(minGapSeconds, 10, 3600);
      answerSeconds = clamp(answerSeconds, 5, 120);
    }

    static Events from(ConfigurationSection section) {
      if (section == null) {
        return DEFAULT;
      }
      return new Events(
          section.getBoolean("enabled", DEFAULT.enabled()),
          section.getInt("mean-interval-seconds", DEFAULT.meanIntervalSeconds()),
          section.getInt("min-gap-seconds", DEFAULT.minGapSeconds()),
          section.getInt("answer-seconds", DEFAULT.answerSeconds()));
    }
  }

  public StationMasterConfig {
    stationRangeBlocks = clamp(stationRangeBlocks, 8, 512);
    trainRangeBlocks = clamp(trainRangeBlocks, 2, 64);
    graceSeconds = clamp(graceSeconds, 0, 600);
    signalTimeoutSeconds = clamp(signalTimeoutSeconds, 3, 120);
    maxHoldSeconds = clamp(maxHoldSeconds, 1, HOLD_CEILING_SECONDS);
    loadWindowMinutes = clamp(loadWindowMinutes, 10, 240);
    emergencyHoldSeconds = clamp(emergencyHoldSeconds, 10, 600);
    job = job == null ? Job.DEFAULT : job;
    watch = watch == null ? Watch.DEFAULT : watch;
    clearing = clearing == null ? Clearing.DEFAULT : clearing;
    events = events == null ? Events.DEFAULT : events;
  }

  public static StationMasterConfig defaults() {
    return new StationMasterConfig(
        true,
        64,
        12,
        30,
        10,
        150,
        60,
        120,
        Job.DEFAULT,
        Watch.DEFAULT,
        Clearing.DEFAULT,
        Events.DEFAULT);
  }

  /** 等发车信号的时限。 */
  public Duration signalTimeout() {
    return Duration.ofSeconds(signalTimeoutSeconds);
  }

  /** 扣车上限。 */
  public Duration maxHold() {
    return Duration.ofSeconds(maxHoldSeconds);
  }

  /** 离开车站的宽限。 */
  public Duration grace() {
    return Duration.ofSeconds(graceSeconds);
  }

  /** 分股道统计的时间窗。 */
  public Duration loadWindow() {
    return Duration.ofMinutes(loadWindowMinutes);
  }

  /** 站台紧急停车扣着的上限。 */
  public Duration emergencyHold() {
    return Duration.ofSeconds(emergencyHoldSeconds);
  }

  /**
   * 读配置段；缺的键用默认值，越界的值截到范围内（扣车上限超过上限时告警）。
   *
   * @param section {@code station-master} 段；没有时全用默认值
   * @param warn 告警
   */
  public static StationMasterConfig from(ConfigurationSection section, Consumer<String> warn) {
    StationMasterConfig defaults = defaults();
    if (section == null) {
      return defaults;
    }
    int maxHold = section.getInt("max-hold-seconds", defaults.maxHoldSeconds());
    if (maxHold > HOLD_CEILING_SECONDS && warn != null) {
      warn.accept(
          "station-master.max-hold-seconds 超过上限 "
              + HOLD_CEILING_SECONDS
              + " 秒，已按 "
              + HOLD_CEILING_SECONDS
              + " 秒处理");
    }
    return new StationMasterConfig(
        section.getBoolean("enabled", defaults.enabled()),
        section.getInt("station-range-blocks", defaults.stationRangeBlocks()),
        section.getInt("train-range-blocks", defaults.trainRangeBlocks()),
        section.getInt("grace-seconds", defaults.graceSeconds()),
        section.getInt("signal-timeout-seconds", defaults.signalTimeoutSeconds()),
        maxHold,
        section.getInt("load-window-minutes", defaults.loadWindowMinutes()),
        section.getInt("emergency-hold-seconds", defaults.emergencyHoldSeconds()),
        Job.from(section.getConfigurationSection("job")),
        Watch.from(section.getConfigurationSection("watch")),
        Clearing.from(section.getConfigurationSection("clearing")),
        Events.from(section.getConfigurationSection("events")));
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
