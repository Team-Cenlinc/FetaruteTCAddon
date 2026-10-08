package org.fetarute.fetaruteTCAddon.drive.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 驾驶成绩的反馈文案：停妥那一刻的对标结果，以及任务结束时聊天栏的逐站成绩单。
 *
 * <p>文案由 {@link Line} 描述（语言键、纯文本占位符、嵌套的子文案），组装逻辑不依赖服务器对象，便于单测；{@link #render} 再交给语言文件渲染。
 */
public final class DriverReport {

  /**
   * 一段文案。
   *
   * @param key 语言键；空串表示什么也不显示
   * @param values 纯文本占位符
   * @param parts 嵌套渲染的子文案占位符
   */
  public record Line(String key, Map<String, String> values, Map<String, Line> parts) {
    public Line {
      Objects.requireNonNull(key, "key");
      values = values == null ? Map.of() : Map.copyOf(values);
      parts = parts == null ? Map.of() : Map.copyOf(parts);
    }

    static Line of(String key, Map<String, String> values) {
      return new Line(key, values, Map.of());
    }

    static Line empty() {
      return new Line("", Map.of(), Map.of());
    }
  }

  private DriverReport() {}

  /** 停妥那一刻在动作栏显示的对标结果；越站或没停妥时为空。 */
  public static Optional<Line> stopResult(StopScore stop) {
    if (stop == null || stop.outcome() == StopAlignment.Outcome.SKIPPED) {
      return Optional.empty();
    }
    return Optional.of(
        new Line(
            "drive.hud.stop-result." + outcomeKey(stop.outcome()),
            Map.of("station", stop.station()),
            Map.of("offset", offset(stop.offsetBlocks()))));
  }

  /** 对标结果配的提示音。 */
  public static DriveCue stopCue(StopAlignment.Outcome outcome) {
    return switch (outcome) {
      case ACCURATE -> DriveCue.STOP_ACCURATE;
      case ACCEPTED -> DriveCue.STOP_ACCEPTED;
      default -> DriveCue.STOP_POOR;
    };
  }

  /** 偏移写成“过标 / 欠标 N 米”：正数为越过停车点，负数为没到。量不出时写 0。 */
  static Line offset(double offsetBlocks) {
    double offset = Double.isFinite(offsetBlocks) ? offsetBlocks : 0.0;
    return Line.of(
        offset > 0.0 ? "drive.hud.stop-result.over" : "drive.hud.stop-result.under",
        Map.of("distance", String.format(Locale.ROOT, "%.1f", Math.abs(offset))));
  }

  /** 任务结束时聊天栏的成绩单（总分那一行之后的各行）：逐站一行，再按需列出防护、信号、警惕、ATO 与晚点。 */
  public static List<Line> sheet(TaskScore score) {
    Objects.requireNonNull(score, "score");
    List<Line> lines = new ArrayList<>();
    for (StopScore stop : score.stops()) {
      Line result =
          stop.outcome() == StopAlignment.Outcome.SKIPPED
              ? Line.of("drive.task.sheet.result.skipped", Map.of())
              : new Line(
                  "drive.task.sheet.result." + outcomeKey(stop.outcome()),
                  Map.of(),
                  Map.of("offset", offset(stop.offsetBlocks())));
      lines.add(
          new Line(
              "drive.task.sheet.stop",
              Map.of("station", stop.station()),
              Map.of("result", result, "flags", flags(stop))));
    }
    if (score.serviceInterventions() + score.emergencyInterventions() + score.forcedStops() > 0) {
      lines.add(
          Line.of(
              "drive.task.sheet.protection",
              Map.of(
                  "service", String.valueOf(score.serviceInterventions()),
                  "emergency", String.valueOf(score.emergencyInterventions()),
                  "forced", String.valueOf(score.forcedStops()))));
    }
    if (score.signalAcknowledgements() + score.signalMisses() > 0) {
      lines.add(
          Line.of(
              "drive.task.sheet.signal",
              Map.of(
                  "confirmed", String.valueOf(score.signalAcknowledgements()),
                  "missed", String.valueOf(score.signalMisses()),
                  "reaction", String.format(Locale.ROOT, "%.1f", score.signalReactionSeconds()))));
    }
    if (score.vigilanceTrips() > 0) {
      lines.add(
          Line.of(
              "drive.task.sheet.vigilance",
              Map.of("trips", String.valueOf(score.vigilanceTrips()))));
    }
    if (score.lateDepartureConfirmations() > 0) {
      lines.add(
          Line.of(
              "drive.task.sheet.ato",
              Map.of("late", String.valueOf(score.lateDepartureConfirmations()))));
    }
    if (score.delayAtStartSeconds().isPresent() || score.delayAtEndSeconds().isPresent()) {
      lines.add(
          new Line(
              "drive.task.sheet.delay",
              Map.of(),
              Map.of(
                  "start", delay(score.delayAtStartSeconds()),
                  "end", delay(score.delayAtEndSeconds()))));
    }
    return lines;
  }

  /** 渲染一段文案；空键渲染为空。 */
  public static Component render(LocaleManager locale, Line line) {
    if (line.key().isEmpty()) {
      return Component.empty();
    }
    TagResolver.Builder resolver = TagResolver.builder();
    for (Map.Entry<String, String> value : line.values().entrySet()) {
      resolver.resolver(Placeholder.unparsed(value.getKey(), value.getValue()));
    }
    for (Map.Entry<String, Line> part : line.parts().entrySet()) {
      resolver.resolver(Placeholder.component(part.getKey(), render(locale, part.getValue())));
    }
    return locale.component(line.key(), resolver.build());
  }

  private static Line flags(StopScore stop) {
    if (stop.wrongDoor() && stop.doorsTakenOver()) {
      return Line.of("drive.task.sheet.flag.both", Map.of());
    }
    if (stop.wrongDoor()) {
      return Line.of("drive.task.sheet.flag.wrong-door", Map.of());
    }
    if (stop.doorsTakenOver()) {
      return Line.of("drive.task.sheet.flag.doors-taken-over", Map.of());
    }
    return Line.empty();
  }

  private static Line delay(OptionalLong seconds) {
    if (seconds.isEmpty()) {
      return Line.of("drive.task.sheet.unknown", Map.of());
    }
    long value = seconds.getAsLong();
    return Line.of(
        "drive.task.sheet.delta",
        Map.of("value", value > 0L ? "+" + value : String.valueOf(value)));
  }

  private static String outcomeKey(StopAlignment.Outcome outcome) {
    return outcome.name().toLowerCase(Locale.ROOT);
  }
}
