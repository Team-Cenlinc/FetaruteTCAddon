package org.fetarute.fetaruteTCAddon.drive.session;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.cab.CabTick;
import org.fetarute.fetaruteTCAddon.drive.cab.Vigilance;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidance;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverGuidanceConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection;
import org.fetarute.fetaruteTCAddon.drive.driver.SignalConfirm;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveDynamics;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.NotchSelector;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.hud.OverspeedLevel;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarRewriter;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupSystem;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCueTracker;

/**
 * 一名驾驶员对一列车的手动驾驶会话。
 *
 * <p>会话保存档位、动力学状态和座位绑定；逐 tick 的速度推进由 {@link ManualDriveAction} 调用 {@link #advance}。
 * 所有方法只能在服务器主线程调用，{@link #phase()}（volatile）与 {@link #rewriter()}（不可变）例外，网络线程会读取它们。
 *
 * <p>会话有三个阶段：{@link Phase#ACTIVE 驾驶中}；驾驶员离开后进入 {@link Phase#STOPPING 制动停车}，由会话自己把列车刹停，
 * 以免无人驾驶的列车一路冲出去；停稳后 {@link Phase#ENDED 结束}。
 */
public final class DriveSession {

  /** 会话阶段。 */
  public enum Phase {
    ACTIVE,
    STOPPING,
    ENDED
  }

  /** 会话结束的原因。 */
  public enum EndReason {
    COMMAND,
    LEFT_SEAT,
    SEAT_LOST,
    TRAIN_GONE,
    OFFLINE,
    GAME_MODE,
    DEATH,
    ADMIN,
    DISABLED,
    /** 驾驶调度列车：交还自动运行（驾驶员离开、调度层请求、管理员或总开关）。 */
    HANDBACK,
    /** 驾驶调度列车：调度层要销毁这列车或列车已异常。 */
    DISPATCH_ABORT,
    /** 驾驶调度列车：任务完成。 */
    TASK_COMPLETE,
    /** 驾驶调度列车：卡住太久，被看门狗收回。 */
    WATCHDOG,
    /** 驾驶调度列车：折返换端没能在时限内坐进前端驾驶室，交还自动运行发车。 */
    CAB_CHANGE_TIMEOUT
  }

  private static final double TICKS_PER_SECOND = 20.0;
  private static final double STEP_SECONDS = 1.0 / TICKS_PER_SECOND;

  /** 车厢实际速度低于它（blocks/tick）视为停住，与 TrainCarts 的 launch 判定相同。 */
  private static final double STALLED_BPT = 0.001;

  /** 我们命令的速度高于它（格/秒）而车厢停住，才算被挡住。 */
  private static final double STALL_DETECT_BPS = 0.2;

  /** 连续多少 tick 被判为停住才确认被挡（跳过传送、动作重建后的瞬时读数）。 */
  private static final int STALL_CONFIRM_TICKS = 3;

  /** 驾驶员离座或已离开时列车自动使用的常用制动档。 */
  private static final Notch UNATTENDED_NOTCH = Notch.B3;

  /** 失效导向安全的制动：制动力不打折。 */
  private static final DoubleUnaryOperator FULL_BRAKE = demand -> 1.0;

  /** 两次调头之间至少间隔的 tick，防止座位序号判断异常时来回调头。 */
  private static final int MIN_REVERSE_INTERVAL_TICKS = 10;

  private final UUID playerId;
  private final String playerName;
  private final DriveConfig config;
  private final DriveDynamics dynamics;
  private final NotchSelector selector = new NotchSelector();
  private final List<ItemStack> hotbar;
  private final HotbarRewriter<ItemStack> rewriter;

