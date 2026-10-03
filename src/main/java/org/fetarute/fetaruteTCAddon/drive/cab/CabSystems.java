package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次驾驶会话里 simulation 级的车上系统：气压与停放制动、制动试验、警惕装置。
 *
 * <p>standard 级使用 {@link #disabled()}：不封锁牵引、制动力不打折、不计警惕。本类不依赖任何服务器对象。
 */
public final class CabSystems {

  /** 牵引被封锁的原因，按优先级排列。 */
  public enum TractionBlock {
    /** 停放制动没有缓解。 */
    PARKING_BRAKE,
    /** 主风缸压力低于封锁线。 */
    LOW_AIR,
    /** 还没做制动试验。 */
    BRAKE_TEST
  }

  private final boolean enabled;
  private final CabConfig config;
  private final AirSystem air;
  private final BrakeTest brakeTest = new BrakeTest();
  private final Vigilance vigilance;
  private BrakeTest.Stage pendingTestStage;

  private CabSystems(boolean enabled, CabConfig config, AirSystem air, Vigilance vigilance) {
    this.enabled = enabled;
    this.config = Objects.requireNonNull(config, "config");
    this.air = Objects.requireNonNull(air, "air");
    this.vigilance = Objects.requireNonNull(vigilance, "vigilance");
  }

  /** standard 级：没有车上系统的约束。 */
  public static CabSystems disabled() {
    CabConfig config = CabConfig.defaults();
    return new CabSystems(
        false,
        config,
        new AirSystem(config, false, config.mainReservoirMaxKpa(), true),
        new Vigilance(Long.MAX_VALUE / 4, 1, 0));
  }

  /**
   * simulation 级。
   *
   * @param manualCompressor 压缩机是否要手动打开（机车牵引）
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
        new AirSystem(config, manualCompressor, mainReservoirKpa, compressorSwitch),
        new Vigilance(config.vigilanceIntervalTicks(), config.vigilanceWarningTicks(), nowTick));
  }

  /**
   * simulation 级的热车交接：列车一直在运行，主风缸满压、停放制动已缓解、制动试验视为已做。
   *
   * @param manualCompressor 压缩机是否要手动打开（机车牵引）；热车交接时开关视为打开
   */
  public static CabSystems hotHandover(CabConfig config, boolean manualCompressor, long nowTick) {
    CabSystems cab =
        simulation(config, manualCompressor, config.mainReservoirMaxKpa(), true, nowTick);
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

  public BrakeTest brakeTest() {
    return brakeTest;
  }

  public Vigilance vigilance() {
    return vigilance;
  }

  /** 牵引被封锁的首要原因；没有封锁时为空。 */
  public Optional<TractionBlock> tractionBlock() {
    if (!enabled) {
      return Optional.empty();
    }
    if (air.parkingApplied()) {
      return Optional.of(TractionBlock.PARKING_BRAKE);
    }
    if (air.tractionLocked()) {
      return Optional.of(TractionBlock.LOW_AIR);
    }
    if (!brakeTest.passed()) {
      return Optional.of(TractionBlock.BRAKE_TEST);
    }
    return Optional.empty();
  }

  /** 制动力可发挥的比例（0–1）。 */
  public double brakeScale() {
    return enabled ? air.brakeScale() : 1.0;
  }

  /**
   * 推进一 tick。
   *
   * @param seconds 时间步长（秒）
   * @param auxPowered 辅助电源是否接通
   * @param brakeDemand 制动力度（常用全制动为 1）
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
    if (!enabled) {
      return Vigilance.Event.NONE;
    }
    air.tick(seconds, auxPowered, brakeDemand);
    if (brakeTest.tick(air.brakeCylinderKpa(), stopped, config)) {
      pendingTestStage = brakeTest.stage();
    }
    if (!attended) {
      vigilance.acknowledge(nowTick);
      return Vigilance.Event.NONE;
    }
    return vigilance.tick(nowTick, !stopped);
  }

  /** 取出并清除制动试验最近一次变化后的进度；没有变化时为空。 */
  public Optional<BrakeTest.Stage> takeBrakeTestChange() {
    BrakeTest.Stage stage = pendingTestStage;
    pendingTestStage = null;
    return Optional.ofNullable(stage);
  }

  /** 驾驶员有操作：警惕装置重新计时。 */
  public void acknowledge(long nowTick) {
    if (enabled) {
      vigilance.acknowledge(nowTick);
    }
  }
}
