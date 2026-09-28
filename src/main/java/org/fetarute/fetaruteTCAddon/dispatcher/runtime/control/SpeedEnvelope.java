package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 控车速度包络：以“本次控车命令之后列车又走过的距离”为自变量的限速上界。
 *
 * <p>调度周期（实服每秒一次）只能在周期点上算出目标速度；两次周期之间若把限速原样保持，列车就按“平一段、切一刀”的阶梯减速。包络把本周期用到的、 随距离收紧的曲线原样保存下来，由逐 tick
 * 限速斜坡按实际行驶距离求值，让限速沿同一条曲线连续下降。
 *
 * <p>约束的距离一律<b>从车头量起</b>（本周期取样时刻）。从上一个图节点量起的距离在两节点之间不会缩短，拿来逐 tick 推算会在下一周期被重置抬高，
 * 反而造成“压过头再放开”。这类约束不能放进包络，只能由周期命令值封顶。
 *
 * <p>包络只描述随距离变化的约束，并且只会让限速更低：调用方始终再用周期命令值封顶，所以包络缺项只会退回旧的逐周期保持，不会放宽任何限制。
 *
 * <p>其中一部分约束另记为“保持约束”（{@link #withHold}）：没有速度上下文的控车调用（过节点时的推进放行）也不得越过它们。 只有随列车前进一定仍然有效、
 * 且只在真正收紧时才给出有限值的约束才能这样登记——例如进站限速（列车会在该站停下）。前方限速边不行：它们越过该边后即失效，
 * 包络却无法得知边的终点，拿它们挡推进放行会把驶入快速区段的车扣在旧边的限速上。
 */
public final class SpeedEnvelope {

  /** 单条约束：给定本周期后又走过的距离（blocks），返回允许的最高速度（blocks/s）。 */
  @FunctionalInterface
  public interface Constraint {
    /**
     * @param traveledBlocks 本周期取样后列车又走过的距离，非负
     * @return 允许的最高速度（blocks/s）；非有限值表示本约束此处不设限或无法求值，调用方会忽略它
     */
    double limitBps(double traveledBlocks);
  }

  private static final SpeedEnvelope EMPTY = new SpeedEnvelope(List.of(), List.of());

  private final List<Constraint> constraints;
  private final List<Constraint> holdConstraints;

  private SpeedEnvelope(List<Constraint> constraints, List<Constraint> holdConstraints) {
    this.constraints = constraints;
    this.holdConstraints = holdConstraints;
  }

  /** 不含任何随距离变化约束的包络。 */
  public static SpeedEnvelope empty() {
    return EMPTY;
  }

  /** 追加一条约束，返回新包络；{@code null} 视为无约束。 */
  public SpeedEnvelope with(Constraint constraint) {
    if (constraint == null) {
      return this;
    }
    return new SpeedEnvelope(append(constraints, constraint), holdConstraints);
  }

  /**
   * 追加一条保持约束：既参与逐 tick 下调，也挡住没有速度上下文的控车调用。
   *
   * <p>约束在不收紧时必须返回非有限值（{@link Double#POSITIVE_INFINITY}），否则会把它的“不设限”值当成上限。
   */
  public SpeedEnvelope withHold(Constraint constraint) {
    if (constraint == null) {
      return this;
    }
    return new SpeedEnvelope(append(constraints, constraint), append(holdConstraints, constraint));
  }

  /** 合并另一包络的全部约束（含保持约束），返回新包络。 */
  public SpeedEnvelope withAll(SpeedEnvelope other) {
    if (other == null || other.constraints.isEmpty()) {
      return this;
    }
    if (constraints.isEmpty()) {
      return other;
    }
    return new SpeedEnvelope(
        concat(constraints, other.constraints), concat(holdConstraints, other.holdConstraints));
  }

  /** 是否没有任何约束。 */
  public boolean isEmpty() {
    return constraints.isEmpty();
  }

  /** 约束条数（诊断与测试用）。 */
  public int size() {
    return constraints.size();
  }

  /**
   * 求走过 {@code traveledBlocks} 后允许的最高速度。
   *
   * @return 各约束的最小值；没有可求值的约束时返回 {@link Double#POSITIVE_INFINITY}
   */
  public double limitBps(double traveledBlocks) {
    return minimum(constraints, traveledBlocks);
  }

  /**
   * 求走过 {@code traveledBlocks} 后保持约束给出的上限。
   *
   * @return 保持约束的最小值；没有收紧的保持约束时返回 {@link Double#POSITIVE_INFINITY}
   */
  public double holdLimitBps(double traveledBlocks) {
    return minimum(holdConstraints, traveledBlocks);
  }

  private static double minimum(List<Constraint> list, double traveledBlocks) {
    double traveled =
        Double.isFinite(traveledBlocks) && traveledBlocks > 0.0 ? traveledBlocks : 0.0;
    double min = Double.POSITIVE_INFINITY;
    for (Constraint constraint : list) {
      double limit = constraint.limitBps(traveled);
      if (Double.isFinite(limit)) {
        min = Math.min(min, Math.max(0.0, limit));
      }
    }
    return min;
  }

  private static List<Constraint> append(List<Constraint> list, Constraint constraint) {
    List<Constraint> merged = new ArrayList<>(list.size() + 1);
    merged.addAll(list);
    merged.add(constraint);
    return List.copyOf(merged);
  }

  private static List<Constraint> concat(List<Constraint> first, List<Constraint> second) {
    if (second.isEmpty()) {
      return first;
    }
    if (first.isEmpty()) {
      return second;
    }
    List<Constraint> merged = new ArrayList<>(first.size() + second.size());
    merged.addAll(first);
    merged.addAll(second);
    return List.copyOf(merged);
  }

  /**
   * 物理制动约束：在前方 {@code distanceBlocks} 处速度须不高于 {@code endSpeedBps}。
   *
   * <p>v(s) = √(v_end² + 2·a·max(0, d − s))。越过约束点后恒为 {@code endSpeedBps}。
   *
   * @param distanceBlocks 从车头到约束点的距离
   * @param endSpeedBps 约束点处的限速
   * @param decelBps2 制动减速度（已乘速度曲线系数）
   * @return 约束；参数不合法时返回只给出 {@code endSpeedBps} 的保守约束，{@code endSpeedBps} 本身不合法时返回 {@code null}
   */
  public static Constraint braking(double distanceBlocks, double endSpeedBps, double decelBps2) {
    if (!Double.isFinite(endSpeedBps) || endSpeedBps < 0.0) {
      return null;
    }
    if (!Double.isFinite(distanceBlocks) || !Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      return traveled -> endSpeedBps;
    }
    double distance = Math.max(0.0, distanceBlocks);
    double endSquared = endSpeedBps * endSpeedBps;
    return traveled -> Math.sqrt(endSquared + 2.0 * decelBps2 * Math.max(0.0, distance - traveled));
  }

  /**
   * 前方限速边的制动约束。
   *
   * @param constraints 前瞻给出的限速边（距离须已从车头量起）
   * @param decelBps2 制动减速度
   */
  public static SpeedEnvelope edgeSpeedConstraints(
      List<SignalLookahead.EdgeSpeedConstraint> constraints, double decelBps2) {
    if (constraints == null || constraints.isEmpty()) {
      return EMPTY;
    }
    SpeedEnvelope envelope = EMPTY;
    for (SignalLookahead.EdgeSpeedConstraint constraint : constraints) {
      Objects.requireNonNull(constraint, "constraint");
      envelope =
          envelope.with(
              braking(constraint.distanceBlocks(), constraint.speedLimitBps(), decelBps2));
    }
    return envelope;
  }
}
