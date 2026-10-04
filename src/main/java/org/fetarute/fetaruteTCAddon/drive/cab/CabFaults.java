package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * 一次驾驶会话里的车上故障：注入、自动恢复、驾驶台处置与随机发生。
 *
 * <ul>
 *   <li>受电中断：到时自动恢复，或由管理员清除；
 *   <li>主断路器跳闸：驾驶台先断开主断，再闭合，闭合耗时走完即复位；
 *   <li>压缩机故障、制动缸漏泄：直到管理员清除或会话结束；
 *   <li>车门故障：直到管理员清除或会话结束，期间可用门旁路解除牵引封锁。门旁路是驾驶员的开关，故障清除后仍保持，须驾驶员自己复位。
 * </ul>
 *
 * <p>状态只存在内存里，不落库、不写列车标签。每次变化记一条事件，由调用方取走后提示驾驶员、写诊断日志。本类不依赖任何服务器对象，时间用服务器 tick。
 */
public final class CabFaults {

  /** 注入的结果。 */
  public enum Outcome {
    INJECTED,
    /** 已经有这个故障。 */
    ALREADY_ACTIVE,
    /** 这种动力方式不会发生这个故障（内燃车没有受电与主断）。 */
    NOT_APPLICABLE
  }

  /** 主断路器跳闸后的复位进度。 */
  public enum BreakerStage {
    /** 已跳闸，等待驾驶员断开主断开关。 */
    TRIPPED,
    /** 已断开，等待驾驶员闭合。 */
    OPENED,
    /** 正在闭合。 */
    CLOSING
  }

  /** 驾驶台点击主断开关的结果。 */
  public enum BreakerClick {
    /** 主断没有跳闸：按平常的启动流程开关处理。 */
    NOT_TRIPPED,
    /** 已断开主断，等待再次闭合。 */
    OPENED,
    /** 开始闭合。 */
    CLOSING,
    /** 正在闭合，请稍候。 */
    BUSY
  }

  /** 事件类型。 */
  public enum Kind {
    /** 管理员注入。 */
    INJECTED,
    /** 随机发生。 */
    RANDOM,
    /** 管理员清除。 */
    CLEARED,
    /** 自动恢复或驾驶员处置完毕。 */
    RECOVERED
  }

  /**
   * 一条故障事件。
   *
   * @param fault 故障
   * @param kind 发生了什么
   */
  public record Event(CabFault fault, Kind kind) {
    public Event {
      Objects.requireNonNull(fault, "fault");
      Objects.requireNonNull(kind, "kind");
    }
  }

  private static final long TICKS_PER_SECOND = 20L;

  private final FaultConfig config;
  private final boolean electric;
  private final RandomGenerator random;
  private final Set<CabFault> active = EnumSet.noneOf(CabFault.class);
  private final List<Event> events = new ArrayList<>();
  private long lineRestoreTick;
  private BreakerStage breakerStage;
  private long breakerDoneTick;
  private boolean doorBypass;
  private long nextRollTick = Long.MIN_VALUE;

  /**
   * @param electric 列车是否为电力牵引（内燃车不会发生受电中断与主断跳闸）
   * @param random 随机故障用的随机数
   */
  public CabFaults(FaultConfig config, boolean electric, RandomGenerator random) {
    this.config = Objects.requireNonNull(config, "config");
    this.electric = electric;
    this.random = Objects.requireNonNull(random, "random");
  }

  /** 使用默认随机数。 */
  public CabFaults(FaultConfig config, boolean electric) {
    this(config, electric, new SplittableRandom());
  }

  /** 这列车会不会发生这个故障。 */
  public boolean applicable(CabFault fault) {
    return electric || !fault.electricOnly();
  }

  /** 注入一个故障。 */
  public Outcome inject(CabFault fault, long nowTick) {
    return activate(fault, nowTick, Kind.INJECTED);
  }

  private Outcome activate(CabFault fault, long nowTick, Kind kind) {
    Objects.requireNonNull(fault, "fault");
    if (!applicable(fault)) {
      return Outcome.NOT_APPLICABLE;
    }
    if (!active.add(fault)) {
      return Outcome.ALREADY_ACTIVE;
    }
    switch (fault) {
      case LINE_LOSS -> lineRestoreTick = nowTick + config.lineLossTicks();
      case BREAKER_TRIP -> breakerStage = BreakerStage.TRIPPED;
      default -> {}
    }
    events.add(new Event(fault, kind));
    return Outcome.INJECTED;
  }

  /**
   * 清除全部故障（管理员命令）。门旁路是驾驶员的开关，不受影响。
   *
   * @return 清除了几个故障
   */
  public int clearAll() {
    int count = active.size();
    for (CabFault fault : active) {
      events.add(new Event(fault, Kind.CLEARED));
    }
    active.clear();
    breakerStage = null;
    return count;
  }

