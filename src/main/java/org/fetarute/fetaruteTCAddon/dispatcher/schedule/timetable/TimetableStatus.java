package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.Locale;
import java.util.Optional;

/**
 * 时刻表的生命周期状态。
 *
 * <p>只有 {@link #PUBLISHED} 会被运行时消费：发车按表出票、到站按表扣留。{@link #DRAFT} 用于构建后的人工核对，{@link #ARCHIVED}
 * 用于保留历史版本而不影响运行。状态是“按表运行”的两道开关之一（另一道是配置里的总开关），因此不提供“半启用”状态——运营侧必须显式 publish 才会改变列车行为。
 */
public enum TimetableStatus {
  /** 已构建但尚未投入运行。 */
  DRAFT,

  /** 正在按表运行。 */
  PUBLISHED,

  /** 历史版本，不参与运行。 */
  ARCHIVED;

  /** 宽松解析命令输入，失败时返回空而不是抛异常。 */
  public static Optional<TimetableStatus> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }
}
