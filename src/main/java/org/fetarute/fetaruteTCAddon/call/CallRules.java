package org.fetarute.fetaruteTCAddon.call;

import java.util.OptionalInt;

/**
 * 一个方向现在能不能叫车。
 *
 * <p>先看有没有车可派，再看同方向是不是已经有叫来的车、下一班是不是很快就到，最后才是线路上限与个人冷却： 玩家最想知道的是“车快来了没有”，其次才是“为什么叫不了”。
 */
public final class CallRules {

  private CallRules() {}

  /** 判定结果。 */
  public enum Outcome {
    /** 可以叫车。 */
    AVAILABLE,
    /** 没有能派的车：首站没有待命车，也没有从车库出车的交路。 */
    NO_SOURCE,
    /** 同方向已有叫来的车在路上或叫车还没派出。 */
    ALREADY_CALLED,
    /** 下一班很快就到，不用叫；或叫来的车比下一班早不了多少（见 {@link Input#minLeadMinutes()}）。 */
    NEXT_TRAIN_SOON,
    /** 线路上叫来的车已达上限。 */
    LINE_LIMIT,
    /** 玩家叫车太频繁。 */
    COOLDOWN
  }

  /**
   * 判定输入。
   *
   * @param sourceAvailable 有没有车可派
   * @param calledTrainMinutes 同方向叫来的车（或还没派出的叫车）几分钟后到；没有时为空
   * @param nextTrainMinutes 同方向下一班（可乘坐的）几分钟后到；看不到时为空
   * @param minWaitMinutes 下一班超过这么多分钟才能叫
   * @param activeCalls 线路上叫来的车与还没派出的叫车数
   * @param maxCalls 线路叫车车数上限
   * @param cooldownSeconds 玩家还要等几秒才能再叫
   * @param estimateMinutes 叫车后约几分钟到站；估不出时为空
   * @param minLeadMinutes 叫来的车至少要比下一班早到几分钟；早得不够多时只会把下一班压在后面晚点
   */
  public record Input(
      boolean sourceAvailable,
      OptionalInt calledTrainMinutes,
      OptionalInt nextTrainMinutes,
      int minWaitMinutes,
      int activeCalls,
      int maxCalls,
      long cooldownSeconds,
      OptionalInt estimateMinutes,
      int minLeadMinutes) {}

  /**
   * 判定结果与要告诉玩家的数。
   *
   * @param outcome 结果
   * @param minutes 可叫时是预计到站分钟；已叫、下一班快到时是那趟车几分钟后到
   * @param seconds 冷却还剩几秒
   */
  public record Verdict(Outcome outcome, OptionalInt minutes, long seconds) {

    public boolean callable() {
      return outcome == Outcome.AVAILABLE;
    }
  }

  /** 按输入判定。 */
  public static Verdict evaluate(Input input) {
    if (!input.sourceAvailable()) {
      return new Verdict(Outcome.NO_SOURCE, OptionalInt.empty(), 0L);
    }
    if (input.calledTrainMinutes().isPresent()) {
      return new Verdict(Outcome.ALREADY_CALLED, input.calledTrainMinutes(), 0L);
    }
    OptionalInt next = input.nextTrainMinutes();
    if (next.isPresent() && next.getAsInt() <= input.minWaitMinutes()) {
      return new Verdict(Outcome.NEXT_TRAIN_SOON, next, 0L);
    }
    OptionalInt estimate = input.estimateMinutes();
    if (next.isPresent()
        && estimate.isPresent()
        && estimate.getAsInt() + Math.max(0, input.minLeadMinutes()) > next.getAsInt()) {
      return new Verdict(Outcome.NEXT_TRAIN_SOON, next, 0L);
    }
    if (input.activeCalls() >= input.maxCalls()) {
      return new Verdict(Outcome.LINE_LIMIT, OptionalInt.empty(), 0L);
    }
    if (input.cooldownSeconds() > 0L) {
      return new Verdict(Outcome.COOLDOWN, OptionalInt.empty(), input.cooldownSeconds());
    }
    return new Verdict(Outcome.AVAILABLE, input.estimateMinutes(), 0L);
  }
}