  private SeatBinding binding;
  private volatile Phase phase = Phase.ACTIVE;
  private EndReason endReason;
  private ReverserPosition reverser = ReverserPosition.FORWARD;
  private boolean leftDoorOpen;
  private boolean rightDoorOpen;
  private long doorsClosingUntilTick = Long.MIN_VALUE;
  private volatile int menuTopSize;
  private volatile boolean rewriteFailed;
  private boolean seated = true;
  private boolean ambiguousCabReversed;
  private long lastReverseTick = Long.MIN_VALUE / 2;
  private int originalHeldSlot = -1;
  private long lastAdvanceTick = Long.MIN_VALUE / 2;
  private long lastSneakTick = Long.MIN_VALUE / 2;
  private long seatLostSinceTick = -1;
  private long groupMissingSinceTick = -1;
  private double lastCapBps;
  private final SpeedLimitTracker speedLimit;
  private final TrainSetup setup;
  private final CabSystems cab;
  private Vigilance.Event pendingVigilanceEvent = Vigilance.Event.NONE;
  private long actionBarHeldUntil = Long.MIN_VALUE;
  private int stallTicks;
  private int actionGeneration;
  private DriverStationStop.Phase lastStationPhase;
  private DriverLink driverLink;
  private double odometerBlocks;
  private boolean adviceBraking;
  private ScoreRules.Result liveScore;
  private long endArmedUntilTick = Long.MIN_VALUE;
  private final CabChange cabChange = new CabChange();

  /** 不需要启动流程的会话（列车已就绪）。 */
  public DriveSession(
      UUID playerId,
      String playerName,
      SeatBinding binding,
      DriveParams params,
      DriveConfig config,
      List<ItemStack> hotbar) {
    this(
        playerId,
        playerName,
        binding,
        params,
        config,
        hotbar,
        TrainSetup.alwaysReady(config.defaultPower(), config.setupTimings()),
        CabSystems.disabled());
  }

  /**
   * @param setup 这列车的启动流程状态；全部接通之前不能牵引
   * @param cab simulation 级的车上系统；standard 级传 {@link CabSystems#disabled()}
   */
  public DriveSession(
      UUID playerId,
      String playerName,
      SeatBinding binding,
      DriveParams params,
      DriveConfig config,
      List<ItemStack> hotbar,
      TrainSetup setup,
      CabSystems cab) {
    this.setup = Objects.requireNonNull(setup, "setup");
    this.cab = Objects.requireNonNull(cab, "cab");
    this.playerId = Objects.requireNonNull(playerId, "playerId");
    this.playerName = Objects.requireNonNull(playerName, "playerName");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.config = Objects.requireNonNull(config, "config");
    this.dynamics = new DriveDynamics(Objects.requireNonNull(params, "params"), config);
    this.hotbar = List.copyOf(hotbar);
    this.rewriter = new HotbarRewriter<>(this.hotbar, () -> menuTopSize);
    this.speedLimit = new SpeedLimitTracker(config.speedLimitOverride());
  }

  public UUID playerId() {
    return playerId;
  }

  public String playerName() {
    return playerName;
  }

  public SeatBinding binding() {
    return binding;
  }

  /**
   * 更新座位绑定。编组调头后车厢序号会翻转，所以驾驶员在座位上时每个 tick 都要用最新位置刷新。
   *
   * @throws IllegalArgumentException 新绑定属于另一列车
   */
  public void rebind(SeatBinding newBinding) {
    Objects.requireNonNull(newBinding, "newBinding");
    if (!newBinding.trainName().equals(binding.trainName())) {
      throw new IllegalArgumentException("绑定的列车名不一致");
    }
    if (newBinding.memberIndex() != binding.memberIndex()) {
      // 换了一节车厢（或整列调头使序号翻转）：之前为“没有前后之分”的座位记的调头次数不再适用。
      ambiguousCabReversed = false;
    }
    this.binding = newBinding;
  }

  public String trainName() {
    return binding.trainName();
  }

  /** 调度给列车改了名（终点站复用接下一班）：座位绑定跟着新车名走，车厢与座位不变。 */
  public void followRename(String newTrainName) {
    this.binding = new SeatBinding(newTrainName, binding.memberIndex(), binding.seatIndex());
  }

  public DriveParams params() {
    return dynamics.params();
  }

  public Phase phase() {
    return phase;
  }

  public EndReason endReason() {
    return endReason;
  }

  public List<ItemStack> hotbar() {
    return hotbar;
  }

  public HotbarRewriter<ItemStack> rewriter() {
    return rewriter;
  }

