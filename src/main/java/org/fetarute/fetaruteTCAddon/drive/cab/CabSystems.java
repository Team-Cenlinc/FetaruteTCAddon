package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次驾驶会话里 simulation 级的车上系统：气压与停放制动、电空复合制动、机车制动管、制动试验、警惕装置、恒功率牵引与车上故障。
 *
 * <ul>
 *   <li>电空复合制动：电力牵引的列车受电正常时，在电制动退出速度以上优先用电制动，不足部分由空气制动补足；只有空气制动部分消耗主风缸。
 *       紧急制动、无人驾驶时的自动制动与停放制动全部由空气制动承担，且不随风压打折（失效导向安全，由调用方处理）；
 *   <li>机车牵引用制动管：制动缸压力由制动管减压量决定，制动管失压时自动紧急制动；动车组的制动缸直接按指令升降；
 *   <li>恒功率：速度高于拐点速度后牵引力按 {@code 拐点速度 ÷ 当前速度} 下降。
 * </ul>
 *
 * <p>standard 级使用 {@link #disabled()}：不封锁牵引、制动力不打折、牵引不降、不计警惕、没有故障。本类不依赖任何服务器对象。
 */
public final class CabSystems {

  /** 牵引被封锁的原因，按优先级排列。 */
  public enum TractionBlock {
    /** 主断路器跳闸。 */
    BREAKER_TRIPPED,
    /** 受电中断（网压消失）。 */
    LINE_LOSS,
    /** 门关好回路不通（车门故障且没有旁路）。 */
    DOOR_CIRCUIT,
    /** 停放制动没有缓解。 */
    PARKING_BRAKE,
    /** 机车的制动管压力不到常用全制动后的压力（未充风或紧急制动后尚未充回）。 */
    BRAKE_PIPE,
    /** 主风缸压力低于封锁线。 */
    LOW_AIR,
    /** 还没做制动试验，或试验没通过。 */
    BRAKE_TEST
  }

  /** 电制动出力超过它（占常用全制动）才算在再生。 */
  private static final double REGEN_THRESHOLD = 0.01;

  private final boolean enabled;
  private final CabConfig config;
  private final CabVehicle vehicle;
  private final AirSystem air;
  private final BrakePipe brakePipe;
  private final BrakeTest brakeTest;
  private final Vigilance vigilance;
  private final CabFaults faults;
  private BrakeTest.Stage pendingTestStage;
  private double electricBrake;
  private boolean emergencyRequested;
  private boolean brakePipeLossNotice;

  private CabSystems(
      boolean enabled,
      CabConfig config,
      CabVehicle vehicle,
      AirSystem air,
      BrakePipe brakePipe,
      Vigilance vigilance) {
    this.enabled = enabled;
    this.config = Objects.requireNonNull(config, "config");
    this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
    this.air = Objects.requireNonNull(air, "air");
    this.brakePipe = brakePipe;
    this.brakeTest = brakePipe != null ? BrakeTest.forBrakePipe() : new BrakeTest();
    this.vigilance = Objects.requireNonNull(vigilance, "vigilance");
    this.faults = new CabFaults(config.faults(), vehicle.electric());
  }

  /** standard 级：没有车上系统的约束。 */
  public static CabSystems disabled() {
    CabConfig config = CabConfig.defaults();
    return new CabSystems(
        false,
        config,
        CabVehicle.defaultMultipleUnit(config),
        new AirSystem(config, false, config.mainReservoirMaxKpa(), true),
        null,
        new Vigilance(Long.MAX_VALUE / 4, 1, 0));
  }

  /**
   * simulation 级，按单节电动车组的特征（没有制动管）。
   *
   * @param manualCompressor 压缩机是否要手动打开
   * @param mainReservoirKpa 主风缸的初始压力（列车上保存的值，已扣除漏泄）
   * @param compressorSwitch 手动压缩机开关的初始状态
   * @param nowTick 当前服务器 tick
   */
  public static CabSystems simulation(
      CabConfig config,
      boolean manualCompressor,
      double mainReservoirKpa,
      boolean compressorSwitch,
      long nowTick) {
    return new CabSystems(
        true,
        config,
        CabVehicle.defaultMultipleUnit(config),
        new AirSystem(config, manualCompressor, mainReservoirKpa, compressorSwitch),
        null,
        new Vigilance(config.vigilanceIntervalTicks(), config.vigilanceWarningTicks(), nowTick));
  }

  /**
   * simulation 级。机车牵引的压缩机要手动打开，制动管从未充风开始。
   *
   * @param vehicle 车辆特征
   * @param mainReservoirKpa 主风缸的初始压力（列车上保存的值，已扣除漏泄）
   * @param compressorSwitch 手动压缩机开关的初始状态
   * @param nowTick 当前服务器 tick
   */
  public static CabSystems simulation(
      CabConfig config,
      CabVehicle vehicle,
      double mainReservoirKpa,
      boolean compressorSwitch,
      long nowTick) {
    return simulation(config, vehicle, mainReservoirKpa, compressorSwitch, 0.0, nowTick);
  }

  private static CabSystems simulation(
      CabConfig config,
      CabVehicle vehicle,
      double mainReservoirKpa,
      boolean compressorSwitch,
      double brakePipeKpa,
      long nowTick) {
    return new CabSystems(
        true,
        config,
        vehicle,
        new AirSystem(config, vehicle.manualCompressor(), mainReservoirKpa, compressorSwitch),
        vehicle.brakePipe()
            ? new BrakePipe(config.brakePipe(), vehicle.cars(), brakePipeKpa)
            : null,
        new Vigilance(config.vigilanceIntervalTicks(), config.vigilanceWarningTicks(), nowTick));
  }

  /**
   * simulation 级的热车交接（按单节电动车组的特征）：主风缸满压、停放制动已缓解、制动试验视为已做。
   *
   * @param manualCompressor 压缩机是否要手动打开；热车交接时开关视为打开
   */
  public static CabSystems hotHandover(CabConfig config, boolean manualCompressor, long nowTick) {
    CabSystems cab =
        simulation(config, manualCompressor, config.mainReservoirMaxKpa(), true, nowTick);
    cab.air().toggleParking();
    cab.brakeTest().markPassed();
    return cab;
  }

  /** simulation 级的热车交接：列车一直在运行，主风缸满压、制动管已充风、停放制动已缓解、制动试验视为已做。 */
  public static CabSystems hotHandover(CabConfig config, CabVehicle vehicle, long nowTick) {
    CabSystems cab =
        simulation(
            config,
            vehicle,
            config.mainReservoirMaxKpa(),
            true,
            config.brakePipe().nominalKpa(),
            nowTick);
    cab.air().toggleParking();
    cab.brakeTest().markPassed();
    return cab;
  }

  public boolean enabled() {
    return enabled;
  }

  public CabConfig config() {
    return config;
  }

  public AirSystem air() {
    return air;
  }

  /** 机车的制动管；动车组没有。 */
  public Optional<BrakePipe> brakePipe() {
    return Optional.ofNullable(brakePipe);
  }

  public BrakeTest brakeTest() {
    return brakeTest;
  }

  public Vigilance vigilance() {
    return vigilance;
  }

  public CabFaults faults() {
    return faults;
  }

  /** 牵引被封锁的首要原因；没有封锁时为空。 */
  public Optional<TractionBlock> tractionBlock() {
    if (!enabled) {
      // standard 级没有车上系统，只有驾驶证练习的应急演练会注入简化故障：受电中断与车门故障照样封锁牵引。
      if (faults.active(CabFault.LINE_LOSS)) {
        return Optional.of(TractionBlock.LINE_LOSS);
      }
      if (faults.doorCircuitOpen()) {
        return Optional.of(TractionBlock.DOOR_CIRCUIT);
      }
      return Optional.empty();
    }
    if (faults.active(CabFault.BREAKER_TRIP)) {
      return Optional.of(TractionBlock.BREAKER_TRIPPED);
    }
    if (faults.active(CabFault.LINE_LOSS)) {
      return Optional.of(TractionBlock.LINE_LOSS);
    }
    if (faults.doorCircuitOpen()) {
      return Optional.of(TractionBlock.DOOR_CIRCUIT);
    }
    if (air.parkingApplied()) {
      return Optional.of(TractionBlock.PARKING_BRAKE);
    }
    if (brakePipe != null && brakePipe.belowFullService()) {
      return Optional.of(TractionBlock.BRAKE_PIPE);
    }
    if (air.tractionLocked()) {
      return Optional.of(TractionBlock.LOW_AIR);
    }
    if (!brakeTest.passed()) {
      return Optional.of(TractionBlock.BRAKE_TEST);
    }
    return Optional.empty();
  }

  /**
   * 常用制动可保证发挥的比例（0–1）：按只靠空气制动、当前风压计。车载防护据此估算制动距离，电制动退出后也停得住。
   *
   * <p>standard 级为 1。
   */
  public double brakeScale() {
    return enabled ? config.blendedBrake().airFraction() * air.brakeScale() : 1.0;
  }

  /**
   * 恒功率段的牵引力系数；standard 级为 1。
   *
   * @param speedBps 当前速度（格/秒）
   */
  public double tractionScale(double speedBps) {
    return enabled ? ConstantPowerConfig.tractionScale(speedBps, vehicle.kneeBps()) : 1.0;
  }

  /**
   * 电制动此刻能承担的比例（占常用全制动）：电力牵引、主电路得电、没有失电故障，且速度在退出速度以上；否则为 0。
   *
   * @param speedBps 当前速度（格/秒）
   * @param mainCircuitPowered 受电、主断路器与辅助电源是否都接通
   */
  public double electricCapacity(double speedBps, boolean mainCircuitPowered) {
    if (!enabled || !vehicle.electric() || !mainCircuitPowered || faults.linePowerLost()) {
      return 0.0;
    }
    return config.blendedBrake().electricCapacity(speedBps);
  }

  /**
   * 常用制动实际发挥与指令之比（0–1）：电制动承担的部分照常发挥，空气制动部分按制动缸实际压力与风压折算。standard 级为 1。
   *
   * @param demand 制动力度（常用全制动为 1）
   * @param speedBps 这一步开始时的速度（格/秒）
   * @param mainCircuitPowered 受电、主断路器与辅助电源是否都接通
   */
  public double serviceBrakeScale(double demand, double speedBps, boolean mainCircuitPowered) {
    if (!enabled) {
      return 1.0;
    }
    BrakeBlend.Split split =
        BrakeBlend.split(demand, electricCapacity(speedBps, mainCircuitPowered));
    return BrakeBlend.deliveredScale(demand, split, airAvailable());
  }

  /** 空气制动此刻能发挥的制动力（占常用全制动），按制动缸实际压力与风压折算。 */
  private double airAvailable() {
    double cylinder = air.brakeCylinderKpa() / Math.max(1.0, config.brakeCylinderMaxKpa());
    return config.blendedBrake().airFraction() * Math.min(1.0, cylinder) * air.brakeScale();
  }

  /** 电制动正在出力（再生）：侧边栏据此加标记。 */
  public boolean regenerating() {
    return enabled && electricBrake > REGEN_THRESHOLD;
  }

  /** 上一步电制动承担的制动力（占常用全制动）。 */
  public double electricBrake() {
    return electricBrake;
  }

  /**
   * 常用制动从施加到制动缸达到全压额外要等的秒数：机车要等制动管减压，按常用全制动减压量与减压速率的一半计；动车组为 0。
   *
   * <p>车载防护把它加进反应时间，免得按动车组的响应估算机车的制动距离。
   */
  public double serviceApplyLagSeconds() {
    if (!enabled || brakePipe == null) {
      return 0.0;
    }
    BrakePipeConfig pipe = config.brakePipe();
    return pipe.fullServiceReductionKpa() / pipe.serviceRateKpaPerSecond() / 2.0;
  }

  /**
   * 推进一 tick（按 {@link #tick(long, double, CabTick)}，主电路随辅助电源，不区分失效导向安全）。
   *
   * @param seconds 时间步长（秒）
   * @param auxPowered 辅助电源是否接通
   * @param brakeDemand 制动力度（常用全制动为 1，超过 1 视为紧急制动）
   * @param stopped 列车是否已停稳
   * @param attended 驾驶员是否在座操作（离座或制动停车阶段不计警惕）
   * @return 警惕装置这一 tick 的事件
   */
  public Vigilance.Event tick(
      long nowTick,
      double seconds,
      boolean auxPowered,
      double brakeDemand,
      boolean stopped,
      boolean attended) {
    boolean emergency = brakeDemand > 1.0;
    return tick(
        nowTick,
        seconds,
        new CabTick(
            auxPowered,
            auxPowered,
            brakeDemand,
            emergency,
            emergency,
            0.0,
            stopped,
            attended,
            false));
  }

  /**
   * 推进一 tick：故障计时、气压与制动管、制动试验与警惕装置。
   *
   * @param seconds 时间步长（秒）
   * @return 警惕装置这一 tick 的事件
   */
  public Vigilance.Event tick(long nowTick, double seconds, CabTick in) {
    Objects.requireNonNull(in, "in");
    if (!enabled) {
      return Vigilance.Event.NONE;
    }
    faults.tick(nowTick, in.attended() && in.running());
    air.setCompressorFailed(faults.active(CabFault.COMPRESSOR));
    air.setCylinderLeak(
        faults.active(CabFault.BRAKE_LEAK) ? config.faults().brakeLeakKpaPerSecond() : 0.0);
    boolean compressorPowered = in.auxPowered() && !(vehicle.electric() && faults.linePowerLost());
    double demand = Math.max(0.0, in.brakeDemand());
    BrakeBlend.Split split =
        BrakeBlend.split(
            demand, in.failSafe() ? 0.0 : electricCapacity(in.speedBps(), in.mainCircuitPowered()));
    electricBrake = split.electric();
    double airFraction = config.blendedBrake().airFraction();
    double commandedReduction = 0.0;
    double cylinderTarget;
    if (brakePipe != null) {
      double serviceRatio = BrakeBlend.cylinderRatio(split.air(), airFraction, 1.0);
      commandedReduction = serviceRatio * config.brakePipe().fullServiceReductionKpa();
      air.consume(brakePipe.tick(seconds, serviceRatio, in.emergency(), air.mainReservoirKpa()));
      cylinderTarget = brakePipe.cylinderTargetKpa(config.brakeCylinderMaxKpa());
      if (in.emergency()) {
        cylinderTarget = Math.max(cylinderTarget, demand * config.brakeCylinderMaxKpa());
      }
      if (brakePipe.takeLoss()) {
        emergencyRequested = true;
        brakePipeLossNotice = true;
      }
    } else {
      double cap = in.failSafe() ? Math.max(1.0, demand) : 1.0;
      cylinderTarget =
          BrakeBlend.cylinderRatio(split.air(), airFraction, cap) * config.brakeCylinderMaxKpa();
    }
    air.tickCylinder(seconds, compressorPowered, cylinderTarget);
    boolean testChanged =
        brakePipe != null
            ? brakeTest.tickBrakePipe(
                brakePipe.pressureKpa(),
                air.brakeCylinderKpa(),
                commandedReduction,
                in.stopped(),
                seconds,
                config)
            : brakeTest.tick(air.brakeCylinderKpa(), in.stopped(), config);
    if (testChanged) {
      pendingTestStage = brakeTest.stage();
    }
    if (!in.attended()) {
      vigilance.acknowledge(nowTick);
      return Vigilance.Event.NONE;
    }
    return vigilance.tick(nowTick, !in.stopped());
  }

  /** 取出并清除制动试验最近一次变化后的进度；没有变化时为空。 */
  public Optional<BrakeTest.Stage> takeBrakeTestChange() {
    BrakeTest.Stage stage = pendingTestStage;
    pendingTestStage = null;
    return Optional.ofNullable(stage);
  }

  /** 取出并清除“要施加紧急制动”的请求（制动管失压）。 */
  public boolean takeEmergencyRequest() {
    boolean requested = emergencyRequested;
    emergencyRequested = false;
    return requested;
  }

  /** 取出并清除“制动管失压”的提示记号。 */
  public boolean takeBrakePipeLoss() {
    boolean loss = brakePipeLossNotice;
    brakePipeLossNotice = false;
    return loss;
  }

  /** 驾驶员有操作：警惕装置重新计时。 */
  public void acknowledge(long nowTick) {
    if (enabled) {
      vigilance.acknowledge(nowTick);
    }
  }
}
