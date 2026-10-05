package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.ConsistArbiter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;

/**
 * 按 route 绑定的编组方案裁决车型。
 *
 * <ul>
 *   <li>出车：按赤字排序，取第一个档案可用、没到出车上限的车型；
 *   <li>复用：只接方案里的车型，赤字大的车型排前面，同车型先到先走；
 *   <li>记账：票派出去之后，按车上实际的车型记一班。
 * </ul>
 *
 * <p>按表出的票不重排复用候选：交路已经决定了哪辆车接哪一班。票上指定了车型（区分车型的表，交路的车型）时只出这个车型、不进份额账； 没指定时出车仍按方案选车型（route
 * 绑了方案，旧的编组来源可能已经没有）。
 */
public final class ConsistDispatchArbiter implements ConsistArbiter {

  private final ConsistPlanService plans;
  private final ConsistInspector inspector;
  private final Function<String, Optional<String>> consistOfTrain;
  private final Consumer<String> debugLogger;

  /**
   * @param plans 编组方案目录
   * @param inspector 查出车上限
   * @param consistOfTrain 列车名 → 车上的车型标签
   * @param debugLogger 调试日志
   */
  public ConsistDispatchArbiter(
      ConsistPlanService plans,
      ConsistInspector inspector,
      Function<String, Optional<String>> consistOfTrain,
      Consumer<String> debugLogger) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.consistOfTrain = Objects.requireNonNull(consistOfTrain, "consistOfTrain");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  @Override
  public List<LayoverRegistry.LayoverCandidate> orderReuseCandidates(
      SpawnTicket ticket, List<LayoverRegistry.LayoverCandidate> candidates) {
    if (ticket == null || candidates == null || candidates.isEmpty() || ticket.timetableDriven()) {
      return candidates;
    }
    UUID routeId = ticket.service().routeId();
    Optional<ResolvedConsistPlan> plan = plans.planForRoute(routeId);
    if (plan.isEmpty()) {
      return candidates;
    }
    Map<String, Integer> order = new HashMap<>();
    List<ResolvedConsistPlan.Member> ranked = plans.rank(routeId, plan.get());
    for (int i = 0; i < ranked.size(); i++) {
      order.put(ranked.get(i).key(), i);
    }
    Map<LayoverRegistry.LayoverCandidate, Integer> rankOf = new LinkedHashMap<>();
    for (LayoverRegistry.LayoverCandidate candidate : candidates) {
      consistOf(candidate).map(order::get).ifPresent(rank -> rankOf.put(candidate, rank));
    }
    List<LayoverRegistry.LayoverCandidate> allowed = new ArrayList<>(rankOf.keySet());
    // List.sort 是稳定排序：同一车型保持到达先后
    allowed.sort(Comparator.comparingInt(rankOf::get));
    if (allowed.size() != candidates.size()) {
      debugLogger.accept(
          "CONSIST_REUSE_FILTERED route="
              + ticket.service().routeCode()
              + " plan="
              + plan.get().plan().name()
              + " rejected="
              + (candidates.size() - allowed.size())
              + " remaining="
              + allowed.size());
    }
    return allowed;
  }

  @Override
  public SpawnChoice chooseSpawn(SpawnTicket ticket) {
    if (ticket == null) {
      return SpawnChoice.legacy();
    }
    UUID routeId = ticket.service().routeId();
    if (ticket.consist().isPresent()) {
      return designated(routeId, ticket.consist().get());
    }
    Optional<ResolvedConsistPlan> plan = plans.planForRoute(routeId);
    if (plan.isEmpty()) {
      return SpawnChoice.legacy();
    }
    List<String> skipped = new ArrayList<>();
    for (ResolvedConsistPlan.Member member : plans.rank(routeId, plan.get())) {
      Optional<ConsistProfile> profile = member.profile();
      if (profile.isEmpty()) {
        skipped.add(member.entry().pattern() + ":unresolved");
        continue;
      }
      if (inspector.exceedsSpawnLimit(profile.get().pattern())) {
        skipped.add(member.entry().pattern() + ":spawn-limit");
        continue;
      }
      return SpawnChoice.chosen(profile.get().pattern(), member.spawnTags());
    }
    return SpawnChoice.blocked(
        "plan=" + plan.get().plan().name() + " " + String.join(",", skipped));
  }

  /**
   * 票上指定了车型：只能出这个车型。档案不可用、方案里已经没有它、或到了出车上限，都不改出别的车型—— 时刻表按这个车型排的时分，换车型就对不上表了。票留着重试，到期由时刻表按出不了车处理。
   */
  private SpawnChoice designated(UUID routeId, String key) {
    Optional<ResolvedConsistPlan.Member> member = plans.member(routeId, key);
    if (member.isEmpty()) {
      return SpawnChoice.blocked("consist=" + key + ":not-in-any-plan");
    }
    ConsistProfile profile = member.get().profile().orElseThrow();
    if (inspector.exceedsSpawnLimit(profile.pattern())) {
      return SpawnChoice.blocked("consist=" + key + ":spawn-limit");
    }
    return SpawnChoice.chosen(profile.pattern(), member.get().spawnTags());
  }

  @Override
  public boolean acceptsForRoute(UUID routeId, LayoverRegistry.LayoverCandidate candidate) {
    if (candidate == null) {
      return false;
    }
    Optional<ResolvedConsistPlan> plan = plans.planForRoute(routeId);
    if (plan.isEmpty()) {
      return true;
    }
    return consistOf(candidate).filter(plan.get()::allows).isPresent();
  }

  @Override
  public void onDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null || trainName == null || ticket.consist().isPresent()) {
      // 票上指定车型的是时刻表排定的，不进间隔发车的份额账。
      return;
    }
    UUID routeId = ticket.service().routeId();
    Optional<ResolvedConsistPlan> plan = plans.planForRoute(routeId);
    if (plan.isEmpty()) {
      return;
    }
    Optional<String> consist = consistOfTrain.apply(trainName);
    if (consist.isEmpty()) {
      debugLogger.accept("CONSIST_RECORD_SKIPPED train=" + trainName + " reason=no-consist-tag");
      return;
    }
    plans.record(routeId, plan.get(), consist.get());
  }

  private static Optional<String> consistOf(LayoverRegistry.LayoverCandidate candidate) {
    for (Map.Entry<String, String> tag : candidate.tags().entrySet()) {
      if (ConsistKey.TRAIN_TAG.equalsIgnoreCase(tag.getKey())) {
        return ConsistKey.of(tag.getValue());
      }
    }
    return Optional.empty();
  }
}
