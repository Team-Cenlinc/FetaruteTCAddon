package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;

/**
 * 控车速度包络：以“本次控车命令之后列车又走过的距离”为自变量的限速上界。
 *
 * <p>调度周期（通常每秒一次）只能在周期点上算出目标速度；两次周期之间若把限速原样保持，列车就按“平一段、切一刀”的阶梯减速。包络把本周期用到的、 随距离收紧的曲线原样保存下来，由逐 tick
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
 *
 * <p>人工驾驶不受进站限速约束（停车由驾驶员自己掌握，越过停车点由驾驶员防护兜底）：进站限速另记一份（{@link #withApproachHold}）， 前方限速边记下原始输入，{@link
 * #manual} 据此给出去掉进站限速、按人工驾驶的目标速度重建制动曲线的包络。
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

  /**
   * 人工驾驶看到的目标速度与包络（见 {@link #manual}）。
   *
   * @param targetBps 目标速度（格/秒）
   * @param envelope 包络
   */
  public record ManualView(double targetBps, SpeedEnvelope envelope) {}

  /**
   * 人工驾驶要另算的部分。
   *
   * @param approach 进站限速约束（人工驾驶不受它限制）
   * @param approachBaseBps 进站限速之前的目标速度；没有进站限速时为 NaN
   * @param edges 前方限速边的原始输入
   * @param edgeCurve 前方限速边的加减速曲线
   * @param edgeConstraints 按自动运行的目标速度建出的前方限速边约束（人工驾驶时重建）
   */
  private record Manual(
      List<Constraint> approach,
      double approachBaseBps,
      List<SignalLookahead.EdgeSpeedConstraint> edges,
      SpeedCurve edgeCurve,
      List<Constraint> edgeConstraints) {

    private static final Manual NONE =
        new Manual(List.of(), Double.NaN, List.of(), null, List.of());

    Manual merge(Manual other) {
      if (other == null || other == NONE) {
        return this;
      }
      if (this == NONE) {
        return other;
      }
      double base =
          Double.isNaN(approachBaseBps)
              ? other.approachBaseBps
              : Double.isNaN(other.approachBaseBps)
                  ? approachBaseBps
                  : Math.min(approachBaseBps, other.approachBaseBps);
      List<SignalLookahead.EdgeSpeedConstraint> mergedEdges = new ArrayList<>(edges);
      mergedEdges.addAll(other.edges);
      return new Manual(
          concat(approach, other.approach),
          base,
          List.copyOf(mergedEdges),
          edgeCurve != null ? edgeCurve : other.edgeCurve,
          concat(edgeConstraints, other.edgeConstraints));
    }
  }

  private static final SpeedEnvelope EMPTY =
      new SpeedEnvelope(List.of(), List.of(), null, 0.0, Manual.NONE);

  private final List<Constraint> constraints;
  private final List<Constraint> holdConstraints;
  private final String originKey;
  private final double originBlocks;
  private final Manual manual;

  private SpeedEnvelope(
      List<Constraint> constraints,
      List<Constraint> holdConstraints,
      String originKey,
      double originBlocks,
      Manual manual) {
    this.constraints = constraints;
    this.holdConstraints = holdConstraints;
    this.originKey = originKey;
    this.originBlocks = originBlocks;
    this.manual = manual;
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
    return new SpeedEnvelope(
        append(constraints, constraint), holdConstraints, originKey, originBlocks, manual);
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
    return new SpeedEnvelope(
        append(constraints, constraint),
        append(holdConstraints, constraint),
        originKey,
        originBlocks,
        manual);
  }

  /**
   * 追加进站限速：对自动运行与 {@link #withHold} 相同；人工驾驶不受它限制（见 {@link #manual}）。
   *
   * @param baseBps 进站限速之前的目标速度
   */
  public SpeedEnvelope withApproachHold(Constraint constraint, double baseBps) {
    if (constraint == null) {
      return this;
    }
    return new SpeedEnvelope(
        append(constraints, constraint),
        append(holdConstraints, constraint),
        originKey,
        originBlocks,
        manual.merge(
            new Manual(
                List.of(constraint),
                Double.isFinite(baseBps) ? Math.max(0.0, baseBps) : Double.NaN,
                List.of(),
                null,
                List.of())));
  }

  /** 合并另一包络的全部约束（含保持约束），返回新包络。 */
  public SpeedEnvelope withAll(SpeedEnvelope other) {
    if (other == null || (other.constraints.isEmpty() && other.manual == Manual.NONE)) {
      return this;
    }
    if (constraints.isEmpty() && originKey == null && manual == Manual.NONE) {
      return other;
    }
    return new SpeedEnvelope(
        concat(constraints, other.constraints),
        concat(holdConstraints, other.holdConstraints),
        originKey,
        originBlocks,
        manual.merge(other.manual));
  }

  /**
   * 人工驾驶看的目标速度与包络：去掉进站限速，前方限速边按去掉进站限速后的目标速度重建制动曲线；其余约束（信号、移动授权）照旧。 没有进站限速时原样返回。
   *
   * <p>停车点前的减速由驾驶员自己掌握：停车信号的授权末端与站台越站阈值另有防护曲线，不靠这里。
   *
   * @param targetBps 调度层给出的目标速度（已含进站限速）
   */
  public ManualView manual(double targetBps) {
    if (manual.approach().isEmpty()) {
      return new ManualView(targetBps, this);
    }
    double cruise =
        Double.isFinite(manual.approachBaseBps())
            ? Math.max(targetBps, manual.approachBaseBps())
            : targetBps;
    Set<Constraint> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
    dropped.addAll(manual.approach());
    dropped.addAll(manual.edgeConstraints());
    SpeedEnvelope rebuilt =
        new SpeedEnvelope(
            without(constraints, dropped),
            without(holdConstraints, dropped),
            originKey,
            originBlocks,
            Manual.NONE);
    List<Constraint> rebuiltEdges = new ArrayList<>(manual.edges().size());
    for (SignalLookahead.EdgeSpeedConstraint edge : manual.edges()) {
      Constraint braking =
          curveBraking(edge.distanceBlocks(), edge.speedLimitBps(), cruise, manual.edgeCurve());
      if (braking != null) {
        rebuilt = rebuilt.with(braking);
        rebuiltEdges.add(braking);
      }
    }
    // 前方限速边的原始输入留着（驾驶员显示前方限速用）；进站限速已去掉，再调 manual 原样返回。
    rebuilt =
        new SpeedEnvelope(
            rebuilt.constraints,
            rebuilt.holdConstraints,
            originKey,
            originBlocks,
            new Manual(
                List.of(),
                Double.NaN,
                manual.edges(),
                manual.edgeCurve(),
                List.copyOf(rebuiltEdges)));
    double target = Math.min(cruise, rebuilt.limitBps(0.0));
    return new ManualView(Math.max(0.0, target), rebuilt);
  }

  /**
   * 前方限速边（距离从取样时的车头量起，限速已考虑覆盖值）：给驾驶员显示前方限速与建议速度用，不参与控车。
   *
   * @return 没有记下限速边时为空表
   */
  public List<SignalLookahead.EdgeSpeedConstraint> edgeLimits() {
    return manual.edges();
  }

  private static List<Constraint> without(List<Constraint> list, Set<Constraint> dropped) {
    List<Constraint> kept = new ArrayList<>(list.size());
    for (Constraint constraint : list) {
      if (!dropped.contains(constraint)) {
        kept.add(constraint);
      }
    }
    return List.copyOf(kept);
  }

  /**
   * 记下本包络的取样位置：车头在图节点 {@code nodeKey} 之后 {@code headBlocks} 格处。
   *
   * <p>逐 tick 斜坡据此把“走过的距离”换回车头位置（{@code SpeedLimitRamp#headProgressBlocks}）：下一周期仍在同一节点之后时，
   * 调度层用这个按实际里程推算的位置，而不是按直线距离插值的估计——弯道上两者不一致，每周期重新取样就会让限速忽高忽低。
   *
   * @param nodeKey 车头之前最近经过的图节点；为空时不记
   * @param headBlocks 车头已驶过该节点的距离
   */
  public SpeedEnvelope withOrigin(String nodeKey, double headBlocks) {
    if (nodeKey == null || nodeKey.isBlank() || !Double.isFinite(headBlocks)) {
      return this;
    }
    return new SpeedEnvelope(
        constraints, holdConstraints, nodeKey, Math.max(0.0, headBlocks), manual);
  }

  /** 取样时车头之前最近经过的图节点；没有记下时为空。 */
  public Optional<String> originKey() {
    return Optional.ofNullable(originKey);
  }

  /** 取样时车头已驶过 {@link #originKey()} 的距离。 */
  public double originBlocks() {
    return originBlocks;
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
   * 按编表运行曲线同一条 S 形制动曲线的约束：前方 {@code distanceBlocks} 处速度须不高于 {@code endSpeedBps}。
   *
   * <p>取值即一条长 {@code distanceBlocks}、限速 {@code cruiseBps}、终点速度 {@code endSpeedBps} 的单边速度天花板（{@link
   * SpeedCeiling}）；越过约束点后恒为 {@code endSpeedBps}。天花板一次算好，逐 tick 求值只是查表。
   *
   * @param distanceBlocks 从车头到约束点的距离
   * @param endSpeedBps 约束点处的限速
   * @param cruiseBps 制动开始前的巡航速度
   * @param curve 加减速曲线
   * @return 约束；约束点限速不低于巡航速度（不收紧）时返回 {@code null}，参数不合法时返回只给出 {@code endSpeedBps} 的保守约束
   */
  public static Constraint curveBraking(
      double distanceBlocks, double endSpeedBps, double cruiseBps, SpeedCurve curve) {
    if (!Double.isFinite(endSpeedBps) || endSpeedBps < 0.0) {
      return null;
    }
    if (curve == null || !Double.isFinite(distanceBlocks) || !Double.isFinite(cruiseBps)) {
      return traveled -> endSpeedBps;
    }
    if (!(cruiseBps > endSpeedBps)) {
      return null;
    }
    if (!(distanceBlocks > 0.0)) {
      return traveled -> endSpeedBps;
    }
    double distance = distanceBlocks;
    SpeedCeiling ceiling =
        SpeedCeiling.of(
            new double[] {distance}, new double[] {cruiseBps}, List.of(), endSpeedBps, curve);
    return traveled -> ceiling.limitBps(traveled);
  }

  /**
   * 前方限速边的制动约束（S 形制动曲线，见 {@link #curveBraking}）。
   *
   * @param constraints 前瞻给出的限速边（距离须已从车头量起）
   * @param curve 加减速曲线
   * @param cruiseBps 制动开始前的巡航速度（本周期目标速度）
   */
  public static SpeedEnvelope edgeSpeedConstraints(
      List<SignalLookahead.EdgeSpeedConstraint> constraints, SpeedCurve curve, double cruiseBps) {
    if (constraints == null || constraints.isEmpty()) {
      return EMPTY;
    }
    List<Constraint> built = new ArrayList<>(constraints.size());
    for (SignalLookahead.EdgeSpeedConstraint constraint : constraints) {
      Objects.requireNonNull(constraint, "constraint");
      Constraint braking =
          curveBraking(constraint.distanceBlocks(), constraint.speedLimitBps(), cruiseBps, curve);
      if (braking != null) {
        built.add(braking);
      }
    }
    // 记下原始输入：人工驾驶不受进站限速时目标速度更高，要按它重建制动曲线（见 manual）。
    return new SpeedEnvelope(
        List.copyOf(built),
        List.of(),
        null,
        0.0,
        new Manual(List.of(), Double.NaN, List.copyOf(constraints), curve, List.copyOf(built)));
  }
}
