package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;

/**
 * 一名玩家的新手教程进度：喂入会话快照，按步骤判定玩家是否做到，产出要呈现的事件。本类不依赖服务器对象，便于单测。
 *
 * <p>步骤按 {@link TutorialStep} 的声明顺序进行，每次判定都按此刻的会话重新裁剪：不适用的步骤（仿真等级不同、调度列车、ATO）不出现，
 * 前提类步骤轮到时已满足就略过。已做到或被跳过的步骤不再重复；当前这一步只要还适用就一直等到做到为止。排在当前步骤之前、 原先被略过的前提又不满足了（例如中途关了机），会先回去教它。
 *
 * <p>每一步至少显示 {@link #MIN_STEP_TICKS}，避免已经满足的操作类步骤一闪而过；说明类步骤显示 {@link #INFO_STEP_TICKS} 后自动继续。
 */
public final class TutorialEngine {

  /** 每一步至少显示多久（tick）才判定做到。 */
  static final long MIN_STEP_TICKS = 40L;

  /** 说明类步骤显示多久（tick）后自动继续。 */
  static final long INFO_STEP_TICKS = 240L;

  /** 惰行要在行驶中保持多久（tick）。 */
  static final long COAST_HOLD_TICKS = 20L;

  /** 一步做了这么久（tick）还没做到就再提示一次。 */
  static final long REMINDER_TICKS = 600L;

  /** 车速达到它（格/秒，约 3.6 km/h）才算起步。 */
  static final double TRACTION_SPEED_BPS = 1.0;

  /** 车速高于它（格/秒）才算在行驶中惰行。 */
  static final double COAST_SPEED_BPS = 0.5;

  /** 当前这一步期间的经过。 */
  static final class Progress {
    private long coastingSince = -1L;
    private boolean coasted;
    private boolean usedServiceBrake;

    boolean coasted() {
      return coasted;
    }

    boolean usedServiceBrake() {
      return usedServiceBrake;
    }

    void observe(TutorialSnapshot snapshot, long nowTick) {
      boolean coasting = snapshot.handle() == Notch.N && snapshot.speedBps() > COAST_SPEED_BPS;
      if (!coasting) {
        coastingSince = -1L;
      } else if (coastingSince < 0L) {
        coastingSince = nowTick;
      } else if (nowTick - coastingSince >= COAST_HOLD_TICKS) {
        coasted = true;
      }
      if (snapshot.handle().kind() == Notch.Kind.BRAKE && !snapshot.stopped()) {
        usedServiceBrake = true;
      }
    }
  }

  private final List<TutorialStep> finished = new ArrayList<>();
  private final Set<TutorialStep> unavailable = EnumSet.noneOf(TutorialStep.class);
  private TutorialStep current;
  private String currentKey;
  private long stepStartedTick;
  private long lastReminderTick;
  private Progress progress = new Progress();
  private boolean ended;

  /**
   * 推进一次。第一次调用时开始第一步。
   *
   * @param snapshot 此刻的会话
   * @param nowTick 当前服务器 tick
   */
  public List<TutorialEvent> tick(TutorialSnapshot snapshot, long nowTick) {
    if (ended) {
      return List.of();
    }
    List<TutorialEvent> events = new ArrayList<>();
    if (current != null && relevant(current, snapshot)) {
      progress.observe(snapshot, nowTick);
      if (completed(snapshot, nowTick)) {
        finished.add(current);
        events.add(new TutorialEvent.StepCompleted(current, false));
        current = null;
      }
    }
    TutorialStep next = firstPending(snapshot);
    if (next != current) {
      begin(next, snapshot, nowTick, events);
    } else if (current != null
        && !current.info()
        && current != TutorialStep.FINISH
        && nowTick - lastReminderTick >= REMINDER_TICKS) {
      lastReminderTick = nowTick;
      events.add(new TutorialEvent.Reminder(current, currentKey));
    }
    return events;
  }

