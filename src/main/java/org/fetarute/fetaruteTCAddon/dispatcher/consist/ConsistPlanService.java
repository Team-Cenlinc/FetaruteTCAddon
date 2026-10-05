package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 编组方案的运行时目录：route 绑了哪份方案、方案里各车型的档案、各 route 按班次份额的记账。
 *
 * <p>方案与档案在 {@link #reload} 时一次解析成不可变快照，之后任何线程只读快照。读 TrainCarts 存车只能在主线程，所以 {@link #reload} 与
 * {@link #resolve} 都只在主线程调用（启动、{@code /fta reload}、方案保存与删除之后）。改了存车之后，要 {@code /fta reload}
 * 或重新保存方案，档案才会更新。
 */
public final class ConsistPlanService {

  /** route metadata 里写方案名的键。 */
  public static final String ROUTE_METADATA_KEY = "consist_plan";

  private final Supplier<Optional<StorageProvider>> storage;
  private final ConsistInspector inspector;
  private final Supplier<ConfigManager.TrainConfigSettings> trainSettings;
  private final Consumer<String> debugLogger;
  private final ConsistSelector selector = new ConsistSelector();

  /** 方案与按车型键的索引；重载时整份替换。 */
  private volatile Catalog catalog = Catalog.EMPTY;

  /**
   * 方案目录的一份快照。
   *
   * @param plans 运营商 → 方案名键 → 方案
   * @param membersByOperator 运营商 → 车型键 → 档案可用的方案车型（按方案 ID 取第一份）
   * @param membersByCompany 公司 → 车型键 → 档案可用的方案车型（按方案 ID 取第一份）
   */
  private record Catalog(
      Map<UUID, Map<String, ResolvedConsistPlan>> plans,
      Map<UUID, Map<String, ResolvedConsistPlan.Member>> membersByOperator,
      Map<UUID, Map<String, ResolvedConsistPlan.Member>> membersByCompany) {
    static final Catalog EMPTY = new Catalog(Map.of(), Map.of(), Map.of());
  }

  private volatile Function<UUID, Optional<RouteDefinitionCache.RouteRecord>> routes =
      routeId -> Optional.empty();

  /**
   * @param storage 存储；未就绪时为空
   * @param inspector 读 TrainCarts 编组
   * @param trainSettings 车种配置（每次解析现读）
   * @param debugLogger 调试日志
   */
  public ConsistPlanService(
      Supplier<Optional<StorageProvider>> storage,
      ConsistInspector inspector,
      Supplier<ConfigManager.TrainConfigSettings> trainSettings,
      Consumer<String> debugLogger) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.trainSettings = Objects.requireNonNull(trainSettings, "trainSettings");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /**
   * 接入交路缓存：按 route 找它的运营商与 metadata。
   *
   * @param routes routeId → 交路记录
   */
  public void attachRoutes(Function<UUID, Optional<RouteDefinitionCache.RouteRecord>> routes) {
    this.routes = routes == null ? routeId -> Optional.empty() : routes;
  }

  /** 重读全部方案并重新解析档案。只在主线程调用。 */
  public void reload() {
    Optional<StorageProvider> provider = storage.get();
    if (provider.isEmpty()) {
      return;
    }
    List<ConsistPlan> plans;
    try {
      plans = provider.get().consistPlans().listAll();
    } catch (RuntimeException ex) {
      debugLogger.accept("CONSIST_PLAN_RELOAD_FAILED error=" + ex);
      return;
    }
    Map<String, ConsistInspection> inspections = new HashMap<>();
    Map<UUID, Map<String, ResolvedConsistPlan>> next = new HashMap<>();
    for (ConsistPlan plan : plans) {
      ResolvedConsistPlan resolved = resolve(plan, inspections);
      next.computeIfAbsent(plan.operatorId(), id -> new HashMap<>()).put(plan.nameKey(), resolved);
      for (ResolvedConsistPlan.Member member : resolved.members()) {
        if (member.profile().isEmpty()) {
          debugLogger.accept(
              "CONSIST_PROFILE_UNRESOLVED plan="
                  + plan.name()
                  + " consist="
                  + member.entry().pattern()
                  + " issues="
                  + member.resolution().issues());
        }
      }
    }
    Map<UUID, Map<String, ResolvedConsistPlan>> frozen = new HashMap<>();
    next.forEach((operator, byName) -> frozen.put(operator, Map.copyOf(byName)));
    this.catalog =
        new Catalog(
            Map.copyOf(frozen),
            membersBy(next, id -> Optional.of(id)),
            companyIndex(provider.get(), next));
    debugLogger.accept("CONSIST_PLAN_RELOAD plans=" + plans.size());
  }

  /** 按公司建车型索引：方案只挂在运营商下，公司从运营商查。查不到公司的运营商不进索引。 */
  private Map<UUID, Map<String, ResolvedConsistPlan.Member>> companyIndex(
      StorageProvider provider, Map<UUID, Map<String, ResolvedConsistPlan>> plans) {
    Map<UUID, Optional<UUID>> companyByOperator = new HashMap<>();
    for (UUID operatorId : plans.keySet()) {
      Optional<UUID> company;
      try {
        company = provider.operators().findById(operatorId).map(Operator::companyId);
      } catch (RuntimeException ex) {
        debugLogger.accept(
            "CONSIST_PLAN_OPERATOR_LOOKUP_FAILED operator=" + operatorId + " error=" + ex);
        company = Optional.empty();
      }
      companyByOperator.put(operatorId, company);
    }
    return membersBy(
        plans, operatorId -> companyByOperator.getOrDefault(operatorId, Optional.empty()));
  }

  /**
   * 车型键索引：各分组里每个车型键取方案 ID 最小、档案可用的那一份（同一公司内同一写法的覆盖项一致，取哪一份都一样；按 ID 取是为了确定）。
   *
   * @param groupOf 运营商 → 分组（运营商本身或它的公司）
   */
  private static Map<UUID, Map<String, ResolvedConsistPlan.Member>> membersBy(
      Map<UUID, Map<String, ResolvedConsistPlan>> plans, Function<UUID, Optional<UUID>> groupOf) {
    List<ResolvedConsistPlan> sorted = new ArrayList<>();
    plans.values().forEach(byName -> sorted.addAll(byName.values()));
    sorted.sort(Comparator.comparing(plan -> plan.plan().id()));
    Map<UUID, Map<String, ResolvedConsistPlan.Member>> out = new HashMap<>();
    for (ResolvedConsistPlan plan : sorted) {
      Optional<UUID> group = groupOf.apply(plan.plan().operatorId());
      if (group.isEmpty()) {
        continue;
      }
      for (ResolvedConsistPlan.Member member : plan.members()) {
        if (member.profile().isPresent()) {
          out.computeIfAbsent(group.get(), id -> new HashMap<>()).putIfAbsent(member.key(), member);
        }
      }
    }
    Map<UUID, Map<String, ResolvedConsistPlan.Member>> frozen = new HashMap<>();
    out.forEach((group, byKey) -> frozen.put(group, Map.copyOf(byKey)));
    return Map.copyOf(frozen);
  }

  /**
   * 解析一份方案：方案书文本 + 每个车型的档案。只在主线程调用。
   *
   * @param plan 方案
   * @return 解析结果
   */
  public ResolvedConsistPlan resolve(ConsistPlan plan) {
    return resolve(plan, new HashMap<>());
  }

  /**
   * 解析单个编组的档案（{@code /fta consist profile}）。只在主线程调用。
   *
   * @param pattern 编组写法
   * @param overrides 覆盖项
   * @return 合成结果
   */
  public ConsistProfiles.Resolution profile(String pattern, ConsistOverrides overrides) {
    return ConsistProfiles.resolve(
        pattern, inspect(pattern, new HashMap<>()), overrides, trainSettings.get());
  }

  /**
   * 运营商下的某份方案。
   *
   * @param operatorId 运营商
   * @param name 方案名（不区分大小写）
   * @return 方案；未保存或已删除时为空
   */
  public Optional<ResolvedConsistPlan> plan(UUID operatorId, String name) {
    if (operatorId == null || name == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        catalog.plans().getOrDefault(operatorId, Map.of()).get(ConsistPlan.nameKey(name)));
  }

  /**
   * 按车型键找一个档案可用的方案车型（按表出车用：交路只记车型键，编组写法与出车标签从方案里取）。
   *
   * <p>先在 route 所属运营商的方案里找，找不到再看同一公司其他运营商的：同一写法在同一公司内的覆盖项一致（方案保存时已拦下冲突），取哪一份都一样。
   * 不跨公司找——别的公司同一写法的覆盖项可以不同，出出来的车会和编表用的车型对不上。
   *
   * @param routeId 出车的 route（定运营商与公司）
   * @param key 车型键（{@link ConsistKey#of} 归一后）
   * @return 车型；route 不在交路缓存里、本公司没有方案列出这个车型、或档案都不可用时为空
   */
  public Optional<ResolvedConsistPlan.Member> member(UUID routeId, String key) {
    if (routeId == null || key == null || key.isBlank()) {
      return Optional.empty();
    }
    Optional<RouteDefinitionCache.RouteRecord> record = routes.apply(routeId);
    if (record.isEmpty()) {
      return Optional.empty();
    }
    Catalog snapshot = catalog;
    Operator operator = record.get().operator();
    ResolvedConsistPlan.Member own =
        snapshot.membersByOperator().getOrDefault(operator.id(), Map.of()).get(key);
    if (own != null) {
      return Optional.of(own);
    }
    return Optional.ofNullable(
        snapshot.membersByCompany().getOrDefault(operator.companyId(), Map.of()).get(key));
  }

  /**
   * route 绑定的方案。
   *
   * @param routeId route
   * @return 方案；没绑、方案不存在或交路不在缓存里时为空
   */
  public Optional<ResolvedConsistPlan> planForRoute(UUID routeId) {
    if (routeId == null) {
      return Optional.empty();
    }
    return routes
        .apply(routeId)
        .flatMap(
            record ->
                planNameOf(record.route().metadata())
                    .flatMap(name -> plan(record.operator().id(), name)));
  }

  /**
   * 这条 route 下一班该优先用的车型，按赤字从大到小。
   *
   * @param routeId route
   * @param plan route 绑定的方案
   * @return 方案里的车型，排在前面的先用
   */
  public List<ResolvedConsistPlan.Member> rank(UUID routeId, ResolvedConsistPlan plan) {
    Objects.requireNonNull(plan, "plan");
    List<String> keys = selector.rank(routeId, plan.fingerprint(), plan.weights());
    List<ResolvedConsistPlan.Member> ranked = new ArrayList<>(keys.size());
    for (String key : keys) {
      plan.member(key).ifPresent(ranked::add);
    }
    return ranked;
  }

  /**
   * 记一班：这条 route 刚由这个车型跑了一班。不在方案里的车型不记。
   *
   * @param routeId route
   * @param plan route 绑定的方案
   * @param pattern 实际跑的车型
   */
  public void record(UUID routeId, ResolvedConsistPlan plan, String pattern) {
    Objects.requireNonNull(plan, "plan");
    plan.member(pattern)
        .ifPresent(member -> selector.record(routeId, plan.fingerprint(), member.key()));
  }

  /**
   * 这条 route 下一班预计的车型档案（未发车 ETA 用）：排在最前、档案可用的那个。
   *
   * @param routeId route
   * @return 档案；route 没绑方案或方案里没有可用档案时为空
   */
  public Optional<ConsistProfile> predictedProfile(UUID routeId) {
    Optional<ResolvedConsistPlan> plan = planForRoute(routeId);
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    for (ResolvedConsistPlan.Member member : rank(routeId, plan.get())) {
      if (member.profile().isPresent()) {
        return member.profile();
      }
    }
    return Optional.empty();
  }

  /**
   * 各车型在这条 route 上已跑的班次（{@code /fta spawn status} 用）。
   *
   * @param routeId route
   * @return 车型键 → 班次
   */
  public Map<String, Long> counts(UUID routeId) {
    return selector.counts(routeId);
  }

  /**
   * 读 route metadata 里的方案名。
   *
   * @param metadata route metadata
   * @return 方案名；没写或空白时为空
   */
  public static Optional<String> planNameOf(Map<String, Object> metadata) {
    if (metadata == null) {
      return Optional.empty();
    }
    Object raw = metadata.get(ROUTE_METADATA_KEY);
    if (raw == null) {
      return Optional.empty();
    }
    String name = raw.toString().trim();
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }

  private ResolvedConsistPlan resolve(
      ConsistPlan plan, Map<String, ConsistInspection> inspections) {
    ConsistPlanBook.Parsed parsed = ConsistPlanBook.parse(plan.body());
    ConfigManager.TrainConfigSettings settings = trainSettings.get();
    List<ResolvedConsistPlan.Member> members = new ArrayList<>(parsed.entries().size());
    for (ConsistPlanBook.Entry entry : parsed.entries()) {
      ConsistInspection inspection = inspect(entry.pattern(), inspections);
      members.add(
          new ResolvedConsistPlan.Member(
              entry,
              ConsistProfiles.resolve(entry.pattern(), inspection, entry.overrides(), settings)));
    }
    return new ResolvedConsistPlan(plan, members, parsed.problems());
  }

  private ConsistInspection inspect(String pattern, Map<String, ConsistInspection> inspections) {
    String key = ConsistKey.of(pattern).orElse("");
    if (key.isEmpty()) {
      return ConsistInspection.unresolved();
    }
    ConsistInspection cached = inspections.get(key);
    if (cached != null) {
      return cached;
    }
    ConsistInspection inspection;
    try {
      inspection = inspector.inspect(ConsistKey.tidy(pattern).orElse(pattern));
    } catch (RuntimeException | LinkageError ex) {
      debugLogger.accept("CONSIST_INSPECT_FAILED consist=" + pattern + " error=" + ex);
      inspection = ConsistInspection.unresolved();
    }
    inspections.put(key, inspection);
    return inspection;
  }
}
