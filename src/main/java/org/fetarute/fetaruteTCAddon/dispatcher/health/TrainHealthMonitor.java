package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchAction;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchEffectClass;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherModeGate;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerRelation;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.utils.StableCollections;

/**
 * 列车健康监控器：检测列车运行异常并尝试自动修复。
 *
 * <p>检测项：
 *
 * <ul>
 *   <li>长时间静止（stall）：有 PROCEED 信号但速度为 0（排除正在停站的列车）
 *   <li>进度不推进：route 索引与最近经过图节点均长时间不变化（排除正在停站的列车）
 * </ul>
 *
 * <p>自动修复采用分级策略（带冷却）：
 *
 * <ul>
 *   <li>STALL：先 refreshSignal，再升级到 forceRelaunch
 *   <li>PROGRESS_STUCK：非 STOP 时先 refreshSignal，再升级到 reissueDestination，最后 forceRelaunch
 *   <li>STOP 下的长时间停滞先重刷信号，再清理可证明的自持 single 反向残留，最后重下发硬 STOP；不 reissue destination，避免健康监控绕过红灯强制动车
 *   <li>若 STOP 期间仍能看到新鲜 blocker 快照，则只在 STOP 宽限窗口内视为合法排队；超宽限后仍进入非动车恢复链，避免 blocker 持续刷新导致永久不自愈
 *   <li>互相阻塞的自动恢复先按列车对执行 refresh → hard STOP；超过销毁阈值且仍未恢复时，销毁 pair leader 作为最终兜底，避免永久占线
 *   <li>普通长时间停滞 cleanup 默认关闭；启用后也只会在安全恢复耗尽、没有实时 blocker 且不属于受控停车时，每批最多清理一列，并优先空车、最后才考虑载客列车
 *   <li>STOP 信号下的 progress stuck 允许更长宽限，避免把正常排队误判为故障
 * </ul>
 */
public final class TrainHealthMonitor {

  /** 同一 safe recovery 候选连续无效达到该次数后，允许后续恢复阶段继续评估。 */
  private static final int SAFE_CANDIDATE_FAILURE_THRESHOLD = 2;

  /**
   * 恢复动作生效后多久之内的推进算"被推了一下"，而不是车自己恢复了。
   *
   * <p>实测恢复动作派发后 6–10 秒车重新推进；真正恢复的车会一直开下去，过了这段时间仍在动或又过了节点。 被推一下就停的车（推进一个节点后又停在下一段前）在这段时间之后不会再动。
   */
  static final Duration RECOVERY_PUSH_WINDOW = Duration.ofSeconds(30);

  /** 列车状态快照。 */
  private record TrainSnapshot(
      int progressIndex,
      String lastPassedGraphNodeId,
      SignalAspect signal,
      double speedBpt,
      Instant captureTime,
      Instant lastMoveTime,
      Instant lastProgressTime) {}

  /** 销毁兜底前的安全恢复尝试结果。 */
  private record FallbackRecoveryAttempt(boolean held, boolean fixed) {
    private static FallbackRecoveryAttempt none() {
      return new FallbackRecoveryAttempt(false, false);
    }

    private static FallbackRecoveryAttempt held(boolean fixed) {
      return new FallbackRecoveryAttempt(true, fixed);
    }
  }

  /** 每列车恢复状态：记录分级进度与最近一次恢复时间。 */
  private static final class RecoveryState {
    private Instant lastStallAttemptAt = Instant.EPOCH;
    private Instant lastProgressAttemptAt = Instant.EPOCH;
    private Instant lastDeadlockAttemptAt = Instant.EPOCH;
    private int stallStage;
    private int progressStage;
    private int progressRecoveryAttempts;
    private int deadlockStage;

    /**
     * 已派发但**尚未验证**的恢复动作的时刻；{@link Instant#EPOCH} 表示没有待验证的恢复。
     *
     * <p>{@code tryFixProgressStuck} 返回 true 的含义只是"派发了一个恢复动作"，不是"车动了"。若据此直接发 {@code
     * HealthAlert.fixed("进度停滞已修复")}，一辆一步没挪的车会在同一进度索引上反复"告警→已修复"地翻转，而 {@code 持续=}
     * 秒数一路上涨，告警与"已修复"的比例也因此失真。所以先记下派发时刻，待进度真正推进后再确认恢复。
     */
    private Instant pendingRecoveryAt = Instant.EPOCH;

    /**
     * 每列挡住本车的驾驶员列车（不在计划停车中的）从什么时候起挡着，按车分开计。
     *
     * <p>请驾驶员交还按它自己挡车的时长算，不按本车自己的静止时长：终点待命车开出前已静止几分钟，一被驾驶员车挡住就会立刻请交还； 先被另一列驾驶员车挡着的时间也不算到后来那列头上。
     */
    private final Map<String, DriverBlock> driverBlocks = new LinkedHashMap<>();

    /**
     * 恢复动作上一次报告生效（解锁、刷新放行、重发目的地、重新发车）的时刻。不随 {@link #resetProgress} 清零；这段停滞结束、进站停站时清零，
     * 免得停站后正常发车被算成"被推了一下"。
     */
    private Instant lastRecoveryPushAt = Instant.EPOCH;

    /**
     * 这段停滞从何时起：第一次把车推动的那个恢复动作的时刻。车被恢复动作推了一下、又停下时，停滞清车按整段计时，不因这几下推进清零； 推动之前的站着（终点待命、排队）不算进去。{@link
     * Instant#EPOCH} 表示没有这样的停滞段，清车按最后一次推进起算。
     *
     * <p>这段停滞结束于：推动窗口外连过两个节点、窗口外过了一个节点后停在别的车前（或停了也没有新的停车记录）、进站停站。
     * 只影响停滞清车与它之前的恢复冻结；互卡判定、恢复链的节奏仍按最后一次推进计。
     */
    private Instant creepSince = Instant.EPOCH;

    /** 这段停滞里恢复动作推动车的次数（一个动作让车连过几个节点只算一次）。 */
    private int creepPushes;

    /** 最近一次算进 {@link #creepPushes} 的恢复动作时刻。 */
    private Instant creepLastPushAt = Instant.EPOCH;

    /** 车被判停滞时，停车记录里第一个 blocker 的持有者；没有时为空串。车一推进就清空。 */
    private String stopBlocker = "";

    /** 这段停滞里车最近一次被推离时挡着它的车；不知道时为空串。 */
    private String creepBlocker = "";

    /** 推动窗口外过了一个节点：再停在 {@link #creepBlocker} 前就接着算，否则这段停滞结束。 */
    private boolean creepEndPending;

    /** 清车关着时的结论缓存：告警一分钟才发一次，不必每轮都解析挡路的车。 */
    private String disabledVerdict = "";

    private Instant disabledVerdictAt = Instant.EPOCH;

    /** 这段停滞里前几次停车已经做过的恢复尝试（推进时 {@link #resetProgress} 会把当次计数清零）。 */
    private int creepAttempts;

    /** 这段停滞里前几次停车最后一次恢复尝试的时刻。 */
    private Instant creepLastAttemptAt = Instant.EPOCH;

    private boolean hasPendingRecovery() {
      return !Instant.EPOCH.equals(pendingRecoveryAt);
    }

    private boolean pushedRecently(Instant now) {
      return !Instant.EPOCH.equals(lastRecoveryPushAt)
          && Duration.between(lastRecoveryPushAt, now).compareTo(RECOVERY_PUSH_WINDOW) <= 0;
    }

    private boolean creeping() {
      return !Instant.EPOCH.equals(creepSince);
    }

    /**
     * 车被推了一下：停滞段从推动它的恢复动作起（已有停滞段时沿用更早的起点），带上这次停车的恢复尝试。
     *
     * @return 这是不是一次新的推动（同一个动作让车连过几个节点时只有第一次为 true）
     */
    private boolean recordPush() {
      if (!creeping()) {
        creepSince = lastRecoveryPushAt;
      }
      creepEndPending = false;
      creepAttempts += progressRecoveryAttempts;
      creepLastAttemptAt = latestAttemptAt();
      if (lastRecoveryPushAt.equals(creepLastPushAt)) {
        return false;
      }
      creepPushes++;
      creepLastPushAt = lastRecoveryPushAt;
      creepBlocker = stopBlocker;
      return true;
    }

    private void endCreep() {
      lastRecoveryPushAt = Instant.EPOCH;
      creepSince = Instant.EPOCH;
      creepPushes = 0;
      creepAttempts = 0;
      creepLastAttemptAt = Instant.EPOCH;
      creepLastPushAt = Instant.EPOCH;
      creepBlocker = "";
      creepEndPending = false;
    }

    /** 停滞清车看的恢复尝试次数：这段停滞里累计的。 */
    private int cleanupAttempts() {
      return creepAttempts + progressRecoveryAttempts;
    }

    /** 最近一次恢复尝试（进度停滞、静止、这段停滞里先前的停车）的时刻；没有时为 {@link Instant#EPOCH}。 */
    private Instant latestAttemptAt() {
      Instant latest = creepLastAttemptAt;
      for (Instant at : new Instant[] {lastProgressAttemptAt, lastStallAttemptAt}) {
        if (at != null && at.isAfter(latest)) {
          latest = at;
        }
      }
      return latest;
    }

    /**
     * 记下这次采样挡住本车的驾驶员列车，返回其中已连续挡满 {@code threshold} 的。
     *
     * <p>一次采样没看到（信号重算、阻挡快照刚过期）不清零，连续两次没看到才重新起算，免得交还被闪动一直推后。
     */
    private List<String> trackDriverBlock(
        java.util.Collection<String> drivers, Instant now, Duration threshold) {
      Map<String, DriverBlock> next = new LinkedHashMap<>();
      driverBlocks.forEach(
          (driver, block) -> {
            if (!drivers.contains(driver) && block.misses() < DRIVER_BLOCK_MISSES_TOLERATED) {
              next.put(driver, new DriverBlock(block.since(), block.misses() + 1));
            }
          });
      List<String> due = new ArrayList<>();
      for (String driver : drivers) {
        DriverBlock previous = driverBlocks.get(driver);
        Instant since = previous == null ? now : previous.since();
        next.put(driver, new DriverBlock(since, 0));
        if (Duration.between(since, now).compareTo(threshold) >= 0) {
          due.add(driver);
        }
      }
      driverBlocks.clear();
      driverBlocks.putAll(next);
      return due;
    }

    /** 这次采样没有驾驶员列车挡住本车。 */
    private void missDriverBlock(Instant now) {
      trackDriverBlock(List.of(), now, Duration.ZERO);
    }

    /** 给出的驾驶员列车里已连续挡满 {@code threshold} 的（不更新记录）。 */
    private List<String> driversBlockingAtLeast(
        java.util.Collection<String> drivers, Instant now, Duration threshold) {
      List<String> due = new ArrayList<>();
      for (String driver : drivers) {
        DriverBlock block = driverBlocks.get(driver);
        if (block != null && Duration.between(block.since(), now).compareTo(threshold) >= 0) {
          due.add(driver);
        }
      }
      return due;
    }

    private void resetStall() {
      stallStage = 0;
      lastStallAttemptAt = Instant.EPOCH;
    }

    private void resetProgress() {
      progressStage = 0;
      progressRecoveryAttempts = 0;
      lastProgressAttemptAt = Instant.EPOCH;
      pendingRecoveryAt = Instant.EPOCH;
    }

    private void resetDeadlock() {
      deadlockStage = 0;
      lastDeadlockAttemptAt = Instant.EPOCH;
      driverBlocks.clear();
    }
  }

  /** 一列驾驶员列车挡住本车的起始时刻与之后连续没看到的采样次数。 */
  private record DriverBlock(Instant since, int misses) {}

  /** 驾驶员列车挡车计时容忍连续几次采样没看到。 */
  private static final int DRIVER_BLOCK_MISSES_TOLERATED = 1;

  /** destroy 前方向证据审计结果。 */
  private record DirectionDestroyAudit(boolean required, String reason) {
    private DirectionDestroyAudit {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
    }

    private static DirectionDestroyAudit none() {
      return new DirectionDestroyAudit(false, "-");
    }

    private static DirectionDestroyAudit required(String reason) {
      return new DirectionDestroyAudit(true, reason);
    }
  }

  /** 一组经过确认的 STOP 互卡 episode；firstSeenAt 不随 blocker 快照抖动重置。 */
  private static final class DeadlockEpisode {
    private final String key;
    private final String trainA;
    private final String trainB;
    private final String conflictKey;
    private final boolean weak;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private int refreshCount;
    private int reissueCount;
    private boolean destroyAttempted;
    private String stableLeader;
    private Set<String> lastBlockerSnapshot = Set.of();

    private DeadlockEpisode(
        String key,
        String trainA,
        String trainB,
        String conflictKey,
        boolean weak,
        Instant firstSeenAt,
        String stableLeader,
        Set<String> lastBlockerSnapshot) {
      this.key = Objects.requireNonNull(key, "key");
      this.trainA = Objects.requireNonNull(trainA, "trainA");
      this.trainB = Objects.requireNonNull(trainB, "trainB");
      this.conflictKey = Objects.requireNonNull(conflictKey, "conflictKey");
      this.weak = weak;
      this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
      this.lastSeenAt = firstSeenAt;
      this.stableLeader = stableLeader;
      updateSnapshot(firstSeenAt, lastBlockerSnapshot);
    }

    private void updateSnapshot(Instant now, Set<String> blockerSnapshot) {
      lastSeenAt = now == null ? Instant.now() : now;
      lastBlockerSnapshot =
          blockerSnapshot == null || blockerSnapshot.isEmpty()
              ? Set.of()
              : StableCollections.copyInInsertionOrder(blockerSnapshot);
    }

    private String survivor() {
      return stableLeader != null && stableLeader.equalsIgnoreCase(trainA) ? trainB : trainA;
    }
  }

  /** 单向 wait-for / stuck-leader 兜底证据，要求跨多次 health sample 持续存在。 */
  private static final class DeadlockFallbackEvidence {
    private final String key;
    private final String followerTrain;
    private final String blockerTrain;
    private final String resource;
    private Instant lastSeenAt;
    private int samples;

    private DeadlockFallbackEvidence(
        String key, String followerTrain, String blockerTrain, String resource, Instant now) {
      this(key, followerTrain, blockerTrain, resource, now, 1);
    }

    private DeadlockFallbackEvidence(
        String key,
        String followerTrain,
        String blockerTrain,
        String resource,
        Instant now,
        int samples) {
      this.key = Objects.requireNonNull(key, "key");
      this.followerTrain = Objects.requireNonNull(followerTrain, "followerTrain");
      this.blockerTrain = Objects.requireNonNull(blockerTrain, "blockerTrain");
      this.resource = resource == null || resource.isBlank() ? "-" : resource.trim();
      this.lastSeenAt = Objects.requireNonNull(now, "now");
      this.samples = Math.max(1, samples);
    }

    private void seenAtLeast(Instant now, int observedSamples) {
      lastSeenAt = now == null ? Instant.now() : now;
      samples = Math.max(samples + 1, Math.max(1, observedSamples));
    }
  }

  /** 本轮检测到的一次 confirmed mutual deadlock。 */
  private record DeadlockObservation(
      String episodeKey,
      String trainA,
      String trainB,
      String conflictKey,
      boolean weak,
      Set<String> blockerSnapshot,
      Set<String> blockerResources,
      RuntimeDispatchService.DeadlockTrainContext firstContext,
      RuntimeDispatchService.DeadlockTrainContext secondContext,
      String blockerSnapshotSource) {

    DeadlockObservation {
      blockerSnapshotSource =
          blockerSnapshotSource == null || blockerSnapshotSource.isBlank()
              ? "stored"
              : blockerSnapshotSource.trim();
    }
  }

  private final RuntimeDispatchService dispatchService;
  private final DwellRegistry dwellRegistry;
  private final HealthAlertBus alertBus;
  private final Consumer<String> debugLogger;

  private final Map<String, TrainSnapshot> snapshots = new ConcurrentHashMap<>();
  private final Map<String, RecoveryState> recoveryStates = new ConcurrentHashMap<>();

  /** 静止阈值（秒）：有 PROCEED 信号但静止超过此时间触发告警。 */
  private Duration stallThreshold = Duration.ofSeconds(30);

  /** 进度不推进阈值（秒）。 */
  private Duration progressStuckThreshold = Duration.ofSeconds(60);

  /** STOP 信号下 progress stuck 的宽限阈值（秒）。 */
  private Duration progressStopGraceThreshold = Duration.ofSeconds(60);

  /** 自动修复动作冷却，避免每轮检查都重复触发同一恢复动作。 */
  private Duration recoveryCooldown = Duration.ofSeconds(10);

  /** STOP 互卡识别阈值（秒）：用于比 progress STOP 宽限更早触发“解锁尝试”。 */
  private Duration deadlockThreshold = Duration.ofSeconds(45);

  /** STOP 互卡最终销毁阈值：为 0 时禁用自动销毁。 */
  private Duration deadlockDestroyThreshold = Duration.ofSeconds(60);

  /** 实体列车 destructive cleanup 总开关。 */
  private boolean trainCleanupEnabled;

  /** 同一互卡对销毁兜底冷却，避免新 episode 立即连续销毁第二列车。 */
  private Duration deadlockDestroyCooldown = Duration.ofSeconds(120);

  /** 空车进入普通 stuck cleanup 的最短无进展时间。 */
  private Duration stuckCleanupThreshold = Duration.ofMinutes(10);

  /** 有玩家乘坐列车进入普通 stuck cleanup 的保护阈值。 */
  private Duration stuckCleanupPassengerThreshold = Duration.ofMinutes(30);

  /** 两次普通 stuck cleanup 尝试之间的全局冷却。 */
  private Duration stuckCleanupCooldown = Duration.ofMinutes(2);

  /** 最近一次普通 stuck cleanup 尝试时间。 */
  private volatile Instant lastStuckCleanupAt = Instant.EPOCH;

  /** confirmed episode 暂时失去 blocker 快照后保留的宽限。 */
  private Duration deadlockEpisodeGrace = Duration.ofSeconds(15);

  /** STOP 互卡进入 confirmed episode 前的最短静止时间。 */
  private Duration deadlockMinStopDuration = Duration.ofSeconds(20);

  /** 阻塞快照有效期：仅在最近可见的 blocker 信息上执行互卡解锁。 */
  private Duration blockerSnapshotMaxAge = Duration.ofSeconds(20);

  /** 停滞告警的清车结论里最多点名几列挡路的车。 */
  private static final int CLEANUP_VERDICT_BLOCKERS_NAMED = 3;

  /** 方向证据不足时，last-resort destroy 必须等待更长时间。 */
  private static final int DIRECTION_AUDIT_LAST_RESORT_THRESHOLD_MULTIPLIER = 3;

  /** 是否启用自动修复。 */
  private boolean autoFixEnabled = true;

  /** 低速判定阈值（blocks per tick）。 */
  private double lowSpeedThresholdBpt = 0.01;

  /** 互卡对级别冷却：避免同一对列车在短窗口内反复执行解锁动作。 */
  private final Map<String, Instant> deadlockPairLastAttemptAt = new ConcurrentHashMap<>();

  /** 互卡 episode 追踪，key=canonical(trainA, trainB, conflictKey)。 */
  private final Map<String, DeadlockEpisode> deadlockEpisodes = new ConcurrentHashMap<>();

  /** 互卡对销毁尝试冷却，key=canonical(trainA, trainB)。 */
  private final Map<String, Instant> deadlockPairLastDestroyAt = new ConcurrentHashMap<>();

  /** 全局 destroy 冷却，避免一个 health 窗口内连续销毁多列车。 */
  private volatile Instant lastDeadlockDestroyAt = Instant.EPOCH;

  /** 单向 wait-for / stuck-leader destroy 兜底证据。 */
  private final Map<String, DeadlockFallbackEvidence> deadlockFallbackEvidence =
      new ConcurrentHashMap<>();

  /** 列车的物理控制权：有驾驶员在岗的车不做恢复动作，死锁里有它时先请它交还。 */
  private ControlAuthority controlAuthority = ControlAuthority.pluginLookup();

  /** 健康诊断 trace 限频。 */
  private final Map<String, String> traceFingerprints = new ConcurrentHashMap<>();

  private final Map<String, Instant> traceLastAt = new ConcurrentHashMap<>();

  /** safe recovery 候选有效性失败计数，key=train/action/conflict。 */
  private final Map<String, Integer> safeCandidateFailureCounts = new ConcurrentHashMap<>();

  /** 上一次为该 (train, action, conflict) 打印的失败计数签名；只在变化时再印。 */
  private final Map<String, String> safeCandidateFailureReported = new ConcurrentHashMap<>();

