package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;

/**
 * 编表时 headway 的缺省来源：baseline 频率是运营目标，时刻表应当从它出发，而不是从一个写死的 300 秒出发。
 *
 * <p>优先级：
 *
 * <ol>
 *   <li>命令显式给的 {@code --headway}
 *   <li>线路的 {@code spawnFreqBaselineSec}
 *   <li>线路 metadata 里的交路组 baseline：只有一个组就用它；多个组按频率合并（{@code 1 / Σ(1/b_i)}），因为时刻表以整条线路为单位排班、 再按
 *       route weight 切分份额，全线的总频率才是它的输入
 *   <li>都没有：{@link TimetableBuildOptions#DEFAULT_HEADWAY_SECONDS}
 * </ol>
 *
 * <p>baseline 是目标，不是硬约束：排出来有冲突时由 {@link TimetableBuilder} 回退到最小可行 headway。 这里只回答"从哪个数出发"。
 */
public final class TimetableHeadwayDefaults {

  private TimetableHeadwayDefaults() {}

  /**
   * 选定 headway 及其来源。
   *
   * @param explicitSeconds 命令显式给的秒数
   * @param lineBaselineSeconds 线路级 baseline
   * @param groups 线路的交路组配置
   * @return 选定结果
   */
  public static Choice resolve(
      Optional<Integer> explicitSeconds,
      Optional<Integer> lineBaselineSeconds,
      List<SpawnGroup> groups) {
    Optional<Integer> explicit = positive(explicitSeconds);
    if (explicit.isPresent()) {
      return new Choice(explicit.get(), Source.EXPLICIT, "--headway");
    }
    Optional<Integer> line = positive(lineBaselineSeconds);
    if (line.isPresent()) {
      return new Choice(line.get(), Source.LINE_BASELINE, "线路 baseline");
    }
    List<SpawnGroup> withBaseline =
        groups == null
            ? List.of()
            : groups.stream()
                .filter(Objects::nonNull)
                .filter(group -> group.baselineSeconds().isPresent())
                .toList();
    if (withBaseline.size() == 1) {
      SpawnGroup group = withBaseline.get(0);
      return new Choice(
          group.baselineSeconds().orElseThrow(),
          Source.GROUP_BASELINE,
          "交路组 " + group.name() + " baseline");
    }
    if (withBaseline.size() > 1) {
      double frequency = 0.0D;
      for (SpawnGroup group : withBaseline) {
        frequency += 1.0D / group.baselineSeconds().orElseThrow();
      }
      int combined = (int) Math.max(1L, Math.round(1.0D / frequency));
      String detail =
          withBaseline.stream()
              .map(group -> group.name() + "=" + group.baselineSeconds().orElseThrow() + "s")
              .collect(Collectors.joining(", "));
      return new Choice(
          combined,
          Source.GROUP_BASELINE,
          String.format(Locale.ROOT, "%d 个交路组 baseline 合并（%s）", withBaseline.size(), detail));
    }
    return new Choice(TimetableBuildOptions.DEFAULT_HEADWAY_SECONDS, Source.DEFAULT, "默认值");
  }

  private static Optional<Integer> positive(Optional<Integer> value) {
    return value == null ? Optional.empty() : value.filter(v -> v != null && v > 0);
  }

  /** 缺省来源。 */
  public enum Source {
    EXPLICIT,
    LINE_BASELINE,
    GROUP_BASELINE,
    DEFAULT
  }

  /**
   * 选定的 headway。
   *
   * @param seconds 秒
   * @param source 来源
   * @param description 供报告显示的来源说明
   */
  public record Choice(int seconds, Source source, String description) {
    public Choice {
      if (seconds <= 0) {
        throw new IllegalArgumentException("headway 必须为正");
      }
      Objects.requireNonNull(source, "source");
      description = description == null ? "" : description;
    }
  }
}
