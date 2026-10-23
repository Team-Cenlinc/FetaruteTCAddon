package org.fetarute.fetaruteTCAddon.drive.inventory;

/**
 * 右键数据包的判定（驾驶员与车掌共用），时刻取 {@link ClientClock} 的读数：按客户端自己的 tick
 * 量，网络抖动、丢包重传让包挤在一起到也不会把按住误判成重新按下，或把两次右键并成一次。
 *
 * <p>按住右键时客户端每 4 tick（约 200 毫秒）重发一次；同一次右键客户端还会在同一拍里连发几个包（对着实体或方块一个，再加一个使用），读数相同。
 */
public final class UseClicks {

  /** 两个包相隔不到这么久（毫秒，客户端的一拍）算同一次右键。 */
  static final long SAME_CLICK_MILLIS = 50L;

  private UseClicks() {}

  /**
   * 与上一个右键包是同一次右键。
   *
   * @param previous 上一个右键包的时刻；没有时为 {@code null}
   */
  public static boolean sameClick(Long previous, long now) {
    return previous != null && now - previous < SAME_CLICK_MILLIS;
  }

  /**
   * 是新按下的右键：与上一个包相隔超过 {@code gapMillis}（按住不放时的重发不算）。
   *
   * @param previous 上一个右键包的时刻；没有时为 {@code null}
   */
  public static boolean fresh(Long previous, long now, long gapMillis) {
    return previous == null || now - previous > gapMillis;
  }
}