  public NotchSelector selector() {
    return selector;
  }

  /**
   * 当前生效的档位。
   *
   * <p>驾驶员在座时取手柄档位；制动停车阶段、驾驶员暂时离座或正在折返换端时取自动制动档。牵引档在{@link #tractionBlocked() 牵引被封锁}时按惰行处理， 制动不受影响。
   */
  public Notch notch() {
    if (!attended()) {
      return UNATTENDED_NOTCH;
    }
    Notch current = selector.current();
    return current.isTraction() && tractionBlocked() ? Notch.N : current;
  }

  /** 驾驶员在驾驶室操纵：会话驾驶中、在座，且不在折返换端途中（换端时坐在原来那一端也不算在岗）。 */
  private boolean attended() {
    return phase == Phase.ACTIVE && seated && !cabChange.holding();
  }

  /** 折返换端的进度。 */
  public CabChange cabChange() {
    return cabChange;
  }

  /** 牵引是否被封锁：列车尚未启动、换向手柄在空挡、有车门没关（含关门动画还没放完），或 simulation 级的车上系统不允许（故障、停放制动、制动管、风压、制动试验）。 */
  public boolean tractionBlocked() {
    return !setup.ready()
        || cab.tractionBlock().isPresent()
        || reverser == ReverserPosition.NEUTRAL
        || anyDoorOpen()
        || doorsClosing(lastAdvanceTick);
  }

  /** 我们命令的当前速度（格/秒）。 */
  public double speedBps() {
    return dynamics.speedBps();
  }

  /** 最近一次推进时的速度上限（格/秒）。 */
  public double capBps() {
    return lastCapBps;
  }

  /**
   * 记下接管时写给 TrainCarts 的速度上限（blocks/tick）。
   *
   * <p>允许超速时，之后每次推进都会把它还原回去，并把牌子等途径设定的新上限记作提示用的限速。
   */
  public void setGuardedSpeedLimit(double blocksPerTick) {
    speedLimit.guard(blocksPerTick);
  }

  /** 接管后线路给出的最近一个限速（格/tick）；会话结束时列车应保留它。 */
  public OptionalDouble observedSpeedLimitBpt() {
    return speedLimit.observedLimitBpt();
  }

  /**
   * 动作栏上显示的限速（格/秒）；没有限速信息时为 {@code NaN}。
   *
   * <p>允许超速时，它是接管之后被牌子等途径设定的限速（车速可以超过它）；否则就是实际生效的速度上限。
   */
  public double displayLimitBps() {
    if (isAto()) {
      // ATO 下由自动运行控车，驾驶员看不到行车许可。
      return Double.NaN;
    }
    if (driverLink != null && driverLink.lastDecision() != null) {
      return driverLink.lastDecision().permittedBps();
    }
    return speedLimit.displayLimitBps(lastCapBps);
  }

  /** 上一次记进诊断日志的停站阶段（只用于在阶段变化时记一条）。 */
  public DriverStationStop.Phase lastStationPhase() {
    return lastStationPhase;
  }

  public void setLastStationPhase(DriverStationStop.Phase phase) {
    this.lastStationPhase = phase;
  }

  /** 驾驶调度列车时的控制链路；驾驶非调度列车时为 {@code null}。 */
  public DriverLink driverLink() {
    return driverLink;
  }

  /** 接上调度列车的控制链路。 */
  public void attachDriverLink(DriverLink link) {
    this.driverLink = link;
  }

  /** 是否在驾驶调度列车。 */
  public boolean isDispatchDriving() {
    return driverLink != null;
  }

  /** ATO：自动运行操纵列车，驾驶员只确认发车，可拉 EB 转为人工驾驶。 */
  public boolean isAto() {
    return driverLink != null && !driverLink.controlsPhysically();
  }

  /** ATO 下没有控车动作推进：按实测车速累计里程（卡住计时、指令后的里程都靠它）。 */
  public void addOdometer(double blocks) {
    if (blocks > 0.0 && Double.isFinite(blocks)) {
      odometerBlocks += blocks;
    }
  }

