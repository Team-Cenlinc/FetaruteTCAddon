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
 * 按运行时控车方式估算站到站走行：从静止起步，受车型加减速约束，按边限速行驶，进站前按进站限速减速。
 *
 * <p>三处取值都和运行时是同一个事实源（{@link Settings#fromConfig}）：
 *
 * <ul>
 *   <li>边限速：调用方给的 {@link EdgeSpeedResolver}（编表接永久限速覆盖，ETA 接运行时的有效限速），缺限速时取默认速度；
 *   <li>进站：{@code runtime.approach-*} 的窗口与限速，触发节点按 {@link StopApproach}；
 *   <li>加减速：车种配置（{@code train.types}）。
 * </ul>
 *
 * <p>编表与 ETA 用的是同一个模型：表定时分与"列车现在离站还有多久"出自同一条运行曲线，晚点才是可比的数。
 *
 * <p>车站停车的终点速度取进站限速：压上站牌之后的居中刹停由 TrainCarts 完成，它的耗时连同开门延迟一起记在 {@link
 * #stationStopOverheadSeconds()}，不在走行里重复算。区间停车点由调度层刹停，终点速度为 0。
 *
 * <p>2026-09-26 用实服 22 分钟日志（32 个站间区段、136 次停站）校核：走行中位误差 +1%，停站"压牌→发车"中位 24 秒（dwell 20 + 开销
 * 4）。旧口径（每站满速通过、瞬间停车）同一批区段少算 42%。
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
      lengths[k] = RunCurve.quantize(length);
      speeds[k] = edgeSpeed(graph, edge);
    }
    List<RunCurve.Cap> caps = List.of();
    double exitSpeed = Double.POSITIVE_INFINITY;
    if (run.stopsAtEnd() && !edges.isEmpty()) {
      StopApproach.Target target =
          StopApproach.targetOf(graph, run.nodes().get(run.nodes().size() - 1));
      double capSpeed = settings.approach().speedFor(target.kind());
      if (capSpeed > 0.0) {
        caps = approachCaps(graph, run.nodes(), lengths, target, capSpeed);
        exitSpeed = capSpeed;
      }
      if (target.kind() == StopApproach.Kind.WAYPOINT) {
        exitSpeed = 0.0;
      }
    }
    MotionParams motion = settings.motion();
    return Optional.of(
        RunCurve.nodeTimes(
            lengths,
            speeds,
            caps,
            run.entrySpeedBps(),
            exitSpeed,
            motion.accelBps2(),
            motion.decelBps2()));
  }

  @Override
  public int stationStopOverheadSeconds() {
    return settings.stationStopOverheadSeconds();
  }

  /** 生效的参数，报告与参数面板用它说明表定时分是按什么算的。 */
  public Settings settings() {
    return settings;
  }

  /** 进站限速区（{@link StopApproach#zones}，与运行时控车同一个判据）按进站限速落成运行曲线的限速区。 */
  private List<RunCurve.Cap> approachCaps(
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
    List<RunCurve.Cap> caps = new ArrayList<>();
    for (StopApproach.Zone zone : StopApproach.zones(position, triggers, settings.approach())) {
      caps.add(new RunCurve.Cap(zone.fromBlocks(), zone.toBlocks(), capSpeed));
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
   * 加减速能力。
   *
   * @param accelBps2 加速度（格/秒²），必须为正
   * @param decelBps2 减速度（格/秒²），必须为正
   */
  public record MotionParams(double accelBps2, double decelBps2) {
    public MotionParams {
      if (!Double.isFinite(accelBps2) || accelBps2 <= 0.0) {
        throw new IllegalArgumentException("accelBps2 必须为正数");
      }
      if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
        throw new IllegalArgumentException("decelBps2 必须为正数");
      }
    }

    /** 没有配置可读时的加减速（插件还没加载配置、单元测试）；有配置时一律读车种配置。 */
    public static MotionParams defaults() {
      return new MotionParams(1.0, 1.2);
    }
  }

  /**
   * 模型参数。
   *
   * @param motion 加减速
   * @param fallbackSpeedBps 边没有限速时的速度，必须为正
   * @param approach 进站规则
   * @param stationStopOverheadSeconds 车站停车在 dwell 之外多耗的秒数，负值按 0
   */
  public record Settings(
      MotionParams motion,
      double fallbackSpeedBps,
      StopApproach.Rule approach,
      int stationStopOverheadSeconds) {

    public Settings {
      Objects.requireNonNull(motion, "motion");
      if (!Double.isFinite(fallbackSpeedBps) || fallbackSpeedBps <= 0.0) {
        throw new IllegalArgumentException("fallbackSpeedBps 必须为正数");
      }
      approach = approach == null ? StopApproach.Rule.disabled() : approach;
      stationStopOverheadSeconds = Math.max(0, stationStopOverheadSeconds);
    }

    /**
     * 与运行时控车读同一组配置：
     *
     * <ul>
     *   <li>加减速：{@code train.default-type} 那个车种。编表不读车库牌子推断车种——牌子所在区块没加载时会退回默认车种， 同一份输入就不再产出同一张表；
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
      return new Settings(
          new MotionParams(train.accelBps2(), train.decelBps2()),
          Double.isFinite(configured) && configured > 0.0 ? configured : fallbackSpeedBps,
          StopApproach.Rule.fromRuntime(runtime),
          config.timetableSettings().stationStopOverheadSeconds());
    }

    /** 换一组加减速，其余不变（未发车票据按车库推断的车种估算时用）。 */
    public Settings withMotion(MotionParams replacement) {
      return new Settings(replacement, fallbackSpeedBps, approach, stationStopOverheadSeconds);
    }
  }
}
