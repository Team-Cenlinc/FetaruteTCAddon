package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Smart Dispatcher 运行时诊断的有界重复抑制器。
 *
 * <p>调度判定会由事件、周期 tick 和授权刷新等多个入口触发。同一稳定状态若每次都写控制台，会掩盖真正的状态跃迁， 也会在服务器关闭时放大已有的线程问题。
 *
 * <p>本类默认处理所有运行时观察 trace：忽略请求号、tick、序列号及版本号后，短时间内相同的状态只输出一次。所有观察 trace
 * 还共享有界窗口预算，避免多列车稳定运行时仍按列车数放大控制台负担。执行器审计和 unlock 事务边界会逐次原样透传， 以保留动作复核及 reservation
 * 创建、重评估、成功或回滚的完整证据。到达提交、STOP 与真实资源生命周期由生产端按语义变化去重后逐次透传，确保实际进度、首次停因、资源释放与解除事件不被预算吞掉；普通观察状态变化仍受预算约束。
 *
 * <p>缓存采用固定容量的访问顺序 LRU，避免高基数的列车或资源标识无限占用内存。该类不参与授权、占用或速度控制， 因而不能作为任何安全判定的输入。
 */
public final class RuntimeDispatchDiagnosticGate implements Consumer<String> {

  private static final Duration DEFAULT_REPEAT_WINDOW = Duration.ofSeconds(5);
  private static final int DEFAULT_MAX_SIGNATURES = 4096;
  private static final Duration DEFAULT_OBSERVATION_BUDGET_WINDOW = Duration.ofMinutes(1);
  private static final int DEFAULT_MAX_OBSERVATION_EMISSIONS = 120;
  private static final int MAX_SUPPRESSION_CATEGORIES = 16;
  private static final int MAX_REPORTED_SUPPRESSION_CATEGORIES = 4;

  private final Consumer<String> output;
  private final long repeatWindowNanos;
  private final int maxSignatures;
  private final long observationBudgetWindowNanos;
  private final long observationBudgetWindowSeconds;
  private final int maxObservationEmissions;
  private final LongSupplier nanoTime;
  private final Map<String, Long> lastEmittedNanos = new LinkedHashMap<>(16, 0.75F, true);
  private final Deque<Long> observationEmissionNanos = new ArrayDeque<>();
  private final Map<String, Long> repeatedSuppressionsByCategory = new LinkedHashMap<>();
  private final Map<String, Long> budgetSuppressionsByCategory = new LinkedHashMap<>();
  private long repeatedSuppressionCount;
  private long budgetSuppressionCount;

  /** 使用运行时默认窗口创建诊断 gate。 */
  public RuntimeDispatchDiagnosticGate(Consumer<String> output) {
    this(output, DEFAULT_MAX_OBSERVATION_EMISSIONS);
  }

  /**
   * 使用指定的每分钟观察预算创建诊断 gate。
   *
   * <p>预算原本是硬编码的 {@value #DEFAULT_MAX_OBSERVATION_EMISSIONS} 条/分钟。实服排查时这个值偏低： 2026-09-13 一轮 42
   * 分钟里丢弃 305,203 行、写出 36,229 行（**89% 被丢**）， 连 {@code SMART_POTENTIAL_PHYSICAL_CHANGE_CONTAINED}
   * 这类只在异常时出现的 trace 都一条没留下—— 于是「没 grep 到」既可能是没发生，也可能是被丢了，**无法区分**，排查因此停摆。
   *
   * <p>排查期间需要能临时调高；稳定运行时应当调回默认值，否则控制台会按列车数放大。
   *
   * @param output 实际日志接收端
   * @param maxObservationEmissionsPerMinute 每分钟最多输出的观察 trace 数；非正值回落到默认
   */
  public RuntimeDispatchDiagnosticGate(
      Consumer<String> output, int maxObservationEmissionsPerMinute) {
    this(
        output,
        DEFAULT_REPEAT_WINDOW,
        DEFAULT_MAX_SIGNATURES,
        DEFAULT_OBSERVATION_BUDGET_WINDOW,
        maxObservationEmissionsPerMinute > 0
            ? maxObservationEmissionsPerMinute
            : DEFAULT_MAX_OBSERVATION_EMISSIONS,
        System::nanoTime);
  }

