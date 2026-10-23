package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 按运行时控车方式估算站到站走行：从静止起步，按车型加减速的 S 形曲线（{@link SpeedCurve}）提速与制动，按边限速行驶，到车站沿舒适减速度曲线刹停。
 *
 * <p>三处取值都和运行时是同一个事实源（{@link Settings#fromConfig}）：
 *
 * <ul>
 *   <li>边限速：调用方给的 {@link EdgeSpeedResolver}（编表接永久限速覆盖，ETA 接运行时的有效限速），缺限速时取默认速度；
 *   <li>进站：车库与区间停车点按 {@code runtime.approach-*} 的窗口与限速，触发节点按 {@link StopApproach}；车站不限速；
 *   <li>加减速：车种配置（{@code train.types}），减速度乘自动运行的舒适比例（{@code train.ato-brake-fraction}）。
 * </ul>
 *
 * <p>编表与 ETA 用的是同一个模型：表定时分与"列车现在离站还有多久"出自同一条运行曲线，晚点才是可比的数。
 *
 * <p>车站停车按舒适减速度刹到站牌节点停稳：运行时列车停在站牌之后（车中心对牌子，或车头对停车位置标），每站比表定晚停约 “站牌到停车点的距离 ÷ 线路速度”（车中心对牌子时即半个车长 ÷
 * 线路速度：短车不到 1 秒，8 节长车约 3～4 秒）。编表不为此读车长与停车位置标， 同一份输入永远给出同一张表；长车多出的这几秒算作晚点，由停站压缩与终点消化。停稳之后到开门的时间记在
 * {@link #stationStopOverheadSeconds()}。 车库按进库限速进场；区间停车点按进站限速到点、由调度层刹停，编表按刹到 0 计。
 */
public final class RunCurveModel implements RunTimeModel {

  private final Settings settings;
  private final EdgeSpeedResolver edgeSpeeds;

  /**
   * @param settings 动力学与进站参数
   * @param edgeSpeeds 边有效限速；为空时用边的基础限速，基础限速缺失再用 {@link Settings#fallbackSpeedBps()}
   */
  public RunCurveModel(Settings settings, EdgeSpeedResolver edgeSpeeds) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.edgeSpeeds = edgeSpeeds;
  }

  @Override
  public Optional<double[]> nodeTimes(RailGraph graph, Run run) {
    Objects.requireNonNull(run, "run");
    Optional<CurveInputs> inputs = curveInputs(graph, run, null, null);
    if (inputs.isEmpty()) {
      return Optional.empty();
    }
    CurveInputs in = inputs.get();
    return Optional.of(
        RunCurve.nodeTimes(
            in.lengths(),
            in.speeds(),
            in.caps(),
            run.entrySpeedBps(),
            in.exitSpeed(),
            settings.motion()));
  }

  @Override
  public Optional<Trajectory> trajectory(RailGraph graph, Run run) {
    Objects.requireNonNull(run, "run");
    Optional<CurveInputs> inputs = curveInputs(graph, run, null, null);
    if (inputs.isEmpty()) {
      return Optional.empty();
    }
    CurveInputs in = inputs.get();
    SpeedCeiling ceiling =
        SpeedCeiling.cached(
            in.lengths(), in.speeds(), in.caps(), in.exitSpeed(), settings.motion());
    RunCurve.Motion motion = RunCurve.profile(ceiling, run.entrySpeedBps(), settings.motion());
    int samples = motion.speed().length;
    if (samples < 2) {
      return Optional.empty();
    }
    double[] distance = new double[samples];
    for (int i = 0; i < samples; i++) {
      distance[i] = i * SpeedCeiling.STEP_BLOCKS;
    }
    return Optional.of(new Trajectory(distance, motion.seconds(), motion.speed()));
  }

  /**
   * 这段走行的速度天花板，沿途各边限速与进站限速区和 {@link #nodeTimes} 完全相同，只有终点按控车的 {@code end} 取。
   *
   * <p>运行时控车用它跟随编表同一条制动曲线：输入只在这里组装一次，控车与编表不会各拼各的。
   *
   * @param curve 加减速曲线（控车取本车车种的自动运行曲线）；为 {@code null} 时只取逐点限速，不提前制动
   * @param end 控车在停车点的终点速度
   */
  public Optional<SpeedCeiling> ceiling(
      RailGraph graph, Run run, SpeedCurve curve, ControlEnd end) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(end, "end");
    return curveInputs(graph, run, end, curve)
        .map(
            in -> SpeedCeiling.cached(in.lengths(), in.speeds(), in.caps(), in.exitSpeed(), curve));
  }

  /**
   * 控车在停车点（路径终点）的终点速度。编表与到站预测按停车点停稳计，控车有两处不同：
   *
   * <ul>
   *   <li>车站：列车停在站牌之后 {@code stationTailBlocks} 格处（车中心对牌子时约半个车长），过站牌时还在刹车途中： 路径延长到这个停车点、刹到
   *       0，一段刹车，压上站牌后由 AutoStation 沿同一条曲线接着刹完；停车点离站牌太近、站牌处的速度低于保底速度时， 终点速度取保底速度（见 {@link
   *       #stationControlExitBps}），车一定压得上站牌、触发停站。关闭速度曲线（{@code curve} 为空）时没有刹车曲线可接，照旧按区间停车点的进站限速进站；
   *   <li>区间停车点（{@code holdWaypointApproach}）：按进站限速到点，由调度层在节点处停车——天花板若在节点降到 0，车会停在牌子前、触发不到停车。
   * </ul>
   *
   * @param holdWaypointApproach 区间停车点按进站限速到点
   * @param stationTailBlocks 车站停车点在站牌之后的距离（格），非正或非有限按 0
   */
  public record ControlEnd(boolean holdWaypointApproach, double stationTailBlocks) {
    public ControlEnd {
      stationTailBlocks =
          Double.isFinite(stationTailBlocks) ? Math.max(0.0, stationTailBlocks) : 0.0;
    }
  }

  /** 压上站牌时的最低速度（格/秒）：车速若在站牌前就降到 0，车停在牌子前、触发不到停站。 */
  public static final double STATION_TRIGGER_FLOOR_BPS = 1.5;

  /** 压上站牌时第一节车厢中心离站牌节点还有约半格（车厢一进站牌所在的轨道就触发）。 */
  static final double STATION_TRIGGER_LEAD_BLOCKS = 0.5;

  /**
   * 控车在车站站牌节点处的终点速度：停车点在站牌之后 {@code tailBlocks} 格，取这段距离沿 {@code curve} 刹停的速度；不低于保底速度，
   * 但保底速度也不超过压上站牌后余下的距离（{@code tailBlocks} + 约半格）里刹得住的速度——单节车停车点就在站牌处，1.5 格/秒在半格里刹不住，最后一拍会骤停。
   *
   * @param curve 控车的加减速曲线
   * @param lastSpeedBps 末段边的限速（制动起点）
   * @param tailBlocks 停车点在站牌之后的距离（格）
   */
  public static double stationControlExitBps(
      SpeedCurve curve, double lastSpeedBps, double tailBlocks) {
    double tail = Math.max(0.0, tailBlocks);
    double floor =
        Math.min(
            STATION_TRIGGER_FLOOR_BPS,
            SpeedCeiling.brakingLimitBps(
                curve, lastSpeedBps, 0.0, tail + STATION_TRIGGER_LEAD_BLOCKS));
    return Math.max(floor, SpeedCeiling.brakingLimitBps(curve, lastSpeedBps, 0.0, tail));
  }

  /**
   * 运行曲线的输入：各边长度与限速、进站限速区、终点速度。
   *
   * @param end 控车的终点；编表与到站预测为 {@code null}（按停车点停稳）
   * @param curve 控车的加减速曲线（算车站终点速度用）
   * @return 有边长度不为正时为空
   */
  private Optional<CurveInputs> curveInputs(
      RailGraph graph, Run run, ControlEnd end, SpeedCurve curve) {
    List<RailEdge> edges = run.edges();
    double[] lengths = new double[edges.size()];
    double[] speeds = new double[edges.size()];
    for (int k = 0; k < edges.size(); k++) {
      RailEdge edge = edges.get(k);
      if (edge == null || edge.lengthBlocks() <= 0) {
        return Optional.empty();
      }
      double length = edge.lengthBlocks();
      if (k == 0 && run.firstEdgeRemainingBlocks().isPresent()) {
        length = Math.min(length, run.firstEdgeRemainingBlocks().getAsDouble());
      }
      lengths[k] = SpeedCeiling.quantize(length);
      speeds[k] = edgeSpeed(graph, edge);
    }
    List<SpeedCeiling.Cap> caps = List.of();
    double exitSpeed = Double.POSITIVE_INFINITY;
    if (run.stopsAtEnd() && !edges.isEmpty()) {
      StopApproach.Target target =
          StopApproach.targetOf(graph, run.nodes().get(run.nodes().size() - 1));
      boolean legacyStation =
          end != null && curve == null && target.kind() == StopApproach.Kind.STATION;
      double capSpeed =
          settings.approach().speedFor(legacyStation ? StopApproach.Kind.WAYPOINT : target.kind());
      if (capSpeed > 0.0) {
        caps = approachCaps(graph, run.nodes(), lengths, target, capSpeed);
        exitSpeed = capSpeed;
      }
      switch (target.kind()) {
        case STATION -> {
          if (end == null) {
            exitSpeed = 0.0;
          } else if (curve != null) {
            double lastSpeed = speeds[speeds.length - 1];
            double tail = end.stationTailBlocks();
            if (SpeedCeiling.brakingLimitBps(curve, lastSpeed, 0.0, tail)
                >= STATION_TRIGGER_FLOOR_BPS) {
              // 一段刹车：路径延长到站牌之后的停车点、刹到 0。站牌处不是刹车的终点，若以站牌处的速度为终点，
              // 曲线会在站牌前收尾、压上站牌后再从头起一段刹车。
              lengths = java.util.Arrays.copyOf(lengths, lengths.length + 1);
              lengths[lengths.length - 1] = SpeedCeiling.quantize(tail);
              speeds = java.util.Arrays.copyOf(speeds, speeds.length + 1);
              speeds[speeds.length - 1] = lastSpeed;
              exitSpeed = 0.0;
            } else {
              // 停车点离站牌太近：按保底速度到站牌（车一定压得上站牌），余下由 AutoStation 刹完。
              exitSpeed = Math.min(exitSpeed, stationControlExitBps(curve, lastSpeed, tail));
            }
          }
        }
        case WAYPOINT -> {
          if (end == null || !end.holdWaypointApproach()) {
            exitSpeed = 0.0;
          }
        }
        case DEPOT -> {}
      }
    }
    return Optional.of(new CurveInputs(lengths, speeds, caps, exitSpeed));
  }

  /** 运行曲线的一组输入。 */
  private record CurveInputs(
      double[] lengths, double[] speeds, List<SpeedCeiling.Cap> caps, double exitSpeed) {}

  @Override
  public int stationStopOverheadSeconds() {
    return settings.stationStopOverheadSeconds();
  }

  /** 出库后第一站停稳到开门比中途站多等的秒数：AutoStation 给车门附件与动画预热（它的首站开门延迟按本值算出，两边是同一个数）。 */
  public static final int FIRST_STATION_DOOR_EXTRA_SECONDS = 2;

  @Override
  public int firstStationStopExtraSeconds() {
    return FIRST_STATION_DOOR_EXTRA_SECONDS;
  }

  /** 生效的参数，报告与参数面板用它说明表定时分是按什么算的。 */
  public Settings settings() {
    return settings;
  }

  /** 进站限速区（{@link StopApproach#zones}，与运行时控车同一个判据）按进站限速落成运行曲线的限速区。 */
  private List<SpeedCeiling.Cap> approachCaps(
      RailGraph graph,
      List<NodeId> nodes,
      double[] lengths,
      StopApproach.Target target,
      double capSpeed) {
    double[] position = new double[nodes.size()];
    for (int k = 0; k < lengths.length; k++) {
      position[k + 1] = position[k] + lengths[k];
    }
    boolean[] triggers = new boolean[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      triggers[i] = target.triggeredBy(graph, nodes.get(i));
    }
    List<SpeedCeiling.Cap> caps = new ArrayList<>();
    for (StopApproach.Zone zone : StopApproach.zones(position, triggers, settings.approach())) {
      caps.add(new SpeedCeiling.Cap(zone.fromBlocks(), zone.toBlocks(), capSpeed));
    }
    return caps;
  }

  private double edgeSpeed(RailGraph graph, RailEdge edge) {
    if (edgeSpeeds != null) {
      double effective = edgeSpeeds.resolve(graph, edge, settings.fallbackSpeedBps());
      if (Double.isFinite(effective) && effective > 0.0) {
        return effective;
      }
    }
    double base = edge.baseSpeedLimit();
    return Double.isFinite(base) && base > 0.0 ? base : settings.fallbackSpeedBps();
  }

  /** 边的有效限速（格/秒）；返回非正数或非有限值表示"没有"，退回边的基础限速与默认速度。 */
  @FunctionalInterface
  public interface EdgeSpeedResolver {
    double resolve(RailGraph graph, RailEdge edge, double fallbackSpeedBps);
  }

  /**
   * 模型参数。
   *
   * @param motion 加减速曲线（减速度已乘自动运行的舒适比例）
   * @param fallbackSpeedBps 边没有限速时的速度，必须为正
   * @param approach 进站规则
   * @param stationStopOverheadSeconds 车站停车在 dwell 之外多耗的秒数，负值按 0
   * @param atoBrakeFraction 换车种时（{@link #withTrain}）减速度乘的舒适比例，在 (0, 1] 内
   */
  public record Settings(
      SpeedCurve motion,
      double fallbackSpeedBps,
      StopApproach.Rule approach,
      int stationStopOverheadSeconds,
      double atoBrakeFraction) {

    public Settings {
      Objects.requireNonNull(motion, "motion");
      if (!Double.isFinite(fallbackSpeedBps) || fallbackSpeedBps <= 0.0) {
        throw new IllegalArgumentException("fallbackSpeedBps 必须为正数");
      }
      approach = approach == null ? StopApproach.Rule.disabled() : approach;
      stationStopOverheadSeconds = Math.max(0, stationStopOverheadSeconds);
      if (!(atoBrakeFraction > 0.0) || atoBrakeFraction > 1.0) {
        atoBrakeFraction = 1.0;
      }
    }

    /** 直接给定曲线：换车种时减速度不再乘比例。 */
    public Settings(
        SpeedCurve motion,
        double fallbackSpeedBps,
        StopApproach.Rule approach,
        int stationStopOverheadSeconds) {
      this(motion, fallbackSpeedBps, approach, stationStopOverheadSeconds, 1.0);
    }

    /**
     * 与运行时控车读同一组配置：
     *
     * <ul>
     *   <li>加减速：{@code train.default-type} 那个车种，减速度乘 {@code
     *       train.ato-brake-fraction}。编表不读车库牌子推断车种—— 牌子所在区块没加载时会退回默认车种， 同一份输入就不再产出同一张表；
     *   <li>进站：{@code runtime.approach-window-blocks / approach-window-edges / approach-speed-bps /
     *       approach-depot-speed-bps}；
     *   <li>缺限速的边：{@code graph.default-speed-blocks-per-second}；
     *   <li>车站停站开销：{@code timetable.station-stop-overhead-seconds}。
     * </ul>
     *
     * @param config 当前配置
     * @param fallbackSpeedBps 配置里的默认速度无效时用的速度
     */
    public static Settings fromConfig(ConfigManager.ConfigView config, double fallbackSpeedBps) {
      Objects.requireNonNull(config, "config");
      ConfigManager.TrainTypeSettings train =
          config.trainConfigSettings().forType(config.trainConfigSettings().defaultTrainType());
      ConfigManager.RuntimeSettings runtime = config.runtimeSettings();
      double configured = config.graphSettings().defaultSpeedBlocksPerSecond();
      double fraction = config.trainConfigSettings().atoBrakeFraction();
      return new Settings(
          SpeedCurve.ato(train.accelBps2(), train.decelBps2(), fraction),
          Double.isFinite(configured) && configured > 0.0 ? configured : fallbackSpeedBps,
          StopApproach.Rule.fromRuntime(runtime),
          config.timetableSettings().stationStopOverheadSeconds(),
          fraction);
    }

    /** 换一组加减速，其余不变；{@code replacement} 原样使用，不再乘舒适比例。 */
    public Settings withMotion(SpeedCurve replacement) {
      return new Settings(
          replacement, fallbackSpeedBps, approach, stationStopOverheadSeconds, atoBrakeFraction);
    }

    /**
     * 换一个车种的加减速（未发车票据按车库推断的车种、在途列车按车上标签、编组方案按车型档案估算时用），减速度乘舒适比例， 与控车同一口径。
     *
     * @param accelBps2 车种满加速度（格/秒²）
     * @param serviceDecelBps2 车种常用制动减速度（格/秒²）
     */
    public Settings withTrain(double accelBps2, double serviceDecelBps2) {
      return withMotion(SpeedCurve.ato(accelBps2, serviceDecelBps2, atoBrakeFraction));
    }
  }
}
