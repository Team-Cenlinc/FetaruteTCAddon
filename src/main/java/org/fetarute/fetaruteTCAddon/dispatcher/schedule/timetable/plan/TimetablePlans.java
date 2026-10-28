package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 运行计划书的只读入口：编表时按运营商与时刻表编号取出绑定的计划书。 */
public final class TimetablePlans {

  private TimetablePlans() {}

  /**
   * 取出时刻表编号绑定的计划书并解析。
   *
   * <p>直接读库，每次编表调用一次即可，不做缓存。
   *
   * @param provider 存储
   * @param operatorId 运营商
   * @param timetableCode 时刻表编号
   * @return 绑定的计划书；编号没有绑定时为空
   */
  public static Optional<ResolvedPlan> resolve(
      StorageProvider provider, UUID operatorId, String timetableCode) {
    Objects.requireNonNull(provider, "provider");
    if (operatorId == null || timetableCode == null || timetableCode.isBlank()) {
      return Optional.empty();
    }
    TimetablePlanRepository plans = provider.timetablePlans();
    return plans
        .findBinding(operatorId, timetableCode.trim())
        .flatMap(binding -> plans.findById(binding.planId()))
        .map(TimetablePlans::resolve);
  }

  /**
   * 解析一本计划书。
   *
   * @param plan 计划书
   * @return 解析结果；正文有问题时服务模式为 {@link ServicePattern#NONE}
   */
  public static ResolvedPlan resolve(TimetablePlan plan) {
    Objects.requireNonNull(plan, "plan");
    TimetablePlanBook.Parsed parsed = TimetablePlanBook.parse(plan.body());
    return new ResolvedPlan(
        plan.code(),
        parsed.pattern(),
        parsed.issues().stream().map(TimetablePlanBook.Issue::message).toList());
  }
}
