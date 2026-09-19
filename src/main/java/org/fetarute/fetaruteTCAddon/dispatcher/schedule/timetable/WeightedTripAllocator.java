package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * 把 weight 解释成<b>目标服务比例</b>并分配班次。
 *
 * <p>weight 是 objective，不是 lottery。{@code WS=5, MT=3, DS=2} 的含义是"长期应收敛到 50/30/20"， 不是"每次发车各按
 * 50%/30%/20% 抽一次"。抽签的问题不在期望值，而在方差：它允许连开五班 WS 再连开三班 DS， 而那在运营上是不可接受的——乘客感知的是间隔，不是长期期望。
 *
 * <p>因此这里用 <b>smooth weighted round-robin</b>（SWRR，即 nginx 的平滑加权轮询）：
 *
 * <pre>
 *   每一轮：current[i] += weight[i]
 *           选出 current 最大者 k
 *           current[k] -= totalWeight
 * </pre>
 *
 * 它是确定性的、无随机源的，并且同时满足两件事：长期比例精确收敛到权重比；短窗口内不出现同一条线连续霸占。 对 5:3:2 它给出的顺序是 A B A C A B A B C
 * A——而不是概率上"什么都可能发生"。
 *
 * <p>暂时不可安排的 route 不会丢掉它应得的份额：SWRR 的 {@code current} 就是天然的 deficit 账本。 跳过一次不扣减 {@code
 * current}，于是它的信用持续累积，约束解除后会连续被选中直到追平。 这一点由 {@link #allocate} 的 {@code feasible} 判定实现，而不是靠外部再记一套账。
 */
public final class WeightedTripAllocator {

  private WeightedTripAllocator() {}

  /**
   * 按权重分配 {@code slots} 个班次。
   *
   * @param candidates 候选，顺序即确定性 tie-break 顺序（调用方应按稳定 key 排序）
   * @param slots 要分配的班次总数
   * @param feasible 第 slot 个时隙上，第 i 个候选（已分到 assigned 班）能否再接一班；恒 true 表示无约束
   * @return 每个成功分配的时隙与它选中的候选，按时隙升序；不可行的时隙不出现在结果里
   */
  public static List<Allocation> allocate(
      List<Candidate> candidates, int slots, FeasibilityCheck feasible) {
    if (candidates == null || candidates.isEmpty() || slots <= 0) {
      return List.of();
    }
    long totalWeight = 0L;
    for (Candidate candidate : candidates) {
      totalWeight += Math.max(0, candidate.weight());
    }
    if (totalWeight <= 0L) {
      return List.of();
    }
    FeasibilityCheck check = feasible == null ? (slot, index, assigned) -> true : feasible;

    long[] current = new long[candidates.size()];
    int[] assigned = new int[candidates.size()];
    List<Allocation> out = new ArrayList<>(slots);

    for (int slot = 0; slot < slots; slot++) {
      int chosen = -1;
      long best = Long.MIN_VALUE;
      // 先整体累加信用，再挑最大者：跳过不可安排的候选时**不**清它的信用，
      // 于是它的 deficit 继续长，约束恢复后自然追赶。
      for (int i = 0; i < candidates.size(); i++) {
        int weight = Math.max(0, candidates.get(i).weight());
        if (weight <= 0) {
          continue;
        }
        current[i] += weight;
      }
      for (int i = 0; i < candidates.size(); i++) {
        if (Math.max(0, candidates.get(i).weight()) <= 0) {
          continue;
        }
        if (!check.canAssign(slot, i, assigned[i])) {
          continue;
        }
        // 严格大于：并列时取下标更小者，而候选顺序由调用方按稳定 key 决定，因此 tie-break 是确定的。
        if (current[i] > best) {
          best = current[i];
          chosen = i;
        }
      }
      if (chosen < 0) {
        // 没有任何候选可安排：本轮的信用照样保留，不回滚，也不硬塞一个非法班次。
        continue;
      }
      current[chosen] -= totalWeight;
      assigned[chosen]++;
      out.add(new Allocation(slot, chosen));
    }
    return List.copyOf(out);
  }

  /**
   * 统计目标比例与实际达成比例。
   *
   * @param candidates 候选
   * @param assignment {@link #allocate} 的结果
   * @return 每个候选的目标份额与实际份额
   */
  public static List<ShareReport> report(List<Candidate> candidates, List<Allocation> assignment) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    Map<Integer, Integer> counts = new LinkedHashMap<>();
    if (assignment != null) {
      for (Allocation allocation : assignment) {
        if (allocation != null) {
          counts.merge(allocation.candidateIndex(), 1, Integer::sum);
        }
      }
    }
    long totalWeight = 0L;
    for (Candidate candidate : candidates) {
      totalWeight += Math.max(0, candidate.weight());
    }
    int totalAssigned = assignment == null ? 0 : assignment.size();
    List<ShareReport> out = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      Candidate candidate = candidates.get(i);
      int count = counts.getOrDefault(i, 0);
      double target =
          totalWeight <= 0L
              ? 0.0D
              : (double) Math.max(0, candidate.weight()) / (double) totalWeight;
      double achieved = totalAssigned <= 0 ? 0.0D : (double) count / (double) totalAssigned;
      out.add(new ShareReport(candidate.key(), candidate.weight(), count, target, achieved));
    }
    return List.copyOf(out);
  }

  /** 按稳定 key 排序候选，保证 tie-break 与输入顺序无关。 */
  public static List<Candidate> sorted(List<Candidate> candidates) {
    if (candidates == null) {
      return List.of();
    }
    return candidates.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(Candidate::key))
        .toList();
  }

  /**
   * 一个参与分配的候选。
   *
   * @param key 稳定排序键（用 route code 这类不随内存布局变化的值）
   * @param weight 目标服务比例权重，非正表示不参与
   */
  public record Candidate(String key, int weight) {
    public Candidate {
      key = key == null ? "" : key;
    }
  }

  /**
   * 分配结果的比例报告。
   *
   * @param key 候选 key
   * @param weight 配置权重
   * @param assignedTrips 实际分到的班次数
   * @param targetShare 目标份额（0..1）
   * @param achievedShare 实际份额（0..1）
   */
  public record ShareReport(
      String key, int weight, int assignedTrips, double targetShare, double achievedShare) {

    /** 目标与实际的绝对差（百分点）。 */
    public double deviationPercentPoints() {
      return Math.abs(targetShare - achievedShare) * 100.0D;
    }
  }

  /**
   * 一次分配：第 {@code slot} 个时隙由第 {@code candidateIndex} 个候选承担。
   *
   * @param slot 时隙序号（从 0 起，乘以 headway 即发车偏移）
   * @param candidateIndex 候选下标
   */
  public record Allocation(int slot, int candidateIndex) {}

  /**
   * 可行性判定。
   *
   * @param slot 第几个时隙（从 0 起）
   * @param index 候选下标
   * @param assigned 该候选已经分到的班次数
   */
  @FunctionalInterface
  public interface FeasibilityCheck {
    boolean canAssign(int slot, int index, int assigned);
  }

  /** 把只依赖候选下标的判定适配成 {@link FeasibilityCheck}。 */
  public static FeasibilityCheck byIndex(IntPredicate predicate) {
    Objects.requireNonNull(predicate, "predicate");
    return (slot, index, assigned) -> predicate.test(index);
  }

  /** 无约束。 */
  public static FeasibilityCheck unconstrained() {
    return (slot, index, assigned) -> true;
  }

  /** 便捷方法：把结果下标映射回候选 key。 */
  public static Optional<String> keyOf(List<Candidate> candidates, int index) {
    if (candidates == null || index < 0 || index >= candidates.size()) {
      return Optional.empty();
    }
    return Optional.of(candidates.get(index).key());
  }
}
