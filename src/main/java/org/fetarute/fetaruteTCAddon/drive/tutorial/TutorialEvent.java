package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.Objects;

/** 教程引擎产生的事件，由驾驶教程的服务器侧逐条呈现给玩家（聊天、副标题、提示音）。 */
public sealed interface TutorialEvent {

  /**
   * 开始新的一步。
   *
   * @param step 步骤
   * @param key 文案键（含调度列车后缀）
   * @param index 第几步（从 1 起）
   * @param total 按此刻的会话估计的总步数
   */
  record StepStarted(TutorialStep step, String key, int index, int total) implements TutorialEvent {
    public StepStarted {
      Objects.requireNonNull(step, "step");
      Objects.requireNonNull(key, "key");
    }
  }

  /**
   * 一步结束。
   *
   * @param step 步骤
   * @param skipped 是玩家跳过的，不是做到的
   */
  record StepCompleted(TutorialStep step, boolean skipped) implements TutorialEvent {
    public StepCompleted {
      Objects.requireNonNull(step, "step");
    }
  }

  /**
   * 这一步做了很久还没做到：再提示一次（只用副标题，不刷聊天）。
   *
   * @param step 步骤
   * @param key 文案键
   */
  record Reminder(TutorialStep step, String key) implements TutorialEvent {
    public Reminder {
      Objects.requireNonNull(step, "step");
      Objects.requireNonNull(key, "key");
    }
  }

  /** 这辆车没有车门动画：开关车门的练习略过。 */
  record DoorsUnavailable() implements TutorialEvent {}

  /** 教程完成。 */
  record Finished() implements TutorialEvent {}

  /** 驾驶在教程完成之前结束，教程中断。 */
  record Interrupted() implements TutorialEvent {}
}