  /**
   * 使用可控时钟创建诊断 gate，供确定性测试和运行时装配使用。
   *
   * @param output 实际日志接收端
   * @param repeatWindow 同一稳定诊断再次输出前的最短间隔
   * @param maxSignatures 最多保留的稳定诊断签名数
   * @param nanoTime 单调时间来源
   */
  RuntimeDispatchDiagnosticGate(
      Consumer<String> output, Duration repeatWindow, int maxSignatures, LongSupplier nanoTime) {
    this(
        output,
        repeatWindow,
        maxSignatures,
        DEFAULT_OBSERVATION_BUDGET_WINDOW,
        DEFAULT_MAX_OBSERVATION_EMISSIONS,
        nanoTime);
  }

  /**
   * 使用可控时钟和观察预算创建诊断 gate，供确定性测试使用。
   *
   * @param output 实际日志接收端
   * @param repeatWindow 同一稳定诊断再次输出前的最短间隔
   * @param maxSignatures 最多保留的稳定诊断签名数
   * @param observationBudgetWindow 观察 trace 的全局预算窗口
   * @param maxObservationEmissions 预算窗口内最多输出的观察 trace 数
   * @param nanoTime 单调时间来源
   */
  RuntimeDispatchDiagnosticGate(
      Consumer<String> output,
      Duration repeatWindow,
      int maxSignatures,
      Duration observationBudgetWindow,
      int maxObservationEmissions,
      LongSupplier nanoTime) {
    this.output = output != null ? output : message -> {};
    Duration resolvedWindow = Objects.requireNonNull(repeatWindow, "repeatWindow");
    if (resolvedWindow.isNegative() || resolvedWindow.isZero()) {
      throw new IllegalArgumentException("repeatWindow 必须大于零");
    }
    if (maxSignatures < 1) {
      throw new IllegalArgumentException("maxSignatures 必须大于零");
    }
    Duration resolvedBudgetWindow =
        Objects.requireNonNull(observationBudgetWindow, "observationBudgetWindow");
    if (resolvedBudgetWindow.isNegative() || resolvedBudgetWindow.isZero()) {
      throw new IllegalArgumentException("observationBudgetWindow 必须大于零");
    }
    if (maxObservationEmissions < 1) {
      throw new IllegalArgumentException("maxObservationEmissions 必须大于零");
    }
    this.repeatWindowNanos = resolvedWindow.toNanos();
    this.maxSignatures = maxSignatures;
    this.observationBudgetWindowNanos = resolvedBudgetWindow.toNanos();
    this.observationBudgetWindowSeconds = resolvedBudgetWindow.toSeconds();
    this.maxObservationEmissions = maxObservationEmissions;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  @Override
  public void accept(String message) {
    if (message == null || message.isBlank()) {
      return;
    }
    if (isUnboundedAudit(message)) {
      output.accept(message);
      return;
    }
    GateDecision decision =
        shouldEmitObservation(
            stableSignature(message), diagnosticCategory(message), nanoTime.getAsLong());
    if (decision.suppressionSummary() != null) {
      output.accept(decision.suppressionSummary());
    }
    if (decision.emitObservation()) {
      output.accept(message);
    }
  }

  private GateDecision shouldEmitObservation(String signature, String category, long nowNanos) {
    synchronized (lastEmittedNanos) {
      Long lastNanos = lastEmittedNanos.get(signature);
      if (lastNanos != null && nowNanos - lastNanos < repeatWindowNanos) {
        repeatedSuppressionCount++;
        recordSuppressionCategory(repeatedSuppressionsByCategory, category);
        return GateDecision.DO_NOT_EMIT;
      }
      discardExpiredObservationEmissions(nowNanos);
      if (observationEmissionNanos.size() >= maxObservationEmissions) {
        budgetSuppressionCount++;
        recordSuppressionCategory(budgetSuppressionsByCategory, category);
        return GateDecision.DO_NOT_EMIT;
      }
      lastEmittedNanos.put(signature, nowNanos);
      trimToCapacity();
      observationEmissionNanos.addLast(nowNanos);
      String suppressionSummary = null;
      if (repeatedSuppressionCount > 0 || budgetSuppressionCount > 0) {
        suppressionSummary =
            "SMART_DISPATCH_DIAGNOSTICS_SUPPRESSED count="
                + (repeatedSuppressionCount + budgetSuppressionCount)
                + " repeat="
                + repeatedSuppressionCount
                + " budgetDrop="
                + budgetSuppressionCount
                + " repeatKinds="
                + describeSuppressionCategories(repeatedSuppressionsByCategory)
                + " budgetKinds="
                + describeSuppressionCategories(budgetSuppressionsByCategory)
                + " budget="
                + maxObservationEmissions
                + " windowSeconds="
                + observationBudgetWindowSeconds;
        repeatedSuppressionCount = 0L;
        budgetSuppressionCount = 0L;
        repeatedSuppressionsByCategory.clear();
        budgetSuppressionsByCategory.clear();
      }
      return new GateDecision(true, suppressionSummary);
    }
  }

  private static void recordSuppressionCategory(Map<String, Long> categories, String category) {
    String resolvedCategory =
        category == null || category.isBlank() ? "UNKNOWN_DIAGNOSTIC" : category;
    if (!categories.containsKey(resolvedCategory)
        && categories.size() >= MAX_SUPPRESSION_CATEGORIES) {
      resolvedCategory = "OTHER_DIAGNOSTIC";
    }
    categories.merge(resolvedCategory, 1L, Long::sum);
  }

  private static String describeSuppressionCategories(Map<String, Long> categories) {
    if (categories == null || categories.isEmpty()) {
      return "[]";
    }
    return categories.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey))
        .limit(MAX_REPORTED_SUPPRESSION_CATEGORIES)
        .map(entry -> entry.getKey() + ":" + entry.getValue())
        .toList()
        .toString();
  }

  private void discardExpiredObservationEmissions(long nowNanos) {
    while (!observationEmissionNanos.isEmpty()
        && nowNanos - observationEmissionNanos.peekFirst() >= observationBudgetWindowNanos) {
      observationEmissionNanos.removeFirst();
    }
  }

  private void trimToCapacity() {
    Iterator<String> iterator = lastEmittedNanos.keySet().iterator();
    while (lastEmittedNanos.size() > maxSignatures && iterator.hasNext()) {
      iterator.next();
      iterator.remove();
    }
  }

  /**
   * 识别必须逐次保留的事务审计。
   *
   * <p>执行器会在每一次请求时重新核对授权条件；unlock reservation 的创建、重评估及终态则共同组成可回放事务链。二者都不能被普通观察预算吞掉。候选选择、{@code
   * PLAN_APPLY} 与逐 tick progress 仍受门控，避免为保留事务证据而重新放大稳定期日志。{@code SMART_STOP_LIFECYCLE}
   * 只由真实的进入、变更和解除产生；{@code SMART_RESOURCE_LIFECYCLE} 只在 claim 创建、释放或 owner/角色/方向变化时产生。 生产端已抑制稳定
   * STOP 和 claim refresh 心跳，必须保留完整变化边界。
   *
   * @param message 原始诊断行
   * @return 是否必须逐次保留的事务审计
   */
  private static boolean isUnboundedAudit(String message) {
    if (message.startsWith("SMART_DISPATCH_EXECUTOR_")) {
      return true;
    }
    // 终点站 layover 是列车"跑完一趟之后去哪了"的唯一记录，全部事件驱动、天然低频
    // （实服 42 分钟：注册 5、复用等待 46、复用成功 18、改名 3），不存在按 tick 放大的路径。
    //
    // 它被预算吞掉时后果很隐蔽：复用会给列车改名，旧名从此不再出现。缺了这几行就无法把改名链接起来，
    // "某个列车名不再出现"会被读成"这辆车冻住了"——2026-09-13 我就是这样误判了一轮，
    // 据此写下的"5 辆车冻死在终点站"是错的（见 dispatch-disproven-hypotheses #11）。
    if (message.startsWith("Layover ")) {
      return true;
    }
    int separator = message.indexOf(' ');
    String rawKind = separator < 0 ? message : message.substring(0, separator);
    // 去掉尾部冒号：两种生产端的行格式不一样。
    //   RuntimeDispatchService 等：`SMART_ROUTE_ARRIVAL train=...`      —— 无冒号
    //   TrainHealthMonitor.traceHealthEvent：`事件名 + ": " + message`  —— **有冒号**
    // 不归一的话，健康事件的 kind 会是 `SMART_DEADLOCK_DESTROY_EXECUTED:`，
    // 与名单里的字面量永不相等——加进名单也完全不起作用，而代码路径俱在、看起来像在工作。
    // 这正是本项目反复出现的那个形状（守卫限定的量与真实情况永不相交，见 fec41c0 / CONFLICT-only）。
    String kind = rawKind.endsWith(":") ? rawKind.substring(0, rawKind.length() - 1) : rawKind;
    return switch (kind) {
      case "SMART_ROUTE_ARRIVAL",
          "SMART_STOP_LIFECYCLE",
          "SMART_RESOURCE_LIFECYCLE",
          "SMART_UNLOCK_RESERVATION_CREATED",
          "SMART_UNLOCK_AUTHORITY_REEVALUATION_REQUESTED",
          "SMART_UNLOCK_AUTHORITY_INVALID_NO_RELEASE",
          "SMART_UNLOCK_AUTHORITY_REJECTED",
          "SMART_UNLOCK_SUCCESS",
          "SMART_UNLOCK_RESERVATION_SUCCESS",
          "SMART_UNLOCK_RESERVATION_EXPIRED",
          "SMART_UNLOCK_FAILED",
          "SMART_UNLOCK_RESERVATION_NO_RELEASE_TIMEOUT",
          "SMART_UNLOCK_RELEASE_EVIDENCE_UNKNOWN",
          "SMART_UNLOCK_ROLLBACK_STARTED",
          "SMART_UNLOCK_RESERVATION_ROLLED_BACK",
          "SMART_UNLOCK_RESERVATION_ROLLBACK",
          "SMART_UNLOCK_ROLLBACK_DONE",
          "SMART_UNLOCK_APPLIED",
          // 恢复层唯一的执行证据。生产端已按 (预约, 生效优先级) 去重，只有意图变化才输出。
          // 它被普通观察预算吞掉时，"恢复层到底动没动"无法回答——实服 2026-09-13 正是如此。
          "SMART_UNLOCK_PRIORITY_INTENT_APPLIED",
          // 周期性**状态**快照（不是状态变化）。它存在的唯一理由就是"事件流推不出当前状态"，
          // 被预算吞掉就等于没加。规模由车队规模与周期节拍决定，不随 tick 放大：
          // 生产端只为"已被挡住超过 10 秒"的车输出，实服约 27 行/分钟。
          "SMART_BLOCKING_SNAPSHOT",
          // 自持单线续行被拒的**内层**原因。四个互不相同的成因此前被外层标签压成同一个字符串，
          // 而承载它的 SMART_SELF_OWNED_CONTINUATION_* 走 SignalComputationTrace.Builder——
          // 那里以信号灯色为判据（PROCEED 一律不输出），实服全场一条都没有。
          // 生产端用 emitRaw，自带按整行内容去重：内层原因不变就不会重复输出。
          "SMART_SELF_OWNED_CONTINUATION_REJECTED",
          // 只因放宽才进入 wait-for graph 的排队位边（持有者自己也被挡住）。
          // 它是"死锁图里少的那条边"这个修复的**唯一**生效证据：被预算吞掉，就分不清
          // 下一轮吞吐的改善是不是由它带来的。规模受限于同时卡住的车对数，不随 tick 放大。
          "SMART_DISPATCH_INPUT_EDGE_QUEUE_POSITION_ADMITTED",
          // 物理联锁覆盖是否真的可用——「车体实际压住哪些区间」这条证据链的**总闸**。
          // 它为假时 livePhysicalEdgeCoverage 一律返回 incomplete，于是任何以实测覆盖为放行条件的
          // 机制（尾部保护释放 / Phase 4）都会 fail-closed 到一个都不放。
          // 而它有两条构建路径：完整图构建索引可用；从持久化快照重建**按设计索引必然为空**，
          // 正常重启的服务器走的正是后者。丢了这一行，就可能在一个结构性为空的证据源上实现机制，
          // 得到代码路径俱在、trace 照常输出、却从不触发的结果——本项目已栽过三次的形状。
          // 每次图激活至多一行，不随 tick 放大。
          "SMART_INTERLOCKING_COVERAGE",
          // 物理进展判据（`a404912`）唯一的生效证据：原判据要回滚、而车体方块证明车动了。
          // 上一轮漏了它，结果 no-physical-progress 不降反升却无法归因——是判据无效，
          // 还是 fail-closed 空转，两种情况要采取的下一步完全相反。
          // 体量受限于同时在途的预约数（实服每轮 225 个），不随 tick 放大。
          "SMART_UNLOCK_PHYSICAL_PROGRESS_SAVED",
          // 「已在区内、要续行、但证不出会离开」的**依据**。结论（ALREADY_INSIDE_CONTINUE_
          // MISSING_EXIT_PROOF）一直看得见，依据却从来没进过日志——承载它的 trace 走
          // SignalComputationTrace.emit，那里以信号灯色为门。实服第十二轮 0 条、被预算丢弃也是 0 条。
          // 代价：`SURC-WS-LC-7203` 整轮 74 分钟停在这条上、到站 0 次，而无从判断为什么。
          // 生产端用 emitRaw，自带按整行内容去重：字段不变就不重复输出。
          "SMART_ENTRY_LOOKAHEAD_BLOCKED",
          // 下面这组是"环终于闭合了没有、闭合之后做了什么"的完整链条。
          //
          // 为什么必须必留：实服第十轮丢弃率 **91%**（输出 49790 行、丢弃 509047 行）。
          // 在这个丢弃率下，不在名单里的事件出不出得来基本是抛硬币——修好之后第一次检测到环，
          // 那一行有九成概率被吞掉，于是"到底修好没有"根本答不出。
          //
          // 体量为什么不必担心：整条链都挂在 planner 的 trace 批次上，而批次由上游
          // plan-unchanged 节流（同一 throttleKey 整批不输出）。实服实测
          // `SMART_WAIT_FOR_GRAPH` 74 分钟只发射 **30 批**（且丢弃 0），
          // 所以这几条的体量上界就是 30 批 × 每批个位数，合计百行量级，占日志 0.3% 以下。
          //
          // 反面教材就在隔壁：`SMART_DEADLOCK_DESTROY_ELIGIBILITY` 残留只有 128 行，
          // 看着很便宜，实际体量是 **3076 行 / 74 分钟 ≈ 41 行/分钟**（丢弃 2948 + 残留 128），
          // 加进来要给日志增重 6%。它是"为什么没资格销毁"，而实服 `destroyEnabled=false`
          // 销毁根本没开——**故意不加**。判体量要用「丢弃 + 残留」，残留不是体量。
          "SMART_DISPATCH_CYCLE_DETECTED",
          "SMART_DISPATCH_PLAN_SELECTED",
          "SMART_NO_SAME_DIRECTION_UNLOCK_PLAN",
          "SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED",
          // **销毁列车**：不可逆动作的记录绝不允许被预算丢掉。实服 destroyEnabled=false，
          // 所以当前体量为 0，纯属"万一哪天开启"的保险——尤其是在放宽了排队位边进图条件之后，
          // 万一放宽造出假环并据此销毁了车，这一行是唯一的证据。
          "SMART_DEADLOCK_DESTROY_EXECUTED",
          // 提权被空耗的证据；每个预约至多一次。
          "SMART_UNLOCK_NO_PHYSICAL_PROGRESS",
          // 队列仲裁结论：生产端已按 (资源, 请求方) 的结论签名去重，只有赢家、决定、原因或位次变化才输出。
          // 它是判断"前方无车却进不去"的唯一直接证据——被普通观察预算吞掉时，长时间阻塞完全无法归因。
          "SMART_PENDING_WINNER_ARBITRATION",
          // 优先级是队列仲裁的输入之一；生产端已按 (列车, 结果签名) 去重，只有解析结果变化才输出。
          // 它被预算吞掉时，无法判断一次阻塞到底来自优先级还是来自入队先后。
          "SMART_PRIORITY_RESOLVED" -> true;
      default -> false;
    };
  }

  private static String stableSignature(String message) {
    StringBuilder signature = new StringBuilder();
    for (String token : message.trim().split("\\s+")) {
      int equalsIndex = token.indexOf('=');
      if (equalsIndex < 1) {
        appendToken(signature, token);
        continue;
      }
      String fieldName = token.substring(0, equalsIndex).toLowerCase(Locale.ROOT);
      if (!isVolatileField(fieldName)) {
        appendToken(signature, token);
      }
    }
    return signature.toString();
  }

  /**
   * 提取稳定、有限且可供运维追溯的诊断类别。
   *
   * <p>只保留 trace 名与可选 {@code reason}；列车名、资源、tick 与计时年龄不能让同一诊断在摘要中无限分裂。
   */
  private static String diagnosticCategory(String message) {
    String[] tokens = message == null ? new String[0] : message.trim().split("\\s+");
    if (tokens.length == 0 || tokens[0].isBlank()) {
      return "UNKNOWN_DIAGNOSTIC";
    }
    String kind = tokens[0];
    for (String token : tokens) {
      if (token.startsWith("reason=") && token.length() > "reason=".length()) {
        return kind + "(" + token + ")";
      }
    }
    return kind;
  }

  private static void appendToken(StringBuilder signature, String token) {
    if (signature.length() > 0) {
      signature.append(' ');
    }
    signature.append(token);
  }

  private static boolean isVolatileField(String fieldName) {
    return fieldName.equals("sequence")
        || fieldName.equals("tick")
        || fieldName.equals("sampletick")
        || fieldName.equals("requestid")
        || fieldName.equals("agems")
        || fieldName.endsWith("version");
  }

  private record GateDecision(boolean emitObservation, String suppressionSummary) {
    private static final GateDecision DO_NOT_EMIT = new GateDecision(false, null);
  }
}