  /**
   * 算此刻的行车引导：前方目标、距离、建议速度与是否提示开始制动（回差按上一次的结论）。
   *
   * <p>ATO 下调度不向驾驶员下发行车许可，只按前方停车点给目标与距离，不给建议速度、不提示制动。
   *
   * @return 驾驶非调度列车时为空
   */
  public Optional<DriverGuidance.Advice> updateGuidance(DriverGuidanceConfig guidance) {
    DriverLink link = driverLink;
    if (link == null) {
      adviceBraking = false;
      return Optional.empty();
    }
    boolean physically = link.controlsPhysically();
    DriverDirective directive = physically ? link.directive() : null;
    DriverProtection.Decision decision = physically ? link.lastDecision() : null;
    double permitted =
        decision != null
            ? decision.permittedBps()
            : directive != null ? directive.permittedBps() : speedBps();
    double requested = directive == null ? Double.POSITIVE_INFINITY : directive.requestedBps();
    // 停车信号没给距离（就地停车）按停车点就在车头处。
    double stopSignal =
        directive != null && directive.isStop()
            ? directive.distanceBlocks().isPresent() ? link.authorityAheadBlocks() : 0.0
            : Double.NaN;
    List<SignalLookahead.EdgeSpeedConstraint> edges =
        directive == null || directive.envelope() == null
            ? List.of()
            : directive.envelope().edgeLimits();
    DriverLink.StationTarget station = link.stationTarget().orElse(null);
    double serviceDecel =
        dynamics.params().decelBps2() * config.brakeFraction(Notch.B4) * cab.brakeScale();
    DriverGuidance.Advice advice =
        DriverGuidance.evaluate(
            new DriverGuidance.Input(
                speedBps(),
                isStopped(),
                requested,
                permitted,
                stopSignal,
                station == null ? Double.NaN : station.remainingBlocks(),
                edges,
                link.travelledSinceDirective(),
                serviceDecel,
                1.0 / (2.0 * config.effortRatePerSecond()),
                config.driver().stopMarginBlocks(),
                adviceBraking),
            guidance);
    if (!physically && advice.brake()) {
      advice = new DriverGuidance.Advice(advice.target(), advice.suggestedBps(), false);
    }
    adviceBraking = advice.brake();
    return Optional.of(advice);
  }

  /**
   * 这一帧给提示音用的驾驶状态。
   *
   * @param advice 这一帧的行车引导；驾驶非调度列车时为 {@code null}
   */
  public DriveCueTracker.Snapshot cueSnapshot(DriverGuidance.Advice advice) {
    DriverLink link = driverLink;
    boolean physically = link == null || link.controlsPhysically();
    boolean overspeedRed =
        physically
            && OverspeedLevel.classify(speedBps(), displayLimitBps(), overspeedRedRatio())
                == OverspeedLevel.OVER;
    if (link == null) {
      return new DriveCueTracker.Snapshot(
          false, -1, DriverProtection.Intervention.NONE, overspeedRed, false, null, false);
    }
    DriverDirective directive = physically ? link.directive() : null;
    int aspectRank =
        directive == null
            ? -1
            : switch (directive.aspect()) {
              case PROCEED -> 0;
              case PROCEED_WITH_CAUTION -> 1;
              case CAUTION -> 2;
              case STOP -> 3;
            };
    DriverProtection.Decision decision = physically ? link.lastDecision() : null;
    return new DriveCueTracker.Snapshot(
        physically && link.signalConfirm().pending(),
        aspectRank,
        decision == null ? DriverProtection.Intervention.NONE : decision.intervention(),
        overspeedRed,
        advice != null && advice.brake(),
        link.stationStop().map(DriverStationStop::phase).orElse(null),
        link.departurePending());
  }

  /** 驾驶调度列车时到此刻为止的成绩估算（侧边栏显示）；还没算过时为空。 */
  public Optional<ScoreRules.Result> liveScore() {
    return Optional.ofNullable(liveScore);
  }

  public void setLiveScore(ScoreRules.Result result) {
    this.liveScore = result;
  }

  /** 驾驶台的“结束驾驶”按钮点过一次，等再次点击确认，直到 {@code untilTick}。 */
  public void armEnd(long untilTick) {
    this.endArmedUntilTick = untilTick;
  }

