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
 * 还共享有界窗口预算，避免多列车稳定运行时仍按列车数放大控制台负担。只有执行器审计会逐次原样透传，以保留每一轮 动作复核的证据；状态变化仍会立即输出，直到观察预算耗尽。
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
    this(
        output,
        DEFAULT_REPEAT_WINDOW,
        DEFAULT_MAX_SIGNATURES,
        DEFAULT_OBSERVATION_BUDGET_WINDOW,
        DEFAULT_MAX_OBSERVATION_EMISSIONS,
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
    if (isExecutorAudit(message)) {
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
   * 执行器会在每一次请求时重新核对授权条件；不得把这些审计记录与观察 trace 一并吞掉。
   *
   * @param message 原始诊断行
   * @return 是否必须逐次保留的执行器审计
   */
  private static boolean isExecutorAudit(String message) {
    return message.startsWith("SMART_DISPATCH_EXECUTOR_");
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
        || fieldName.endsWith("version");
  }

  private record GateDecision(boolean emitObservation, String suppressionSummary) {
    private static final GateDecision DO_NOT_EMIT = new GateDecision(false, null);
  }
}
