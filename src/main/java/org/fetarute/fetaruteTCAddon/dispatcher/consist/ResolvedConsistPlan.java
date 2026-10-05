package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;

/**
 * 解析好的编组方案：方案书的每一行配上它的车型档案。
 *
 * <p>档案解析失败的车型仍算"许可车型"——已经在线上的这种车照样可以被复用到这条 route 上，只是不能再出车。
 *
 * @param plan 方案
 * @param members 车型，按书里的顺序
 * @param bookProblems 方案书的文本问题（存库前已拦下，旧数据或手改数据库时才会有）
 */
public record ResolvedConsistPlan(
    ConsistPlan plan, List<Member> members, List<ConsistPlanBook.Problem> bookProblems) {

  /**
   * 方案里的一个车型。
   *
   * @param entry 方案书里的那一行
   * @param resolution 档案合成结果
   */
  public record Member(ConsistPlanBook.Entry entry, ConsistProfiles.Resolution resolution) {
    public Member {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(resolution, "resolution");
    }

    /** 车型键。 */
    public String key() {
      return entry.key();
    }

    /** 权重。 */
    public int weight() {
      return entry.weight();
    }

    /** 档案；解析失败时为空，这时不能出车。 */
    public Optional<ConsistProfile> profile() {
      return resolution.profile();
    }

    /**
     * 出车后写到车上的标签。车种一律写（推断与默认得到的车种也写上，控车才与估算同一个车种）；加减速与最高速度只在方案覆盖时写， 否则沿用存车自带的标签与配置。
     *
     * @return 标签键 → 值；档案不可用时为空
     */
    public Map<String, String> spawnTags() {
      if (profile().isEmpty()) {
        return Map.of();
      }
      Map<String, String> tags = new LinkedHashMap<>();
      tags.put(TrainConfigResolver.TAG_TRAIN_TYPE, profile().get().type().name());
      ConsistOverrides overrides = entry.overrides();
      overrides
          .accelBps2()
          .ifPresent(
              value -> tags.put(TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2, String.valueOf(value)));
      overrides
          .decelBps2()
          .ifPresent(
              value -> tags.put(TrainConfigResolver.TAG_TRAIN_DECEL_BPS2, String.valueOf(value)));
      overrides
          .maxSpeedBps()
          .ifPresent(
              value -> tags.put(TrainConfigResolver.TAG_TRAIN_MAX_BPS, String.valueOf(value)));
      return tags;
    }
  }

  public ResolvedConsistPlan {
    Objects.requireNonNull(plan, "plan");
    members = List.copyOf(members);
    bookProblems = List.copyOf(bookProblems);
  }

  /** 方案指纹，见 {@link ConsistPlan#fingerprint()}。 */
  public String fingerprint() {
    return plan.fingerprint();
  }

  /**
   * 方案里的车型。
   *
   * @param pattern 编组写法或车型键（比较前归一）
   * @return 车型；不在方案里时为空
   */
  public Optional<Member> member(String pattern) {
    Optional<String> key = ConsistKey.of(pattern);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    for (Member member : members) {
      if (member.key().equals(key.get())) {
        return Optional.of(member);
      }
    }
    return Optional.empty();
  }

  /** 这个车型许不许跑绑定本方案的 route。 */
  public boolean allows(String pattern) {
    return member(pattern).isPresent();
  }

  /** 交给 {@link ConsistSelector} 的权重表。 */
  public List<ConsistSelector.Weighted> weights() {
    List<ConsistSelector.Weighted> weights = new ArrayList<>(members.size());
    for (Member member : members) {
      weights.add(new ConsistSelector.Weighted(member.key(), member.weight()));
    }
    return weights;
  }
}