  /** “结束驾驶”是否在等再次点击确认。 */
  public boolean endArmed(long nowTick) {
    return nowTick < endArmedUntilTick;
  }

  /** 让正在运行的控车动作自行退出（转为 ATO 时由自动运行接着操纵）。 */
  public void releaseAction() {
    nextActionGeneration();
  }

  /** 会话累计走过的距离（格），按积分速度计。 */
  public double odometerBlocks() {
    return odometerBlocks;
  }

  /** 立即施加紧急制动（停稳前不能缓解）。 */
  public void forceEmergency() {
    selector.force(Notch.EB);
  }

  /** 超出限速的比例达到它时标红，否则标黄。 */
  public double overspeedRedRatio() {
    return config.overspeedRedRatio();
  }

  /** 是否已停稳。 */
  public boolean isStopped() {
    return dynamics.speedBps() <= config.stoppedSpeedBps();
  }

  /** 这列车的启动流程状态。 */
  public TrainSetup setup() {
    return setup;
  }

  /** simulation 级的车上系统。 */
  public CabSystems cab() {
    return cab;
  }

  /** 当前的牵引（正）或制动（负）力度，满力为 1，紧急制动可超过 1。 */
  public double effort() {
    return dynamics.effort();
  }

  /** 紧急制动力相对常用制动的倍数，即力度的下限的绝对值。 */
  public double emergencyMultiplier() {
    return config.emergencyMultiplier();
  }

  /** 驾驶员有操作（换档、右键）：警惕装置重新计时。 */
  public void acknowledgeVigilance(long nowTick) {
    cab.acknowledge(nowTick);
  }

  /** 驾驶台提示占着动作栏到哪个 tick 为止；之前驾驶 HUD 不覆盖动作栏。 */
  public long actionBarHeldUntil() {
    return actionBarHeldUntil;
  }

  public void holdActionBar(long untilTick) {
    this.actionBarHeldUntil = untilTick;
  }

  /** 取出并清除上一次推进时警惕装置产生的事件。 */
  public Vigilance.Event takeVigilanceEvent() {
    Vigilance.Event event = pendingVigilanceEvent;
    pendingVigilanceEvent = Vigilance.Event.NONE;
    return event;
  }

  /** 本会话启动流程的操作方式（取会话开始时的仿真等级）。 */
  public SimulationLevel.SetupMode setupMode() {
    return config.level().setupMode();
  }

  /** 换向手柄的位置。 */
  public ReverserPosition reverser() {
    return reverser;
  }

  public void setReverser(ReverserPosition position) {
    this.reverser = Objects.requireNonNull(position, "position");
  }

  /** 左车门（按驾驶员面朝的方向）是否打开。 */
  public boolean isLeftDoorOpen() {
    return leftDoorOpen;
  }

  /** 右车门（按驾驶员面朝的方向）是否打开。 */
  public boolean isRightDoorOpen() {
    return rightDoorOpen;
  }

  public boolean anyDoorOpen() {
    return leftDoorOpen || rightDoorOpen;
  }

  /** 车门正在关：关门动画放到这个 tick 才结束。 */
  public void markDoorsClosing(long untilTick) {
    doorsClosingUntilTick = Math.max(doorsClosingUntilTick, untilTick);
  }

  /** 关门动画是否还在放（车门还没真正关上）。 */
  public boolean doorsClosing(long nowTick) {
    return !anyDoorOpen() && nowTick < doorsClosingUntilTick;
  }

  /**
   * 记录车门状态。
   *
   * @param left 是否为左车门；否则为右车门
   * @param open 是否打开
   */
  public void setDoorOpen(boolean left, boolean open) {
    if (left) {
      leftDoorOpen = open;
    } else {
      rightDoorOpen = open;
    }
  }

  /** 发给该驾驶员的背包数据包曾改写失败（客户端可能看到过真实物品）；网络线程会写入。 */
  public boolean rewriteFailed() {
    return rewriteFailed;
  }

  public void markRewriteFailed() {
    this.rewriteFailed = true;
  }