  /**
   * 跳过当前这一步（说明类步骤的「继续」也走这里）。跳过开门时关门一并跳过；跳过最后一步即完成教程。
   *
   * @param snapshot 此刻的会话
   */
  public List<TutorialEvent> skip(TutorialSnapshot snapshot, long nowTick) {
    if (ended || current == null) {
      return List.of();
    }
    if (current == TutorialStep.FINISH) {
      ended = true;
      return List.of(new TutorialEvent.Finished());
    }
    List<TutorialEvent> events = new ArrayList<>();
    TutorialStep skipped = current;
    finished.add(skipped);
    if (skipped == TutorialStep.OPEN_DOORS) {
      finished.add(TutorialStep.CLOSE_DOORS);
    }
    events.add(new TutorialEvent.StepCompleted(skipped, true));
    current = null;
    begin(firstPending(snapshot), snapshot, nowTick, events);
    return events;
  }

  /** 这辆车没有车门动画：开关车门的练习不再适用；正在练的话告诉玩家已略过（下一次推进时进入下一步）。 */
  public List<TutorialEvent> doorsUnavailable() {
    if (ended || !unavailable.isEmpty()) {
      return List.of();
    }
    unavailable.add(TutorialStep.OPEN_DOORS);
    unavailable.add(TutorialStep.CLOSE_DOORS);
    if (current == TutorialStep.OPEN_DOORS || current == TutorialStep.CLOSE_DOORS) {
      return List.of(new TutorialEvent.DoorsUnavailable());
    }
    return List.of();
  }

  /** 驾驶会话结束：已到最后一步则教程完成，否则中断。 */
  public List<TutorialEvent> sessionEnded() {
    if (ended) {
      return List.of();
    }
    ended = true;
    return List.of(
        current == TutorialStep.FINISH
            ? new TutorialEvent.Finished()
            : new TutorialEvent.Interrupted());
  }

  /** 当前这一步；还没开始或已结束时为空。 */
  public Optional<TutorialStep> current() {
    return ended ? Optional.empty() : Optional.ofNullable(current);
  }

  /** 教程已完成或中断。 */
  public boolean ended() {
    return ended;
  }

  /** 已做到或被跳过的步骤，按先后。 */
  public List<TutorialStep> finished() {
    return List.copyOf(finished);
  }

  /** 按此刻的会话，还要做的步骤（含当前这一步），按先后。 */
  public List<TutorialStep> pending(TutorialSnapshot snapshot) {
    List<TutorialStep> steps = new ArrayList<>();
    for (TutorialStep step : TutorialStep.values()) {
      if (finished.contains(step) || !relevant(step, snapshot)) {
        continue;
      }
      if (step != current && step.skipsWhenSatisfied(snapshot)) {
        continue;
      }
      steps.add(step);
    }
    return steps;
  }

  private boolean relevant(TutorialStep step, TutorialSnapshot snapshot) {
    return !unavailable.contains(step) && step.relevant(snapshot);
  }

  private boolean completed(TutorialSnapshot snapshot, long nowTick) {
    long elapsed = nowTick - stepStartedTick;
    if (current.info()) {
      return elapsed >= INFO_STEP_TICKS;
    }
    return elapsed >= MIN_STEP_TICKS && current.done(snapshot, progress);
  }

  private TutorialStep firstPending(TutorialSnapshot snapshot) {
    List<TutorialStep> steps = pending(snapshot);
    return steps.isEmpty() ? null : steps.get(0);
  }

  private void begin(
      TutorialStep step, TutorialSnapshot snapshot, long nowTick, List<TutorialEvent> events) {
    current = step;
    progress = new Progress();
    stepStartedTick = nowTick;
    lastReminderTick = nowTick;
    if (step == null) {
      // 结束驾驶一步总是适用，这里只是防御：没有步骤可做时当作完成。
      ended = true;
      events.add(new TutorialEvent.Finished());
      return;
    }
    currentKey = step.key(snapshot);
    events.add(
        new TutorialEvent.StepStarted(
            step, currentKey, finished.size() + 1, finished.size() + pending(snapshot).size()));
  }
}
