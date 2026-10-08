package org.fetarute.fetaruteTCAddon.drive.session;

/**
 * 驾驶员按 Shift 离座的防误操作：行驶中一律拦下；停稳且有未结束的任务时须在两秒内再按一次；其余直接放行。
 *
 * <p>中文输入法常用 Shift 切换中英文，驾驶员容易误按。按住不放时离座请求可能接连不断地来，只有新按下的一次才算“再按一次”：收得到潜行键事件时以潜行键重新按下为准；
 * 收不到时（旧版服务端）退而看离座请求之间是否隔了至少一个 tick。本类不依赖服务器对象。
 */
public final class SeatExitGuard {

  /** 第一次按下后，多少 tick 内再按一次才离座。 */
  static final long CONFIRM_WINDOW_TICKS = 40L;

  /** 行驶中拦下时，两次提示至少隔多少 tick（连续误按不刷屏）。 */
  static final long MOVING_NOTICE_TICKS = 100L;

  private static final long NEVER = Long.MIN_VALUE / 2;

  /** 判定结果。 */
  public enum Decision {
    /** 放行离座。 */
    ALLOW,
    /** 行驶中，拦下并提示（连续误按时隔一段时间才再提示）。 */
    MOVING,
    /** 有未结束的任务，拦下并提示再按一次。 */
    CONFIRM,
    /** 拦下但不提示：同一次按键的后续请求，或行驶中刚提示过。 */
    QUIET
  }

  private long armedTick = NEVER;
  private long allowedTick = NEVER;
  private long movingNoticeTick = NEVER;
  private long lastAttemptTick = NEVER;
  private long lastAttemptSneakTick = NEVER;

  /**
   * 判定一次玩家发起的离座。
   *
   * @param leavingAllowed 此刻本就允许离座（折返换端途中）
   * @param moving 列车在行驶
   * @param unfinishedTask 驾驶员有未结束的任务（离座即按放弃处理）
   * @param lastSneakTick 最近一次按下潜行键的 tick
   * @param sneakEdges 收得到潜行键按下、松开的事件（{@code lastSneakTick} 只在重新按下时变）
   * @param nowTick 当前 tick
   */
  public Decision decide(
      boolean leavingAllowed,
      boolean moving,
      boolean unfinishedTask,
      long lastSneakTick,
      boolean sneakEdges,
      long nowTick) {
    if (nowTick == allowedTick) {
      // 同一 tick 里重复来的请求（同一次离座）：已放行的照样放行。
      return Decision.ALLOW;
    }
    boolean newPress =
        sneakEdges ? lastSneakTick > lastAttemptSneakTick : nowTick - lastAttemptTick > 1;
    lastAttemptTick = nowTick;
    lastAttemptSneakTick = lastSneakTick;
    if (leavingAllowed || (!moving && !unfinishedTask)) {
      return allow(nowTick);
    }
    if (moving) {
      armedTick = NEVER;
      if (!newPress || nowTick - movingNoticeTick < MOVING_NOTICE_TICKS) {
        return Decision.QUIET;
      }
      movingNoticeTick = nowTick;
      return Decision.MOVING;
    }
    if (!newPress) {
      return Decision.QUIET;
    }
    if (nowTick - armedTick <= CONFIRM_WINDOW_TICKS) {
      return allow(nowTick);
    }
    armedTick = nowTick;
    return Decision.CONFIRM;
  }

  private Decision allow(long nowTick) {
    armedTick = NEVER;
    allowedTick = nowTick;
    return Decision.ALLOW;
  }
}