  /** 菜单窗口上半部分的槽位数；没有打开菜单时为 0。网络线程会读取它。 */
  public int menuTopSize() {
    return menuTopSize;
  }

  public void setMenuTopSize(int size) {
    this.menuTopSize = Math.max(0, size);
  }

  /**
   * 驾驶员所在的驾驶室当前是不是编组的车头端。
   *
   * <p>座位在编组前半的车厢时算车头端，后半算车尾端。单节车、以及奇数节编组正中间的车厢没有前后之分（调头后序号不变）， 这时改为记录我们调过几次头。
   *
   * @param memberCount 编组节数
   */
  public boolean cabAtHead(int memberCount) {
    if (hasNoFront(memberCount)) {
      return !ambiguousCabReversed;
    }
    return binding.cabSign(memberCount) > 0;
  }

  private boolean hasNoFront(int memberCount) {
    return memberCount <= 1 || ((memberCount & 1) == 1 && binding.memberIndex() == memberCount / 2);
  }

  /**
   * 驾驶员想去的方向是否就是编组车头指向的方向。
   *
   * <p>前进挡时驾驶员要朝自己面朝的方向走，后退挡时朝相反方向走；空挡不牵引，无所谓方向。TrainCarts 只会沿车头方向给正的前进力，所以两者不一致时必须先把整列调头。
   *
   * @param memberCount 编组节数
   */
  public boolean travelsTowardHead(int memberCount) {
    if (reverser == ReverserPosition.NEUTRAL) {
      return true;
    }
    return cabAtHead(memberCount) == (reverser == ReverserPosition.FORWARD);
  }

  /**
   * 现在能不能调头：已停稳，且距离上一次调头已过最小间隔。
   *
   * @param nowTick 当前服务器 tick
   */
  public boolean mayReverse(long nowTick) {
    return isStopped() && nowTick - lastReverseTick >= MIN_REVERSE_INTERVAL_TICKS;
  }

  /**
   * 记下我们刚把整列调了头。
   *
   * @param memberCount 编组节数
   * @param nowTick 当前服务器 tick
   */
  public void noteReversedByUs(int memberCount, long nowTick) {
    if (hasNoFront(memberCount)) {
      ambiguousCabReversed = !ambiguousCabReversed;
    }
    lastReverseTick = nowTick;
  }

  /** 会话开始前玩家选中的快捷栏槽位；结束时还原。尚未记录时为 -1。 */
  public int originalHeldSlot() {
    return originalHeldSlot;
  }

  public void setOriginalHeldSlot(int slot) {
    this.originalHeldSlot = slot;
  }

  /** 上一次推进发生的服务器 tick；用来发现动作被 TrainCarts 清掉了。 */
  public long lastAdvanceTick() {
    return lastAdvanceTick;
  }

  /** 记下动作刚被挂上的时刻，避免在它第一次推进之前被当作已丢失而重复挂载。 */
  public void touch(long nowTick) {
    lastAdvanceTick = nowTick;
  }

  /**
   * 开始新一代控车动作，返回它的代号。
   *
   * <p>旧一代动作下次更新时发现代号不符就自行结束，所以任何时刻最多只有一个动作在推进速度。
   */
  int nextActionGeneration() {
    return ++actionGeneration;
  }

  int actionGeneration() {
    return actionGeneration;
  }

  /** 以给定速度重新开始积分；接管已在运动的列车时使用。 */
  public void resetSpeed(double speedBps) {
    dynamics.reset(speedBps);
  }

  public void noteSneak(long nowTick) {
    this.lastSneakTick = nowTick;
  }

  /** 潜行后多少 tick 内离座视为主动离座。 */
  public boolean sneakedRecently(long nowTick) {
    return nowTick - lastSneakTick <= config.exitSneakWindowTicks();
  }

  /** 记录驾驶员离开座位，返回已离开多少 tick。 */
  public long markSeatLost(long nowTick) {
    seated = false;
    if (seatLostSinceTick < 0) {
      seatLostSinceTick = nowTick;
    }
    return nowTick - seatLostSinceTick;
  }

