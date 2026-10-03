package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

/**
 * 驾驶员站停的停车窗口：列车中心离停车点多近算停准、多近可以开门。
 *
 * <p>窗口来自驾驶配置，随驾驶链路交给每一次停站，站台、防护、提示与评分都用这一份。
 *
 * @param accurateBlocks 偏移在这个范围内算停准（格）
 * @param acceptBlocks 偏移在这个范围内可以开门（格）；未到更多时须前移，越过更多时防护强制停车
 */
public record StopWindow(double accurateBlocks, double acceptBlocks) {

  /** 默认窗口：网络延迟与服务器 tick 会让同样的操作停偏零点几格，留出余量。 */
  public static final StopWindow DEFAULTS = new StopWindow(2.5, 6.0);

  public StopWindow {
    if (!valid(accurateBlocks, acceptBlocks)) {
      throw new IllegalArgumentException(
          "停车窗口须满足 0 < 停准 < 可开门: " + accurateBlocks + " / " + acceptBlocks);
    }
  }

  /** 两个范围是否构成合法窗口（0 &lt; 停准 &lt; 可开门）。 */
  public static boolean valid(double accurateBlocks, double acceptBlocks) {
    return Double.isFinite(accurateBlocks)
        && Double.isFinite(acceptBlocks)
        && accurateBlocks > 0.0
        && acceptBlocks > accurateBlocks;
  }

  /** 偏移所在的窗口。量不出偏移（{@code NaN}）时按可接受处理，不挡住停站。 */
  public StopAlignment.Window classify(double offsetBlocks) {
    if (Double.isNaN(offsetBlocks)) {
      return StopAlignment.Window.ACCEPTED;
    }
    double abs = Math.abs(offsetBlocks);
    if (abs <= accurateBlocks) {
      return StopAlignment.Window.ACCURATE;
    }
    if (abs <= acceptBlocks) {
      return StopAlignment.Window.ACCEPTED;
    }
    return offsetBlocks < 0.0 ? StopAlignment.Window.SHORT : StopAlignment.Window.OVERRUN;
  }
}
