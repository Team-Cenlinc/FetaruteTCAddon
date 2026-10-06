package org.fetarute.fetaruteTCAddon.drive.session;

/**
 * 驾驶员按 Shift 离座的防误操作：行驶中一律拦下；停稳且有未结束的任务时须在两秒内再按一次；其余直接放行。
 *
 * <p>中文输入法常用 Shift 切换中英文，驾驶员容易误按。按住不放时离座请求可能每 tick 都来一次，只有新按下的一次才算“再按一次”： 潜行键重新按下，或与上一次离座请求隔了至少一个
 * tick。本类不依赖服务器对象。
 */
public final class SeatExitGuard {

  /** 第一次按下后，多少 tick 内再按一次才离座。 */
  static final long CONFIRM_WINDOW_TICKS = 40L;

  private static final long NEVER = Long.MIN_VALUE / 2;

  /** 判定结果。 */
  public enum Decision {
    /** 放行离座。 */
    ALLOW,
    /** 行驶中，拦下并提示。 */
    MOVING,
    /** 有未结束的任务，拦下并提示再按一次。 */
    CONFIRM,
    /** 同一次按键的后续请求：照样拦下，不再重复提示。 */
    QUIET
  }

  private long armedTick = NEVER;
  private long allowedTick = NEVER;
  private long lastAttemptTick = NEVER;
  private long lastAttemptSneakTick = NEVER;

  /**
   * 判定一次玩家发起的离座。
   *
   * @param leavingAllowed 此刻本就允许离座（折返换端途中）
   * @param moving 列车在行驶
   * @param unfinishedTask 驾驶员有未结束的任务（离座即按放弃处理）
   * @param lastSneakTick 最近一次按下潜行键的 tick
   * @param nowTick 当前 tick
   */
  public Decision decide(
      boolean leavingAllowed,
      boolean moving,
      boolean unfinishedTask,
      long lastSneakTick,
      long nowTick) {
    if (nowTick == allowedTick) {
      // 同一 tick 里重复来的请求（同一次离座）：已放行的照样放行。
      return Decision.ALLOW;
    }
    boolean newPress = nowTick - lastAttemptTick > 1 || lastSneakTick > lastAttemptSneakTick;
    lastAttemptTick = nowTick;
    lastAttemptSneakTick = lastSneakTick;
    if (leavingAllowed || (!moving && !unfinishedTask)) {
      return allow(nowTick);
    }
    if (moving) {
      armedTick = NEVER;
      return newPress ? Decision.MOVING : Decision.QUIET;
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
