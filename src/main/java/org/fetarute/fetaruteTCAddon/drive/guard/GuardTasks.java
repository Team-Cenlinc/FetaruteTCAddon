package org.fetarute.fetaruteTCAddon.drive.guard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;

/**
 * 车掌任务：一个车次一名车掌、一名玩家一个未结束的任务。领取后等列车到接班站，坐进车尾驾驶室即上岗；始发站的终点站待命车派车前先扣着等车掌。
 *
 * <p>本类只记状态与判定，不依赖服务器对象；只在服务器主线程使用。
 */
public final class GuardTasks {

  /** 还没对上列车、计划发车已过这么久仍没等到就作废（与驾驶任务相同）。 */
  public static final Duration EXPIRE_AFTER = DriverTaskManager.EXPIRE_AFTER;

  /** 领取的结果。 */
  public enum ClaimOutcome {
    /** 已领取。 */
    CLAIMED,
    /** 车掌功能未启用。 */
    DISABLED,
    /** 正在值乘：先离岗再领。 */
    ON_DUTY,
    /** 正在驾驶：车掌与驾驶员不能同时当。 */
    DRIVING,
    /** 已有未结束的车掌任务。 */
    ALREADY_HAS_TASK,
    /** 这一班的车掌已被别人领走。 */
    TAKEN,
    /** 车次已取消或在本站终到。 */
    UNAVAILABLE,
    /** 被事件取消。 */
    CANCELLED
  }

  /** 已领取的任务此刻该怎么办。 */
  public enum Step {
    /** 接着等。 */
    WAIT,
    /** 列车停在接班站：坐进车尾驾驶室即上岗。 */
    BOARD,
    /** 列车已开过接班站。 */
    EXPIRE_DEPARTED,
    /** 过了计划发车太久仍没等到列车。 */
    EXPIRE_TIMEOUT
  }

  /** 始发站终点站待命车派车前的判定。 */
  public enum HoldVerdict {
    /** 照常派车。 */
    DISPATCH,
    /** 扣着，接着等车掌。 */
    HOLD,
    /** 开始扣车：通知车掌上车。 */
    START,
    /** 等到时限车掌还没上岗：照常派车，这一班作废。 */
    GIVE_UP
  }

  private final Map<UUID, GuardTask> byPlayer = new HashMap<>();
  private Predicate<GuardTask> beforeClaim = task -> true;

  /** 领取之前的关卡（外部插件可取消）。 */
  public void setBeforeClaim(Predicate<GuardTask> gate) {
    this.beforeClaim = gate == null ? task -> true : gate;
  }

  /** 玩家当前的任务（含刚结束、还没被新任务替换的）。 */
  public Optional<GuardTask> taskOf(UUID playerId) {
    return Optional.ofNullable(playerId == null ? null : byPlayer.get(playerId));
  }

  /** 玩家未结束的任务。 */
  public Optional<GuardTask> activeTaskOf(UUID playerId) {
    return taskOf(playerId).filter(task -> !task.state().finished());
  }

  /** 领了这一班车掌、还没结束的任务；没人领时为空。 */
  public Optional<GuardTask> taskForTrip(TaskKey key) {
    if (key == null) {
      return Optional.empty();
    }
    for (GuardTask task : byPlayer.values()) {
      if (!task.state().finished() && task.key().equals(key)) {
        return Optional.of(task);
      }
    }
    return Optional.empty();
  }

  /** 车掌已被领走（未结束）的车次与领取人，车掌任务板据此标明谁领了哪一班。 */
  public Map<TaskKey, TaskBoardEntries.Claimant> claimants() {
    Map<TaskKey, TaskBoardEntries.Claimant> claimants = new HashMap<>();
    for (GuardTask task : byPlayer.values()) {
      if (!task.state().finished()) {
        claimants.put(
            task.key(), new TaskBoardEntries.Claimant(task.playerId(), task.playerName()));
      }
    }
    return claimants;
  }

  /** 已领取、还没上岗的任务。 */
  public List<GuardTask> claimed() {
    List<GuardTask> out = new ArrayList<>();
    for (GuardTask task : byPlayer.values()) {
      if (task.state() == GuardTask.State.CLAIMED) {
        out.add(task);
      }
    }
    return out;
  }

  /** 是否有还没结束的任务。 */
  public boolean hasActive() {
    for (GuardTask task : byPlayer.values()) {
      if (!task.state().finished()) {
        return true;
      }
    }
    return false;
  }

  /** 全部任务（含刚结束的）。 */
  public List<GuardTask> all() {
    return new ArrayList<>(byPlayer.values());
  }

