package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;

/**
 * 车掌在一次停站里的作业与时限。
 *
 * <p>站台推进停站阶段（停妥 → 停站 → 等关门 → 等发车 → 放行），车掌会话每 tick 按阶段计时；站台在等发车那一步每秒问一次是否还扣着。
 *
 * <ul>
 *   <li>开门：停妥后 {@link GuardConfig#openDoorsTicks()} 不开，由站台代开，记一次超时；
 *   <li>关门：停站时间到后 {@link GuardConfig#closeDoorsTicks()} 不关，由站台代关，记一次超时；
 *   <li>发车铃：门全关后只累计出站放行着的时间，满 {@link GuardConfig#departSignalTicks()} 还没按，传送回座、代发发车信号，记一次超时。
 * </ul>
 *
 * <p>异常情况报告把当前这一步的时限延长 {@link GuardConfig#incidentExtensionTicks()}，每站最多 {@link
 * GuardConfig#incidentMaxPerStop()} 次。只在服务器主线程使用，不依赖服务器对象。
 */
public final class GuardStopWork {

  /** 时限到了，要由站台代做的动作。 */
  public enum Action {
    NONE,
    /** 代开站台侧的门。 */
    FORCE_OPEN,
    /** 代关车门。 */
    FORCE_CLOSE
  }

  /** 出发确认的结果。 */
  public enum Confirm {
    /** 已确认。 */
    CONFIRMED,
    /** 本站已经确认过。 */
    ALREADY,
    /** 车门还没关好（还没到等发车这一步）。 */
    DOORS_OPEN,
    /** 出站信号未开放。 */
    EXIT_CLOSED,
    /** 已放行，不用再确认。 */
    RELEASED
  }

  /** 一长发车铃的结果。 */
  public enum Signal {
    /** 发车信号已发出。 */
    GIVEN,
    /** 已经发过。 */
    ALREADY,
    /** 车门还没关好。 */
    DOORS_OPEN,
    /** 还没确认出发信号。 */
    NOT_CONFIRMED,
    /** 不在车掌座位上。 */
    NOT_SEATED
  }

  /** 出站门控放行着时站台每隔这么久问一次；两次询问相隔更久，说明中间门控关过。 */
  static final long DEPARTURE_POLL_TICKS = 20L;

  private final GuardConfig config;
  private Phase lastPhase;
  private long stepTicks;
  private long extensionTicks;
  private int incidents;
  private boolean timedOut;
  private boolean forcedOpen;
  private boolean forcedClose;
  private boolean forcedSignal;
  private boolean confirmed;
  private boolean signalGiven;
  private boolean released;
  private boolean exitOpen;
  private long departOpenTicks;
  private long lastQueryTick = -1L;

  /** 站台上次问时出站是否放行：两次都放行着，中间那段才算。 */
  private boolean lastQueryOpen;

