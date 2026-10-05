package org.fetarute.fetaruteTCAddon.drive.setup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一列车的启动流程状态机：钥匙 → 受电 → 主断路器（仅电力）→ 辅助电源，全部接通才允许牵引。
 *
 * <p>每个系统有三种状态：断开、接通中（等待耗时走完）、已接通。规则：
 *
 * <ul>
 *   <li>接通一个系统要求它的前一步已接通；同一时刻只有一个系统在接通中；
 *   <li>断开一个系统要求它的后一步已断开（例如必须先断主断路器才能降弓）；钥匙例外，拔钥匙只让驾驶室失效，不影响列车已接通的系统，便于换端；
 *   <li>一键启动（{@link #startAll}）按顺序自动接通剩下的步骤；一键关机（{@link #shutdown}）倒序全部断开。
 * </ul>
 *
 * <p>时间用调用方传入的服务器 tick。本类不依赖任何服务器对象。
 */
public final class TrainSetup {

  /** 系统状态。 */
  public enum State {
    OFF,
    STARTING,
    ON
  }

  /** 一次开关操作的结果。 */
  public enum ToggleResult {
    /** 开始接通。 */
    STARTING,
    /** 已断开。 */
    SWITCHED_OFF,
    /** 前一步还没接通。 */
    NEEDS_PREREQUISITE,
    /** 后一步还接通着，不能先断这一步。 */
    INTERLOCKED,
    /** 已有系统正在接通。 */
    BUSY,
    /** 这种受电方式没有该系统。 */
    NOT_APPLICABLE
  }

  /**
   * 接通中的系统与剩余时间。
   *
   * @param system 正在接通的系统
   * @param remainingTicks 剩余 tick
   */
  public record Progress(SetupSystem system, long remainingTicks) {}

  private final PowerSupply supply;
  private final SetupTimings timings;
  private final List<SetupSystem> sequence;
  private final Map<SetupSystem, State> states = new EnumMap<>(SetupSystem.class);
  private SetupSystem starting;
  private long startingDoneTick;
  private boolean autoStart;

  public TrainSetup(PowerSupply supply, SetupTimings timings) {
    this.supply = Objects.requireNonNull(supply, "supply");
    this.timings = Objects.requireNonNull(timings, "timings");
    List<SetupSystem> order = new ArrayList<>();
    for (SetupSystem system : SetupSystem.values()) {
      if (system != SetupSystem.BREAKER || supply.electric()) {
        order.add(system);
      }
    }
    this.sequence = List.copyOf(order);
    for (SetupSystem system : sequence) {
      states.put(system, State.OFF);
    }
  }

  /** 全部已接通的状态机：不需要启动流程的仿真等级使用。 */
  public static TrainSetup alwaysReady(PowerSupply supply, SetupTimings timings) {
    TrainSetup setup = new TrainSetup(supply, timings);
    for (SetupSystem system : setup.sequence) {
      setup.states.put(system, State.ON);
    }
    return setup;
  }

  public PowerSupply supply() {
    return supply;
  }

  /** 这种受电方式下的启动顺序。 */
  public List<SetupSystem> sequence() {
    return sequence;
  }

  /** 该受电方式是否有这个系统。 */
  public boolean applies(SetupSystem system) {
    return states.containsKey(system);
  }

  /** 系统状态；没有该系统时视为断开。 */
  public State state(SetupSystem system) {
    return states.getOrDefault(system, State.OFF);
  }

  /** 是否全部接通，可以牵引。 */
  public boolean ready() {
    for (State state : states.values()) {
      if (state != State.ON) {
        return false;
      }
    }
    return true;
  }

  /** 主电路是否得电：受电、主断路器（仅电力）与辅助电源都已接通。不看钥匙，驾驶员离开后列车仍可保持得电。 */
  public boolean mainCircuitPowered() {
    for (SetupSystem system : sequence) {
      if (system != SetupSystem.KEY && state(system) != State.ON) {
        return false;
      }
    }
    return true;
  }

  /** 按启动顺序第一个还没接通的系统；全部接通时为空。 */
  public Optional<SetupSystem> nextStep() {
    for (SetupSystem system : sequence) {
      if (state(system) != State.ON) {
        return Optional.of(system);
      }
    }
    return Optional.empty();
  }

  /** 是否有系统正在接通，或一键启动尚未完成。 */
  public boolean busy() {
    return starting != null || autoStart;
  }

  /** 是否正在执行一键启动。 */
  public boolean autoStarting() {
    return autoStart;
  }

  /** 正在接通的系统与剩余时间；没有时为空。 */
  public Optional<Progress> progress(long nowTick) {
    if (starting == null) {
      return Optional.empty();
    }
    return Optional.of(new Progress(starting, Math.max(0L, startingDoneTick - nowTick)));
  }

  /**
   * 恢复列车上保存的已接通系统。
   *
   * <p>只接受从受电开始连续接通的前缀（例如保存了辅助电源却没有主断路器，就只恢复到受电），避免恢复出违反顺序的状态。
   */
  public void restore(Set<SetupSystem> persistedOn) {
    Objects.requireNonNull(persistedOn, "persistedOn");
    for (SetupSystem system : sequence) {
      if (!system.persistent()) {
        continue;
      }
      if (!persistedOn.contains(system)) {
        break;
      }
      states.put(system, State.ON);
    }
  }

  /** 应随列车保存的已接通系统。 */
  public Set<SetupSystem> persistentOn() {
    Set<SetupSystem> on = EnumSet.noneOf(SetupSystem.class);
    for (Map.Entry<SetupSystem, State> entry : states.entrySet()) {
      if (entry.getKey().persistent() && entry.getValue() == State.ON) {
        on.add(entry.getKey());
      }
    }
    return on;
  }

  /** 切换一个系统：断开的开始接通，接通的断开。 */
  public ToggleResult toggle(SetupSystem system, long nowTick) {
    if (!applies(system)) {
      return ToggleResult.NOT_APPLICABLE;
    }
    if (starting != null) {
      return ToggleResult.BUSY;
    }
    autoStart = false;
    if (state(system) == State.ON) {
      return switchOff(system);
    }
    return beginStart(system, nowTick);
  }

  /** 一键启动：从第一个没接通的步骤起按顺序接通，直到全部接通。 */
  public void startAll(long nowTick) {
    if (ready()) {
      return;
    }
    autoStart = true;
    advanceAuto(nowTick);
  }

  /** 一键关机：取消正在进行的接通，倒序断开全部系统（含钥匙）。 */
  public void shutdown() {
    autoStart = false;
    starting = null;
    for (SetupSystem system : sequence) {
      states.put(system, State.OFF);
    }
  }

  /** 驾驶员离开：钥匙随驾驶员离开，正在进行的接通作废；列车已接通的系统保持。 */
  public void cabDeactivated() {
    autoStart = false;
    if (starting != null) {
      states.put(starting, State.OFF);
      starting = null;
    }
    if (applies(SetupSystem.KEY)) {
      states.put(SetupSystem.KEY, State.OFF);
    }
  }

  /**
   * 推进时间：接通中的系统到时完成，一键启动接着开始下一步。
   *
   * @return 状态是否有变化
   */
  public boolean tick(long nowTick) {
    boolean changed = false;
    if (starting != null && nowTick >= startingDoneTick) {
      states.put(starting, State.ON);
      starting = null;
      changed = true;
    }
    if (autoStart && starting == null) {
      changed |= advanceAuto(nowTick);
    }
    return changed;
  }

  private boolean advanceAuto(long nowTick) {
    for (SetupSystem system : sequence) {
      if (state(system) == State.OFF) {
        return beginStart(system, nowTick) == ToggleResult.STARTING;
      }
    }
    autoStart = false;
    return false;
  }

  private ToggleResult beginStart(SetupSystem system, long nowTick) {
    Optional<SetupSystem> previous = neighbour(system, -1);
    if (previous.isPresent() && state(previous.get()) != State.ON) {
      autoStart = false;
      return ToggleResult.NEEDS_PREREQUISITE;
    }
    int ticks = timings.ticksOf(system, supply);
    states.put(system, State.STARTING);
    starting = system;
    startingDoneTick = nowTick + ticks;
    if (ticks == 0) {
      tick(nowTick);
    }
    return ToggleResult.STARTING;
  }

  private ToggleResult switchOff(SetupSystem system) {
    Optional<SetupSystem> next = neighbour(system, 1);
    if (system != SetupSystem.KEY && next.isPresent() && state(next.get()) != State.OFF) {
      return ToggleResult.INTERLOCKED;
    }
    states.put(system, State.OFF);
    return ToggleResult.SWITCHED_OFF;
  }

  /** 启动顺序里的前一步（{@code -1}）或后一步（{@code 1}）。钥匙之后的一步才依赖钥匙，钥匙本身没有后续联锁。 */
  private Optional<SetupSystem> neighbour(SetupSystem system, int offset) {
    int index = sequence.indexOf(system) + offset;
    if (index < 0 || index >= sequence.size()) {
      return Optional.empty();
    }
    return Optional.of(sequence.get(index));
  }
}