  public TrainHealthMonitor(
      RuntimeDispatchService dispatchService,
      DwellRegistry dwellRegistry,
      HealthAlertBus alertBus,
      Consumer<String> debugLogger) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.dwellRegistry = dwellRegistry; // 可为 null，表示不排除停站列车
    this.alertBus = alertBus != null ? alertBus : new HealthAlertBus();
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /**
   * 受控停站：正在 dwell，或正被按表扣在站里等点。
   *
   * <p>两种情况下静止、进度不变都是计划内的。按表扣车比 dwell 长得多（最长 150 秒），不排除的话停滞检测会在扣留期间 派发恢复动作，一路升级到强制重发，把等点的车提前放走。
   */
  private boolean inControlledStationStop(String trainName) {
    if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent()) {
      return true;
    }
    // 有驾驶员在岗：静止和进度不变由驾驶员负责，不能替他重发车、改目的地或销毁；卡太久由驾驶侧的阶梯交还。
    if (controlAuthority.isDriverControlledName(trainName)) {
      return true;
    }
    // 停着等驾驶员上车接班：有时限，到时限由驾驶侧放行，期间不派恢复动作。
    if (controlAuthority.awaitingDriver(trainName)) {
      return true;
    }
    StationStopCoordinator stationStops = dispatchService.stationStops();
    return stationStops != null && stationStops.holdingForSchedule(trainName);
  }

  /** 替换控制权（测试用）。 */
  void setControlAuthority(ControlAuthority authority) {
    this.controlAuthority = authority == null ? ControlAuthority.NONE : authority;
  }

  /** 阻挡者里有没有驾驶员在岗的车（含 ATO）。 */
  private boolean blockedByDriver(java.util.Collection<String> trainNames) {
    if (trainNames == null) {
      return false;
    }
    for (String name : trainNames) {
      if (name != null && controlAuthority.hasDriver(name)) {
        return true;
      }
    }
    return false;
  }

  /** 阻挡者里有驾驶员在岗、且此刻不在计划停车（停站、发车门控、按表扣车）中的车：只有它们挡车才计时、才请交还。 */
  private List<String> driversHoldingUp(java.util.Collection<String> trainNames) {
    List<String> drivers = new ArrayList<>();
    for (String name : trainNames) {
      if (name != null && controlAuthority.hasDriver(name) && !inPlannedStop(name)) {
        drivers.add(name);
      }
    }
    return drivers;
  }

  /** 列车此刻在计划停车中：停站计时、发车门控、按表扣车、人工扣车、终点站待命或等开出下一趟（自动运行同样会停着等）。 */
  private boolean inPlannedStop(String trainName) {
    if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent()) {
      return true;
    }
    if (dispatchService.hasDepartureGate(trainName)
        || controlAuthority.awaitingTurnback(trainName)) {
      return true;
    }
    StationStopCoordinator stationStops = dispatchService.stationStops();
    if (stationStops != null && stationStops.holdingForSchedule(trainName)) {
      return true;
    }
    return dispatchService
        .deadlockTrainContext(trainName)
        .map(
            context ->
                context.dwelling()
                    || context.departureGateHeld()
                    || context.layoverReady()
                    || context.manualHold())
        .orElse(false);
  }

  /**
   * 列车中有驾驶员控制的车时，请它们交还自动运行。
   *
   * @return 是否有驾驶员控制的车（此时本轮不做销毁类处理）
   */
  private boolean requestDriverHandback(java.util.Collection<String> trainNames) {
    boolean found = false;
    if (trainNames == null) {
      return false;
    }
    for (String name : trainNames) {
      if (name != null && controlAuthority.hasDriver(name)) {
        controlAuthority.requestHandback(name, "deadlock");
        found = true;
      }
    }
    return found;
  }

  /** 设置静止阈值。 */
  public void setStallThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.stallThreshold = threshold;
    }
  }

  /** 设置进度不推进阈值。 */
  public void setProgressStuckThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.progressStuckThreshold = threshold;
    }
  }

  /** 设置 STOP 信号下 progress stuck 的宽限阈值。 */
  public void setProgressStopGraceThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.progressStopGraceThreshold = threshold;
    }
  }

  /** 设置 STOP 互卡识别阈值。 */
  public void setDeadlockThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative() && !threshold.isZero()) {
      this.deadlockThreshold = threshold;
    }
  }

  /** 设置 STOP 互卡最终销毁阈值；0 表示禁用自动销毁。 */
  public void setDeadlockDestroyThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative()) {
      this.deadlockDestroyThreshold = threshold;
    }
  }

  /** 设置实体列车 destructive cleanup 总开关。 */
  public void setTrainCleanupEnabled(boolean enabled) {
    this.trainCleanupEnabled = enabled;
  }

  /** 设置同一互卡对销毁冷却时间。 */
  public void setDeadlockDestroyCooldown(Duration cooldown) {
    if (cooldown != null && !cooldown.isNegative()) {
      this.deadlockDestroyCooldown = cooldown;
    }
  }

  /** 设置空车 cleanup 阈值。 */
  public void setStuckCleanupThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative() && !threshold.isZero()) {
      this.stuckCleanupThreshold = threshold;
      if (stuckCleanupPassengerThreshold.compareTo(threshold) < 0) {
        stuckCleanupPassengerThreshold = threshold;
      }
    }
  }

  /** 设置载客列车 cleanup 保护阈值。 */
  public void setStuckCleanupPassengerThreshold(Duration threshold) {
    if (threshold != null && !threshold.isNegative() && !threshold.isZero()) {
      this.stuckCleanupPassengerThreshold =
          threshold.compareTo(stuckCleanupThreshold) < 0 ? stuckCleanupThreshold : threshold;
    }
  }

  /** 设置普通 stuck cleanup 的全局冷却。 */
  public void setStuckCleanupCooldown(Duration cooldown) {
    if (cooldown != null && !cooldown.isNegative()) {
      this.stuckCleanupCooldown = cooldown;
    }
  }

  /** 设置互卡 episode 在 blocker 快照抖动后的保留宽限。 */
  public void setDeadlockEpisodeGrace(Duration grace) {
    if (grace != null && !grace.isNegative()) {
      this.deadlockEpisodeGrace = grace;
    }
  }

  /** 设置进入 confirmed mutual deadlock 前的最短 STOP 静止时间。 */
  public void setDeadlockMinStopDuration(Duration minStopDuration) {
    if (minStopDuration != null && !minStopDuration.isNegative()) {
      this.deadlockMinStopDuration = minStopDuration;
    }
  }

  /** 设置 blocker 快照有效期。 */
  public void setBlockerSnapshotMaxAge(Duration maxAge) {
    if (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) {
      this.blockerSnapshotMaxAge = maxAge;
    }
  }

  /** 设置自动修复动作冷却时间。 */
  public void setRecoveryCooldown(Duration cooldown) {
    if (cooldown != null && !cooldown.isNegative()) {
      this.recoveryCooldown = cooldown;
    }
  }

  /** 设置是否启用自动修复。 */
  public void setAutoFixEnabled(boolean enabled) {
    this.autoFixEnabled = enabled;
  }

  /** 设置低速判定阈值。 */
  public void setLowSpeedThresholdBpt(double threshold) {
    if (threshold >= 0) {
      this.lowSpeedThresholdBpt = threshold;
    }
  }

  /**
   * 立即执行一次“互卡优先”的强制解锁。
   *
   * <p>该入口用于人工介入（例如命令行触发），不等待 progress stuck 阈值，也不受恢复冷却限制。STOP 闭塞只会重新刷新信号并复下发硬 STOP，不会重发
   * destination 或 relaunch，避免人工修复绕过红灯运动抑制。
   *
   * <p>逐车处理顺序与 {@link #check(Set, Instant)} 相同：按列车名自然序。
   *
   * @param activeTrains 当前存活列车集合
   * @param now 当前时间（为空时使用当前时刻）
   * @return 成功执行的互卡解锁次数（按列车对计数）
   */
  public int forceUnlockNow(Set<String> activeTrains, Instant now) {
    Instant effectiveNow = now != null ? now : Instant.now();
    Set<String> active =
        activeTrains == null
            ? Set.of()
            : StableCollections.copySorted(activeTrains, Comparator.naturalOrder());
    Set<String> activeKeys = new java.util.HashSet<>();
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key != null) {
        activeKeys.add(key);
      }
    }

    int fixedCount = 0;
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key == null) {
        continue;
      }
      Optional<RuntimeDispatchService.TrainRuntimeState> stateOpt =
          dispatchService.getTrainState(trainName);
      if (stateOpt.isEmpty()) {
        continue;
      }
      if (inControlledStationStop(trainName)) {
        continue;
      }
      RuntimeDispatchService.TrainRuntimeState state = stateOpt.get();
      boolean stationary = state.speedBlocksPerTick() <= lowSpeedThresholdBpt;
      if (!stationary || state.signalAspect() == SignalAspect.PROCEED) {
        continue;
      }
      Set<String> blockers = dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
      Optional<String> mutualBlocker = findMutualBlocker(trainName, activeKeys);
      if (mutualBlocker.isEmpty()) {
        if (state.signalAspect() == SignalAspect.STOP && !blockers.isEmpty()) {
          debugLogger.accept("TrainHealthMonitor 手动恢复单车 STOP: train=" + trainName);
          dispatchService.refreshSignalByName(trainName);
          dispatchService.reapplyHardStopByName(trainName, "manual-stop-unlock");
        }
        continue;
      }
      if (!isPairLeader(trainName, mutualBlocker.get())) {
        continue;
      }
      RecoveryState recovery = recoveryStates.computeIfAbsent(key, unused -> new RecoveryState());
      if (forceFixMutualDeadlock(trainName, mutualBlocker.get(), recovery, effectiveNow)) {
        fixedCount++;
      }
    }
    return fixedCount;
  }

  /**
   * 执行一次健康检查。
   *
   * <p><b>逐车处理顺序</b>：按列车名自然序（{@link String#compareTo}）。同一 tick 内先处理到的列车先做修复/兜底 destroy，
   * 两车同时满足互卡兜底条件时谁先动手就取决于这个顺序；若用 {@code Set.copyOf}，顺序会随 JVM 进程随机变化。按名字排序也让生产（调用方传 {@code
   * HashSet}）与回归骨架（传插入序集合）对同一组列车给出同一顺序。规则见 {@link StableCollections}。
   *
   * @param activeTrains 当前存活的列车名集合
   * @param now 当前时间
   * @return 检查结果
   */
  public CheckResult check(Set<String> activeTrains, Instant now) {
    if (now == null) {
      now = Instant.now();
    }
    Set<String> active =
        activeTrains == null
            ? Set.of()
            : StableCollections.copySorted(activeTrains, Comparator.naturalOrder());
    Set<String> activeKeys = new java.util.HashSet<>();
    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key != null) {
        activeKeys.add(key);
      }
    }

    // 清理已消失列车的快照/恢复状态
    snapshots.keySet().removeIf(name -> !activeKeys.contains(name));
    recoveryStates.keySet().removeIf(name -> !activeKeys.contains(name));

    int stallCount = 0;
    int recoveryDispatchedCount = 0;
    int progressStuckCount = 0;
    int fixedCount = 0;
    List<StuckTrainCleanupPolicy.Candidate> stuckCleanupCandidates = new ArrayList<>();

    for (String trainName : active) {
      String key = keyOf(trainName);
      if (key == null) {
        continue;
      }
      Optional<RuntimeDispatchService.TrainRuntimeState> stateOpt =
          dispatchService.getTrainState(trainName);
      if (stateOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.TrainRuntimeState state = stateOpt.get();

      TrainSnapshot prev = snapshots.get(key);
      RecoveryState recovery = recoveryStates.computeIfAbsent(key, unused -> new RecoveryState());

      int currentProgress = state.progressIndex();
      String currentGraphNode = state.lastPassedGraphNode().map(Object::toString).orElse(null);
      SignalAspect currentSignal = state.signalAspect();
      double currentSpeed = state.speedBlocksPerTick();

      boolean isMoving = currentSpeed > lowSpeedThresholdBpt;
      boolean progressed =
          prev != null
              && (currentProgress != prev.progressIndex()
                  || !Objects.equals(currentGraphNode, prev.lastPassedGraphNodeId()));

      // 更新快照
      Instant lastMove =
          prev != null && isMoving ? now : (prev != null ? prev.lastMoveTime() : now);
      Instant lastProgress =
          prev != null && progressed ? now : (prev != null ? prev.lastProgressTime() : now);

      TrainSnapshot current =
          new TrainSnapshot(
              currentProgress,
              currentGraphNode,
              currentSignal,
              currentSpeed,
              now,
              lastMove,
              lastProgress);
      snapshots.put(key, current);

      if (prev == null) {
        continue; // 首次采样，跳过检测
      }
      boolean creepingBefore = recovery.creeping();
      boolean pushProgress = progressed && recovery.pushedRecently(now);
      if (pushProgress) {
        // 恢复动作刚推过：先不算恢复。推进一个节点就又停下的车，停滞清车接着按整段计。
        if (recovery.recordPush()) {
          traceHealthEvent(
              "HEALTH_RECOVERY_PUSH_PROGRESS",
              "recovery-push:" + key,
              "train="
                  + trainName
                  + " idx="
                  + currentProgress
                  + " pushes="
                  + recovery.creepPushes
                  + " blocker="
                  + (recovery.creepBlocker.isEmpty() ? "-" : recovery.creepBlocker)
                  + " creepSeconds="
                  + Duration.between(recovery.creepSince, now).toSeconds());
        }
      } else if (progressed && recovery.creeping()) {
        if (!recovery.creepEndPending && !recovery.creepBlocker.isEmpty()) {
          // 推动窗口外过了一个节点：长区间上慢慢爬过去的车也会这样，先看它再停在谁前面。
          recovery.creepEndPending = true;
        } else if (endCreepRecovered(trainName, recovery, now)) {
          fixedCount++;
        }
      }
      if (progressed) {
        // **只有到这里，才有资格说"恢复了"**——车的进度索引真的向前走了。
        // 若在派发恢复动作那一刻就宣布已修复，一辆一步没挪的车会被反复宣布"已修复"。
        // 被推一下的推进、停滞段还没结束时都不算：恢复要等这段停滞结束（endCreepRecovered）才宣布。
        if (recovery.hasPendingRecovery() && !pushProgress && !creepingBefore) {
          long waitedSeconds =
              Math.max(0L, Duration.between(recovery.pendingRecoveryAt, now).toSeconds());
          fixedCount++;
          alertBus.publish(
              HealthAlert.fixed(
                  HealthAlert.AlertType.PROGRESS_STUCK,
                  trainName,
                  "进度停滞已恢复: 恢复动作后 "
                      + waitedSeconds
                      + "秒 车辆重新推进 idx="
                      + currentProgress
                      + " signal="
                      + currentSignal));
        }
        recovery.stopBlocker = "";
        recovery.resetProgress();
        recovery.resetDeadlock();
      }

      // 排除受控停站（dwell 或按表扣车）的列车：这期间静止和进度不变都是正常的
      if (inControlledStationStop(trainName)) {
        // Dwell 是受控停车，不应计入下一段 STOP 的互卡/停滞年龄。每次采样都把两个时钟锚定到当前时刻，
        // 这样停站结束后的短暂信号重算窗口会从零开始观察。
        snapshots.put(
            key,
            new TrainSnapshot(
                currentProgress, currentGraphNode, currentSignal, currentSpeed, now, now, now));
        recovery.resetStall();
        recovery.resetProgress();
        recovery.resetDeadlock();
        if (endCreepRecovered(trainName, recovery, now)) {
          fixedCount++;
        }
        recovery.endCreep();
        continue;
      }

      Duration progressDuration = Duration.between(lastProgress, now);
      if (recovery.creepEndPending && !progressed && !isMoving) {
        // 窗口外过了一个节点后停下：停在同一列车前是同一段停滞，接着算；停在别的车前，或停够了也没有新的停车记录，这段停滞结束。
        Optional<String> restopBlocker = freshStopBlocker(trainName, recovery);
        if (restopBlocker.isPresent() && restopBlocker.get().equals(recovery.creepBlocker)) {
          recovery.creepEndPending = false;
        } else if ((restopBlocker.isPresent()
                || progressDuration.compareTo(progressStuckThreshold) > 0)
            && endCreepRecovered(trainName, recovery, now)) {
          fixedCount++;
        }
      }
      // 停滞清车按整段停滞计：被恢复动作推一下又停下的车不从零起算（见 RecoveryState#creepSince）。
      Duration cleanupDuration =
          recovery.creeping() ? Duration.between(recovery.creepSince, now) : progressDuration;
      // 停滞段里的车：这一次停车至少先试过一次恢复，而且清车此刻真会接手（不是载客宽限、不在排队等活车），才停止推动；
      // 否则推动还在一个节点一个节点地把它往前送，冻结了只会让它停在原地。
      boolean cleanupRecoveryAttemptLimitReached =
          trainCleanupEnabled
              && cleanupDuration.compareTo(stuckCleanupThreshold) >= 0
              && recovery.cleanupAttempts() >= 3
              && (!recovery.creeping()
                  || (recovery.progressRecoveryAttempts >= 1
                      && cleanupWouldTake(trainName, cleanupDuration)));

      // 检测：有 PROCEED 信号但长时间静止
      if (currentSignal == SignalAspect.PROCEED && !isMoving) {
        Duration stallDuration = Duration.between(lastMove, now);
        if (stallDuration.compareTo(stallThreshold) > 0) {
          stallCount++;
          boolean fixed = false;
          if (autoFixEnabled && !cleanupRecoveryAttemptLimitReached) {
            fixed = tryFixStall(trainName, recovery, now);
            if (fixed) {
              fixedCount++;
              recovery.lastRecoveryPushAt = now;
            }
          }
          alertBus.publish(
              fixed
                  ? HealthAlert.fixed(
                      HealthAlert.AlertType.STALL,
                      trainName,
                      "列车静止已修复: 持续=" + stallDuration.toSeconds() + "秒")
                  : HealthAlert.of(
                      HealthAlert.AlertType.STALL,
                      trainName,
                      "列车静止: 持续=" + stallDuration.toSeconds() + "秒"));
        }
      } else {
        recovery.resetStall();
      }

      // 检测：进度长时间不推进
      Optional<DeadlockObservation> deadlockObservation =
          !progressed
                  && !isMoving
                  && currentSignal == SignalAspect.STOP
                  && progressDuration.compareTo(deadlockThreshold) > 0
                  && progressDuration.compareTo(deadlockMinStopDuration) >= 0
              ? findConfirmedMutualDeadlock(trainName, activeKeys, current, progressDuration, now)
              : Optional.empty();
      if (deadlockObservation.isPresent()
          && requestDriverHandback(
              List.of(deadlockObservation.get().trainA(), deadlockObservation.get().trainB()))) {
        recovery.resetDeadlock();
        continue;
      }
      if (deadlockObservation.isPresent()) {
        recovery.missDriverBlock(now);
        DeadlockObservation observation = deadlockObservation.get();
        if (!Objects.equals(keyOf(trainName), keyOf(observation.trainA()))) {
          recovery.resetDeadlock();
          continue;
        }
        DeadlockEpisode episode = updateDeadlockEpisode(observation, now);
        progressStuckCount++;
        boolean fixed = false;
        traceSmartProgressStuckBridge(
            observation.trainA(), currentSignal, progressDuration, "confirmed-deadlock");
        if (autoFixEnabled) {
          fixed =
              tryFixMutualDeadlockEpisode(episode, observation, progressDuration, recovery, now);
          if (fixed) {
            // 互卡路径保持"当场计入"：它的终局动作是销毁，那是**当场可验证的状态变化**
            // （车没了），不是"派发了动作、等着看车动不动"。progress-stuck 那边不同，
            // 那边派发的是 refresh/reissue/unlock，能不能生效要看车后来动没动。
            fixedCount++;
            recoveryDispatchedCount++;
          }
        }
        String message =
            "互卡停滞: train="
                + observation.trainA()
                + " blocker="
                + observation.trainB()
                + " conflict="
                + observation.conflictKey()
                + " 持续="
                + progressDuration.toSeconds()
                + "秒 idx="
                + currentProgress
                + " signal="
                + currentSignal;
        alertBus.publish(
            fixed
                ? HealthAlert.fixed(HealthAlert.AlertType.PROGRESS_STUCK, trainName, message)
                : HealthAlert.of(HealthAlert.AlertType.PROGRESS_STUCK, trainName, message));
        continue;
      } else if (!progressed
          && !isMoving
          && currentSignal == SignalAspect.STOP
          && progressDuration.compareTo(deadlockMinStopDuration) >= 0) {
        traceDeadlockSkipped(trainName, current, progressDuration, activeKeys, now);
        Set<String> blockers =
            dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
        if (blockedByDriver(blockers)) {
          // 阻挡者是有驾驶员的车：绝不因它销毁别的车。在它后面短暂排队是正常的，它在站里停站、按表扣车时更不算；
          // 从它停站以外挡住本车起算，到了本来会动用销毁兜底的时限才请它交还自动运行。
          List<String> due =
              recovery.trackDriverBlock(driversHoldingUp(blockers), now, deadlockDestroyThreshold);
          if (autoFixEnabled && !due.isEmpty()) {
            requestDriverHandback(due);
          }
          continue;
        }
        recovery.missDriverBlock(now);
        if (autoFixEnabled
            && tryDestroyDeadlockFallback(trainName, current, progressDuration, activeKeys, now)) {
          fixedCount++;
          continue;
        }
      } else {
        recovery.missDriverBlock(now);
      }

      boolean waitingOnFreshStopBlockersWithinGrace =
          currentSignal == SignalAspect.STOP
              && progressDuration.compareTo(progressStopGraceThreshold) <= 0
              && hasRecentBlockers(trainName);
      if (waitingOnFreshStopBlockersWithinGrace) {
        recovery.resetProgress();
        continue;
      }

      boolean allowProgressStuckCheck =
          !progressed
              && progressDuration.compareTo(progressStuckThreshold) > 0
              && (currentSignal != SignalAspect.STOP
                  || progressDuration.compareTo(progressStopGraceThreshold) > 0);
      if (allowProgressStuckCheck) {
        progressStuckCount++;
        recovery.stopBlocker = freshStopBlocker(trainName, null).orElse("");
        boolean fixed = false;
        RuntimeDispatchService.SmartRecoveryInput smartRecoveryInput =
            traceSmartProgressStuckBridge(
                trainName, currentSignal, progressDuration, "progress-stuck");
        if (autoFixEnabled && !cleanupRecoveryAttemptLimitReached) {
          fixed =
              tryFixProgressStuck(
                  trainName, currentSignal, progressDuration, recovery, now, smartRecoveryInput);
          if (fixed) {
            recoveryDispatchedCount++;
            recovery.lastRecoveryPushAt = now;
            if (!recovery.hasPendingRecovery()) {
              // 只记"已派发、待验证"。真正的 fixedCount 在车重新推进那一刻才加。
              recovery.pendingRecoveryAt = now;
            }
          }
        }
        String cleanupVerdict =
            collectStuckCleanupCandidate(
                trainName, cleanupDuration, recovery, now, stuckCleanupCandidates, activeKeys);
        // 无论是否派发了动作，这里都只是**告警**：车还没动。
        alertBus.publish(
            HealthAlert.of(
                HealthAlert.AlertType.PROGRESS_STUCK,
                trainName,
                (fixed ? "进度停滞已派发恢复动作: 持续=" : "进度停滞: 持续=")
                    + progressDuration.toSeconds()
                    + "秒 idx="
                    + currentProgress
                    + " signal="
                    + currentSignal
                    + " 恢复尝试="
                    + recovery.progressRecoveryAttempts
                    + creepSummary(trainName, currentSignal, recovery, cleanupDuration)
                    + cleanupVerdict));
      } else if (progressed || progressDuration.compareTo(progressStuckThreshold) <= 0) {
        recovery.resetProgress();
      }
    }

    if (autoFixEnabled && tryCleanupLongStuckTrain(stuckCleanupCandidates, now)) {
      fixedCount++;
    }

    traceSwitcherOccupantBlockingMany(active, now);
    pruneDeadlockEpisodes(activeKeys, now);
    pruneDeadlockFallbackEvidence(activeKeys, now);
    return new CheckResult(stallCount, progressStuckCount, fixedCount, recoveryDispatchedCount);
  }

  /** 清除所有快照。 */
  public void clear() {
    snapshots.clear();
    recoveryStates.clear();
    deadlockPairLastAttemptAt.clear();
    deadlockPairLastDestroyAt.clear();
    lastDeadlockDestroyAt = Instant.EPOCH;
    lastStuckCleanupAt = Instant.EPOCH;
    deadlockEpisodes.clear();
    deadlockFallbackEvidence.clear();
    traceFingerprints.clear();
    traceLastAt.clear();
    safeCandidateFailureCounts.clear();
  }

  /** 尝试修复静止列车。 */
  private boolean tryFixStall(String trainName, RecoveryState recovery, Instant now) {
    if (!canAttempt(now, recovery.lastStallAttemptAt)) {
      return false;
    }
    if (!smartDispatcherAllowsHealthMutation(
        trainName, DispatchAction.SMART_HEALTH_SIGNAL_RECOVERY, "health-stall")) {
      return false;
    }
    traceSmartRecoveryDecision(trainName, "SMART_FORWARD_UNLOCK_CANDIDATE", "stall");
    int nextStage = Math.min(recovery.stallStage + 1, 2);
    boolean fixed = false;
    if (nextStage == 1) {
      debugLogger.accept("TrainHealthMonitor 修复静止(stage=refresh): train=" + trainName);
      dispatchService.refreshSignalByName(trainName);
      traceSmartRecoveryDecision(trainName, "SMART_FORWARD_UNLOCK_APPLIED", "refresh-signal");
      fixed = true;
    } else {
      debugLogger.accept("TrainHealthMonitor 修复静止(stage=relaunch): train=" + trainName);
      fixed = dispatchService.forceRelaunchByName(trainName);
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
      traceSmartRecoveryDecision(trainName, "SMART_FORWARD_UNLOCK_APPLIED", "relaunch");
    }
    recovery.lastStallAttemptAt = now;
    recovery.stallStage = fixed ? nextStage : 0;
    return fixed;
  }

  /** 尝试修复进度停滞。 */
  private boolean tryFixProgressStuck(
      String trainName,
      SignalAspect currentSignal,
      Duration progressDuration,
      RecoveryState recovery,
      Instant now,
      RuntimeDispatchService.SmartRecoveryInput smartRecoveryInput) {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput != null
            ? smartRecoveryInput
            : RuntimeDispatchService.SmartRecoveryInput.fallback(
                trainName, progressDuration, currentSignal);
    if (!canAttempt(now, recovery.lastProgressAttemptAt)) {
      traceSmartRecoveryDecision(trainName, "SMART_RECOVERY_SKIPPED", "recovery-cooldown");
      return false;
    }
    if (smartDispatcherMode() == SmartDispatcherMode.ENFORCE) {
      recovery.progressRecoveryAttempts++;
    }
    debugLogger.accept(
        "SMART_RECOVERY_ACTION_ORDER train="
            + trainName
            // 只列出实际实现的动作。宣告并不存在的动作（如 SMART_HOLD_FOLLOWERS、
            // SMART_DESTROY_CANDIDATE；destroy 走的是另一条 fallback 路径）会让日志宣告的恢复能力
            // 大于实际，读日志时会据此误判"该动作试过了"。
            + " order=SMART_RELEASE_SELF_OWNED_STALE_RETAIN,SMART_RELEASE_PHYSICAL_EDGE_RETAIN,"
            + "SMART_DRAIN_UNLOCK,SMART_FORWARD_UNLOCK,SMART_QUEUE_POSITION_YIELD");
    RuntimeDispatchService.SmartRecoveryActionResult selfRetainRelease =
        safeSmartRecoveryResult(dispatchService.applySmartSelfOwnedStaleRetainRelease(input));
    if (selfRetainRelease.candidate()) {
      debugLogger.accept(
          "SMART_RECOVERY_DECISION train="
              + trainName
              + " recoveryDecision="
              + selfRetainRelease.decision()
              + " reason="
              + selfRetainRelease.reason()
              + " effectClass="
              + selfRetainRelease.effectClass()
              + " applied="
              + selfRetainRelease.applied());
      if (shouldHoldForSafeCandidate(trainName, null, selfRetainRelease, false)) {
        recovery.lastProgressAttemptAt = now;
        return selfRetainRelease.applied() && selfRetainRelease.effectiveness().effective();
      }
    }
    RuntimeDispatchService.SmartRecoveryActionResult drainUnlock =
        safeSmartRecoveryResult(dispatchService.applySmartDrainUnlock(input));
    if (drainUnlock.candidate()) {
      debugLogger.accept(
          "SMART_RECOVERY_DECISION train="
              + trainName
              + " recoveryDecision="
              + drainUnlock.decision()
              + " reason="
              + drainUnlock.reason()
              + " effectClass="
              + drainUnlock.effectClass()
              + " applied="
              + drainUnlock.applied());
      if (shouldHoldForSafeCandidate(trainName, null, drainUnlock, false)) {
        recovery.lastProgressAttemptAt = now;
        return drainUnlock.applied() && drainUnlock.effectiveness().effective();
      }
    }
    RuntimeDispatchService.SmartRecoveryActionResult forwardUnlock =
        safeSmartRecoveryResult(dispatchService.applySmartForwardUnlock(input));
    if (forwardUnlock.candidate()) {
      debugLogger.accept(
          "SMART_RECOVERY_DECISION train="
              + trainName
              + " recoveryDecision="
              + forwardUnlock.decision()
              + " reason="
              + forwardUnlock.reason()
              + " effectClass="
              + forwardUnlock.effectClass()
              + " applied="
              + forwardUnlock.applied());
      if (shouldHoldForSafeCandidate(trainName, null, forwardUnlock, false)) {
        recovery.lastProgressAttemptAt = now;
        return forwardUnlock.applied() && forwardUnlock.effectiveness().effective();
      }
    }
    // 排队位次让出：割等待环上唯一一条"不是占用"的边。
    //
    // 排在前三个动作之后是刻意的：前三个都是让**自己**让出东西（自持 retain、drain、forward
    // unlock），只有这一个动的是**别人**的排队位次，代价是队列公平性，因此放在最后。
    //
    // 它补上的是前三个动作给不出的一类解：两车在相邻道岔上长时间互卡、等待图反复检测到同一个环，
    // 而前三个动作没有一个能割它——
    // 因为环上两条边一条是 MOVEMENT_REQUIRED、一条是 QUEUE_POSITION，
    // 前三个动作都只处理自持资源。
    RuntimeDispatchService.SmartRecoveryActionResult queueYield =
        safeSmartRecoveryResult(dispatchService.applySmartQueuePositionYield(input));
    if (queueYield.candidate()) {
      debugLogger.accept(
          "SMART_RECOVERY_DECISION train="
              + trainName
              + " recoveryDecision="
              + queueYield.decision()
              + " reason="
              + queueYield.reason()
              + " effectClass="
              + queueYield.effectClass()
              + " applied="
              + queueYield.applied());
      if (shouldHoldForSafeCandidate(trainName, null, queueYield, false)) {
        recovery.lastProgressAttemptAt = now;
        return queueYield.applied() && queueYield.effectiveness().effective();
      }
    }
    if (!smartDispatcherAllowsHealthMutation(
        trainName, DispatchAction.SMART_HEALTH_SIGNAL_RECOVERY, "health-progress-stuck")) {
      return false;
    }
    if (currentSignal == SignalAspect.STOP) {
      if (progressDuration.compareTo(progressStopGraceThreshold) <= 0) {
        traceSmartRecoveryDecision(trainName, "SMART_RECOVERY_SKIPPED", "stop-grace");
        return false;
      }
      int nextStage = Math.min(recovery.progressStage + 1, 2);
      if (nextStage == 1) {
        debugLogger.accept("TrainHealthMonitor 恢复停滞(stage=refresh-stop): train=" + trainName);
        dispatchService.refreshSignalByName(trainName);
      } else {
        debugLogger.accept(
            "TrainHealthMonitor 恢复停滞(stage=self-owned-single-cleanup): train=" + trainName);
        boolean cleaned = dispatchService.clearSelfOwnedSingleDirectionMismatchByName(trainName);
        if (!cleaned) {
          debugLogger.accept("TrainHealthMonitor 恢复停滞(stage=hard-stop): train=" + trainName);
          boolean applied =
              dispatchService.reapplyHardStopByName(trainName, "health-stop-progress-stuck");
          if (!applied) {
            dispatchService.refreshSignalByName(trainName);
          }
        }
      }
      recovery.lastProgressAttemptAt = now;
      // STOP 停滞下不自动 relaunch/reissue，避免健康监控绕过红灯；后续 tick 继续 refresh/cleanup/hard-stop。
      recovery.progressStage = nextStage;
      return false;
    }

    int nextStage = Math.min(recovery.progressStage + 1, 3);
    boolean fixed = false;
    if (nextStage == 1) {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=refresh): train=" + trainName);
      dispatchService.refreshSignalByName(trainName);
      fixed = true;
    } else if (nextStage == 2) {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=reissue): train=" + trainName);
      fixed = dispatchService.reissueDestinationByName(trainName);
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
    } else {
      debugLogger.accept("TrainHealthMonitor 修复停滞(stage=relaunch): train=" + trainName);
      debugLogger.accept(
          "SMART_HEALTH_EFFECT_EXECUTION action=SMART_HEALTH_SIGNAL_RECOVERY effect=FORCE_RELAUNCH train="
              + trainName
              + " source=health-progress-stuck dispatcherAction=false");
      fixed = dispatchService.forceRelaunchByName(trainName);
      if (!fixed) {
        fixed = dispatchService.reissueDestinationByName(trainName);
      }
      if (!fixed) {
        dispatchService.refreshSignalByName(trainName);
      }
    }

    recovery.lastProgressAttemptAt = now;
    recovery.progressStage = fixed ? nextStage : 0;
    return fixed;
  }

  /**
   * 收集普通停滞清理候选，并给出附在停滞告警末尾的清车结论。
   *
   * <p>结论只在停满清车阈值之后给出（之前清车本来就不接）。停滞告警是 WARN 必留行，运维排查时往往只拿得到这一行：它要自己说清"为什么还没清"、
   * 在等谁、等的那列是什么状态，而不是让人再去翻 debug 日志；只有"恢复尝试=N"的告警回答不了这些问题。
   *
   * @param activeKeys 本轮存活列车键：挡路的车不在其中就是残留占用
   * @return 以空格开头的结论，如 {@code " 清车=等待 X(停滞 700秒)"}；未到清车阈值时为空串
   */
  private String collectStuckCleanupCandidate(
      String trainName,
      Duration progressDuration,
      RecoveryState recovery,
      Instant now,
      List<StuckTrainCleanupPolicy.Candidate> candidates,
      Set<String> activeKeys) {
    if (progressDuration == null
        || progressDuration.compareTo(stuckCleanupThreshold) < 0
        || candidates == null) {
      return "";
    }
    if (!trainCleanupEnabled) {
      // 清车关着也说出在等谁：这一行常常是唯一留下来的现场，"为什么停着"不能只剩"未开启"。
      // 告警一分钟才发一次，解析挡路车的结果按同一节奏复用，不每轮都解析。
      if (recovery != null
          && Duration.between(recovery.disabledVerdictAt, now).compareTo(DISABLED_VERDICT_REUSE)
              < 0) {
        return recovery.disabledVerdict;
      }
      Set<String> blockers = dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
      String verdict =
          " 清车=未开启"
              + (blockers.isEmpty() ? "" : " 等待 " + describeBlockers(blockers, activeKeys, now));
      if (recovery != null) {
        recovery.disabledVerdict = verdict;
        recovery.disabledVerdictAt = now;
      }
      return verdict;
    }
    Optional<StuckTrainCleanupPolicy.Candidate> built =
        cleanupCandidate(
            trainName, progressDuration, cleanupRecoveryObservationComplete(recovery, now));
    if (built.isEmpty()) {
      traceHealthEvent(
          "STUCK_CLEANUP_SKIPPED",
          "stuck-cleanup-context:" + keyOf(trainName),
          "train=" + trainName + " reason=context-missing");
      return " 清车=缺少运行时上下文";
    }
    StuckTrainCleanupPolicy.Candidate candidate = built.get();
    Set<String> blockers = candidate.blockers();
    candidates.add(candidate);
    StuckTrainCleanupPolicy.Eligibility eligibility =
        StuckTrainCleanupPolicy.eligibility(
            candidate, stuckCleanupThreshold, stuckCleanupPassengerThreshold);
    if (eligibility != StuckTrainCleanupPolicy.Eligibility.ELIGIBLE) {
      traceHealthEvent(
          "STUCK_CLEANUP_SKIPPED",
          "stuck-cleanup-skip:" + keyOf(trainName),
          "train="
              + trainName
              + " reason="
              + eligibility.reason()
              + " stuck="
              + progressDuration.toSeconds()
              + "s attempts="
              + (recovery == null ? 0 : recovery.progressRecoveryAttempts));
    }
    return " 清车=" + cleanupVerdict(eligibility, blockers, activeKeys, now);
  }

  /** 清车关着时，停滞结论里挡路车的解析结果复用多久（与告警总线的稳定状态提醒间隔相同）。 */
  private static final Duration DISABLED_VERDICT_REUSE = Duration.ofMinutes(1);

  /**
   * 按运行时当前状态组一份清车候选；运行时解析不到这辆车时为空。
   *
   * @param stuckDuration 清车看的停滞时长
   * @param recoveryExhausted 恢复是否已用尽
   */
  private Optional<StuckTrainCleanupPolicy.Candidate> cleanupCandidate(
      String trainName, Duration stuckDuration, boolean recoveryExhausted) {
    return dispatchService
        .deadlockTrainContext(trainName)
        .map(
            context -> {
              Set<String> blockers =
                  dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
              return new StuckTrainCleanupPolicy.Candidate(
                  context,
                  stuckDuration,
                  recoveryExhausted,
                  !blockers.isEmpty()
                      || dispatchService.hasRecentGateQueueEntry(trainName, blockerSnapshotMaxAge),
                  blockers);
            });
  }

  /** 清车此刻会不会接手这辆车（假定恢复已用尽）：停滞段里的车据此决定还要不要接着推。 */
  private boolean cleanupWouldTake(String trainName, Duration stuckDuration) {
    return cleanupCandidate(trainName, stuckDuration, true)
        .map(
            candidate ->
                StuckTrainCleanupPolicy.eligibility(
                        candidate, stuckCleanupThreshold, stuckCleanupPassengerThreshold)
                    == StuckTrainCleanupPolicy.Eligibility.ELIGIBLE)
        .orElse(false);
  }

  /**
   * 这段停滞结束：车自己开起来了或进了站。被推动过的车到这时才宣布"已恢复"——推一下的推进不算。
   *
   * @return 是否结束了一段停滞并发了"已恢复"
   */
  private boolean endCreepRecovered(String trainName, RecoveryState recovery, Instant now) {
    if (!recovery.creeping()) {
      return false;
    }
    alertBus.publish(
        HealthAlert.fixed(
            HealthAlert.AlertType.PROGRESS_STUCK,
            trainName,
            "进度停滞已恢复: 推动 "
                + recovery.creepPushes
                + " 次后车辆持续推进 累计停滞="
                + Duration.between(recovery.creepSince, now).toSeconds()
                + "秒"));
    recovery.endCreep();
    return true;
  }

  /** 运行时当前停车记录。给了 {@code recovery} 且这段停滞里推动过车时，只认推动之后才进入的记录：推动之前的旧记录说的是上一次为什么停。 */
  private Optional<RuntimeStopState> freshStopState(String trainName, RecoveryState recovery) {
    Optional<RuntimeStopState> stop = dispatchService.getActiveStopState(trainName);
    if (recovery == null || Instant.EPOCH.equals(recovery.creepLastPushAt)) {
      return stop;
    }
    return stop.filter(state -> !state.enteredAt().isBefore(recovery.creepLastPushAt));
  }

  /** {@link #freshStopState} 里第一个 blocker 的持有者；记录在但没有 blocker 或持有者未知时为空串。 */
  private Optional<String> freshStopBlocker(String trainName, RecoveryState recovery) {
    return freshStopState(trainName, recovery)
        .map(
            stop ->
                stop.blockers().isEmpty() || "-".equals(stop.blockers().get(0).owner())
                    ? ""
                    : stop.blockers().get(0).owner());
  }

  /**
   * 被恢复动作推过又停下的车，告警补上整段停滞、推动次数与这次是谁拦住的；没有这样的停滞段时为空串。
   *
   * <p>"再停"取运行时当前的停车记录：推进之后重新停下是新的一段停车，它的停因与 blocker 说的就是这一次为什么停。
   */
  private String creepSummary(
      String trainName, SignalAspect signal, RecoveryState recovery, Duration cleanupDuration) {
    if (!recovery.creeping()) {
      return "";
    }
    return " 累计停滞="
        + cleanupDuration.toSeconds()
        + "秒 恢复后推进="
        + recovery.creepPushes
        + "次 再停="
        + restopCause(trainName, signal, recovery);
  }

  /** 这次停下的原因：停因代码，加上第一个 blocker（持有者与资源）。推动之前就有的停车记录不算这一次的。 */
  private String restopCause(String trainName, SignalAspect signal, RecoveryState recovery) {
    Optional<RuntimeStopState> stop = freshStopState(trainName, recovery);
    if (stop.isEmpty()) {
      if (dispatchService.getActiveStopState(trainName).isPresent()) {
        return "未记录新停因(停车记录早于推动)";
      }
      return signal == SignalAspect.STOP ? "未记录停因" : "信号" + signal + "未推进";
    }
    List<RuntimeStopState.Blocker> blockers = stop.get().blockers();
    if (blockers == null || blockers.isEmpty()) {
      return stop.get().reasonCode();
    }
    RuntimeStopState.Blocker first = blockers.get(0);
    String owner = "-".equals(first.owner()) ? "未知" : first.owner();
    return stop.get().reasonCode()
        + "("
        + owner
        + " 持有 "
        + first.resource()
        + (blockers.size() > 1 ? " 等" + blockers.size() + "处" : "")
        + ")";
  }

  /** 清车结论的中文短语：够格就是"待执行"，排队等前车时点名在等谁、那列车此刻是什么状态。 */
  private String cleanupVerdict(
      StuckTrainCleanupPolicy.Eligibility eligibility,
      Set<String> blockers,
      Set<String> activeKeys,
      Instant now) {
    return switch (eligibility) {
      case ELIGIBLE -> "待执行";
      case RECOVERY_NOT_EXHAUSTED -> "恢复观察中";
      case TRAIN_MOVING -> "列车在动";
      case CONTROLLED_STOP -> "受控停车";
      case PASSENGER_GRACE -> "载客宽限";
      case CLEANUP_THRESHOLD_NOT_REACHED -> "未到阈值";
      case CONTEXT_MISSING -> "缺少运行时上下文";
      case WAITING_ON_LIVE_BLOCKER -> blockers.isEmpty()
          ? "排队(未记录前车)"
          : "等待 " + describeBlockers(blockers, activeKeys, now);
    };
  }

  /** 按列车名排序，最多点名三列，每列附上它此刻的状态。 */
  private String describeBlockers(Set<String> blockers, Set<String> activeKeys, Instant now) {
    List<String> names =
        blockers.stream()
            .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
            .toList();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < Math.min(names.size(), CLEANUP_VERDICT_BLOCKERS_NAMED); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(names.get(i))
          .append('(')
          .append(blockerState(names.get(i), activeKeys, now))
          .append(')');
    }
    if (names.size() > CLEANUP_VERDICT_BLOCKERS_NAMED) {
      out.append(" 等").append(names.size()).append("列");
    }
    return out.toString();
  }

  /**
   * 挡路那列车此刻的状态：运行时解析不到、也不在存活列车里就是残留占用（车已不在、占用还挂着）；待命、停站、扣车各有各的机制收拾； 否则报它自己停了多久——停得和本车一样久，多半是同一个死结。
   *
   * <p>先问运行时：占用上记的是逻辑名，折返复用改名后与存活列车名对不上，但运行时的别名解析认得它，不能报成残留占用。
   */
  private String blockerState(String blocker, Set<String> activeKeys, Instant now) {
    String key = keyOf(blocker);
    Optional<RuntimeDispatchService.DeadlockTrainContext> context =
        dispatchService.deadlockTrainContext(blocker);
    if (context.isEmpty() && (key == null || activeKeys == null || !activeKeys.contains(key))) {
      return "残留占用";
    }
    if (context.isPresent()) {
      RuntimeDispatchService.DeadlockTrainContext value = context.get();
      if (value.layoverReady()) {
        return "终点待命";
      }
      if (value.dwelling()) {
        return "停站";
      }
      if (value.departureGateHeld()) {
        return "发车扣车";
      }
      if (value.manualHold()) {
        return "人工扣车";
      }
      if (value.speedBlocksPerTick() > lowSpeedThresholdBpt) {
        return "运行中";
      }
    }
    // 占用上记的可能是改名前的逻辑名；快照与恢复状态按运行时现在的车名记。
    String runtimeKey = context.map(value -> keyOf(value.trainName())).orElse(key);
    TrainSnapshot snapshot = runtimeKey == null ? null : snapshots.get(runtimeKey);
    if (snapshot == null) {
      return "状态未知";
    }
    String stalled = "停滞 " + Duration.between(snapshot.lastProgressTime(), now).toSeconds() + "秒";
    RecoveryState blockerRecovery = recoveryStates.get(runtimeKey);
    return blockerRecovery != null && blockerRecovery.creeping()
        ? stalled + " 累计 " + Duration.between(blockerRecovery.creepSince, now).toSeconds() + "秒"
        : stalled;
  }

  /**
   * 判断最后一次分级恢复后是否已经留出完整观察窗。
   *
   * <p>第三次 progress 恢复只完成“恢复链耗尽”，不能在同一次健康检查中立刻销毁。必须经过至少一个后续采样，并等待最后一次 progress 或 stall recovery
   * cooldown 到期，才能把列车交给 cleanup 复审。
   */
  private boolean cleanupRecoveryObservationComplete(RecoveryState recovery, Instant now) {
    if (recovery == null || now == null || recovery.cleanupAttempts() < 3) {
      return false;
    }
    if (recovery.creeping() && recovery.progressRecoveryAttempts < 1) {
      // 被推过又停下：这一次停车还没试过恢复，先试一次再说用尽。
      return false;
    }
    Instant lastRecoveryAttempt = recovery.latestAttemptAt();
    if (lastRecoveryAttempt == null
        || lastRecoveryAttempt.equals(Instant.EPOCH)
        || !now.isAfter(lastRecoveryAttempt)) {
      return false;
    }
    return !now.isBefore(lastRecoveryAttempt.plus(recoveryCooldown));
  }

  /**
   * 从本轮所有普通停滞候选中最多清理一列车。
   *
   * <p>选择完成后仍调用 RuntimeDispatchService 重新解析实体与上下文；Smart Dispatcher mode gate 再限制 destroy
   * 副作用。这里不释放占用，后车恢复依赖真实 GroupRemove 触发的 OccupancyReleased 事件。
   *
   * <p>普通候选之后再看停滞车之间的等待环（{@link StuckTrainCleanupPolicy#waitCycleTargets}）：环上的车排队等的正是环上的车，
   * 谁都等不到头。清掉环上一列，其余的车下一拍就能重新授权。复审时把环成员一并交给运行时，只有挡住它的车此刻仍全在环里才放行； 被拒就接着试下一列，普通候选被拒也不妨碍试环上的车。
   */
  private boolean tryCleanupLongStuckTrain(
      List<StuckTrainCleanupPolicy.Candidate> candidates, Instant now) {
    if (!trainCleanupEnabled
        || candidates == null
        || candidates.isEmpty()
        || now == null
        || now.isBefore(lastStuckCleanupAt.plus(stuckCleanupCooldown))) {
      return false;
    }
    List<CleanupTarget> targets = new ArrayList<>();
    StuckTrainCleanupPolicy.select(
            candidates, stuckCleanupThreshold, stuckCleanupPassengerThreshold)
        .ifPresent(candidate -> targets.add(new CleanupTarget(candidate, List.of())));
    for (StuckTrainCleanupPolicy.WaitCycle cycle :
        StuckTrainCleanupPolicy.waitCycleTargets(
            candidates, stuckCleanupThreshold, stuckCleanupPassengerThreshold)) {
      targets.add(new CleanupTarget(cycle.target(), cycle.members()));
    }
    for (CleanupTarget target : targets) {
      if (reviewedForCleanup(target, now)) {
        return executeStuckCleanup(target, now);
      }
    }
    return false;
  }

  /** 普通清理的一个目标；{@code waitCycle} 非空时它是等待环上的车，列出整个环。 */
  private record CleanupTarget(
      StuckTrainCleanupPolicy.Candidate candidate, List<String> waitCycle) {}

  private boolean reviewedForCleanup(CleanupTarget target, Instant now) {
    RuntimeDispatchService.DeadlockTrainContext context = target.candidate().context();
    Set<String> blockers =
        dispatchService.recentBlockerTrains(context.trainName(), blockerSnapshotMaxAge);
    if (blockedByDriver(blockers)) {
      // 挡住它的是驾驶员控制的车：不清掉被挡的车；与互卡检测同一个计时，挡够时限才请那列车交还自动运行。
      RecoveryState recovery = recoveryStates.get(keyOf(context.trainName()));
      boolean handback =
          recovery != null
              && requestDriverHandback(
                  recovery.driversBlockingAtLeast(
                      driversHoldingUp(blockers), now, deadlockDestroyThreshold));
      debugLogger.accept(
          "STUCK_CLEANUP_DRIVER_HANDBACK train=" + context.trainName() + " requested=" + handback);
      return false;
    }
    if (!target.waitCycle().isEmpty()) {
      debugLogger.accept(
          "STUCK_CLEANUP_WAIT_CYCLE train="
              + context.trainName()
              + " cycle="
              + String.join(",", target.waitCycle()));
    }
    SmartDispatcherController.StuckCleanupReview review =
        dispatchService.reviewStuckCleanupCandidate(
            context.trainName(),
            context.progressIndex(),
            target.candidate().recoveryExhausted(),
            target.candidate().stuckDuration(),
            stuckCleanupThreshold,
            stuckCleanupPassengerThreshold,
            Set.copyOf(target.waitCycle()));
    if (!review.allowed()) {
      traceHealthEvent(
          "STUCK_CLEANUP_SKIPPED",
          "stuck-cleanup-review:" + keyOf(context.trainName()),
          "train=" + context.trainName() + " reason=" + review.reason());
      return false;
    }
    return true;
  }

  private boolean executeStuckCleanup(CleanupTarget target, Instant now) {
    StuckTrainCleanupPolicy.Candidate candidate = target.candidate();
    RuntimeDispatchService.DeadlockTrainContext context = candidate.context();
    if (!smartDispatcherAllowsHealthMutation(
        context.trainName(),
        DispatchAction.EXECUTE_VERIFIED_STUCK_CLEANUP,
        "health-stuck-cleanup")) {
      return false;
    }
    debugLogger.accept(
        "STUCK_CLEANUP_CANDIDATE_SELECTED train="
            + context.trainName()
            + " stuck="
            + candidate.stuckDuration().toSeconds()
            + "s passengers="
            + context.hasPassengers()
            + " operation="
            + context.operationType()
            + " depotRelated="
            + context.depotRelated());
    debugLogger.accept(
        "SMART_HEALTH_EFFECT_EXECUTION action=EXECUTE_VERIFIED_STUCK_CLEANUP "
            + "effect=DESTROY_TRAIN train="
            + context.trainName()
            + " source=health-stuck-cleanup dispatcherAction=false");
    lastStuckCleanupAt = now;
    boolean destroyed =
        dispatchService.destroyTrainByName(context.trainName(), "health-stuck-cleanup-timeout");
    debugLogger.accept(
        (destroyed ? "STUCK_CLEANUP_EXECUTED" : "STUCK_CLEANUP_FAILED")
            + " train="
            + context.trainName()
            + " postRemovalRecovery=occupancy-release-event");
    if (destroyed) {
      alertBus.publish(
          HealthAlert.fixed(
              HealthAlert.AlertType.PROGRESS_STUCK,
              context.trainName(),
              "停滞清车: 已销毁 持续="
                  + candidate.stuckDuration().toSeconds()
                  + "秒 idx="
                  + context.progressIndex()
                  + (target.waitCycle().isEmpty()
                      ? ""
                      : " 等待环=" + String.join(",", target.waitCycle()))));
    }
    return destroyed;
  }

  /**
   * 查找“互相阻塞”的对端列车。
   *
   * <p>判定条件：
   *
   * <ul>
   *   <li>A 的 blocker 列表包含 B
   *   <li>B 的 blocker 列表包含 A
   *   <li>B 当前仍在线
   * </ul>
   */
  private Optional<String> findMutualBlocker(String trainName, Set<String> activeTrainKeys) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Set<String> blockers = dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge);
    if (blockers.isEmpty()) {
      return Optional.empty();
    }
    String trainKey = keyOf(trainName);
    if (trainKey == null) {
      return Optional.empty();
    }
    for (String blocker : blockers) {
      String blockerKey = keyOf(blocker);
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeTrainKeys == null || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      Set<String> reverse = dispatchService.recentBlockerTrains(blocker, blockerSnapshotMaxAge);
      for (String reverseTrain : reverse) {
        String reverseKey = keyOf(reverseTrain);
        if (trainKey.equals(reverseKey)) {
          return Optional.of(blocker);
        }
      }
    }
    return Optional.empty();
  }

  /** 查找满足“同一 single conflict + 已知对向方向”的 confirmed mutual deadlock。 */
  private Optional<DeadlockObservation> findConfirmedMutualDeadlock(
      String trainName,
      Set<String> activeTrainKeys,
      TrainSnapshot currentSnapshot,
      Duration progressDuration,
      Instant now) {
    if (trainName == null || trainName.isBlank() || currentSnapshot == null) {
      return Optional.empty();
    }
    String trainKey = keyOf(trainName);
    if (trainKey == null) {
      return Optional.empty();
    }
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    if (snapshot.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (!isUsableSingleConflictBlocker(blocker)) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeTrainKeys == null || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockBlockerInfo> reverseOpt =
          findReverseSingleConflictBlocker(blocker.trainName(), trainKey, blocker.conflictKey());
      if (reverseOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerInfo reverse = reverseOpt.get();
      if (!isKnownOpposite(blocker.direction(), reverse.direction())) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockTrainContext> firstContextOpt =
          dispatchService.deadlockTrainContext(trainName);
      Optional<RuntimeDispatchService.DeadlockTrainContext> secondContextOpt =
          dispatchService.deadlockTrainContext(blocker.trainName());
      if (firstContextOpt.isEmpty() || secondContextOpt.isEmpty()) {
        continue;
      }
      RuntimeDispatchService.DeadlockTrainContext firstContext = firstContextOpt.get();
      RuntimeDispatchService.DeadlockTrainContext secondContext = secondContextOpt.get();
      if (!isEligibleDeadlockContext(firstContext)
          || !isEligibleDeadlockContext(secondContext)
          || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
        continue;
      }
      String firstKey = firstTrainInPair(trainKey, blockerKey);
      boolean currentIsFirst = trainKey.equals(firstKey);
      String canonicalTrainA = currentIsFirst ? trainName : blocker.trainName();
      String canonicalTrainB = currentIsFirst ? blocker.trainName() : trainName;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextA =
          currentIsFirst ? firstContext : secondContext;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextB =
          currentIsFirst ? secondContext : firstContext;
      String episodeKey = episodeKey(trainKey, blockerKey, blocker.conflictKey());
      Set<String> blockerSnapshot =
          Set.of(
              trainName + "->" + blocker.trainName() + "@" + blocker.conflictKey(),
              blocker.trainName() + "->" + trainName + "@" + blocker.conflictKey());
      Set<String> blockerResources = Set.of(blocker.conflictKey());
      return Optional.of(
          new DeadlockObservation(
              episodeKey,
              canonicalTrainA,
              canonicalTrainB,
              blocker.conflictKey(),
              false,
              blockerSnapshot,
              blockerResources,
              canonicalContextA,
              canonicalContextB,
              "stored"));
    }
    Optional<DeadlockObservation> liveCycle =
        findLiveMutualBlockerCycle(
            trainName, trainKey, activeTrainKeys, snapshot, progressDuration);
    if (liveCycle.isPresent()) {
      return liveCycle;
    }
    Optional<DeadlockObservation> weakObservation =
        findWeakMutualDeadlock(trainName, trainKey, activeTrainKeys, snapshot, progressDuration);
    if (weakObservation.isPresent()) {
      return weakObservation;
    }
    return Optional.empty();
  }

  private Optional<DeadlockObservation> findLiveMutualBlockerCycle(
      String trainName,
      String trainKey,
      Set<String> activeTrainKeys,
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot,
      Duration progressDuration) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (!isLiveHardCycleBlocker(blocker)) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null
          || blockerKey.equals(trainKey)
          || activeTrainKeys == null
          || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerSnapshot reverseSnapshot =
          recentDeadlockBlockerSnapshot(blocker.trainName());
      Optional<RuntimeDispatchService.DeadlockBlockerInfo> reverseOpt =
          reverseSnapshot.blockers().stream()
              .filter(candidate -> trainKey.equals(keyOf(candidate.trainName())))
              .filter(this::isLiveHardCycleBlocker)
              .findFirst();
      if (reverseOpt.isEmpty()) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockTrainContext> firstContextOpt =
          dispatchService.deadlockTrainContext(trainName);
      Optional<RuntimeDispatchService.DeadlockTrainContext> secondContextOpt =
          dispatchService.deadlockTrainContext(blocker.trainName());
      if (firstContextOpt.isEmpty()
          || secondContextOpt.isEmpty()
          || !isEligibleDeadlockContext(firstContextOpt.get())
          || !isEligibleDeadlockContext(secondContextOpt.get())
          || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerInfo reverse = reverseOpt.get();
      String firstKey = firstTrainInPair(trainKey, blockerKey);
      boolean currentIsFirst = trainKey.equals(firstKey);
      String canonicalTrainA = currentIsFirst ? trainName : blocker.trainName();
      String canonicalTrainB = currentIsFirst ? blocker.trainName() : trainName;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextA =
          currentIsFirst ? firstContextOpt.get() : secondContextOpt.get();
      RuntimeDispatchService.DeadlockTrainContext canonicalContextB =
          currentIsFirst ? secondContextOpt.get() : firstContextOpt.get();
      String resourceA = liveCycleResourceKey(blocker);
      String resourceB = liveCycleResourceKey(reverse);
      String conflictKey = "live:" + pairKey(trainKey, blockerKey);
      Set<String> blockerSnapshot =
          Set.of(
              trainName + "->" + blocker.trainName() + "@" + resourceA,
              blocker.trainName() + "->" + trainName + "@" + resourceB);
      Set<String> blockerResources = new LinkedHashSet<>();
      blockerResources.add(resourceA);
      blockerResources.add(resourceB);
      debugLogger.accept(
          "SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED trainA="
              + canonicalTrainA
              + " trainB="
              + canonicalTrainB
              + " resourceA="
              + resourceA
              + " resourceB="
              + resourceB);
      return Optional.of(
          new DeadlockObservation(
              episodeKey(trainKey, blockerKey, conflictKey),
              canonicalTrainA,
              canonicalTrainB,
              conflictKey,
              false,
              blockerSnapshot,
              blockerResources,
              canonicalContextA,
              canonicalContextB,
              "live"));
    }
    return Optional.empty();
  }

  private Optional<DeadlockObservation> findWeakMutualDeadlock(
      String trainName,
      String trainKey,
      Set<String> activeTrainKeys,
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot,
      Duration progressDuration) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null
          || blockerKey.equals(trainKey)
          || activeTrainKeys == null
          || !activeTrainKeys.contains(blockerKey)) {
        continue;
      }
      RuntimeDispatchService.DeadlockBlockerSnapshot reverseSnapshot =
          recentDeadlockBlockerSnapshot(blocker.trainName());
      boolean reverse =
          reverseSnapshot.blockers().stream()
              .anyMatch(
                  info ->
                      keyOf(info.trainName()) != null && keyOf(info.trainName()).equals(trainKey));
      if (!reverse) {
        continue;
      }
      Optional<RuntimeDispatchService.DeadlockTrainContext> firstContextOpt =
          dispatchService.deadlockTrainContext(trainName);
      Optional<RuntimeDispatchService.DeadlockTrainContext> secondContextOpt =
          dispatchService.deadlockTrainContext(blocker.trainName());
      if (firstContextOpt.isEmpty()
          || secondContextOpt.isEmpty()
          || !isEligibleDeadlockContext(firstContextOpt.get())
          || !isEligibleDeadlockContext(secondContextOpt.get())
          || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
        continue;
      }
      String firstKey = firstTrainInPair(trainKey, blockerKey);
      boolean currentIsFirst = trainKey.equals(firstKey);
      String canonicalTrainA = currentIsFirst ? trainName : blocker.trainName();
      String canonicalTrainB = currentIsFirst ? blocker.trainName() : trainName;
      RuntimeDispatchService.DeadlockTrainContext canonicalContextA =
          currentIsFirst ? firstContextOpt.get() : secondContextOpt.get();
      RuntimeDispatchService.DeadlockTrainContext canonicalContextB =
          currentIsFirst ? secondContextOpt.get() : firstContextOpt.get();
      String conflictKey = "weaker:" + pairKey(trainKey, blockerKey);
      Set<String> blockerSnapshot =
          Set.of(
              trainName + "->" + blocker.trainName() + "@weaker",
              blocker.trainName() + "->" + trainName + "@weaker");
      Set<String> blockerResources =
          blocker.conflictKey() == null || blocker.conflictKey().isBlank()
              ? Set.of()
              : Set.of(blocker.conflictKey());
      return Optional.of(
          new DeadlockObservation(
              episodeKey(trainKey, blockerKey, conflictKey),
              canonicalTrainA,
              canonicalTrainB,
              conflictKey,
              true,
              blockerSnapshot,
              blockerResources,
              canonicalContextA,
              canonicalContextB,
              "stored"));
    }
    return Optional.empty();
  }

  private boolean isLiveCycleBlockerResource(RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    if (blocker == null || blocker.trainName() == null || blocker.trainName().isBlank()) {
      return false;
    }
    String resource = liveCycleResourceKey(blocker);
    return resource.startsWith("switcher:") || resource.startsWith("single:");
  }

  /**
   * 判断 live wait-for 边是否携带可用于 hard-cycle 的实体授权证据。
   *
   * <p>资源名称只能说明 blocker 位于哪一类冲突区，不能证明它代表真实占用。队列位次、软预约、保护保留与旧版 UNKNOWN 快照都只能进入 weak
   * 诊断；只有明确的硬阻塞关系与移动/实体 footprint claim 同时存在时，才允许升级为 non-weak episode。
   */
  private boolean isLiveHardCycleBlocker(RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    return isLiveCycleBlockerResource(blocker) && isExplicitHardBlocker(blocker);
  }

  private static boolean isExplicitHardBlocker(RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    if (blocker == null) {
      return false;
    }
    boolean hardRole =
        ClaimRole.MOVEMENT_REQUIRED.name().equals(blocker.role())
            || ClaimRole.PHYSICAL_FOOTPRINT.name().equals(blocker.role());
    boolean hardRelation =
        BlockerRelation.OPPOSITE_SINGLE_CONFLICT.name().equals(blocker.relation())
            || BlockerRelation.SWITCHER_CONFLICT.name().equals(blocker.relation())
            || BlockerRelation.HARD_OCCUPANCY.name().equals(blocker.relation());
    return hardRole && hardRelation;
  }

  private static String liveCycleResourceKey(RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    if (blocker == null) {
      return "-";
    }
    if (blocker.conflictKey() != null && !blocker.conflictKey().isBlank()) {
      return blocker.conflictKey();
    }
    String resourceKey = blocker.resourceKey();
    if (resourceKey == null || resourceKey.isBlank()) {
      return "-";
    }
    return resourceKey.startsWith("CONFLICT:")
        ? resourceKey.substring("CONFLICT:".length())
        : resourceKey;
  }

  private Optional<RuntimeDispatchService.DeadlockBlockerInfo> findReverseSingleConflictBlocker(
      String blockerTrain, String targetTrainKey, String conflictKey) {
    RuntimeDispatchService.DeadlockBlockerSnapshot reverse =
        recentDeadlockBlockerSnapshot(blockerTrain);
    if (reverse.blockers().isEmpty()) {
      return Optional.empty();
    }
    for (RuntimeDispatchService.DeadlockBlockerInfo candidate : reverse.blockers()) {
      if (!isUsableSingleConflictBlocker(candidate)) {
        continue;
      }
      String candidateKey = keyOf(candidate.trainName());
      if (targetTrainKey.equals(candidateKey) && conflictKey.equals(candidate.conflictKey())) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private RuntimeDispatchService.DeadlockBlockerSnapshot recentDeadlockBlockerSnapshot(
      String trainName) {
    return Optional.ofNullable(
            dispatchService.recentDeadlockBlockers(trainName, blockerSnapshotMaxAge))
        .orElseGet(
            () -> new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH));
  }

  private static boolean isUsableSingleConflictBlocker(
      RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    return blocker != null
        && isExplicitHardBlocker(blocker)
        && blocker.trainName() != null
        && !blocker.trainName().isBlank()
        && blocker.conflictKey() != null
        && blocker.conflictKey().startsWith("single:")
        && !blocker.conflictKey().contains(":cycle:")
        && blocker.direction().isPresent()
        && blocker.direction().get() != CorridorDirection.UNKNOWN;
  }

  private static boolean isKnownOpposite(
      Optional<CorridorDirection> first, Optional<CorridorDirection> second) {
    return first != null
        && second != null
        && first.isPresent()
        && second.isPresent()
        && first.get() != CorridorDirection.UNKNOWN
        && second.get() != CorridorDirection.UNKNOWN
        && first.get().opposite() == second.get();
  }

  private boolean isEligibleDeadlockContext(RuntimeDispatchService.DeadlockTrainContext context) {
    return context != null
        && context.signalAspect() == SignalAspect.STOP
        && context.speedBlocksPerTick() <= lowSpeedThresholdBpt
        && !context.dwelling()
        && !context.departureGateHeld()
        && !context.layoverReady()
        && !context.manualHold();
  }

  private DeadlockEpisode updateDeadlockEpisode(DeadlockObservation observation, Instant now) {
    return deadlockEpisodes.compute(
        observation.episodeKey(),
        (key, existing) -> {
          if (existing == null) {
            String leader =
                chooseStableLeader(observation.firstContext(), observation.secondContext());
            DeadlockEpisode created =
                new DeadlockEpisode(
                    key,
                    observation.trainA(),
                    observation.trainB(),
                    observation.conflictKey(),
                    observation.weak(),
                    now,
                    leader,
                    observation.blockerSnapshot());
            traceDeadlockEpisodeCreated(created, observation, now);
            return created;
          }
          existing.updateSnapshot(now, observation.blockerSnapshot());
          return existing;
        });
  }

  private void pruneDeadlockEpisodes(Set<String> activeKeys, Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    deadlockEpisodes
        .values()
        .removeIf(
            episode -> {
              String firstKey = keyOf(episode.trainA);
              String secondKey = keyOf(episode.trainB);
              if (firstKey == null
                  || secondKey == null
                  || activeKeys == null
                  || !activeKeys.contains(firstKey)
                  || !activeKeys.contains(secondKey)) {
                return true;
              }
              return effectiveNow.isAfter(episode.lastSeenAt.plus(deadlockEpisodeGrace));
            });
  }

  private void closeDeadlockEpisodeAfterDestroy(DeadlockEpisode episode, Instant now) {
    if (episode == null) {
      return;
    }
    deadlockEpisodes.remove(episode.key);
    debugLogger.accept(
        "HEALTH_EPISODE_CLOSED episode="
            + episode.key
            + " train="
            + episode.stableLeader
            + " reason=destroyed at="
            + (now == null ? Instant.now() : now));
  }

  /**
   * 判断列车当前是否仍持有“新鲜”的 blocker 快照。
   *
   * <p>用于区分“合法 STOP 排队等待”和“信号/调度状态疑似丢失”。 只要 blocker 快照仍在有效期内，就优先认为列车正在等待前车或冲突区放行， 不立即升级到 {@code
   * reissueDestination}；超过 STOP 宽限后即使 blocker 仍持续刷新，也会进入停滞恢复链，避免长时间互卡被“新鲜快照”无限掩盖。
   */
  private boolean hasRecentBlockers(String trainName) {
    return trainName != null
        && !trainName.isBlank()
        && !dispatchService.recentBlockerTrains(trainName, blockerSnapshotMaxAge).isEmpty();
  }

  private void traceDeadlockEpisodeCreated(
      DeadlockEpisode episode, DeadlockObservation observation, Instant now) {
    if (episode == null || observation == null) {
      return;
    }
    String type = episodeType(observation);
    long stoppedMs =
        Math.max(
            0L,
            Duration.between(
                    snapshots
                        .getOrDefault(keyOf(episode.trainA), emptySnapshot(now))
                        .lastProgressTime(),
                    now)
                .toMillis());
    Duration required = observation.weak() ? Duration.ZERO : deadlockDestroyThreshold;
    traceHealthEvent(
        "DEADLOCK_EPISODE_CREATED",
        "episode-created:" + episode.key,
        "episodeId="
            + episode.key
            + " episodeType="
            + type
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " blockerResources="
            + observation.blockerResources()
            + " commonConflictKey="
            + episode.conflictKey
            + " directions="
            + directionsSummary(observation)
            + " firstSeenTime="
            + episode.firstSeenAt
            + " stoppedDurationMs="
            + stoppedMs
            + " requiredDestroyThresholdMs="
            + required.toMillis()
            + " destroyPolicy="
            + (observation.weak() ? "diagnostic-only" : "confirmed-live-hard-cycle")
            + " destroyEnabled="
            + trainCleanupEnabled
            + " blockerSnapshotSource="
            + observation.blockerSnapshotSource()
            + " sourceSnapshotAgeMs="
            + snapshotAgeMs(episode.trainA, now));
  }

  private void traceDeadlockSkipped(
      String trainName,
      TrainSnapshot current,
      Duration progressDuration,
      Set<String> activeKeys,
      Instant now) {
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    String reason = classifyDeadlockSkip(trainName, snapshot, activeKeys);
    if ("NONE".equals(reason)) {
      return;
    }
    boolean thresholdPassed =
        progressDuration != null && progressDuration.compareTo(requiredDestroyThreshold(null)) >= 0;
    String fallbackIneligibleReason =
        thresholdPassed ? fallbackPrecheckIneligibleReason(reason, snapshot) : "below-threshold";
    traceHealthEvent(
        "DEADLOCK_DESTROY_SKIPPED",
        "deadlock-skip:" + keyOf(trainName) + ":" + reason,
        "reason="
            + reason
            + " trainA="
            + trainName
            + " trainB="
            + snapshot.trainNames()
            + " blockers="
            + summarizeBlockers(snapshot)
            + " lastBlockerSnapshotAgeMs="
            + snapshotAgeMs(trainName, now)
            + " conflictKey="
            + conflictSummary(snapshot)
            + " directions="
            + directionSummary(snapshot)
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " signal="
            + (current == null ? "-" : current.signal())
            + " skipPhase="
            + (thresholdPassed ? "THRESHOLD_PASSED" : "EARLY")
            + " thresholdPassed="
            + thresholdPassed
            + " fallbackEvidenceChecked="
            + false
            + " fallbackIneligibleReason="
            + fallbackIneligibleReason);
  }

  private String fallbackPrecheckIneligibleReason(
      String skipReason, RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "NO_FALLBACK_EVIDENCE";
    }
    if (skipReason == null || skipReason.isBlank()) {
      return "FALLBACK_ELIGIBILITY_NOT_CHECKED";
    }
    return "fallback-not-yet-checked:" + skipReason;
  }

  private String classifyDeadlockSkip(
      String trainName,
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot,
      Set<String> activeKeys) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "BLOCKER_SNAPSHOT_MISSING";
    }
    boolean hasSingle = false;
    boolean hasUnknown = false;
    boolean hasSwitcherOrHard = false;
    String trainKey = keyOf(trainName);
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (blocker == null || blocker.trainName().isBlank()) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null || blockerKey.equals(trainKey)) {
        continue;
      }
      if (activeKeys != null && !activeKeys.contains(blockerKey)) {
        continue;
      }
      if (blocker.conflictKey().startsWith("single:")) {
        hasSingle = true;
        if (blocker.direction().isEmpty()
            || blocker.direction().get() == CorridorDirection.UNKNOWN) {
          hasUnknown = true;
        }
        Optional<RuntimeDispatchService.DeadlockBlockerInfo> reverse =
            findReverseSingleConflictBlocker(blocker.trainName(), trainKey, blocker.conflictKey());
        if (reverse.isEmpty()) {
          return "NOT_MUTUAL";
        }
        if (!isKnownOpposite(blocker.direction(), reverse.get().direction())) {
          return "UNKNOWN_DIRECTION";
        }
        return "NONE";
      }
      hasSwitcherOrHard = true;
    }
    if (hasSwitcherOrHard) {
      return "SWITCHER_OR_NODE_EDGE_NOT_CONFIRMED";
    }
    if (hasSingle && hasUnknown) {
      return "UNKNOWN_DIRECTION";
    }
    return "NOT_SAME_SINGLE_CONFLICT";
  }

  private boolean tryDestroyDeadlockFallback(
      String trainName,
      TrainSnapshot current,
      Duration progressDuration,
      Set<String> activeKeys,
      Instant now) {
    if (trainName == null
        || trainName.isBlank()
        || current == null
        || progressDuration == null
        || current.signal() != SignalAspect.STOP) {
      return false;
    }
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    boolean destroyed = false;
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (blocker == null || blocker.trainName().isBlank()) {
        continue;
      }
      String blockerKey = keyOf(blocker.trainName());
      if (blockerKey == null
          || blockerKey.equals(keyOf(trainName))
          || activeKeys == null
          || !activeKeys.contains(blockerKey)) {
        continue;
      }
      String resource = fallbackResourceKey(blocker);
      DeadlockFallbackEvidence evidence =
          rememberDeadlockFallbackEvidence(trainName, blocker.trainName(), resource, now);
      if (currentTrainIsStuckDestroyTarget(trainName, current, progressDuration)) {
        if (evaluateDeadlockDestroyFallback(
            evidence,
            "CURRENT_TRAIN_AUTHORITY_FALLBACK",
            blocker.trainName(),
            trainName,
            resource,
            progressDuration,
            now)) {
          destroyed = true;
          break;
        }
      }
      if (evaluateDeadlockDestroyFallback(
          evidence,
          "PLANNER_WAIT_FOR_EDGE_FALLBACK",
          trainName,
          blocker.trainName(),
          resource,
          progressDuration,
          now)) {
        destroyed = true;
        break;
      }
    }
    if (!destroyed
        && tryDestroyFollowerStuckLeaderFallback(
            trainName, current, progressDuration, activeKeys, now)) {
      destroyed = true;
    }
    if (!destroyed
        && dispatchService.recentSmartUnlockNoReleaseTimeout(trainName, deadlockDestroyCooldown)) {
      DeadlockFallbackEvidence evidence =
          rememberDeadlockFallbackEvidence(trainName, trainName, "unlock-no-release-timeout", now);
      destroyed =
          evaluateDeadlockDestroyFallback(
              evidence,
              "UNLOCK_NO_RELEASE_TIMEOUT_FALLBACK",
              trainName,
              trainName,
              "unlock-no-release-timeout",
              progressDuration,
              now);
    }
    return destroyed;
  }

  /**
   * blocker snapshot 缺失时使用 admission 侧“follower 被 stuck leader 阻塞”证据做 destroy 兜底。
   *
   * <p>这里仍复用 {@link #evaluateDeadlockDestroyFallback} 的所有硬安全门：阈值、无进度、STOP、无 active
   * unlock、无乘客、无手动控制和 cooldown。
   */
  private boolean tryDestroyFollowerStuckLeaderFallback(
      String trainName,
      TrainSnapshot current,
      Duration progressDuration,
      Set<String> activeKeys,
      Instant now) {
    if (trainName == null
        || trainName.isBlank()
        || current == null
        || current.signal() != SignalAspect.STOP
        || progressDuration == null
        || progressDuration.compareTo(requiredDestroyThreshold(null)) < 0) {
      return false;
    }
    Optional<RuntimeDispatchService.FollowerStuckLeaderEvidence> evidenceOpt =
        dispatchService.recentFollowerStuckLeaderEvidence(trainName, deadlockDestroyCooldown);
    if (evidenceOpt == null || evidenceOpt.isEmpty()) {
      return false;
    }
    RuntimeDispatchService.FollowerStuckLeaderEvidence runtimeEvidence = evidenceOpt.get();
    if (runtimeEvidence.samples() < 2) {
      return false;
    }
    String leaderKey = keyOf(runtimeEvidence.leaderTrain());
    if (leaderKey == null || activeKeys == null || !activeKeys.contains(leaderKey)) {
      return false;
    }
    DeadlockFallbackEvidence evidence =
        rememberDeadlockFallbackEvidence(
            runtimeEvidence.followerTrain(),
            runtimeEvidence.leaderTrain(),
            runtimeEvidence.resource(),
            now,
            runtimeEvidence.samples());
    return evaluateDeadlockDestroyFallback(
        evidence,
        "STUCK_LEADER_FALLBACK",
        runtimeEvidence.followerTrain(),
        runtimeEvidence.leaderTrain(),
        runtimeEvidence.resource(),
        progressDuration,
        now);
  }

  private boolean currentTrainIsStuckDestroyTarget(
      String trainName, TrainSnapshot current, Duration progressDuration) {
    if (trainName == null
        || trainName.isBlank()
        || current == null
        || current.signal() != SignalAspect.STOP
        || progressDuration == null
        || progressDuration.compareTo(requiredDestroyThreshold(null)) < 0
        || progressDuration.compareTo(deadlockMinStopDuration) < 0) {
      return false;
    }
    RuntimeDispatchService.SmartRecoveryInput input =
        dispatchService.smartRecoveryInput(trainName, progressDuration, current.signal());
    return input.movementTokenState() == SignalComputationTrace.TokenState.INVALID
        || !input.destinationPresent();
  }

  private DeadlockFallbackEvidence rememberDeadlockFallbackEvidence(
      String followerTrain, String blockerTrain, String resource, Instant now) {
    return rememberDeadlockFallbackEvidence(followerTrain, blockerTrain, resource, now, 1);
  }

  private DeadlockFallbackEvidence rememberDeadlockFallbackEvidence(
      String followerTrain,
      String blockerTrain,
      String resource,
      Instant now,
      int observedSamples) {
    String key =
        keyOf(followerTrain)
            + "->"
            + keyOf(blockerTrain)
            + "@"
            + (resource == null || resource.isBlank() ? "-" : resource.trim());
    return deadlockFallbackEvidence.compute(
        key,
        (unused, existing) -> {
          if (existing == null) {
            return new DeadlockFallbackEvidence(
                key, followerTrain.trim(), blockerTrain.trim(), resource, now, observedSamples);
          }
          existing.seenAtLeast(now, observedSamples);
          return existing;
        });
  }

  private boolean evaluateDeadlockDestroyFallback(
      DeadlockFallbackEvidence evidence,
      String requestedEvidenceGroup,
      String followerTrain,
      String targetTrain,
      String resource,
      Duration followerProgressDuration,
      Instant now) {
    String targetKey = keyOf(targetTrain);
    TrainSnapshot targetSnapshot = targetKey == null ? null : snapshots.get(targetKey);
    Duration targetNoProgress =
        targetSnapshot == null
            ? Duration.ZERO
            : Duration.between(targetSnapshot.lastProgressTime(), now);
    RuntimeDispatchService.SmartRecoveryInput targetInput =
        dispatchService.smartRecoveryInput(
            targetTrain,
            targetNoProgress,
            targetSnapshot == null ? SignalAspect.STOP : targetSnapshot.signal());
    RuntimeDispatchService.DeadlockTrainContext targetContext =
        dispatchService.deadlockTrainContext(targetTrain).orElse(null);
    boolean currentTrainAuthorityEvidence =
        "CURRENT_TRAIN_AUTHORITY_FALLBACK".equals(requestedEvidenceGroup);
    boolean requestedStuckLeaderEvidence = "STUCK_LEADER_FALLBACK".equals(requestedEvidenceGroup);
    boolean stuckLeaderEvidence =
        !currentTrainAuthorityEvidence
            && (targetInput.movementTokenState() == SignalComputationTrace.TokenState.INVALID
                || !targetInput.destinationPresent());
    String evidenceGroup =
        currentTrainAuthorityEvidence
                || "UNLOCK_NO_RELEASE_TIMEOUT_FALLBACK".equals(requestedEvidenceGroup)
            ? requestedEvidenceGroup
            : requestedStuckLeaderEvidence || stuckLeaderEvidence
                ? "STUCK_LEADER_FALLBACK"
                : "PLANNER_WAIT_FOR_EDGE_FALLBACK";
    boolean repeatedEvidence =
        evidenceGroup.equals("UNLOCK_NO_RELEASE_TIMEOUT_FALLBACK")
            || (evidence != null && evidence.samples >= 2);
    boolean plannerWaitForEdgePresent =
        evidence != null && evidence.samples >= 2 && !evidence.resource.isBlank();
    boolean unlockNoReleaseTimeoutPresent =
        dispatchService.recentSmartUnlockNoReleaseTimeout(targetTrain, deadlockDestroyCooldown)
            || dispatchService.recentSmartUnlockNoReleaseTimeout(
                followerTrain, deadlockDestroyCooldown);
    boolean activeUnlock =
        dispatchService.hasActiveSmartUnlockReservation(targetTrain)
            || dispatchService.hasActiveSmartUnlockReservation(followerTrain);
    boolean recentlyReleased =
        dispatchService.recentSmartUnlockBlockerRelease(targetTrain, deadlockDestroyCooldown)
            || dispatchService.recentSmartUnlockBlockerRelease(
                followerTrain, deadlockDestroyCooldown);
    RuntimeDispatchService.DeadlockBlockerSnapshot targetBlockerSnapshot =
        recentDeadlockBlockerSnapshot(targetTrain);
    boolean blockerSnapshotPresent = !targetBlockerSnapshot.blockers().isEmpty();
    long cooldownRemainingMs = destroyCooldownRemainingMs(followerTrain, targetTrain, now);
    int passengerCount = targetContext != null && targetContext.hasPassengers() ? 1 : 0;
    boolean manualControl = targetContext != null && targetContext.manualHold();
    FallbackRecoveryAttempt fallbackRecovery =
        trySafeFallbackRecoveryBeforeDestroy(
            targetTrain,
            followerTrain,
            resource,
            evidenceGroup,
            targetInput,
            targetSnapshot,
            targetNoProgress,
            targetContext,
            repeatedEvidence,
            activeUnlock,
            recentlyReleased,
            passengerCount,
            manualControl,
            followerProgressDuration,
            now);
    if (fallbackRecovery.held()) {
      return fallbackRecovery.fixed();
    }
    String ineligibleReason =
        fallbackDestroyIneligibleReason(
            targetSnapshot,
            targetNoProgress,
            targetInput,
            targetContext,
            repeatedEvidence,
            activeUnlock,
            recentlyReleased,
            cooldownRemainingMs,
            passengerCount,
            manualControl,
            evidenceGroup);
    boolean eligible = "NONE".equals(ineligibleReason);
    traceDeadlockDestroyEligibility(
        targetTrain,
        followerTrain,
        targetSnapshot,
        targetNoProgress,
        targetInput,
        activeUnlock,
        passengerCount,
        manualControl,
        blockerSnapshotPresent,
        plannerWaitForEdgePresent,
        (requestedStuckLeaderEvidence || stuckLeaderEvidence) && repeatedEvidence,
        unlockNoReleaseTimeoutPresent,
        resource,
        evidenceGroup,
        eligible,
        ineligibleReason,
        cooldownRemainingMs,
        now);
    if (!eligible) {
      traceFallbackDeadlockDestroySkipped(
          targetTrain,
          followerTrain,
          resource,
          evidenceGroup,
          ineligibleReason,
          followerProgressDuration,
          now);
      return false;
    }
    RuntimeDispatchService.RuntimeTrainResolution resolution = resolveForDestroyTrace(targetTrain);
    SmartDispatcherController.DeadlockDestroyReview review =
        dispatchService.reviewDeadlockDestroyCandidate(
            "fallback:" + (evidence == null ? keyOf(targetTrain) : evidence.key),
            followerTrain,
            targetTrain,
            targetTrain,
            resource,
            false,
            true,
            false,
            false,
            false,
            false,
            resolution,
            false,
            true,
            false,
            "-",
            false,
            false,
            false,
            targetNoProgress,
            requiredDestroyThreshold(null));
    if (!review.allowed()) {
      traceFallbackDeadlockDestroySkipped(
          targetTrain,
          followerTrain,
          resource,
          evidenceGroup,
          review.reason(),
          followerProgressDuration,
          now);
      return false;
    }
    if (!smartDispatcherAllowsHealthMutation(
        targetTrain,
        DispatchAction.EXECUTE_VERIFIED_DEADLOCK_DESTROY,
        "health-deadlock-destroy-fallback")) {
      traceFallbackDeadlockDestroySkipped(
          targetTrain,
          followerTrain,
          resource,
          evidenceGroup,
          "destroy-action-disabled-by-mode",
          followerProgressDuration,
          now);
      return false;
    }
    debugLogger.accept(
        "SMART_HEALTH_EFFECT_EXECUTION action=EXECUTE_VERIFIED_DEADLOCK_DESTROY effect=DESTROY_TRAIN train="
            + targetTrain
            + " source=health-deadlock-destroy-fallback dispatcherAction=false");
    dispatchService.scheduleSurvivorRefreshAfterTrainRemoved(targetTrain, followerTrain);
    boolean destroyed = dispatchService.destroyTrainByName(targetTrain, "health-deadlock-timeout");
    traceSmartDeadlockDestroyExecuted(
        targetTrain,
        followerTrain,
        resource,
        evidenceGroup,
        destroyed ? evidenceGroup : destroyFailureReason(resolution, false),
        targetNoProgress,
        targetInput,
        passengerCount,
        now);
    if (destroyed) {
      rememberDeadlockDestroy(followerTrain, targetTrain, now);
    }
    return destroyed;
  }

  /**
   * 销毁兜底前复用 Smart Dispatcher 的安全恢复动作。
   *
   * <p>该路径只尝试已经由运行时实现安全门的恢复动作，不直接动车、不清外部占用，也不绕过 active unlock / 乘客 /
   * 手动保持等硬约束。若安全候选需要继续观察，则本轮销毁被跳过；只有恢复已应用且核验有效时才上报 fixed。
   */
  private FallbackRecoveryAttempt trySafeFallbackRecoveryBeforeDestroy(
      String targetTrain,
      String followerTrain,
      String resource,
      String evidenceGroup,
      RuntimeDispatchService.SmartRecoveryInput targetInput,
      TrainSnapshot targetSnapshot,
      Duration targetNoProgress,
      RuntimeDispatchService.DeadlockTrainContext targetContext,
      boolean repeatedEvidence,
      boolean activeUnlock,
      boolean recentlyReleased,
      int passengerCount,
      boolean manualControl,
      Duration followerProgressDuration,
      Instant now) {
    if (!repeatedEvidence
        || targetTrain == null
        || targetTrain.isBlank()
        || targetSnapshot == null
        || targetContext == null
        || targetNoProgress == null
        || targetNoProgress.compareTo(requiredDestroyThreshold(null)) < 0
        || targetNoProgress.compareTo(deadlockMinStopDuration) < 0
        || targetSnapshot.signal() != SignalAspect.STOP
        || targetContext.signalAspect() != SignalAspect.STOP
        || targetContext.speedBlocksPerTick() > lowSpeedThresholdBpt
        || targetContext.dwelling()
        || targetContext.departureGateHeld()
        || targetContext.layoverReady()
        || activeUnlock
        || recentlyReleased
        || passengerCount > 0
        || manualControl) {
      return FallbackRecoveryAttempt.none();
    }
    RuntimeDispatchService.SmartRecoveryInput input =
        targetInput == null
            ? RuntimeDispatchService.SmartRecoveryInput.fallback(
                targetTrain, targetNoProgress, targetSnapshot.signal())
            : targetInput;
    debugLogger.accept(
        "SMART_FALLBACK_RECOVERY_ACTION_ORDER train="
            + targetTrain
            + fallbackCounterpartField(evidenceGroup)
            + emptyDash(followerTrain)
            + " evidenceGroup="
            + emptyDash(evidenceGroup)
            + " order=SMART_RELEASE_SELF_OWNED_STALE_RETAIN,SMART_RELEASE_PHYSICAL_EDGE_RETAIN,"
            + "SMART_DRAIN_UNLOCK,SMART_FORWARD_UNLOCK");

    FallbackRecoveryAttempt selfRetainRelease =
        fallbackRecoveryCandidate(
            targetTrain,
            followerTrain,
            resource,
            evidenceGroup,
            safeSmartRecoveryResult(dispatchService.applySmartSelfOwnedStaleRetainRelease(input)),
            followerProgressDuration,
            now);
    if (selfRetainRelease.held()) {
      return selfRetainRelease;
    }

    FallbackRecoveryAttempt drainUnlock =
        fallbackRecoveryCandidate(
            targetTrain,
            followerTrain,
            resource,
            evidenceGroup,
            safeSmartRecoveryResult(dispatchService.applySmartDrainUnlock(input)),
            followerProgressDuration,
            now);
    if (drainUnlock.held()) {
      return drainUnlock;
    }

    return fallbackRecoveryCandidate(
        targetTrain,
        followerTrain,
        resource,
        evidenceGroup,
        safeSmartRecoveryResult(dispatchService.applySmartForwardUnlock(input)),
        followerProgressDuration,
        now);
  }

  private FallbackRecoveryAttempt fallbackRecoveryCandidate(
      String targetTrain,
      String followerTrain,
      String resource,
      String evidenceGroup,
      RuntimeDispatchService.SmartRecoveryActionResult result,
      Duration followerProgressDuration,
      Instant now) {
    if (result == null || !result.candidate()) {
      return FallbackRecoveryAttempt.none();
    }
    String conflictKey = safeConflictKey(resource, result.effectiveness());
    traceRecoveryCandidateSelected(targetTrain, result.decision(), conflictKey);
    debugLogger.accept(
        "SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE train="
            + targetTrain
            + " reason=fallback-safe-recovery-candidate"
            + " recoveryDecision="
            + result.decision()
            + " conflict="
            + conflictKey
            + " evidenceGroup="
            + emptyDash(evidenceGroup));
    if (!shouldHoldForSafeCandidate(targetTrain, conflictKey, result, true)) {
      return FallbackRecoveryAttempt.none();
    }
    traceFallbackDeadlockDestroySkipped(
        targetTrain,
        followerTrain,
        resource,
        evidenceGroup,
        result.reason(),
        followerProgressDuration,
        now);
    return FallbackRecoveryAttempt.held(result.applied() && result.effectiveness().effective());
  }

  private String fallbackDestroyIneligibleReason(
      TrainSnapshot targetSnapshot,
      Duration targetNoProgress,
      RuntimeDispatchService.SmartRecoveryInput targetInput,
      RuntimeDispatchService.DeadlockTrainContext targetContext,
      boolean repeatedEvidence,
      boolean activeUnlock,
      boolean recentlyReleased,
      long cooldownRemainingMs,
      int passengerCount,
      boolean manualControl,
      String evidenceGroup) {
    // 注意：`DESTROY_DISABLED` 这一道**故意放在整条链的最后**，见方法末尾。
    //
    // 若排在第一道，关掉销毁时这条链会就此短路，后面八道（阈值、静止时长、信号状态、
    // 活跃解锁预约、冷却、证据重复性、授权状态…）**一次都不被求值**，每次评估都只报 `DESTROY_DISABLED`，
    // 于是"就算打开销毁，卡住的车到底够不够格"这个问题**只能靠真的打开来回答**，
    // 而那是个不可逆、玩家可见的动作。
    //
    // 放在最后：销毁行为**完全不变**（关闭时依旧永远 eligible=false），
    // 但报出的是**真正拦住它的那一道**，于是可以在不冒任何风险的前提下回答那个问题。
    if (targetSnapshot == null || targetContext == null) {
      return "TARGET_STATE_MISSING";
    }
    if (targetNoProgress.compareTo(requiredDestroyThreshold(null)) < 0) {
      return "BELOW_DESTROY_THRESHOLD";
    }
    if (targetNoProgress.compareTo(deadlockMinStopDuration) < 0) {
      return "PROGRESS_RECENT";
    }
    if (targetSnapshot.signal() != SignalAspect.STOP) {
      return "TARGET_NOT_STOPPED";
    }
    if (targetContext.signalAspect() != SignalAspect.STOP
        || targetContext.speedBlocksPerTick() > lowSpeedThresholdBpt
        || targetContext.dwelling()
        || targetContext.departureGateHeld()
        || targetContext.layoverReady()) {
      return "TARGET_CONTEXT_NOT_ELIGIBLE";
    }
    if (activeUnlock) {
      return "ACTIVE_UNLOCK_RESERVATION";
    }
    if (recentlyReleased) {
      return "RECENT_UNLOCK_RELEASE";
    }
    if (passengerCount > 0) {
      return "PLAYER_PASSENGER_PRESENT";
    }
    if (manualControl) {
      return "MANUAL_CONTROL";
    }
    if (cooldownRemainingMs > 0L) {
      return "DESTROY_COOLDOWN_ACTIVE";
    }
    if (!repeatedEvidence) {
      return "EVIDENCE_NOT_REPEATED";
    }
    if (!"UNLOCK_NO_RELEASE_TIMEOUT_FALLBACK".equals(evidenceGroup)
        && targetInput.movementTokenState() == SignalComputationTrace.TokenState.ACTIVE
        && targetInput.destinationPresent()) {
      return "TARGET_AUTHORITY_ACTIVE";
    }
    // 走到这里说明**其余每一道都过了**：这辆车在销毁开启时就会被销毁。
    // 放在最后判，是为了让关闭状态下的日志能回答"打开会怎样"，而无需真的打开。
    // 语义不变：关闭时永远 eligible=false。
    if (!trainCleanupEnabled
        || deadlockDestroyThreshold == null
        || deadlockDestroyThreshold.isZero()) {
      return "DESTROY_DISABLED";
    }
    return "NONE";
  }

  private void traceDeadlockDestroyEligibility(
      String trainName,
      String blockerTrain,
      TrainSnapshot snapshot,
      Duration noProgressDuration,
      RuntimeDispatchService.SmartRecoveryInput input,
      boolean activeUnlock,
      int passengerCount,
      boolean manualControl,
      boolean blockerSnapshotPresent,
      boolean plannerWaitForEdgePresent,
      boolean followerStuckLeaderEvidencePresent,
      boolean unlockNoReleaseTimeoutPresent,
      String resource,
      String evidenceGroup,
      boolean eligible,
      String ineligibleReason,
      long cooldownRemainingMs,
      Instant now) {
    RuntimeDispatchService.SmartRecoveryInput safeInput =
        input == null
            ? RuntimeDispatchService.SmartRecoveryInput.fallback(
                trainName,
                noProgressDuration,
                snapshot == null ? SignalAspect.STOP : snapshot.signal())
            : input;
    traceHealthEvent(
        "SMART_DEADLOCK_DESTROY_ELIGIBILITY",
        "destroy-eligibility:" + keyOf(trainName) + ":" + evidenceGroup + ":" + eligible,
        "train="
            + emptyDash(trainName)
            + " canonicalTrain="
            + emptyDash(keyOf(trainName))
            + " tick="
            + (now == null ? Instant.now() : now).toEpochMilli()
            + " destroyEnabled="
            + trainCleanupEnabled
            + " thresholdSeconds="
            + requiredDestroyThreshold(null).toSeconds()
            + " cooldownRemainingMs="
            + cooldownRemainingMs
            + " stoppedDurationMs="
            + (noProgressDuration == null ? 0L : noProgressDuration.toMillis())
            + " noProgressDurationMs="
            + (noProgressDuration == null ? 0L : noProgressDuration.toMillis())
            + " signal="
            + (snapshot == null ? "-" : snapshot.signal())
            + " finalPhysicalAspect="
            + (snapshot == null ? "-" : snapshot.signal())
            + " movementTokenState="
            + safeInput.movementTokenState()
            + " destinationPresent="
            + safeInput.destinationPresent()
            + " activeUnlockReservation="
            + activeUnlock
            + " playerPassengerCount="
            + passengerCount
            + " manualControl="
            + manualControl
            + " blockerSnapshotPresent="
            + blockerSnapshotPresent
            + " plannerWaitForEdgePresent="
            + plannerWaitForEdgePresent
            + " followerStuckLeaderEvidencePresent="
            + followerStuckLeaderEvidencePresent
            + " unlockNoReleaseTimeoutPresent="
            + unlockNoReleaseTimeoutPresent
            + fallbackCounterpartField(evidenceGroup)
            + emptyDash(blockerTrain)
            + " resource="
            + emptyDash(resource)
            + " conflictKey="
            + emptyDash(resource)
            + " evidenceGroup="
            + emptyDash(evidenceGroup)
            + " eligible="
            + eligible
            + " ineligibleReason="
            + emptyDash(ineligibleReason));
  }

  /**
   * 按恢复目标与等待边的关系标注另一辆车，不能把恢复目标一律当作被后车等待的 leader。
   *
   * <p>当前车授权失效时，另一辆车仍是其 blocker；恢复 blocker 时，另一辆车只提供等待证据。 unlock 自身超时没有外部阻塞关系，因此使用中性字段。
   */
  private static String fallbackCounterpartField(String evidenceGroup) {
    return switch (evidenceGroup) {
      case "STUCK_LEADER_FALLBACK" -> " evidenceFollower=";
      case "PLANNER_WAIT_FOR_EDGE_FALLBACK" -> " evidenceWaiter=";
      case "CURRENT_TRAIN_AUTHORITY_FALLBACK" -> " blockerTrain=";
      default -> " counterpartTrain=";
    };
  }

  private void traceFallbackDeadlockDestroySkipped(
      String trainName,
      String blockerTrain,
      String resource,
      String evidenceGroup,
      String reason,
      Duration progressDuration,
      Instant now) {
    boolean thresholdPassed =
        progressDuration != null && progressDuration.compareTo(requiredDestroyThreshold(null)) >= 0;
    traceHealthEvent(
        "DEADLOCK_DESTROY_SKIPPED",
        "fallback-destroy-skip:" + keyOf(trainName) + ":" + reason,
        "reason="
            + emptyDash(reason)
            + " trainA="
            + emptyDash(trainName)
            + " trainB="
            + emptyDash(blockerTrain)
            + " blockers=["
            + emptyDash(resource)
            + "] lastBlockerSnapshotAgeMs="
            + snapshotAgeMs(trainName, now)
            + " conflictKey="
            + emptyDash(resource)
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " skipPhase="
            + (thresholdPassed ? "THRESHOLD_PASSED" : "EARLY")
            + " thresholdPassed="
            + thresholdPassed
            + " fallbackEvidenceChecked=true"
            + " fallbackIneligibleReason="
            + emptyDash(reason)
            + " evidenceGroup="
            + emptyDash(evidenceGroup));
  }

  private void traceSmartDeadlockDestroyExecuted(
      String trainName,
      String blockerTrain,
      String resource,
      String evidenceGroup,
      String reason,
      Duration noProgressDuration,
      RuntimeDispatchService.SmartRecoveryInput input,
      int passengerCount,
      Instant now) {
    RuntimeDispatchService.SmartRecoveryInput safeInput =
        input == null
            ? RuntimeDispatchService.SmartRecoveryInput.fallback(
                trainName, noProgressDuration, SignalAspect.STOP)
            : input;
    traceHealthEvent(
        "SMART_DEADLOCK_DESTROY_EXECUTED",
        "smart-destroy-executed:" + keyOf(trainName) + ":" + evidenceGroup,
        "train="
            + emptyDash(trainName)
            + " canonicalTrain="
            + emptyDash(keyOf(trainName))
            + " reason="
            + emptyDash(reason)
            + " evidenceGroup="
            + emptyDash(evidenceGroup)
            + " stoppedDurationMs="
            + (noProgressDuration == null ? 0L : noProgressDuration.toMillis())
            + " noProgressDurationMs="
            + (noProgressDuration == null ? 0L : noProgressDuration.toMillis())
            + fallbackCounterpartField(evidenceGroup)
            + emptyDash(blockerTrain)
            + " resource="
            + emptyDash(resource)
            + " movementTokenState="
            + safeInput.movementTokenState()
            + " destinationPresent="
            + safeInput.destinationPresent()
            + " activeUnlockReservation=false"
            + " playerPassengerCount="
            + passengerCount
            + " carsDestroyed=-1"
            + " cleanupClaims=deferred"
            + " cooldownUntil="
            + (now == null ? Instant.now() : now).plus(deadlockDestroyCooldown).toEpochMilli());
  }

  private String fallbackResourceKey(RuntimeDispatchService.DeadlockBlockerInfo blocker) {
    if (blocker == null) {
      return "-";
    }
    String resource = liveCycleResourceKey(blocker);
    if (resource == null || resource.isBlank() || "-".equals(resource)) {
      resource = blocker.resourceKey();
    }
    return resource == null || resource.isBlank() ? "-" : resource;
  }

  private long destroyCooldownRemainingMs(String firstTrain, String secondTrain, Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    long globalRemaining =
        Math.max(
            0L,
            Duration.between(effectiveNow, lastDeadlockDestroyAt.plus(deadlockDestroyCooldown))
                .toMillis());
    String pairKey = pairKey(safeTrainKey(firstTrain), safeTrainKey(secondTrain));
    Instant pairLast = deadlockPairLastDestroyAt.get(pairKey);
    long pairRemaining =
        pairLast == null
            ? 0L
            : Math.max(
                0L,
                Duration.between(effectiveNow, pairLast.plus(deadlockDestroyCooldown)).toMillis());
    return Math.max(globalRemaining, pairRemaining);
  }

  private void rememberDeadlockDestroy(String firstTrain, String secondTrain, Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    lastDeadlockDestroyAt = effectiveNow;
    deadlockPairLastDestroyAt.put(
        pairKey(safeTrainKey(firstTrain), safeTrainKey(secondTrain)), effectiveNow);
  }

  private static String safeTrainKey(String trainName) {
    String key = keyOf(trainName);
    return key == null ? "-" : key;
  }

  private void pruneDeadlockFallbackEvidence(Set<String> activeKeys, Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    deadlockFallbackEvidence
        .values()
        .removeIf(
            evidence -> {
              if (evidence == null) {
                return true;
              }
              String followerKey = keyOf(evidence.followerTrain);
              String blockerKey = keyOf(evidence.blockerTrain);
              if (activeKeys == null
                  || followerKey == null
                  || blockerKey == null
                  || !activeKeys.contains(followerKey)
                  || !activeKeys.contains(blockerKey)) {
                return true;
              }
              return effectiveNow.isAfter(evidence.lastSeenAt.plus(deadlockEpisodeGrace));
            });
  }

  private void traceSwitcherOccupantBlockingMany(Set<String> activeTrains, Instant now) {
    if (activeTrains == null || activeTrains.isEmpty()) {
      return;
    }
    Map<String, Set<String>> blockedByBlocker = new LinkedHashMap<>();
    Map<String, Set<String>> resourcesByBlocker = new LinkedHashMap<>();
    for (String blockedTrain : activeTrains) {
      RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
          recentDeadlockBlockerSnapshot(blockedTrain);
      for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker == null || blocker.trainName().isBlank()) {
          continue;
        }
        String blockerKey = keyOf(blocker.trainName());
        if (blockerKey == null || blockerKey.equals(keyOf(blockedTrain))) {
          continue;
        }
        blockedByBlocker
            .computeIfAbsent(blocker.trainName(), unused -> new LinkedHashSet<>())
            .add(blockedTrain);
        if (!blocker.conflictKey().isBlank()) {
          resourcesByBlocker
              .computeIfAbsent(blocker.trainName(), unused -> new LinkedHashSet<>())
              .add(blocker.conflictKey());
        }
      }
    }
    for (Map.Entry<String, Set<String>> entry : blockedByBlocker.entrySet()) {
      if (entry.getValue().size() < 2) {
        continue;
      }
      String blockerTrain = entry.getKey();
      TrainSnapshot blockerSnapshot = snapshots.get(keyOf(blockerTrain));
      if (blockerSnapshot == null
          || blockerSnapshot.signal() != SignalAspect.STOP
          || blockerSnapshot.speedBpt() > lowSpeedThresholdBpt) {
        continue;
      }
      Set<String> resources = resourcesByBlocker.getOrDefault(blockerTrain, Set.of());
      Set<String> switcherKeys = switcherKeys(resources);
      Set<String> ownClaims =
          Optional.ofNullable(dispatchService.currentConflictClaimKeys(blockerTrain))
              .orElse(Set.of());
      boolean protectedSwitcherClaimPresent =
          ownClaims.stream().anyMatch(key -> key != null && key.startsWith("switcher:"));
      traceHealthEvent(
          "SWITCHER_OCCUPANT_BLOCKING_MANY",
          "occupant-many:" + keyOf(blockerTrain) + ":" + entry.getValue().size(),
          "blockerTrain="
              + blockerTrain
              + " blockedTrains="
              + entry.getValue()
              + " blockedCount="
              + entry.getValue().size()
              + " commonResources="
              + resources
              + " containsSwitcherConflict="
              + !switcherKeys.isEmpty()
              + " switcherConflictKeys="
              + switcherKeys
              + " blockerStoppedDurationMs="
              + Duration.between(blockerSnapshot.lastProgressTime(), now).toMillis()
              + " blockerCurrentNode=-"
              + " blockerLastPassedGraphNode="
              + (blockerSnapshot.lastPassedGraphNodeId() == null
                  ? "-"
                  : blockerSnapshot.lastPassedGraphNodeId())
              + " blockerRouteIndex="
              + blockerSnapshot.progressIndex()
              + " protectedSwitcherClaimPresent="
              + protectedSwitcherClaimPresent
              + " whyNotDestroyed="
              + (!switcherKeys.isEmpty() ? "WEAK_DIAGNOSTIC_ONLY" : "NOT_MUTUAL")
              + " whetherEpisodeExists="
              + hasEpisodeFor(blockerTrain)
              + " remainingMsUntilWeakDestroy="
              + -1L);
    }
  }

  private String episodeType(DeadlockObservation observation) {
    if (observation == null) {
      return "UNKNOWN";
    }
    if (!observation.weak() && observation.conflictKey().startsWith("single:")) {
      return "CONFIRMED_SINGLE";
    }
    if (!observation.weak() && "live".equals(observation.blockerSnapshotSource())) {
      return "LIVE_MUTUAL_BLOCKER_CYCLE";
    }
    if (observation.blockerResources().stream().anyMatch(key -> key.startsWith("switcher:"))) {
      return "SWITCHER_OR_NODE_EDGE_WEAK";
    }
    return observation.weak() ? "WEAK_MUTUAL" : "UNKNOWN";
  }

  private String directionsSummary(DeadlockObservation observation) {
    if (observation == null) {
      return "[]";
    }
    List<String> values = new ArrayList<>();
    RuntimeDispatchService.DeadlockBlockerSnapshot first =
        recentDeadlockBlockerSnapshot(observation.trainA());
    RuntimeDispatchService.DeadlockBlockerSnapshot second =
        recentDeadlockBlockerSnapshot(observation.trainB());
    values.addAll(directionValues(first));
    values.addAll(directionValues(second));
    return values.toString();
  }

  private String directionSummary(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    return directionValues(snapshot).toString();
  }

  private List<String> directionValues(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      values.add(
          blocker.trainName()
              + "@"
              + (blocker.conflictKey().isBlank() ? "-" : blocker.conflictKey())
              + "="
              + blocker.direction().map(Enum::name).orElse("UNKNOWN"));
    }
    return values;
  }

  private String summarizeBlockers(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "[]";
    }
    List<String> values = new ArrayList<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      values.add(
          blocker.trainName()
              + "@"
              + (blocker.conflictKey().isBlank() ? "-" : blocker.conflictKey()));
    }
    return values.toString();
  }

  private String conflictSummary(RuntimeDispatchService.DeadlockBlockerSnapshot snapshot) {
    if (snapshot == null || snapshot.blockers().isEmpty()) {
      return "-";
    }
    Set<String> conflicts = new LinkedHashSet<>();
    for (RuntimeDispatchService.DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (!blocker.conflictKey().isBlank()) {
        conflicts.add(blocker.conflictKey());
      }
    }
    return conflicts.isEmpty() ? "-" : conflicts.toString();
  }

  private long snapshotAgeMs(String trainName, Instant now) {
    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        recentDeadlockBlockerSnapshot(trainName);
    if (snapshot == null || snapshot.sampledAt().equals(Instant.EPOCH) || now == null) {
      return -1L;
    }
    return Math.max(0L, Duration.between(snapshot.sampledAt(), now).toMillis());
  }

  private static Set<String> switcherKeys(Set<String> resources) {
    if (resources == null || resources.isEmpty()) {
      return Set.of();
    }
    Set<String> switchers = new LinkedHashSet<>();
    for (String resource : resources) {
      if (resource != null && resource.startsWith("switcher:")) {
        switchers.add(resource);
      }
    }
    return StableCollections.copyInInsertionOrder(switchers);
  }

  private boolean hasEpisodeFor(String trainName) {
    String key = keyOf(trainName);
    if (key == null) {
      return false;
    }
    return deadlockEpisodes.values().stream()
        .anyMatch(
            episode -> key.equals(keyOf(episode.trainA)) || key.equals(keyOf(episode.trainB)));
  }

  private TrainSnapshot emptySnapshot(Instant now) {
    Instant effectiveNow = now == null ? Instant.now() : now;
    return new TrainSnapshot(
        0, null, SignalAspect.STOP, 0.0, effectiveNow, effectiveNow, effectiveNow);
  }

  private void traceHealthEvent(String eventName, String key, String message) {
    String event = eventName == null || eventName.isBlank() ? "HEALTH_TRACE" : eventName;
    String traceKey = key == null || key.isBlank() ? event : key;
    String fingerprint = event + "|" + message;
    Instant now = Instant.now();
    String previous = traceFingerprints.get(traceKey);
    Instant previousAt = traceLastAt.get(traceKey);
    if (fingerprint.equals(previous)
        && previousAt != null
        && Duration.between(previousAt, now).compareTo(Duration.ofSeconds(30)) < 0) {
      return;
    }
    traceFingerprints.put(traceKey, fingerprint);
    traceLastAt.put(traceKey, now);
    debugLogger.accept(event + ": " + message);
  }

  private SmartDispatcherMode smartDispatcherMode() {
    SmartDispatcherMode mode = dispatchService.smartDispatcherMode();
    return mode == null ? SmartDispatcherMode.OBSERVE_ONLY : mode;
  }

  private void traceSmartDispatcherMode(String trainName, SmartDispatcherMode mode, String source) {
    debugLogger.accept(
        "SMART_DISPATCH_MODE train=" + trainName + " mode=" + mode + " source=" + source);
  }

  private void traceSmartDispatcherActionSuppressed(
      String trainName,
      SmartDispatcherMode mode,
      String action,
      DispatchEffectClass effectClass,
      String reason) {
    debugLogger.accept(
        "SMART_DISPATCH_ACTION_OBSERVED train="
            + trainName
            + " mode="
            + mode
            + " action="
            + action
            + " effectClass="
            + effectClass);
    debugLogger.accept(
        "SMART_DISPATCH_EFFECT_CLASS train="
            + trainName
            + " action="
            + action
            + " effectClass="
            + effectClass);
    debugLogger.accept(
        "SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " action="
            + action
            + " effectClass="
            + effectClass
            + " reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
    debugLogger.accept(
        "SMART_ACTION_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " action="
            + action
            + " effectClass="
            + effectClass
            + " reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
  }

  /**
   * HealthMonitor 进入 Smart effect chain 的唯一入口。
   *
   * <p>健康监控只能请求已登记的 {@link DispatchAction}，不能自带字符串动作名或 effect class。具体列车操作仍在前方的 deadlock
   * review、refresh 或 hard-stop 分支中执行；本入口只在 ENFORCE 下授权该已命名效果。
   */
  private boolean smartDispatcherAllowsHealthMutation(
      String trainName, DispatchAction action, String source) {
    DispatchAction safeAction = action == null ? DispatchAction.NONE : action;
    DispatchEffectClass effectClass = safeAction.effectClass();
    SmartDispatcherMode mode = smartDispatcherMode();
    traceSmartDispatcherMode(trainName, mode, source);
    boolean allowed = SmartDispatcherModeGate.allows(mode, safeAction);
    if (allowed) {
      debugLogger.accept(
          "SMART_ACTION_ALLOWED_BY_EFFECT_GATE train="
              + trainName
              + " mode="
              + mode
              + " action="
              + safeAction
              + " effectClass="
              + effectClass
              + " source="
              + source);
      debugLogger.accept(
          "SMART_RECOVERY_ALLOWED_BY_EFFECT_GATE train="
              + trainName
              + " recoveryDecision="
              + safeAction
              + " effectClass="
              + effectClass
              + " source="
              + source
              + " mode="
              + mode);
      debugLogger.accept(
          "SMART_RECOVERY_PATH_SELECTED train="
              + trainName
              + " action="
              + safeAction
              + " effectClass="
              + effectClass
              + " source="
              + source);
      return true;
    }
    if (mode == SmartDispatcherMode.OFF) {
      debugLogger.accept(
          "SMART_DISPATCH_DISABLED train=" + trainName + " source=" + source + " mode=" + mode);
    }
    debugLogger.accept(
        "SMART_HEALTH_EFFECT_SUPPRESSED train="
            + trainName
            + " action="
            + safeAction
            + " mode="
            + mode
            + " effectClass="
            + effectClass
            + " source="
            + source);
    traceSmartDispatcherActionSuppressed(
        trainName,
        mode,
        safeAction.name(),
        effectClass,
        mode == SmartDispatcherMode.OFF ? "smart-dispatcher-off" : "observe-only-no-side-effects");
    debugLogger.accept(
        "SMART_RECOVERY_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " recoveryDecision="
            + safeAction
            + " effectClass="
            + effectClass
            + " source="
            + source);
    return false;
  }

  private void traceSmartRecoveryDecision(String trainName, String event, String reason) {
    debugLogger.accept(
        event
            + " train="
            + trainName
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
    debugLogger.accept(
        "SMART_RECOVERY_DECISION train="
            + trainName
            + " decision="
            + event
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
  }

  private void traceRecoveryCandidateSelected(String trainName, String action, String conflictKey) {
    debugLogger.accept(
        "SMART_RECOVERY_CANDIDATE_SELECTED train="
            + trainName
            + " action="
            + (action == null || action.isBlank() ? "-" : action)
            + " conflictKey="
            + (conflictKey == null || conflictKey.isBlank() ? "-" : conflictKey));
  }

  private RuntimeDispatchService.SmartRecoveryActionResult safeSmartRecoveryResult(
      RuntimeDispatchService.SmartRecoveryActionResult result) {
    return result != null
        ? result
        : RuntimeDispatchService.SmartRecoveryActionResult.skipped("smart-action-not-available");
  }

  private DirectionDestroyAudit directionDestroyAudit(
      DeadlockEpisode episode,
      RuntimeDispatchService.SmartRecoveryInput input,
      RuntimeDispatchService.SmartRecoveryActionResult... recoveryResults) {
    Optional<String> plannerReason = recentPlannerDirectionAuditReason(episode);
    if (plannerReason.isPresent()) {
      return DirectionDestroyAudit.required(plannerReason.get());
    }
    if (input != null && directionDestroyAuditReason(input.primaryReason())) {
      return DirectionDestroyAudit.required(input.primaryReason());
    }
    if (recoveryResults != null) {
      for (RuntimeDispatchService.SmartRecoveryActionResult result : recoveryResults) {
        if (result != null && directionDestroyAuditReason(result.reason())) {
          return DirectionDestroyAudit.required(result.reason());
        }
      }
    }
    return DirectionDestroyAudit.none();
  }

  private Optional<String> recentPlannerDirectionAuditReason(DeadlockEpisode episode) {
    if (episode == null) {
      return Optional.empty();
    }
    for (String trainName : List.of(episode.stableLeader, episode.trainA, episode.trainB)) {
      if (trainName == null || trainName.isBlank()) {
        continue;
      }
      try {
        Optional<String> candidate =
            dispatchService.recentDirectionAuditReason(trainName, blockerSnapshotMaxAge);
        if (candidate != null
            && candidate.isPresent()
            && directionDestroyAuditReason(candidate.get())) {
          return candidate;
        }
      } catch (RuntimeException ignored) {
        // 运行时 mock 或启动早期状态不可用时忽略，destroy review 会继续 fail closed。
      }
    }
    return Optional.empty();
  }

  private static boolean directionDestroyAuditReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return false;
    }
    String normalized = reason.trim().toUpperCase(Locale.ROOT);
    return normalized.equals("INSUFFICIENT_DIRECTION_EVIDENCE")
        || normalized.equals("NEED_DIRECTION_AUDIT")
        || normalized.equals("WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM")
        || normalized.contains("SINGLE-CONFLICT-DIRECTION-UNKNOWN")
        || normalized.contains("UNKNOWN-DIRECTION");
  }

  private boolean triggerDirectionReaudit(
      DeadlockEpisode episode, DirectionDestroyAudit audit, Instant now) {
    if (episode == null || audit == null || !audit.required()) {
      return false;
    }
    debugLogger.accept(
        "SMART_DIRECTION_REAUDIT_REQUESTED episode="
            + episode.key
            + " train="
            + episode.stableLeader
            + " peer="
            + episode.survivor()
            + " conflict="
            + episode.conflictKey
            + " reason="
            + audit.reason()
            + " lastResortEligible="
            + directionAuditLastResortDestroyAllowed(episode, now));
    dispatchService.refreshSignalByName(episode.trainA);
    dispatchService.refreshSignalByName(episode.trainB);
    return true;
  }

  private boolean directionAuditLastResortDestroyAllowed(DeadlockEpisode episode, Instant now) {
    if (episode == null || now == null || !allBlockersLiveHard(episode)) {
      return false;
    }
    Duration threshold = directionAuditLastResortThreshold(episode);
    return Duration.between(episode.firstSeenAt, now).compareTo(threshold) >= 0;
  }

  private Duration directionAuditLastResortThreshold(DeadlockEpisode episode) {
    Duration base = requiredDestroyThreshold(episode);
    if (base.isZero() || base.isNegative()) {
      return base;
    }
    return base.multipliedBy(DIRECTION_AUDIT_LAST_RESORT_THRESHOLD_MULTIPLIER);
  }

  /**
   * 互卡恢复链要不要停在这个动作上。
   *
   * <p>"链要不要继续往下走"按恢复语境判（{@code destroyContext=false}）：未经测量的有效要计数、连着两次没解开就放行。若按销毁语境判，
   * 假定有效一律当真：一辆车每隔几秒释放一次车后的保护占用、下一拍又占回，链会永远停在第一步，每次还报"已修复"， 真正能解开的排队位让位一次也轮不到。销毁的去留另由 {@link
   * #appliedEffective} 在调用方挡住，不随这里放宽。
   */
  private boolean holdsMutualChain(
      String trainName,
      String conflictKey,
      RuntimeDispatchService.SmartRecoveryActionResult result) {
    return shouldHoldForSafeCandidate(trainName, conflictKey, result, false);
  }

  /** 动作落地且有效（含未经测量的假定有效）：本轮不销毁，这是互卡链的销毁口径。 */
  private static boolean appliedEffective(RuntimeDispatchService.SmartRecoveryActionResult result) {
    return result != null && result.applied() && result.effectiveness().effective();
  }

  /** 动作落地且有效是测量出来的：只有这时才报"已修复"，假定有效不算。 */
  private static boolean measuredEffective(
      RuntimeDispatchService.SmartRecoveryActionResult result) {
    return appliedEffective(result)
        && !RuntimeDispatchService.SmartRecoveryEffectiveness.ASSUMED_EFFECTIVE_REASON.equals(
            result.effectiveness().reason());
  }

  private boolean shouldHoldForSafeCandidate(
      String trainName,
      String conflictKey,
      RuntimeDispatchService.SmartRecoveryActionResult result,
      boolean destroyContext) {
    if (result == null || !result.candidate()) {
      return false;
    }
    if (!result.applied()) {
      // 候选但**没落地**也必须计数。若这里无条件 return true，一个永远候选、
      // 永远落不了地的动作会**永久卡住整条恢复链**，后面的动作永远轮不到。
      // 无效分支（下面）本来就有计数放行机制，“没落地”比“落地了但无效”更弱，
      // 没有理由反而享受无限期的优先权。
      return countSafeCandidateFailure(
          trainName, conflictKey, result, destroyContext, "not-applied");
    }
    RuntimeDispatchService.SmartRecoveryEffectiveness effectiveness = result.effectiveness();
    String resolvedConflict = safeConflictKey(conflictKey, effectiveness);
    String key = safeCandidateFailureKey(trainName, result.decision(), resolvedConflict);
    // "有效"必须是**测量**出来的。5 参构造器的 legacy 默认只是"applied ⇒ effective"的假定：
    // 同一辆车可能反复释放同一组资源（下一 tick 就被重新拿回），若每次都在这里被当成有效、
    // 把失败计数清零，链会永远停在第一步，割排队位那一步永远轮不到。未测量 ≠ 有效，按 fail-closed 计数。
    //
    // **但这个判据同时服务两件性质相反的事，必须按上下文分开：**
    //   destroyContext=false —— 恢复链要不要继续往下走。这里"假定有效"必须计数，否则链停在第一步。
    //   destroyContext=true  —— 要不要**跳过销毁**。这里必须继续挡住：本项目的既定目标是
    //                           解锁疏通而不是超时删车（deadlock-destroy / stuck-cleanup 默认关闭）。
    // 若不分开，同一个改动会把销毁门槛从"永远够不到"降成"两次尝试"——链修好了，
    // 却顺手改了安全姿态，而那是两个独立的决定。
    boolean assumedEffectiveOnly =
        RuntimeDispatchService.SmartRecoveryEffectiveness.ASSUMED_EFFECTIVE_REASON.equals(
            effectiveness.reason());
    if (effectiveness.effective() && (destroyContext || !assumedEffectiveOnly)) {
      safeCandidateFailureCounts.remove(key);
      safeCandidateFailureReported.remove(key);
      if (destroyContext) {
        debugLogger.accept(
            "SMART_DESTROY_NOT_REACHED_SAFE_UNLOCK_EFFECTIVE train="
                + trainName
                + " action="
                + result.decision()
                + " conflictKey="
                + resolvedConflict);
      }
      return true;
    }
    return countSafeCandidateFailure(
        trainName,
        conflictKey,
        result,
        destroyContext,
        assumedEffectiveOnly ? "assumed-effective" : "ineffective");
  }

  /**
   * 记一次“这个候选没能解决问题”，并决定还要不要继续抢着恢复链。
   *
   * <p>两种失败走同一个计数器：{@code not-applied}（候选但根本没落地）与 {@code
   * ineffective}（落地了但车没动）。两者都必须计数：若前者无条件保持优先权，一个永远候选、永远落不了地的动作能把排在它后面的动作永久饿死。
   */
  private boolean countSafeCandidateFailure(
      String trainName,
      String conflictKey,
      RuntimeDispatchService.SmartRecoveryActionResult result,
      boolean destroyContext,
      String failureKind) {
    RuntimeDispatchService.SmartRecoveryEffectiveness effectiveness = result.effectiveness();
    String resolvedConflict = safeConflictKey(conflictKey, effectiveness);
    String key = safeCandidateFailureKey(trainName, result.decision(), resolvedConflict);
    int count =
        safeCandidateFailureCounts.merge(
            key,
            1,
            (oldValue, increment) ->
                Math.min(SAFE_CANDIDATE_FAILURE_THRESHOLD, oldValue + increment));
    // 这条已进诊断必留名单（它是"链为什么没停在第一步"的唯一证据）。计数在阈值处饱和，
    // 之后每次尝试都会再走到这里；按 (train, action, conflict, kind, count) 去重，
    // 同一辆车只在计数或失败形态变化时再印，体量不随恢复尝试放大。
    String signature = key + "|" + failureKind + "|" + count;
    if (!signature.equals(safeCandidateFailureReported.put(key, signature))) {
      debugLogger.accept(
          "SMART_RECOVERY_SAFE_CANDIDATE_FAILED_COUNT train="
              + trainName
              + " action="
              + result.decision()
              + " conflictKey="
              + resolvedConflict
              + " failureKind="
              + failureKind
              + " count="
              + count);
    }
    if (destroyContext) {
      debugLogger.accept(
          "SMART_DESTROY_NOT_REACHED_SAFE_UNLOCK_INEFFECTIVE_CONTINUING train="
              + trainName
              + " action="
              + result.decision()
              + " conflictKey="
              + resolvedConflict
              + " count="
              + count);
    }
    if (count < SAFE_CANDIDATE_FAILURE_THRESHOLD) {
      return true;
    }
    if (destroyContext) {
      debugLogger.accept(
          "SMART_DESTROY_SAFE_ALTERNATIVE_BYPASSED_AFTER_INEFFECTIVE train="
              + trainName
              + " conflictKey="
              + resolvedConflict
              + " failedAction="
              + result.decision()
              + " count="
              + count);
    }
    return false;
  }

  private static String safeConflictKey(
      String conflictKey, RuntimeDispatchService.SmartRecoveryEffectiveness effectiveness) {
    if (conflictKey != null && !conflictKey.isBlank() && !"-".equals(conflictKey)) {
      return conflictKey.trim();
    }
    if (effectiveness != null
        && effectiveness.conflictKey() != null
        && !effectiveness.conflictKey().isBlank()) {
      return effectiveness.conflictKey();
    }
    return "-";
  }

  private static String safeCandidateFailureKey(
      String trainName, String action, String conflictKey) {
    String trainKey = keyOf(trainName);
    return (trainKey == null ? "-" : trainKey)
        + "|"
        + (action == null || action.isBlank() ? "-" : action.trim())
        + "|"
        + (conflictKey == null || conflictKey.isBlank() ? "-" : conflictKey.trim());
  }

  private RuntimeDispatchService.SmartRecoveryInput traceSmartProgressStuckBridge(
      String trainName, SignalAspect currentSignal, Duration progressDuration, String source) {
    RuntimeDispatchService.SmartRecoveryInput input =
        dispatchService.smartRecoveryInput(trainName, progressDuration, currentSignal);
    debugLogger.accept(
        "SMART_STUCK_TRAIN_DETECTED train="
            + input.train()
            + " stuckDurationSeconds="
            + input.stuckDurationSeconds()
            + " signal="
            + input.signal()
            + " movementInhibited="
            + input.movementInhibited()
            + " movementTokenState="
            + input.movementTokenState()
            + " destinationPresent="
            + input.destinationPresent()
            + " blockerCount="
            + input.blockerCount()
            + " hardBlockers="
            + input.hardBlockers()
            + " currentNode="
            + nodeText(input.currentNode())
            + " nextNode="
            + nodeText(input.nextNode())
            + " routeId="
            + input.routeId()
            + " currentIndex="
            + input.currentIndex()
            + " lastPassedGraphNode="
            + input.lastPassedGraphNode()
            + " insideSingleRegion="
            + input.insideSingleRegion()
            + " insideSwitcherRegion="
            + input.insideSwitcherRegion()
            + " source="
            + source);
    debugLogger.accept(
        "SMART_RECOVERY_INPUT train="
            + input.train()
            + " stuckDurationSeconds="
            + input.stuckDurationSeconds()
            + " signal="
            + input.signal()
            + " movementInhibited="
            + input.movementInhibited()
            + " movementTokenState="
            + input.movementTokenState()
            + " destinationPresent="
            + input.destinationPresent()
            + " blockerCount="
            + input.blockerCount()
            + " hardBlockers="
            + input.hardBlockers()
            + " currentNode="
            + nodeText(input.currentNode())
            + " nextNode="
            + nodeText(input.nextNode())
            + " routeId="
            + input.routeId()
            + " currentIndex="
            + input.currentIndex()
            + " lastPassedGraphNode="
            + input.lastPassedGraphNode()
            + " insideSingleRegion="
            + input.insideSingleRegion()
            + " insideSwitcherRegion="
            + input.insideSwitcherRegion()
            + " recoveryDecision=pending"
            + " primaryReason="
            + input.primaryReason()
            + " source="
            + source);
    return input;
  }

  private static String nodeText(NodeId node) {
    return node == null ? "-" : node.value();
  }

  /**
   * 互卡解锁：优先轻量恢复，超过销毁阈值且多轮恢复无效时销毁 pair leader。
   *
   * <p>为避免两车同时执行恢复动作导致抖动，仅由 pair key 较小的一侧执行。
   */
  private boolean tryFixMutualDeadlockEpisode(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      RecoveryState recovery,
      Instant now) {
    if (episode == null || observation == null || recovery == null || now == null) {
      return false;
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant pairLast = deadlockPairLastAttemptAt.get(pairKey);
    if (!canAttempt(now, pairLast) || !canAttempt(now, recovery.lastDeadlockAttemptAt)) {
      return false;
    }
    SmartDispatcherMode mode = smartDispatcherMode();
    traceSmartDispatcherMode(episode.trainA, mode, "health-deadlock");
    if (!smartDispatcherAllowsHealthMutation(
        episode.trainA, DispatchAction.SMART_HEALTH_SIGNAL_RECOVERY, "health-deadlock")) {
      traceDeadlockDestroySkipped(
          episode, observation, progressDuration, now, "smart-dispatcher-" + mode.name());
      return false;
    }
    boolean liveBlockerCycle = "live".equals(observation.blockerSnapshotSource());
    if (liveBlockerCycle) {
      debugLogger.accept(
          "SMART_RECOVERY_EVALUATION_ENTER train="
              + episode.stableLeader
              + " reason=live-blocker-cycle blockerSnapshotSource="
              + observation.blockerSnapshotSource());
    }

    if (!liveBlockerCycle && episode.refreshCount <= 0) {
      traceSmartRecoveryDecision(episode.trainA, "SMART_HOLD_AND_BLOCK_FOLLOWERS", "refresh-pair");
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=refresh-pair): train="
              + episode.trainA
              + " blocker="
              + episode.trainB
              + " conflict="
              + episode.conflictKey);
      dispatchService.refreshSignalByName(episode.trainA);
      dispatchService.refreshSignalByName(episode.trainB);
      debugLogger.accept(
          "SMART_FOLLOWERS_BLOCKED_BEHIND_STUCK_TRAIN train="
              + episode.trainA
              + " blocker="
              + episode.trainB
              + " conflict="
              + episode.conflictKey);
      episode.refreshCount++;
      recovery.lastDeadlockAttemptAt = now;
      recovery.deadlockStage = Math.max(recovery.deadlockStage, 1);
      deadlockPairLastAttemptAt.put(pairKey, now);
      return false;
    }

    if (!liveBlockerCycle && episode.reissueCount <= 0) {
      traceSmartRecoveryDecision(episode.trainA, "SMART_HEALTH_SIGNAL_RECOVERY", "hard-stop-pair");
      debugLogger.accept(
          "TrainHealthMonitor 解锁互卡(stage=hard-stop-pair): pair="
              + episode.trainA
              + "/"
              + episode.trainB
              + " conflict="
              + episode.conflictKey);
      dispatchService.reapplyHardStopByName(episode.trainA, "health-deadlock-confirmed");
      dispatchService.reapplyHardStopByName(episode.trainB, "health-deadlock-confirmed");
      episode.reissueCount++;
      recovery.lastDeadlockAttemptAt = now;
      recovery.deadlockStage = Math.max(recovery.deadlockStage, 2);
      deadlockPairLastAttemptAt.put(pairKey, now);
      return false;
    }

    long remainingMsUntilDestroy = remainingMsUntilDestroy(episode, now);
    Optional<RuntimeDispatchService.DeadlockDrainability> drainability =
        dispatchService.deadlockDrainability(
            episode.key,
            episode.trainA,
            episode.trainB,
            episode.conflictKey,
            remainingMsUntilDestroy);
    boolean drainabilityBlocksDestroy =
        drainability.map(RuntimeDispatchService.DeadlockDrainability::drainable).orElse(false);
    if (drainability.isPresent()) {
      traceSmartRecoveryDecision(
          episode.stableLeader, "SMART_DRAIN_UNLOCK_CANDIDATE", drainability.get().reason());
      traceDeadlockDrainability(episode, drainability.get(), now);
      if (drainability.get().drainable()) {
        traceSmartRecoveryDecision(
            episode.stableLeader, "SMART_DRAIN_UNLOCK_APPLIED", drainability.get().reason());
        traceRecoveryCandidateSelected(
            episode.stableLeader, "SMART_DRAIN_UNLOCK", episode.conflictKey);
        RuntimeDispatchService.SmartRecoveryActionResult drainabilityOnly =
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_DRAIN_UNLOCK",
                drainability.get().reason(),
                DispatchEffectClass.SIGNAL_CONSTRAINT,
                new RuntimeDispatchService.SmartRecoveryEffectiveness(
                    "SMART_DRAIN_UNLOCK",
                    episode.conflictKey,
                    false,
                    SignalAspect.STOP,
                    false,
                    false,
                    SignalComputationTrace.TokenState.NONE,
                    SignalComputationTrace.TokenState.NONE,
                    false,
                    false,
                    false,
                    true,
                    "drainability-candidate-only"));
        if (shouldHoldForSafeCandidate(
            episode.stableLeader, episode.conflictKey, drainabilityOnly, true)) {
          return false;
        }
        drainabilityBlocksDestroy = false;
      }
    }

    RuntimeDispatchService.SmartRecoveryInput smartInput =
        dispatchService.smartRecoveryInput(
            episode.stableLeader, Duration.between(episode.firstSeenAt, now), SignalAspect.STOP);
    // 链的去留与销毁的去留分开判（见 holdsMutualChain）：动作落地且有效（含未经测量的假定有效）本轮就不销毁，
    // 但假定有效连着两次没解开，链照样往下走，轮到后面的疏通、前推与排队位让位。
    boolean safeUnlockAppliedEffective = false;
    RuntimeDispatchService.SmartRecoveryActionResult selfRetainRelease =
        safeSmartRecoveryResult(dispatchService.applySmartSelfOwnedStaleRetainRelease(smartInput));
    if (selfRetainRelease.candidate()) {
      traceRecoveryCandidateSelected(
          episode.stableLeader, selfRetainRelease.decision(), episode.conflictKey);
      debugLogger.accept(
          "SMART_DESTROY_NOT_REACHED_SAFE_UNLOCK_AVAILABLE train="
              + episode.stableLeader
              + " recoveryDecision="
              + selfRetainRelease.decision()
              + " conflict="
              + episode.conflictKey);
      safeUnlockAppliedEffective |= appliedEffective(selfRetainRelease);
      if (holdsMutualChain(episode.stableLeader, episode.conflictKey, selfRetainRelease)) {
        traceDeadlockDestroySkipped(
            episode, observation, progressDuration, now, "self-owned-stale-retain-release");
        recovery.lastDeadlockAttemptAt = now;
        deadlockPairLastAttemptAt.put(pairKey, now);
        return measuredEffective(selfRetainRelease);
      }
    }
    RuntimeDispatchService.SmartRecoveryActionResult smartDrainUnlock =
        safeSmartRecoveryResult(dispatchService.applySmartDrainUnlock(smartInput));
    if (smartDrainUnlock.candidate()) {
      traceRecoveryCandidateSelected(
          episode.stableLeader, smartDrainUnlock.decision(), episode.conflictKey);
      debugLogger.accept(
          "SMART_DESTROY_NOT_REACHED_SAFE_UNLOCK_AVAILABLE train="
              + episode.stableLeader
              + " recoveryDecision="
              + smartDrainUnlock.decision()
              + " conflict="
              + episode.conflictKey);
      safeUnlockAppliedEffective |= appliedEffective(smartDrainUnlock);
      if (holdsMutualChain(episode.stableLeader, episode.conflictKey, smartDrainUnlock)) {
        traceDeadlockDestroySkipped(episode, observation, progressDuration, now, "drain-unlock");
        recovery.lastDeadlockAttemptAt = now;
        deadlockPairLastAttemptAt.put(pairKey, now);
        return measuredEffective(smartDrainUnlock);
      }
    }
    RuntimeDispatchService.SmartRecoveryActionResult forwardUnlock =
        safeSmartRecoveryResult(dispatchService.applySmartForwardUnlock(smartInput));
    boolean forwardUnlockBlocksDestroy = false;
    if (forwardUnlock.candidate()) {
      traceRecoveryCandidateSelected(
          episode.stableLeader, forwardUnlock.decision(), episode.conflictKey);
      debugLogger.accept(
          "SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE train="
              + episode.stableLeader
              + " reason=forward-unlock-candidate"
              + " conflict="
              + episode.conflictKey);
      safeUnlockAppliedEffective |= appliedEffective(forwardUnlock);
      if (holdsMutualChain(episode.stableLeader, episode.conflictKey, forwardUnlock)) {
        forwardUnlockBlocksDestroy = true;
        traceDeadlockDestroySkipped(
            episode, observation, progressDuration, now, "forward-unlock-candidate");
        recovery.lastDeadlockAttemptAt = now;
        deadlockPairLastAttemptAt.put(pairKey, now);
        return measuredEffective(forwardUnlock);
      }
    }
    // 排队位让位：互卡里常见的一种是"一辆只被对方的排队位挡住、另一辆被这一辆的硬占挡住"，前三个动作都只处理自持资源，割不开它。
    // 只差一个排队位的可能是配对中的任意一辆，只处理 A 车会把恰好需要让位的 B 车整个跳过。
    // 两辆都试；让位本身要求证实经过排队位的等待环，不会凭空改队列。
    for (String yieldTrain : List.of(episode.trainA, episode.trainB)) {
      RuntimeDispatchService.SmartRecoveryActionResult queueYield =
          safeSmartRecoveryResult(
              dispatchService.applySmartQueuePositionYield(
                  dispatchService.smartRecoveryInput(
                      yieldTrain, Duration.between(episode.firstSeenAt, now), SignalAspect.STOP)));
      if (!queueYield.candidate()) {
        continue;
      }
      traceRecoveryCandidateSelected(yieldTrain, queueYield.decision(), episode.conflictKey);
      safeUnlockAppliedEffective |= appliedEffective(queueYield);
      if (holdsMutualChain(yieldTrain, episode.conflictKey, queueYield)) {
        traceDeadlockDestroySkipped(
            episode, observation, progressDuration, now, "queue-position-yield");
        recovery.lastDeadlockAttemptAt = now;
        deadlockPairLastAttemptAt.put(pairKey, now);
        return measuredEffective(queueYield);
      }
    }
    if (safeUnlockAppliedEffective) {
      // 有动作落地且（至少被假定）有效：销毁仍按旧口径挡住，只是链不再停在第一步。
      traceDeadlockDestroySkipped(
          episode, observation, progressDuration, now, "safe-unlock-applied");
      recovery.lastDeadlockAttemptAt = now;
      deadlockPairLastAttemptAt.put(pairKey, now);
      return false;
    }
    debugLogger.accept(
        "SMART_RECOVERY_NO_SAFE_CANDIDATE train="
            + episode.stableLeader
            + " reason=no-effective-self-retain-drain-forward"
            + " conflictKey="
            + episode.conflictKey);
    DirectionDestroyAudit directionAudit =
        directionDestroyAudit(
            episode, smartInput, selfRetainRelease, smartDrainUnlock, forwardUnlock);
    boolean directionReauditAttempted = triggerDirectionReaudit(episode, directionAudit, now);
    boolean directionLastResortDestroy =
        directionAudit.required() && directionAuditLastResortDestroyAllowed(episode, now);
    boolean directionBlockingActiveTraffic = liveBlockerCycle && allBlockersLiveHard(episode);

    if (!shouldDestroyDeadlockLeader(episode, progressDuration, now)) {
      String skipReason = destroySkipReason(episode, progressDuration, now);
      if (episode.weak) {
        RuntimeDispatchService.RuntimeTrainResolution weakResolution =
            resolveForDestroyTrace(episode.stableLeader);
        SmartDispatcherController.DeadlockDestroyReview weakReview =
            dispatchService.reviewDeadlockDestroyCandidate(
                episode.key,
                episode.trainA,
                episode.trainB,
                episode.stableLeader,
                episode.conflictKey,
                true,
                false,
                false,
                false,
                false,
                false,
                weakResolution,
                false,
                true,
                false,
                "-",
                false,
                false,
                false,
                Duration.between(episode.firstSeenAt, now),
                Duration.ZERO);
        skipReason = weakReview.reason();
      }
      debugLogger.accept(
          "SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE train="
              + episode.stableLeader
              + " reason="
              + skipReason
              + " conflict="
              + episode.conflictKey);
      traceDeadlockDestroySkipped(episode, observation, progressDuration, now, skipReason);
      return false;
    }
    RuntimeDispatchService.RuntimeTrainResolution resolution =
        resolveForDestroyTrace(episode.stableLeader);
    SmartDispatcherController.DeadlockDestroyReview destroyReview =
        dispatchService.reviewDeadlockDestroyCandidate(
            episode.key,
            episode.trainA,
            episode.trainB,
            episode.stableLeader,
            episode.conflictKey,
            episode.weak,
            allBlockersLiveHard(episode),
            drainabilityBlocksDestroy,
            false,
            forwardUnlockBlocksDestroy,
            false,
            resolution,
            false,
            true,
            directionAudit.required(),
            directionAudit.reason(),
            directionReauditAttempted,
            directionLastResortDestroy,
            directionBlockingActiveTraffic,
            Duration.between(episode.firstSeenAt, now),
            requiredDestroyThreshold(episode));
    if (!destroyReview.allowed()) {
      debugLogger.accept(
          "SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE train="
              + episode.stableLeader
              + " reason="
              + destroyReview.reason()
              + " conflict="
              + episode.conflictKey);
      traceDeadlockDestroySkipped(
          episode, observation, progressDuration, now, destroyReview.reason());
      return false;
    }
    traceSmartRecoveryDecision(
        episode.stableLeader, "SMART_DESTROY_CANDIDATE", destroyReview.reason());
    if (!smartDispatcherAllowsHealthMutation(
        episode.stableLeader,
        DispatchAction.EXECUTE_VERIFIED_DEADLOCK_DESTROY,
        "health-deadlock-destroy")) {
      debugLogger.accept(
          "SMART_DESTROY_SUPPRESSED_BY_MODE train="
              + episode.stableLeader
              + " mode="
              + smartDispatcherMode()
              + " effectClass="
              + DispatchEffectClass.DESTROY_ACTION);
      traceDeadlockDestroySkipped(
          episode, observation, progressDuration, now, "destroy-action-disabled-by-mode");
      return false;
    }
    debugLogger.accept(
        "SMART_HEALTH_EFFECT_EXECUTION action=EXECUTE_VERIFIED_DEADLOCK_DESTROY effect=DESTROY_TRAIN train="
            + episode.stableLeader
            + " source=health-deadlock-destroy dispatcherAction=false");
    debugLogger.accept(
        "SMART_DESTROY_CANDIDATE train="
            + episode.stableLeader
            + " conflict="
            + episode.conflictKey
            + " episode="
            + episode.key);
    traceDeadlockDestroyCandidateSelected(episode, observation, progressDuration, now);
    traceDeadlockDestroyAttempted(episode, observation, progressDuration, now, resolution);
    debugLogger.accept(
        "TrainHealthMonitor 解锁互卡(stage=destroy-leader): train="
            + episode.stableLeader
            + " survivor="
            + episode.survivor()
            + " conflict="
            + episode.conflictKey
            + " episode="
            + episode.key
            + " blockers="
            + episode.lastBlockerSnapshot
            + " age="
            + Duration.between(episode.firstSeenAt, now).toSeconds()
            + "s");
    dispatchService.scheduleSurvivorRefreshAfterTrainRemoved(
        episode.stableLeader, episode.survivor());
    boolean destroyed =
        dispatchService.destroyTrainByName(episode.stableLeader, "health-deadlock-timeout");
    debugLogger.accept(
        "SMART_DESTROY_EXECUTED train="
            + episode.stableLeader
            + " destroyed="
            + destroyed
            + " conflict="
            + episode.conflictKey);
    debugLogger.accept(
        "SMART_DESTROY_VERIFY train="
            + episode.stableLeader
            + " destroyed="
            + destroyed
            + " survivor="
            + episode.survivor());
    traceDeadlockDestroyResult(
        episode, resolution, destroyed, destroyFailureReason(resolution, destroyed), now);
    RuntimeDispatchService.SmartRecoveryInput destroyInput =
        dispatchService.smartRecoveryInput(
            episode.stableLeader, progressDuration, SignalAspect.STOP);
    traceSmartDeadlockDestroyExecuted(
        episode.stableLeader,
        episode.survivor(),
        episode.conflictKey,
        episodeType(observation),
        destroyed ? "CONFIRMED_DEADLOCK_TIMEOUT" : destroyFailureReason(resolution, false),
        progressDuration,
        destroyInput,
        dispatchService
            .deadlockTrainContext(episode.stableLeader)
            .map(context -> context.hasPassengers() ? 1 : 0)
            .orElse(0),
        now);
    if (destroyed) {
      episode.destroyAttempted = true;
      closeDeadlockEpisodeAfterDestroy(episode, now);
    }
    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = 3;
    deadlockPairLastAttemptAt.put(pairKey, now);
    rememberDeadlockDestroy(episode.trainA, episode.trainB, now);
    return destroyed;
  }

  private void traceDeadlockDestroyCandidateSelected(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now) {
    if (episode == null || observation == null) {
      return;
    }
    traceHealthEvent(
        "DEADLOCK_DESTROY_CANDIDATE_SELECTED",
        "destroy-candidate:" + episode.key,
        "episodeId="
            + episode.key
            + " selectedLeader="
            + episode.stableLeader
            + " selectionReason=stable-leader-score"
            + " episodeType="
            + episodeType(observation)
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis()
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " thresholdMs="
            + requiredDestroyThreshold(episode).toMillis()
            + " weakEpisode="
            + episode.weak
            + " blockers="
            + episode.lastBlockerSnapshot);
  }

  private void traceDeadlockDrainability(
      DeadlockEpisode episode,
      RuntimeDispatchService.DeadlockDrainability drainability,
      Instant now) {
    if (episode == null || drainability == null) {
      return;
    }
    traceHealthEvent(
        drainability.drainable() ? "DEADLOCK_DRAINABLE" : "DEADLOCK_DRAIN_FAILED",
        "deadlock-drain:" + episode.key + ":" + drainability.reason(),
        "episodeId="
            + episode.key
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " zoneId="
            + drainability.zoneId()
            + " drainable="
            + drainability.drainable()
            + " drainCandidate="
            + emptyDash(drainability.drainCandidate())
            + " exitNode="
            + drainability.exitNode().map(NodeId::value).orElse("-")
            + " drainPath="
            + drainability.drainPath()
            + " reason="
            + drainability.reason()
            + " remainingMsUntilDestroy="
            + drainability.remainingMsUntilDestroy()
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis());
  }

  private void traceDeadlockDestroyAttempted(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now,
      RuntimeDispatchService.RuntimeTrainResolution resolution) {
    if (episode == null) {
      return;
    }
    RuntimeDispatchService.RuntimeTrainResolution effective =
        resolution == null ? failedResolution(episode.stableLeader, "RESOLVE_FAILED") : resolution;
    traceHealthEvent(
        "DEADLOCK_DESTROY_ATTEMPTED",
        "health-destroy-attempt:" + episode.key,
        "episodeId="
            + episode.key
            + " requestedName="
            + emptyDash(episode.stableLeader)
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " propertiesFound="
            + effective.propertiesFound()
            + " source=HEALTH_MONITOR_DEADLOCK"
            + " episodeType="
            + episodeType(observation)
            + " conflictKey="
            + episode.conflictKey
            + " blockerResources="
            + (observation == null ? Set.of() : observation.blockerResources())
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis()
            + " thresholdMs="
            + requiredDestroyThreshold(episode).toMillis());
  }

  private void traceDeadlockDestroyResult(
      DeadlockEpisode episode,
      RuntimeDispatchService.RuntimeTrainResolution resolution,
      boolean success,
      String failureReason,
      Instant now) {
    if (episode == null) {
      return;
    }
    RuntimeDispatchService.RuntimeTrainResolution effective =
        resolution == null ? failedResolution(episode.stableLeader, "RESOLVE_FAILED") : resolution;
    traceHealthEvent(
        "DEADLOCK_DESTROY_RESULT",
        "health-destroy-result:" + episode.key + ":" + success + ":" + failureReason,
        "episodeId="
            + episode.key
            + " requestedName="
            + emptyDash(episode.stableLeader)
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " success="
            + success
            + " failureReason="
            + emptyDash(failureReason)
            + " episodeAgeMs="
            + Duration.between(episode.firstSeenAt, now).toMillis());
  }

  private void traceDeadlockDestroySkipped(
      DeadlockEpisode episode,
      DeadlockObservation observation,
      Duration progressDuration,
      Instant now,
      String reason) {
    if (episode == null || reason == null || reason.isBlank() || "NONE".equals(reason)) {
      return;
    }
    long remainingMs =
        Math.max(
            0L,
            requiredDestroyThreshold(episode)
                .minus(Duration.between(episode.firstSeenAt, now))
                .toMillis());
    boolean thresholdPassed = remainingMs <= 0L;
    traceHealthEvent(
        "DEADLOCK_DESTROY_SKIPPED",
        "destroy-skip:" + episode.key + ":" + reason,
        "episodeId="
            + episode.key
            + " reason="
            + reason
            + " trainA="
            + episode.trainA
            + " trainB="
            + episode.trainB
            + " blockers="
            + episode.lastBlockerSnapshot
            + " lastBlockerSnapshotAgeMs="
            + snapshotAgeMs(episode.trainA, now)
            + " remainingMsUntilDestroyCandidate="
            + (episode.weak ? -1L : remainingMs)
            + " conflictKey="
            + episode.conflictKey
            + " directions="
            + directionsSummary(observation)
            + " stoppedDurationMs="
            + (progressDuration == null ? 0L : progressDuration.toMillis())
            + " skipPhase="
            + (thresholdPassed ? "THRESHOLD_PASSED" : "EARLY")
            + " thresholdPassed="
            + thresholdPassed
            + " fallbackEvidenceChecked=false"
            + " fallbackIneligibleReason="
            + emptyDash(reason));
  }

  private RuntimeDispatchService.RuntimeTrainResolution resolveForDestroyTrace(String trainName) {
    return dispatchService.resolveRuntimeTrainForHealth(
        trainName, RuntimeDispatchService.RuntimeTrainResolvePurpose.DESTROY);
  }

  private String destroyFailureReason(
      RuntimeDispatchService.RuntimeTrainResolution resolution, boolean destroyed) {
    if (destroyed) {
      return "NONE";
    }
    if (resolution == null
        || resolution.matchedBy() == RuntimeDispatchService.RuntimeTrainMatchKind.FAILED) {
      return "RESOLVE_FAILED";
    }
    if (!resolution.propertiesFound()) {
      return "NO_PROPERTIES";
    }
    return "DESTROY_API_FAILED";
  }

  private String destroySkipReason(
      DeadlockEpisode episode, Duration progressDuration, Instant now) {
    if (episode == null) {
      return "NOT_MUTUAL";
    }
    if (!trainCleanupEnabled
        || deadlockDestroyThreshold == null
        || deadlockDestroyThreshold.isZero()) {
      return "DESTROY_DISABLED";
    }
    if (episode.destroyAttempted) {
      return "DESTROY_ALREADY_ATTEMPTED";
    }
    if (episode.weak) {
      return "WEAK_EPISODE_DIAGNOSTIC_ONLY";
    }
    Duration episodeAge = Duration.between(episode.firstSeenAt, now);
    Duration requiredThreshold = requiredDestroyThreshold(episode);
    if (episodeAge.compareTo(requiredThreshold) < 0) {
      return "BELOW_DESTROY_THRESHOLD";
    }
    Duration requiredStop = minPositive(progressStopGraceThreshold, requiredThreshold);
    if (progressDuration == null || progressDuration.compareTo(requiredStop) < 0) {
      return "NOT_STATIONARY";
    }
    if (dispatchService.hasActiveSmartUnlockReservation(episode.stableLeader)
        || dispatchService.hasActiveSmartUnlockReservation(episode.trainA)
        || dispatchService.hasActiveSmartUnlockReservation(episode.trainB)) {
      return "ACTIVE_UNLOCK_RESERVATION";
    }
    RuntimeDispatchService.DeadlockTrainContext targetContext =
        dispatchService.deadlockTrainContext(episode.stableLeader).orElse(null);
    if (targetContext == null) {
      return "TARGET_STATE_MISSING";
    }
    if (!isEligibleDeadlockContext(targetContext)) {
      return "TARGET_CONTEXT_NOT_ELIGIBLE";
    }
    if (targetContext.hasPassengers()) {
      return "PLAYER_PASSENGER_PRESENT";
    }
    if (targetContext.manualHold()) {
      return "MANUAL_CONTROL";
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant lastDestroy = deadlockPairLastDestroyAt.get(pairKey);
    if (lastDestroy != null && now.isBefore(lastDestroy.plus(deadlockDestroyCooldown))) {
      return "DESTROY_COOLDOWN_ACTIVE";
    }
    if (now.isBefore(lastDeadlockDestroyAt.plus(deadlockDestroyCooldown))) {
      return "DESTROY_COOLDOWN_ACTIVE";
    }
    return "NONE";
  }

  private Duration requiredDestroyThreshold(DeadlockEpisode episode) {
    if (deadlockDestroyThreshold == null) {
      return Duration.ZERO;
    }
    return episode != null && episode.weak ? Duration.ZERO : deadlockDestroyThreshold;
  }

  /**
   * 在 destroy 审查前使用最新 typed blocker 快照重新确认双向 hard wait-for 边。
   *
   * <p>episode 只保存稳定身份与首见时间，不能把此前的 {@code live:} key 当作当前硬占用证明。恢复动作可能已经使 claim 降级、释放或改写；因此最终
   * destructive action 必须再次看到双方互相指向、且 relation/role 仍为明确硬语义。single episode 还必须保持同一 conflict
   * 与已知对向方向。
   */
  private boolean allBlockersLiveHard(DeadlockEpisode episode) {
    if (episode == null || episode.weak) {
      return false;
    }
    Optional<RuntimeDispatchService.DeadlockBlockerInfo> first =
        latestHardBlockerBetween(episode.trainA, episode.trainB, episode.conflictKey);
    Optional<RuntimeDispatchService.DeadlockBlockerInfo> second =
        latestHardBlockerBetween(episode.trainB, episode.trainA, episode.conflictKey);
    if (first.isEmpty() || second.isEmpty()) {
      return false;
    }
    return !episode.conflictKey.startsWith("single:")
        || isKnownOpposite(first.get().direction(), second.get().direction());
  }

  private Optional<RuntimeDispatchService.DeadlockBlockerInfo> latestHardBlockerBetween(
      String waitingTrain, String blockerTrain, String episodeConflictKey) {
    String blockerKey = keyOf(blockerTrain);
    if (blockerKey == null) {
      return Optional.empty();
    }
    return recentDeadlockBlockerSnapshot(waitingTrain).blockers().stream()
        .filter(blocker -> blockerKey.equals(keyOf(blocker.trainName())))
        .filter(
            blocker ->
                episodeConflictKey.startsWith("single:")
                    ? episodeConflictKey.equals(blocker.conflictKey())
                        && isUsableSingleConflictBlocker(blocker)
                    : episodeConflictKey.startsWith("live:") && isLiveHardCycleBlocker(blocker))
        .findFirst();
  }

  private long remainingMsUntilDestroy(DeadlockEpisode episode, Instant now) {
    if (episode == null || now == null) {
      return -1L;
    }
    long remaining =
        requiredDestroyThreshold(episode)
            .minus(Duration.between(episode.firstSeenAt, now))
            .toMillis();
    return Math.max(0L, remaining);
  }

  private static RuntimeDispatchService.RuntimeTrainResolution failedResolution(
      String trainName, String reason) {
    return new RuntimeDispatchService.RuntimeTrainResolution(
        trainName,
        "",
        null,
        RuntimeDispatchService.RuntimeTrainMatchKind.FAILED,
        Optional.empty(),
        Optional.empty(),
        reason);
  }

  private static String emptyDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private boolean shouldDestroyDeadlockLeader(
      DeadlockEpisode episode, Duration progressDuration, Instant now) {
    if (!trainCleanupEnabled
        || episode == null
        || episode.destroyAttempted
        || episode.weak
        || deadlockDestroyThreshold == null
        || deadlockDestroyThreshold.isZero()
        || now == null) {
      return false;
    }
    Duration episodeAge = Duration.between(episode.firstSeenAt, now);
    Duration requiredThreshold = requiredDestroyThreshold(episode);
    if (episodeAge.compareTo(requiredThreshold) < 0) {
      return false;
    }
    Duration requiredStop = minPositive(progressStopGraceThreshold, requiredThreshold);
    if (progressDuration == null || progressDuration.compareTo(requiredStop) < 0) {
      return false;
    }
    if (dispatchService.hasActiveSmartUnlockReservation(episode.stableLeader)
        || dispatchService.hasActiveSmartUnlockReservation(episode.trainA)
        || dispatchService.hasActiveSmartUnlockReservation(episode.trainB)) {
      return false;
    }
    RuntimeDispatchService.DeadlockTrainContext targetContext =
        dispatchService.deadlockTrainContext(episode.stableLeader).orElse(null);
    if (targetContext == null
        || !isEligibleDeadlockContext(targetContext)
        || targetContext.hasPassengers()
        || targetContext.manualHold()) {
      return false;
    }
    String pairKey = pairKey(keyOf(episode.trainA), keyOf(episode.trainB));
    Instant lastDestroy = deadlockPairLastDestroyAt.get(pairKey);
    boolean pairReady =
        lastDestroy == null || !now.isBefore(lastDestroy.plus(deadlockDestroyCooldown));
    boolean globalReady = !now.isBefore(lastDeadlockDestroyAt.plus(deadlockDestroyCooldown));
    return pairReady && globalReady;
  }

  private static Duration minPositive(Duration first, Duration second) {
    if (first == null || first.isNegative() || first.isZero()) {
      return second == null ? Duration.ZERO : second;
    }
    if (second == null || second.isNegative() || second.isZero()) {
      return first;
    }
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static String chooseStableLeader(
      RuntimeDispatchService.DeadlockTrainContext first,
      RuntimeDispatchService.DeadlockTrainContext second) {
    int firstScore = destroyScore(first);
    int secondScore = destroyScore(second);
    if (firstScore != secondScore) {
      return firstScore > secondScore ? first.trainName() : second.trainName();
    }
    if (first.progressIndex() != second.progressIndex()) {
      return first.progressIndex() < second.progressIndex()
          ? first.trainName()
          : second.trainName();
    }
    if (first.priority() != second.priority()) {
      return first.priority() < second.priority() ? first.trainName() : second.trainName();
    }
    String firstKey = keyOf(first.trainName());
    String secondKey = keyOf(second.trainName());
    if (firstKey == null) {
      return second.trainName();
    }
    if (secondKey == null) {
      return first.trainName();
    }
    return firstKey.compareTo(secondKey) <= 0 ? first.trainName() : second.trainName();
  }

  private static int destroyScore(RuntimeDispatchService.DeadlockTrainContext context) {
    if (context == null) {
      return Integer.MIN_VALUE;
    }
    int score = 0;
    if (context.dwelling()
        || context.departureGateHeld()
        || context.layoverReady()
        || context.manualHold()) {
      score -= 10_000;
    }
    if (context.nearRouteEnd()) {
      score -= 200;
    }
    if (context.hasPassengers()) {
      score -= 100;
    }
    if (context.operationType() == RouteOperationType.RETURN) {
      score += 500;
    } else if (context.operationType() == RouteOperationType.CREATE) {
      score += 250;
    }
    if (context.depotRelated()) {
      score += 100;
    }
    return score;
  }

  /**
   * 手动模式下的互卡解锁。
   *
   * <p>一次执行完整的“refresh -> hard-stop”链路，确保 STOP 互卡等待期间不会通过健康修复重新注入运动 destination。
   */
  private boolean forceFixMutualDeadlock(
      String trainName, String blockerTrain, RecoveryState recovery, Instant now) {
    if (trainName == null || blockerTrain == null) {
      return false;
    }
    String trainKey = keyOf(trainName);
    String blockerKey = keyOf(blockerTrain);
    if (trainKey == null || blockerKey == null) {
      return false;
    }
    String pairKey = pairKey(trainKey, blockerKey);
    debugLogger.accept(
        "TrainHealthMonitor 手动解锁互卡: train=" + trainName + " blocker=" + blockerTrain);

    dispatchService.refreshSignalByName(trainName);
    dispatchService.refreshSignalByName(blockerTrain);

    boolean applied = false;
    applied = dispatchService.reapplyHardStopByName(trainName, "manual-mutual-deadlock") || applied;
    applied =
        dispatchService.reapplyHardStopByName(blockerTrain, "manual-mutual-deadlock") || applied;

    recovery.lastDeadlockAttemptAt = now;
    recovery.deadlockStage = applied ? 3 : 0;
    deadlockPairLastAttemptAt.put(pairKey, now);
    return false;
  }

  private static String pairKey(String first, String second) {
    return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
  }

  private static String episodeKey(String first, String second, String conflictKey) {
    return pairKey(first, second) + "|" + (conflictKey == null ? "unknown" : conflictKey);
  }

  private static String firstTrainInPair(String first, String second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static boolean isPairLeader(String trainName, String blockerTrain) {
    String trainKey = keyOf(trainName);
    String blockerKey = keyOf(blockerTrain);
    if (trainKey == null || blockerKey == null) {
      return false;
    }
    return trainKey.equals(firstTrainInPair(trainKey, blockerKey));
  }

  /**
   * 把本类持有的墙钟基准**向前平移** {@code gap}，用于服务器冻结后的补偿。
   *
   * <p><b>只能由调度层调用</b>（{@code HealthMonitor.tick}）——只有它知道“该跑而没跑”。 本类的 {@link #check}
   * 是用例直接调的，它们以任意步长推进时钟， 把检测放在 {@code check} 里会把正常的大步长推进误判成冻结。
   *
   * <p><b>为什么是平移而不是清空</b>：清空会把冻结**前**已经积累的真实停滞也抄掉， 那是另一个方向的错。平移后，冻结前已卡 40 秒的车仍然是 40 秒。
   *
   * <p>服务器冻结（主线程停摆、宿主机休眠等，HikariCP 通常同时报 {@code Thread starvation or clock leap detected}）恢复后的第一个
   * tick 里，多辆车会<b>在同一瞬间</b>全部跨过停滞阈值，而它们随后就会自己恢复。 那不是死锁，是墙钟在说谎。而 {@code deadlock-threshold-seconds}、
   * {@code stuck-cleanup-threshold-seconds} 只有数十秒到数分钟，与一次冻结的时长同阶；若销毁兜底开着，一次冻结就是一次大规模删车。
   */
  public void rebaseAfterFreeze(Duration gap) {
    if (gap == null || gap.isZero() || gap.isNegative()) {
      return;
    }
    debugLogger.accept(
        "SMART_HEALTH_CLOCK_DISCONTINUITY gapSeconds="
            + gap.toSeconds()
            + " rebasedTrains="
            + snapshots.size()
            + " rebasedRecoveryStates="
            + recoveryStates.size()
            + " reason=server-freeze-or-clock-leap");
    snapshots.replaceAll(
        (name, snapshot) ->
            snapshot == null
                ? null
                : new TrainSnapshot(
                    snapshot.progressIndex(),
                    snapshot.lastPassedGraphNodeId(),
                    snapshot.signal(),
                    snapshot.speedBpt(),
                    shiftInstant(snapshot.captureTime(), gap),
                    shiftInstant(snapshot.lastMoveTime(), gap),
                    shiftInstant(snapshot.lastProgressTime(), gap)));
    for (RecoveryState recovery : recoveryStates.values()) {
      if (recovery == null) {
        continue;
      }
      recovery.lastStallAttemptAt = shiftInstant(recovery.lastStallAttemptAt, gap);
      recovery.lastProgressAttemptAt = shiftInstant(recovery.lastProgressAttemptAt, gap);
      recovery.lastDeadlockAttemptAt = shiftInstant(recovery.lastDeadlockAttemptAt, gap);
      recovery.pendingRecoveryAt = shiftInstant(recovery.pendingRecoveryAt, gap);
      recovery.lastRecoveryPushAt = shiftInstant(recovery.lastRecoveryPushAt, gap);
      recovery.creepSince = shiftInstant(recovery.creepSince, gap);
      recovery.creepLastAttemptAt = shiftInstant(recovery.creepLastAttemptAt, gap);
      recovery.creepLastPushAt = shiftInstant(recovery.creepLastPushAt, gap);
      recovery.disabledVerdictAt = shiftInstant(recovery.disabledVerdictAt, gap);
    }
    deadlockPairLastAttemptAt.replaceAll((key, at) -> shiftInstant(at, gap));
    deadlockPairLastDestroyAt.replaceAll((key, at) -> shiftInstant(at, gap));
    traceLastAt.replaceAll((key, at) -> shiftInstant(at, gap));
  }

  /** {@link Instant#EPOCH} 表示“从来没有过”，不得平移——平移了就变成一个假的近期时刻。 */
  private static Instant shiftInstant(Instant at, Duration gap) {
    return at == null || Instant.EPOCH.equals(at) ? at : at.plus(gap);
  }

  private boolean canAttempt(Instant now, Instant lastAttemptAt) {
    if (now == null) {
      return false;
    }
    if (lastAttemptAt == null || lastAttemptAt.equals(Instant.EPOCH)) {
      return true;
    }
    return !now.isBefore(lastAttemptAt.plus(recoveryCooldown));
  }

  private static String keyOf(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return null;
    }
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  /** 检查结果。 */
  /**
   * 一次健康检查的结果。
   *
   * <p>{@code fixedCount} 与 {@code recoveryDispatchedCount} 是**两件事**，不可混为一谈：
   * 前者是"车确实重新推进了"，后者是"派发了一个恢复动作"。派发不等于恢复——一辆车可以在同一个 idx 上被反复"修好"而一步没挪。
   */
  public record CheckResult(
      int stallCount, int progressStuckCount, int fixedCount, int recoveryDispatchedCount) {

    /** 兼容旧调用：未区分派发与恢复时，两者同值。 */
    public CheckResult(int stallCount, int progressStuckCount, int fixedCount) {
      this(stallCount, progressStuckCount, fixedCount, fixedCount);
    }

    public int totalAnomalies() {
      return stallCount + progressStuckCount;
    }

    public boolean hasAnomalies() {
      return totalAnomalies() > 0;
    }
  }
}
