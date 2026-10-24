package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import org.fetarute.fetaruteTCAddon.drive.guard.BuzzerPress;

/**
 * 驾驶员呼叫信号员：车上没有车掌时两短铃的识别，以及同一名驾驶员短时间内重复呼叫的合并。本类不依赖服务器对象，只在主线程调用。
 *
 * <p>车上有车掌时，驾驶员的铃由车掌值乘那边识别（一短收到、两短呼叫车掌）；没有车掌时按同样的按法在这里识别，只认两短，一长与一短不作数。 同一名驾驶员 {@value
 * #COOLDOWN_MILLIS} 毫秒内的重复呼叫只转给信号员一次，其间沿用上一次的结果告诉驾驶员，免得信号员被刷屏。
 */
public final class SignallerCalls {

  /** 同一名驾驶员两次呼叫至少相隔多久（毫秒）才再转给信号员。 */
  static final long COOLDOWN_MILLIS = 5_000L;

  /**
   * 一次呼叫的结果。
   *
   * @param reached 有信号员值守的控制区收到了呼叫
   * @param repeated 冷却期内的重复呼叫：没有再转给信号员，{@code reached} 是上一次的结果
   */
  public record Outcome(boolean reached, boolean repeated) {}

  /** 最近一次转给信号员的呼叫。 */
  private record LastCall(long at, boolean reached, CallReason reason) {}

  private final Consumer<String> warn;
  private final Map<UUID, BuzzerPress> presses = new HashMap<>();
  private final Map<UUID, LastCall> lastCalls = new HashMap<>();

  /**
   * @param warn 信号员模块处理呼叫出错时的日志
   */
  public SignallerCalls(Consumer<String> warn) {
    this.warn = warn != null ? warn : message -> {};
  }

  /**
   * 车上没有车掌时驾驶员按了一下铃。
   *
   * @param at 按下的时刻（客户端时钟的读数）
   * @param longTicks 按住多久算一长（与车掌的铃同一配置）
   * @param doubleTicks 两次短按相隔多久以内算两短
   */
  public void press(UUID driver, long at, int longTicks, int doubleTicks) {
    presses.computeIfAbsent(driver, id -> new BuzzerPress(longTicks, doubleTicks)).press(at);
  }

  /**
   * 定时调用：交出按成两短的驾驶员，并忘掉已过冷却期的呼叫。
   *
   * @param clock 驾驶员此刻的客户端时钟读数
   * @param driving 驾驶员是否仍在驾驶；不在驾驶的忘掉按到一半的铃
   * @param nowMillis 此刻（毫秒，与 {@link #call} 同一时钟）
   * @return 呼叫信号员的驾驶员
   */
  public List<UUID> tick(ToLongFunction<UUID> clock, Predicate<UUID> driving, long nowMillis) {
    if (!lastCalls.isEmpty()) {
      lastCalls.values().removeIf(last -> !cooling(last, nowMillis));
    }
    if (presses.isEmpty()) {
      return List.of();
    }
    List<UUID> calls = new ArrayList<>();
    Iterator<Map.Entry<UUID, BuzzerPress>> it = presses.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<UUID, BuzzerPress> entry = it.next();
      if (!driving.test(entry.getKey())) {
        it.remove();
        continue;
      }
      entry
          .getValue()
          .tick(clock.applyAsLong(entry.getKey()))
          .filter(kind -> kind == BuzzerPress.Kind.CALL)
          .ifPresent(kind -> calls.add(entry.getKey()));
    }
    return calls;
  }

  /**
   * 把呼叫转给信号员；冷却期内不再转，沿用上一次的结果。信号员模块出错时当作没人接。
   *
   * @param trainName 驾驶员驾驶的列车（当前车名）
   * @param nowMillis 此刻（毫秒，单调时钟）
   */
  public Outcome call(UUID driver, String trainName, long nowMillis, SignallerLink link) {
    return call(driver, trainName, CallReason.GENERAL, nowMillis, link);
  }

  /**
   * 带原因的呼叫；冷却期内不再转，沿用上一次的结果。
   *
   * @param reason 原因（两短铃呼叫为一般联系）
   */
  public Outcome call(
      UUID driver, String trainName, CallReason reason, long nowMillis, SignallerLink link) {
    Objects.requireNonNull(driver, "driver");
    LastCall last = lastCalls.get(driver);
    CallReason given = reason == null ? CallReason.GENERAL : reason;
    // 冷却期内写明了别的原因：照转（信号员那边替换先前的原因）
    boolean newReason = given != CallReason.GENERAL && last != null && last.reason() != given;
    if (last != null && cooling(last, nowMillis) && !newReason) {
      return new Outcome(last.reached(), true);
    }
    boolean reached;
    try {
      reached = (link != null ? link : SignallerLink.NONE).call(driver, trainName, given);
    } catch (RuntimeException ex) {
      warn.accept("转给信号员的呼叫处理失败: " + trainName + " " + ex);
      reached = false;
    }
    lastCalls.put(driver, new LastCall(nowMillis, reached, given));
    return new Outcome(reached, false);
  }

  private static boolean cooling(LastCall last, long nowMillis) {
    long elapsed = nowMillis - last.at();
    return elapsed >= 0L && elapsed < COOLDOWN_MILLIS;
  }
}