  public GuardStopWork(GuardConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * 每 tick 按站台推进到的阶段计时。
   *
   * @return 时限到了要由站台代做的动作（每步只返回一次）
   */
  public Action tick(Phase phase) {
    if (phase != lastPhase) {
      lastPhase = phase;
      stepTicks = 0L;
      extensionTicks = 0L;
    }
    if (phase == Phase.OPEN_DOORS) {
      stepTicks++;
      if (!forcedOpen && stepTicks > config.openDoorsTicks() + extensionTicks) {
        forcedOpen = true;
        timedOut = true;
        return Action.FORCE_OPEN;
      }
    } else if (phase == Phase.CLOSE_DOORS) {
      stepTicks++;
      if (!forcedClose && stepTicks > config.closeDoorsTicks() + extensionTicks) {
        forcedClose = true;
        timedOut = true;
        return Action.FORCE_CLOSE;
      }
    }
    return Action.NONE;
  }

  /**
   * 站台在等发车那一步每秒问一次：车掌放行了没有。
   *
   * <p>前方不放行（{@code exitOpen} 为假）时一直扣着，那段时间不计时；相邻两次询问都放行着，中间那段才累计，满时限即代发发车信号（记一次超时）。
   *
   * @param exitOpen 此刻出站门控是否放行
   * @param now 当前 tick
   * @return 是否还扣着
   */
  public boolean holdDeparture(boolean exitOpen, long now) {
    if (released) {
      return false;
    }
    this.exitOpen = exitOpen;
    long sinceLast = lastQueryTick < 0L ? Long.MAX_VALUE : now - lastQueryTick;
    boolean openBefore = lastQueryOpen;
    lastQueryTick = now;
    lastQueryOpen = exitOpen;
    if (!exitOpen) {
      return true;
    }
    if (openBefore && sinceLast <= DEPARTURE_POLL_TICKS) {
      departOpenTicks += sinceLast;
    }
    if (signalGiven) {
      released = true;
      return false;
    }
    if (departOpenTicks >= config.departSignalTicks() + extensionForStep(Phase.WAIT_DEPARTURE)) {
      forcedSignal = true;
      timedOut = true;
      released = true;
      return false;
    }
    return true;
  }

  /**
   * 出发确认：出站开放时按下即确认，确认在列车动之前一直有效。
   *
   * @param phase 站台此刻的停站阶段
   */
  public Confirm confirm(Phase phase) {
    if (released) {
      return Confirm.RELEASED;
    }
    if (confirmed) {
      return Confirm.ALREADY;
    }
    if (phase != Phase.WAIT_DEPARTURE) {
      return Confirm.DOORS_OPEN;
    }
    if (!exitOpen) {
      return Confirm.EXIT_CLOSED;
    }
    confirmed = true;
    return Confirm.CONFIRMED;
  }

  /**
   * 一长发车铃：门全关、已回座、已确认出发信号才算发车信号。
   *
   * @param phase 站台此刻的停站阶段
   * @param seated 车掌是否坐在自己的座位上
   */
  public Signal signal(Phase phase, boolean seated) {
    if (signalGiven || released) {
      return Signal.ALREADY;
    }
    if (phase != Phase.WAIT_DEPARTURE) {
      return Signal.DOORS_OPEN;
    }
    if (!seated) {
      return Signal.NOT_SEATED;
    }
    if (!confirmed) {
      return Signal.NOT_CONFIRMED;
    }
    signalGiven = true;
    return Signal.GIVEN;
  }

  /**
   * 异常情况报告：当前这一步的时限延长一次。
   *
   * @return 本站还能报告、已延长时为真
   */
  public boolean report() {
    if (released || incidents >= config.incidentMaxPerStop()) {
      return false;
    }
    incidents++;
    extensionTicks += config.incidentExtensionTicks();
    return true;
  }

  private long extensionForStep(Phase phase) {
    return lastPhase == phase ? extensionTicks : 0L;
  }

  /** 本站报告过几次。 */
  public int incidents() {
    return incidents;
  }

  /** 本站还能不能报告。 */
  public boolean canReport() {
    return !released && incidents < config.incidentMaxPerStop();
  }

  /** 本站有没有超时（站台代开、代关或代发发车信号）。 */
  public boolean timedOut() {
    return timedOut;
  }

  public boolean forcedOpen() {
    return forcedOpen;
  }

  public boolean forcedClose() {
    return forcedClose;
  }

  /** 发车铃超时，由站台代发了发车信号：车掌要被传送回座位。 */
  public boolean forcedSignal() {
    return forcedSignal;
  }

  public boolean confirmed() {
    return confirmed;
  }

  public boolean signalGiven() {
    return signalGiven;
  }

  /** 车掌这边已放行（发了发车信号或超时代发）。 */
  public boolean released() {
    return released;
  }

  /** 站台上次问时出站是否放行。 */
  public boolean exitOpen() {
    return exitOpen;
  }

  /**
   * 当前这一步还剩多少 tick（含报告的延长）；不计时的阶段为 -1。
   *
   * @param phase 站台此刻的停站阶段
   */
  public long remainingTicks(Phase phase) {
    if (phase != lastPhase) {
      return -1L;
    }
    return switch (phase) {
      case OPEN_DOORS -> Math.max(0L, config.openDoorsTicks() + extensionTicks - stepTicks);
      case CLOSE_DOORS -> Math.max(0L, config.closeDoorsTicks() + extensionTicks - stepTicks);
      case WAIT_DEPARTURE -> released
          ? -1L
          : Math.max(0L, config.departSignalTicks() + extensionTicks - departOpenTicks);
      default -> -1L;
    };
  }
}
