package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;

/**
 * 驾驶员 Boss 栏这一帧显示什么：标题的语言键与占位符、建议速度、是否提示开始制动、颜色与进度。
 *
 * <p>停站中（开门到发车）显示停站阶段，进度是剩余停站时间；其余时候显示行车引导的目标，进度是离目标的距离占引导范围的比例。 本类不依赖服务器对象，由 {@link DriveBossBar}
 * 渲染。
 *
 * @param titleKey 标题主体的语言键
 * @param values 标题里的占位符
 * @param suggestedKmh 建议速度（km/h）；不显示时为空
 * @param brake 提示开始制动
 * @param ato 在 ATO 运行
 * @param tone 颜色
 * @param progress 进度，0–1
 */
public record DriverBossBarView(
    String titleKey,
    Map<String, String> values,
    OptionalInt suggestedKmh,
    boolean brake,
    boolean ato,
    Tone tone,
    double progress) {

  /** Boss 栏的颜色，按目标种类区分。 */
  public enum Tone {
    /** 前方畅通、发车信号。 */
    GREEN,
    /** 前方限速降低、关门。 */
    YELLOW,
    /** 进站、开门。 */
    BLUE,
    /** 停车信号、开始制动。 */
    RED,
    /** 停站计时、等待发车、无行车许可。 */
    WHITE
  }

  private static final double KMH_PER_BPS = 3.6;

  /** 停稳时建议速度低于它（格/秒）就不显示（停在停车点前显示“建议 0”没有意义）。 */
  private static final double SHOW_SUGGESTION_BPS = 0.3;

  public DriverBossBarView {
    Objects.requireNonNull(titleKey, "titleKey");
    values = values == null ? Map.of() : Map.copyOf(values);
    suggestedKmh = suggestedKmh == null ? OptionalInt.empty() : suggestedKmh;
    Objects.requireNonNull(tone, "tone");
    progress = Double.isFinite(progress) ? Math.max(0.0, Math.min(1.0, progress)) : 1.0;
  }

  /**
   * 组装这一帧。
   *
   * @param stationHint 车站提示（{@link DriverStationHint#of}）
   * @param advice 行车引导
   * @param dwellTotalTicks 本次停站的总时长（用于停站进度）；不知道时为空
   * @param dwellRemainingTicks 剩余停站时长
   * @param ato 在 ATO 运行
   * @param hasDirective 已收到调度的行车许可（ATO 下忽略）
   * @param stopped 是否停稳
   * @param stationLabel 前方停车点的站名
   * @param rangeBlocks 引导范围（格）
   */
  public static DriverBossBarView of(
      Optional<DriverStationHint.Hint> stationHint,
      DriverGuidance.Advice advice,
      OptionalLong dwellTotalTicks,
      long dwellRemainingTicks,
      boolean ato,
      boolean hasDirective,
      boolean stopped,
      String stationLabel,
      double rangeBlocks) {
    Objects.requireNonNull(advice, "advice");
    if (stationHint.isPresent() && stationHint.get().atStation()) {
      return atStation(stationHint.get(), dwellTotalTicks, dwellRemainingTicks, ato);
    }
    if (!ato && !hasDirective) {
      return new DriverBossBarView(
          "drive.bossbar.no-signal", Map.of(), OptionalInt.empty(), false, false, Tone.WHITE, 1.0);
    }
    OptionalInt suggestion =
        ato || (stopped && advice.suggestedBps() < SHOW_SUGGESTION_BPS)
            ? OptionalInt.empty()
            : OptionalInt.of((int) Math.round(advice.suggestedBps() * KMH_PER_BPS));
    DriverGuidance.Target target = advice.target();
    double progress = advice.progress(rangeBlocks);
    String distance =
        Double.isFinite(target.distanceBlocks())
            ? DriverStationHint.formatDistance(target.distanceBlocks())
            : "";
    return switch (target.kind()) {
      case STOP_SIGNAL -> new DriverBossBarView(
          "drive.bossbar.stop-signal",
          Map.of("distance", distance),
          suggestion,
          advice.brake(),
          ato,
          Tone.RED,
          progress);
      case STATION -> new DriverBossBarView(
          "drive.bossbar.station-approach",
          Map.of("station", stationLabel == null ? "" : stationLabel, "distance", distance),
          suggestion,
          advice.brake(),
          ato,
          Tone.BLUE,
          progress);
      case SPEED_LIMIT -> new DriverBossBarView(
          "drive.bossbar.limit",
          Map.of(
              "limit_kmh",
              String.valueOf(Math.round(target.endSpeedBps() * KMH_PER_BPS)),
              "distance",
              distance),
          suggestion,
          advice.brake(),
          ato,
          Tone.YELLOW,
          progress);
      case CLEAR -> ato
          ? new DriverBossBarView(
              "drive.bossbar.ato-clear",
              Map.of("station", stationLabel == null ? "" : stationLabel),
              OptionalInt.empty(),
              false,
              true,
              Tone.GREEN,
              1.0)
          : new DriverBossBarView(
              "drive.bossbar.clear",
              Map.of("limit_kmh", String.valueOf(Math.round(target.endSpeedBps() * KMH_PER_BPS))),
              suggestion,
              false,
              false,
              Tone.GREEN,
              1.0);
    };
  }

  /** 停站中：开门、停站计时、关门、等待发车、发车信号。 */
  private static DriverBossBarView atStation(
      DriverStationHint.Hint hint,
      OptionalLong dwellTotalTicks,
      long dwellRemainingTicks,
      boolean ato) {
    String key = "drive.bossbar.station." + hint.key().substring("drive.hud.station.".length());
    return switch (hint.kind()) {
      case DWELL -> new DriverBossBarView(
          key,
          hint.values(),
          OptionalInt.empty(),
          false,
          ato,
          Tone.WHITE,
          dwellTotalTicks.isPresent() && dwellTotalTicks.getAsLong() > 0L
              ? dwellRemainingTicks / (double) dwellTotalTicks.getAsLong()
              : 1.0);
      case DEPART -> new DriverBossBarView(
          key, hint.values(), OptionalInt.empty(), false, ato, Tone.GREEN, 1.0);
      case OPEN_DOORS -> new DriverBossBarView(
          key, hint.values(), OptionalInt.empty(), false, ato, Tone.BLUE, 1.0);
      case CLOSE_DOORS, DOORS_CLOSING -> new DriverBossBarView(
          key, hint.values(), OptionalInt.empty(), false, ato, Tone.YELLOW, 1.0);
      default -> new DriverBossBarView(
          key, hint.values(), OptionalInt.empty(), false, ato, Tone.WHITE, 1.0);
    };
  }
}