  /**
   * 推进时间：受电到时恢复、主断闭合完成，并按配置掷一次随机故障（每秒一次）。
   *
   * @param randomEligible 此刻能不能发生随机故障（驾驶员在座、列车已启动）
   */
  public void tick(long nowTick, boolean randomEligible) {
    if (active.contains(CabFault.LINE_LOSS) && nowTick >= lineRestoreTick) {
      recover(CabFault.LINE_LOSS);
    }
    if (breakerStage == BreakerStage.CLOSING && nowTick >= breakerDoneTick) {
      recover(CabFault.BREAKER_TRIP);
    }
    if (!randomEligible || !config.enabled() || !active.isEmpty()) {
      return;
    }
    if (nextRollTick == Long.MIN_VALUE) {
      nextRollTick = nowTick + TICKS_PER_SECOND;
      return;
    }
    if (nowTick < nextRollTick) {
      return;
    }
    nextRollTick = nowTick + TICKS_PER_SECOND;
    if (random.nextDouble() >= config.chancePerSecond()) {
      return;
    }
    List<CabFault> candidates = new ArrayList<>();
    for (CabFault fault : CabFault.values()) {
      if (config.types().contains(fault) && applicable(fault)) {
        candidates.add(fault);
      }
    }
    if (!candidates.isEmpty()) {
      activate(candidates.get(random.nextInt(candidates.size())), nowTick, Kind.RANDOM);
    }
  }

  private void recover(CabFault fault) {
    if (active.remove(fault)) {
      events.add(new Event(fault, Kind.RECOVERED));
    }
    if (fault == CabFault.BREAKER_TRIP) {
      breakerStage = null;
    }
  }

  /**
   * 驾驶台点击主断开关：跳闸后第一次点击断开，第二次开始闭合，闭合耗时走完即复位。
   *
   * @param closeTicks 闭合主断的耗时（tick）
   */
  public BreakerClick clickBreaker(long nowTick, int closeTicks) {
    if (breakerStage == null) {
      return BreakerClick.NOT_TRIPPED;
    }
    switch (breakerStage) {
      case TRIPPED -> {
        breakerStage = BreakerStage.OPENED;
        return BreakerClick.OPENED;
      }
      case OPENED -> {
        breakerStage = BreakerStage.CLOSING;
        breakerDoneTick = nowTick + Math.max(0, closeTicks);
        if (closeTicks <= 0) {
          recover(CabFault.BREAKER_TRIP);
        }
        return BreakerClick.CLOSING;
      }
      default -> {
        return BreakerClick.BUSY;
      }
    }
  }

  /** 主断复位进度；主断没有跳闸时为空。 */
  public Optional<BreakerStage> breakerStage() {
    return Optional.ofNullable(breakerStage);
  }

  /** 主断闭合还剩的 tick；不在闭合中时为 0。 */
  public long breakerRemainingTicks(long nowTick) {
    return breakerStage == BreakerStage.CLOSING ? Math.max(0L, breakerDoneTick - nowTick) : 0L;
  }

  /**
   * 切换门旁路。
   *
   * @return 切换后是否旁路
   */
  public boolean toggleDoorBypass() {
    doorBypass = !doorBypass;
    return doorBypass;
  }

  /** 门旁路是否接通。 */
  public boolean doorBypassed() {
    return doorBypass;
  }

  /** 是否有这个故障。 */
  public boolean active(CabFault fault) {
    return active.contains(fault);
  }

  /** 是否有任何故障。 */
  public boolean any() {
    return !active.isEmpty();
  }

  /** 当前的全部故障（按枚举顺序）。 */
  public List<CabFault> activeFaults() {
    List<CabFault> list = new ArrayList<>();
    for (CabFault fault : CabFault.values()) {
      if (active.contains(fault)) {
        list.add(fault);
      }
    }
    return List.copyOf(list);
  }

  /** 列车是否失去网压（受电中断或主断跳闸）：没有牵引、没有电制动，压缩机也没有电。 */
  public boolean linePowerLost() {
    return active.contains(CabFault.LINE_LOSS) || active.contains(CabFault.BREAKER_TRIP);
  }

  /** 门关好回路是否不通且没有旁路：封锁牵引。 */
  public boolean doorCircuitOpen() {
    return active.contains(CabFault.DOOR) && !doorBypass;
  }

  /** 取出并清除积压的事件。 */
  public List<Event> takeEvents() {
    if (events.isEmpty()) {
      return List.of();
    }
    List<Event> taken = List.copyOf(events);
    events.clear();
    return taken;
  }
}
