package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

/**
 * 驾驶员站停的停车窗口：列车离停车点多近算停准、多近可以开门、越过多远算越站。
 *
 * <p>窗口来自驾驶配置，随驾驶链路交给每一次停站，站台、防护、提示与评分都用这一份。
 *
 * @param accurateBlocks 偏移在这个范围内算停准（格）
 * @param acceptBlocks 偏移在这个范围内可以开门（格）；未到更多时须前移
 * @param skipBlocks 越过停车点超过这么远（格）算越站：本站不停，列车继续开；不超过时仍停站开门，按停过头计
 */
public record StopWindow(double accurateBlocks, double acceptBlocks, double skipBlocks) {

  /** 默认窗口：网络延迟与服务器 tick 会让同样的操作停偏零点几格，留出余量。 */
  public static final StopWindow DEFAULTS = new StopWindow(2.5, 6.0, 12.0);

  public StopWindow {
    if (!valid(accurateBlocks, acceptBlocks, skipBlocks)) {
      throw new IllegalArgumentException(
          "停车窗口须满足 0 < 停准 < 可开门 < 越站: "
              + accurateBlocks
              + " / "
              + acceptBlocks
              + " / "
              + skipBlocks);
    }
  }

  /** 三个范围是否构成合法窗口（0 &lt; 停准 &lt; 可开门 &lt; 越站）。 */
  public static boolean valid(double accurateBlocks, double acceptBlocks, double skipBlocks) {
    return Double.isFinite(accurateBlocks)
        && Double.isFinite(acceptBlocks)
        && Double.isFinite(skipBlocks)
        && accurateBlocks > 0.0
        && acceptBlocks > accurateBlocks
        && skipBlocks > acceptBlocks;
  }

  /** 偏移对应的停车结果。量不出偏移（{@code NaN}）时按可接受处理，不挡住停站。 */
  public StopAlignment.Outcome classify(double offsetBlocks) {
    if (Double.isNaN(offsetBlocks)) {
      return StopAlignment.Outcome.ACCEPTED;
    }
    double abs = Math.abs(offsetBlocks);
    if (abs <= accurateBlocks) {
      return StopAlignment.Outcome.ACCURATE;
    }
    if (abs <= acceptBlocks) {
      return StopAlignment.Outcome.ACCEPTED;
    }
    if (offsetBlocks < 0.0) {
      return StopAlignment.Outcome.SHORT;
    }
    return offsetBlocks > skipBlocks
        ? StopAlignment.Outcome.SKIPPED
        : StopAlignment.Outcome.OVERRUN;
  }
}
