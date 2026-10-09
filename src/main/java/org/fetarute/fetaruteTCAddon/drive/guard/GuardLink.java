package org.fetarute.fetaruteTCAddon.drive.guard;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;

/**
 * 一名车掌与一列车之间的链路：站台交来的停站、这一站的作业与时限、连续几站有超时。
 *
 * <p>车上有车掌时车门归车掌：站台把停站交给它（与驾驶员控车时同一个 {@link DriverStationStop}），车掌会话按它开关门、回报车门；
 * 站台在等发车那一步问它是否还扣着。只在服务器主线程读写。
 */
public final class GuardLink {

  private final UUID playerId;
  private final String trainName;
  private final LongSupplier clock;
  private GuardConfig config;
  private TrainProperties properties;
  private DriverStationStop stop;
  private GuardStopWork work;
  private boolean settled = true;
  private int consecutiveTimeoutStops;
  private int completedStops;
  private int timeoutStops;

  /** 紧急停车扣到这个 tick 自动解除；没扣着时为 -1。 */
  private long emergencyUntilTick = -1L;

  private boolean cabHold;
  private boolean turnbackPending;
  private final java.util.ArrayDeque<Settled> settledStops = new java.util.ArrayDeque<>();

  /**
   * 结算过的一站，等车掌会话记进这一趟的成绩。
   *
   * @param stop 站台的停站对象
   * @param work 这一站的作业
   */
  public record Settled(DriverStationStop stop, GuardStopWork work) {}

  /**
   * @param clock 当前服务器 tick
   */
  public GuardLink(
      UUID playerId,
      String trainName,
      TrainProperties properties,
      GuardConfig config,
      LongSupplier clock) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.trainName = Objects.requireNonNull(trainName, "trainName");
    this.properties = properties;
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public UUID playerId() {
    return playerId;
  }

  /** 绑定时的车名（调度改名后仍用它找回）。 */
  public String trainName() {
    return trainName;
  }

  public TrainProperties properties() {
    return properties;
  }

  /** 列车属性对象被换掉（区块重载等）时重新挂上。 */
  public void rebind(TrainProperties properties) {
    this.properties = properties;
  }

  /** 重载配置后换用新的时限（进行中的这一站仍按旧的）。 */
  public void setConfig(GuardConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * 站台交来一次停站：上一站的作业结算，开始这一站。
   *
   * @param next 站台的停站对象
   */
  public void beginStationStop(DriverStationStop next) {
    settle();
    this.stop = Objects.requireNonNull(next, "next");
    this.work = new GuardStopWork(config);
    this.settled = false;
  }

  /** 进行中的停站；已结束或没有时为空。 */
  public Optional<DriverStationStop> stationStop() {
    return stop != null && stop.active() ? Optional.of(stop) : Optional.empty();
  }

  /** 最近一次停站（结束了也留着，直到下一站；出站监视要看它）。 */
  public Optional<DriverStationStop> lastStop() {
    return Optional.ofNullable(stop);
  }

  /** 这一站的作业；还没停过站时为空。 */
  public Optional<GuardStopWork> work() {
    return Optional.ofNullable(work);
  }

  /**
   * 站台在等发车那一步每秒问一次。
   *
   * @param exitOpen 此刻出站门控是否放行
   * @return 是否还扣着等车掌的发车信号
   */
  public boolean holdDeparture(boolean exitOpen) {
    return stop != null
        && stop.active()
        && work != null
        && work.holdDeparture(exitOpen, clock.getAsLong());
  }

  /** 当前服务器 tick。 */
  public long now() {
    return clock.getAsLong();
  }

  /** 紧急停车扣着：自动运行（含 ATO）的车停住后调度不替它起步（按驾驶员控制处理），直到车掌解除或到时限。人工驾驶的车由驾驶员制动，不在这里扣。 */
  public boolean emergencyHold() {
    return emergencyUntilTick >= 0L;
  }

  /**
   * 拉下紧急停车并扣住。
   *
   * @param untilTick 到这个 tick 自动解除
   */
  public void latchEmergency(long untilTick) {
    this.emergencyUntilTick = Math.max(0L, untilTick);
  }

  /** 扣着的紧急停车已到时限。 */
  public boolean emergencyExpired() {
    return emergencyUntilTick >= 0L && clock.getAsLong() >= emergencyUntilTick;
  }

  /** 解除紧急停车。 */
  public void releaseEmergency() {
    this.emergencyUntilTick = -1L;
  }

  /** 车掌扣着列车（紧急停车或换端扣车）：调度层按驾驶员控制处理，不替它起步。 */
  public boolean holdsTrain() {
    return emergencyHold() || cabHold;
  }

  /** 换端扣车：没有人工驾驶的驾驶员时，从终点站待命起扣着列车，派车放行时只调头、不发车，车掌坐进车尾端后解除，交回自动运行发车。 期间调度层按驾驶员控制处理。 */
  public boolean cabHold() {
    return cabHold;
  }

  /**
   * @return 状态是否有变化
   */
  public boolean setCabHold(boolean hold) {
    if (cabHold == hold) {
      return false;
    }
    cabHold = hold;
    return true;
  }

  /** 只有车掌的列车停在终点站待命、派车还没放行：放行那一拍要按发车方向调头。 */
  public boolean turnbackPending() {
    return turnbackPending;
  }

  public void setTurnbackPending(boolean pending) {
    this.turnbackPending = pending;
  }

  /**
   * 派车放行：取走待调头标记。
   *
   * @return 放行前是否在等调头
   */
  public boolean takeTurnback() {
    boolean pending = turnbackPending;
    turnbackPending = false;
    return pending;
  }

  /**
   * 这一站作业结束（列车开走、停站作废或下一站开始）：记入连续超时与完成站数。重复调用只记一次。
   *
   * @return 这一站是否有超时；本来就已结算时为空
   */
  public Optional<Boolean> settle() {
    if (settled || work == null) {
      return Optional.empty();
    }
    settled = true;
    settledStops.add(new Settled(stop, work));
    boolean timedOut = work.timedOut();
    if (timedOut) {
      consecutiveTimeoutStops++;
      timeoutStops++;
    } else {
      consecutiveTimeoutStops = 0;
    }
    completedStops++;
    return Optional.of(timedOut);
  }

  /** 取走结算过、还没记进成绩的站（按结算顺序）。 */
  public java.util.List<Settled> drainSettled() {
    java.util.List<Settled> drained = new java.util.ArrayList<>(settledStops);
    settledStops.clear();
    return drained;
  }

  /** 连续几站有超时（完成一站不超时即清零）。 */
  public int consecutiveTimeoutStops() {
    return consecutiveTimeoutStops;
  }

  /** 连续超时已达到配置的站数：车掌任务要结束。 */
  public boolean tooManyTimeouts() {
    return config.endAfterTimeouts() > 0 && consecutiveTimeoutStops >= config.endAfterTimeouts();
  }

  /** 已结算的停站数。 */
  public int completedStops() {
    return completedStops;
  }

  /** 有超时的停站数。 */
  public int timeoutStops() {
    return timeoutStops;
  }
}