  public void markSeated() {
    seatLostSinceTick = -1;
    seated = true;
  }

  /** 记录找不到编组，返回已找不到多少 tick。 */
  public long markGroupMissing(long nowTick) {
    if (groupMissingSinceTick < 0) {
      groupMissingSinceTick = nowTick;
    }
    return nowTick - groupMissingSinceTick;
  }

  public void markGroupFound() {
    groupMissingSinceTick = -1;
  }

  /**
   * 驾驶员离开：转入制动停车。
   *
   * @return 是否转入了制动停车；已经结束或已在制动时返回 {@code false}
   */
  public boolean beginStopping(EndReason reason) {
    if (phase != Phase.ACTIVE) {
      return false;
    }
    this.endReason = reason;
    this.phase = Phase.STOPPING;
    return true;
  }

  /** 结束会话，不再控车。 */
  public void finish(EndReason reason) {
    if (phase == Phase.ENDED) {
      return;
    }
    if (endReason == null) {
      endReason = reason;
    }
    this.phase = Phase.ENDED;
    dynamics.reset(0.0);
  }

  /**
   * 推进一 tick：按档位积分速度，并在制动停车阶段停稳后结束会话。
   *
   * @param group 被驾驶的编组
   * @param nowTick 当前服务器 tick
   */
  void advance(MinecartGroup group, long nowTick) {
    if (nowTick == lastAdvanceTick) {
      return;
    }
    lastAdvanceTick = nowTick;
    var properties = group.getProperties();
    lastCapBps =
        speedLimit.onTick(
            properties.getSpeedLimit(), dynamics.params().maxSpeedBps(), properties::setSpeedLimit);
    detectStall(group);
    Notch effective = notch();
    boolean parkingBraking = cab.enabled() && cab.air().parkingApplied() && !isStopped();
    if (parkingBraking && effective != Notch.EB) {
      // 行进中停放制动（弹簧制动）施加着，例如失风后自动施加：至少按 B4 制动。
      effective = Notch.B4;
    }
    if (driverLink != null && driverLink.controlsPhysically()) {
      effective = superviseDriver(effective, nowTick);
    }
    // 失效导向安全：紧急制动、无人驾驶时（含折返换端途中）的自动制动与停放制动不靠主风缸，不随风压打折，也不用电制动。
    boolean failSafe = effective == Notch.EB || parkingBraking || !attended();
    boolean mainCircuit = setup.mainCircuitPowered();
    double speedBefore = dynamics.speedBps();
    dynamics.step(
        STEP_SECONDS,
        effective,
        lastCapBps,
        cab.tractionScale(speedBefore),
        failSafe ? FULL_BRAKE : demand -> cab.serviceBrakeScale(demand, speedBefore, mainCircuit));
    odometerBlocks += dynamics.speedBps() * STEP_SECONDS;
    boolean attended = attended();
    Vigilance.Event event =
        cab.tick(
            nowTick,
            STEP_SECONDS,
            new CabTick(
                setup.state(SetupSystem.AUX) == TrainSetup.State.ON,
                mainCircuit,
                Math.max(0.0, -dynamics.effort()),
                failSafe,
                effective == Notch.EB,
                speedBefore,
                isStopped(),
                attended,
                setup.ready()));
    if (cab.takeEmergencyRequest()) {
      // 制动管失压：紧急制动，停稳前不能缓解。
      selector.force(Notch.EB);
    }
    if (event == Vigilance.Event.TRIPPED && driverLink != null) {
      driverLink.countVigilanceTrip();
    }
    if (event == Vigilance.Event.TRIPPED) {
      // 警惕装置超时：紧急制动，停稳前不能缓解（档位选择器的紧急制动锁定）。
      selector.force(Notch.EB);
    }
    if (event != Vigilance.Event.NONE) {
      pendingVigilanceEvent = event;
    }
    if (phase == Phase.STOPPING && dynamics.speedBps() <= config.stoppedSpeedBps()) {
      finish(endReason);
    }
  }

