package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpawnTrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 由 TrainCarts 的原始情况与方案覆盖项合成车型档案。纯函数，不碰服务器。
 *
 * <p>每一项都按"方案覆盖 &gt; 存车标签 &gt; 推断 &gt; 配置"取，与运行时 {@link TrainConfigResolver#resolve} 的次序一致：
 * 加减速标签优先于车种预设，车种标签缺失时按默认车种。多出的一级"按编组名推断车种"沿用未发车 ETA 的口径。
 */
public final class ConsistProfiles {

  private ConsistProfiles() {}

  /** 问题种类。 */
  public enum IssueKind {
    /** TrainCarts 解析不出任何车厢：存车不存在且写法也不是原版车。 */
    UNRESOLVED,
    /** 写法不是存车名，按 TrainCarts 写法解析：拼错的存车名会被当成原版车，只能提示。 */
    NOT_SAVED_TRAIN,
    /** 各节车厢上同一个标签的值不一致，又没有在方案里覆盖：出车后控车读到哪个不确定。 */
    CONFLICTING_TAG,
    /** 标签的值读不懂，按缺失处理。 */
    UNREADABLE_TAG
  }

  /**
   * 一处问题。
   *
   * @param kind 种类
   * @param blocking 是否导致这个车型不能用
   * @param detail 相关的标签或写法
   */
  public record Issue(IssueKind kind, boolean blocking, String detail) {
    public Issue {
      Objects.requireNonNull(kind, "kind");
      detail = detail == null ? "" : detail;
    }
  }

  /**
   * 合成结果。
   *
   * @param profile 档案；有阻断问题时为空
   * @param issues 问题与提示
   */
  public record Resolution(Optional<ConsistProfile> profile, List<Issue> issues) {
    public Resolution {
      Objects.requireNonNull(profile, "profile");
      issues = List.copyOf(issues);
    }
  }

  /**
   * 合成一个车型的档案。
   *
   * @param pattern 编组写法
   * @param inspection TrainCarts 读到的原始情况
   * @param overrides 方案覆盖项
   * @param settings 车种配置
   * @return 合成结果
   */
  public static Resolution resolve(
      String pattern,
      ConsistInspection inspection,
      ConsistOverrides overrides,
      ConfigManager.TrainConfigSettings settings) {
    Objects.requireNonNull(inspection, "inspection");
    Objects.requireNonNull(overrides, "overrides");
    Objects.requireNonNull(settings, "settings");
    String tidy = ConsistKey.tidy(pattern).orElse("");
    List<Issue> issues = new ArrayList<>();
    if (tidy.isEmpty() || inspection.cars() <= 0) {
      issues.add(new Issue(IssueKind.UNRESOLVED, true, tidy));
      return new Resolution(Optional.empty(), issues);
    }
    if (!inspection.savedTrain()) {
      issues.add(new Issue(IssueKind.NOT_SAVED_TRAIN, false, tidy));
    }

    Optional<TrainType> tagType =
        overrides.type().isPresent()
            ? Optional.empty()
            : single(inspection, TrainConfigResolver.TAG_TRAIN_TYPE, issues)
                .flatMap(value -> parseType(value, issues));
    TrainType type;
    ConsistProfile.TypeSource source;
    if (overrides.type().isPresent()) {
      type = overrides.type().get();
      source = ConsistProfile.TypeSource.PLAN;
    } else if (tagType.isPresent()) {
      type = tagType.get();
      source = ConsistProfile.TypeSource.TAG;
    } else {
      Optional<TrainType> inferred = SpawnTrainConfigResolver.inferTrainTypeFromPattern(tidy);
      type = inferred.orElse(settings.defaultTrainType());
      source =
          inferred.isPresent() ? ConsistProfile.TypeSource.NAME : ConsistProfile.TypeSource.DEFAULT;
    }
    ConfigManager.TrainTypeSettings preset = settings.forType(type);
    double accel =
        pick(
            overrides.accelBps2(),
            inspection,
            TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2,
            preset.accelBps2(),
            issues);
    double decel =
        pick(
            overrides.decelBps2(),
            inspection,
            TrainConfigResolver.TAG_TRAIN_DECEL_BPS2,
            preset.decelBps2(),
            issues);
    OptionalDouble maxSpeed =
        overrides.maxSpeedBps().isPresent()
            ? overrides.maxSpeedBps()
            : single(inspection, TrainConfigResolver.TAG_TRAIN_MAX_BPS, issues)
                .map(value -> positive(value, TrainConfigResolver.TAG_TRAIN_MAX_BPS, issues))
                .orElse(OptionalDouble.empty());
    if (issues.stream().anyMatch(Issue::blocking)) {
      return new Resolution(Optional.empty(), issues);
    }
    return new Resolution(
        Optional.of(
            new ConsistProfile(
                tidy,
                inspection.cars(),
                inspection.lengthBlocks(),
                type,
                source,
                accel,
                decel,
                maxSpeed,
                overrides.displayName(),
                inspection.savedTrain(),
                inspection.spawnLimit())),
        issues);
  }

  private static double pick(
      OptionalDouble override,
      ConsistInspection inspection,
      String tag,
      double fallback,
      List<Issue> issues) {
    if (override.isPresent()) {
      return override.getAsDouble();
    }
    return single(inspection, tag, issues)
        .map(value -> positive(value, tag, issues))
        .filter(OptionalDouble::isPresent)
        .map(OptionalDouble::getAsDouble)
        .orElse(fallback);
  }

  /** 标签在各节车厢上唯一的值；不一致时记阻断问题并按缺失返回。 */
  private static Optional<String> single(
      ConsistInspection inspection, String tag, List<Issue> issues) {
    Set<String> values = inspection.tag(tag);
    if (values.isEmpty()) {
      return Optional.empty();
    }
    if (values.size() > 1) {
      issues.add(
          new Issue(
              IssueKind.CONFLICTING_TAG,
              true,
              tag + "=" + String.join("/", new java.util.TreeSet<>(values))));
      return Optional.empty();
    }
    return Optional.of(values.iterator().next());
  }

  private static Optional<TrainType> parseType(String value, List<Issue> issues) {
    Optional<TrainType> type = TrainType.parse(value);
    if (type.isEmpty()) {
      issues.add(
          new Issue(
              IssueKind.UNREADABLE_TAG, false, TrainConfigResolver.TAG_TRAIN_TYPE + "=" + value));
    }
    return type;
  }

  private static OptionalDouble positive(String value, String tag, List<Issue> issues) {
    try {
      double parsed = Double.parseDouble(value.trim());
      if (Double.isFinite(parsed) && parsed > 0.0) {
        return OptionalDouble.of(parsed);
      }
    } catch (NumberFormatException ignored) {
      // 读不懂与非正数同样按缺失处理，下面记一条提示
    }
    issues.add(new Issue(IssueKind.UNREADABLE_TAG, false, tag + "=" + value));
    return OptionalDouble.empty();
  }
}