  /**
   * 登记一个任务（任务板领取或插件派出）。
   *
   * @param cancelledOrFinal 车次已取消或在本站终到
   */
  public ClaimOutcome register(
      UUID playerId,
      String playerName,
      DriverTaskManager.TaskSpec spec,
      boolean cancelledOrFinal,
      Instant now) {
    if (activeTaskOf(playerId).isPresent()) {
      return ClaimOutcome.ALREADY_HAS_TASK;
    }
    if (taskForTrip(spec.key()).isPresent()) {
      return ClaimOutcome.TAKEN;
    }
    if (cancelledOrFinal) {
      return ClaimOutcome.UNAVAILABLE;
    }
    GuardTask task = new GuardTask(playerId, playerName, spec, now);
    if (!beforeClaim.test(task)) {
      return ClaimOutcome.CANCELLED;
    }
    byPlayer.put(playerId, task);
    return ClaimOutcome.CLAIMED;
  }

  /**
   * 结束玩家还没上岗的任务。
   *
   * @return 结束了的任务；没有已领取未上岗的任务时为空
   */
  public Optional<GuardTask> dropClaim(UUID playerId, GuardTask.State state, String reason) {
    Optional<GuardTask> task =
        taskOf(playerId).filter(found -> found.state() == GuardTask.State.CLAIMED);
    task.ifPresent(found -> found.finish(state, reason));
    return task;
  }

  /** 忘掉已结束的任务（玩家离线后清理）。 */
  public void forgetFinished(UUID playerId) {
    GuardTask task = byPlayer.get(playerId);
    if (task != null && task.state().finished()) {
      byPlayer.remove(playerId);
    }
  }

  /**
   * 判定已领取的任务。
   *
   * @param matched 列车已绑定这一班
   * @param lastStopSequence 列车最近停过的停靠序号；还没停过为 -1
   * @param dwelling 列车正在停站
   */
  public static Step step(
      GuardTask task, boolean matched, int lastStopSequence, boolean dwelling, Instant now) {
    int takeover = task.takeoverStopSequence();
    if (matched && lastStopSequence > takeover) {
      return Step.EXPIRE_DEPARTED;
    }
    if (matched && lastStopSequence == takeover && dwelling) {
      return Step.BOARD;
    }
    if (task.heldTrain() != null) {
      // 始发站扣着车等车掌：时限由派车侧判定。
      return Step.WAIT;
    }
    boolean approaching = matched && lastStopSequence < takeover;
    if (!approaching && now.isAfter(task.plannedDeparture().plus(EXPIRE_AFTER))) {
      return Step.EXPIRE_TIMEOUT;
    }
    return Step.WAIT;
  }

  /**
   * 这列车正被别的车次的车掌扣着（始发站等车掌上岗、还没到时限）：不能派去跑别的车次。
   *
   * @param key 要派的车次
   */
  public boolean heldForOther(String trainName, TaskKey key, Instant now) {
    for (GuardTask task : byPlayer.values()) {
      if (task.state() == GuardTask.State.CLAIMED
          && task.heldTrain() != null
          && task.heldTrain().equalsIgnoreCase(trainName)
          && !task.key().equals(key)
          && now.isBefore(task.holdDeadline())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 始发站终点站待命车派去跑这一班之前：车掌领了、要在始发站上岗时先扣着一列车等他上岗，等到时限照常派车。扣车期间这一班的其他候选车也不派（不来回换车）；
   * 车掌坐进扣着的那一列上岗后只派这一列，其他候选车等到时限才放。
   *
   * @param task 领了这一班车掌的任务；没人领时为 {@code null}
   * @param trainName 要派的列车
   * @param online 车掌在线
   */
  public static HoldVerdict layover(GuardTask task, String trainName, boolean online, Instant now) {
    if (task == null || task.state().finished() || task.takeoverStopSequence() != 0) {
      return HoldVerdict.DISPATCH;
    }
    String held = task.heldTrain();
    if (task.state() == GuardTask.State.ON_DUTY) {
      if (held == null
          || held.equalsIgnoreCase(trainName)
          || trainName.equalsIgnoreCase(task.trainName())) {
        return HoldVerdict.DISPATCH;
      }
      return now.isBefore(task.holdDeadline()) ? HoldVerdict.HOLD : HoldVerdict.DISPATCH;
    }
    if (held == null) {
      // 车掌不在线就不等：扣着车只会让这一班白白晚点。
      return online ? HoldVerdict.START : HoldVerdict.DISPATCH;
    }
    if (!online || !now.isBefore(task.holdDeadline())) {
      return HoldVerdict.GIVE_UP;
    }
    return HoldVerdict.HOLD;
  }
}