  /**
   * 保护包络：按调度层的指令决定此刻是否介入，返回介入后实际生效的档位。
   *
   * <p>立即停住时把积分速度清零（控车动作随之把列车速度写成 0）；紧急制动锁住手柄，停稳后才能缓解。
   */
  private Notch superviseDriver(Notch effective, long nowTick) {
    DriverLink link = driverLink;
    boolean stopped = isStopped();
    // 信号变严要右键确认：行车中迟迟不确认先常用制动，再紧急制动。
    SignalConfirm confirm = link.signalConfirm();
    if (link.directive() != null) {
      confirm.observe(
          link.directive().aspect(),
          nowTick,
          !stopped,
          config.level() == SimulationLevel.SIMULATION);
    }
    SignalConfirm.Intervention unconfirmed = confirm.intervention(nowTick, !stopped);
    if (unconfirmed == SignalConfirm.Intervention.EMERGENCY) {
      selector.force(Notch.EB);
      effective = Notch.EB;
    } else if (unconfirmed == SignalConfirm.Intervention.SERVICE) {
      effective = atLeastServiceBrake(effective);
    }
    if (stopped && link.emergencyLatched()) {
      link.releaseEmergency();
    }
    double serviceDecel =
        dynamics.params().decelBps2() * config.brakeFraction(Notch.B4) * cab.brakeScale();
    double emergencyDecel = dynamics.params().decelBps2() * config.emergencyMultiplier();
    DriverLink.StationTarget station = link.stationTarget().orElse(null);
    DriverProtection.Decision decision =
        DriverProtection.evaluate(
            new DriverProtection.Input(
                dynamics.speedBps(),
                stopped,
                link.directive(),
                link.ticksSinceDirective(),
                link.travelledSinceDirective(),
                serviceDecel,
                emergencyDecel,
                1.0 / (2.0 * config.effortRatePerSecond()) + cab.serviceApplyLagSeconds(),
                link.serviceStopRequested(),
                link.serviceLatched(),
                station == null ? Double.NaN : station.remainingBlocks(),
                station != null && station.precise()),
            config.driver());
    link.recordDecision(decision);
    if (decision.handbackRequested()) {
      link.requestHandback("directive-stale");
    }
    switch (decision.intervention()) {
      case CLAMP -> {
        dynamics.reset(0.0);
        selector.force(Notch.EB);
        return Notch.EB;
      }
      case EMERGENCY -> {
        selector.force(Notch.EB);
        return Notch.EB;
      }
      case SERVICE -> {
        return atLeastServiceBrake(effective);
      }
      default -> {}
    }
    if (link.emergencyLatched()) {
      selector.force(Notch.EB);
      return Notch.EB;
    }
    if (decision.tractionInhibited() && effective.isTraction()) {
      return Notch.N;
    }
    return effective;
  }

  /** 至少按 B4 制动；手柄已在更重的档位时保持。 */
  private static Notch atLeastServiceBrake(Notch effective) {
    if (effective == Notch.EB || effective == Notch.B4) {
      return effective;
    }
    return Notch.B4;
  }

  /**
   * 写给 TrainCarts 的前进力（blocks/tick，已按物理小步数均分）。
   *
   * <p>永远不为负：TrainCarts 的前进力是沿当前运动方向的，负值会让速度方向每个 tick 翻转一次。要朝车尾方向开，先把整列调头。
   */
  double forcePerStep(int stepCount) {
    return dynamics.speedBps() / TICKS_PER_SECOND / Math.max(1, stepCount);
  }

  /** 我们命令了速度而车厢却停住，说明被挡（碰撞、脱轨），把积分速度归零，免得挡开后列车突然冲出去。 */
  private void detectStall(MinecartGroup group) {
    if (dynamics.speedBps() <= STALL_DETECT_BPS) {
      stallTicks = 0;
      return;
    }
    boolean anyStopped = false;
    for (MinecartMember<?> member : group) {
      if (member.getRealSpeed() < STALLED_BPT) {
        anyStopped = true;
        break;
      }
    }
    stallTicks = anyStopped ? stallTicks + 1 : 0;
    if (stallTicks >= STALL_CONFIRM_TICKS) {
      dynamics.reset(0.0);
      stallTicks = 0;
    }
  }
}
