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
 * 乘务员呼叫信号员：车上没有车掌时驾驶员两短铃的识别，两短铃之后弹框选原因还是直接呼叫（{@link #buzz}，驾驶员与车掌共用），以及同一名乘务员（驾驶员或车掌）
 * 短时间内重复呼叫的合并。本类不依赖服务器对象，只在主线程调用。
 *
 * <p>车上有车掌时，驾驶员的铃由车掌值乘那边识别（一短收到、两短呼叫车掌）；没有车掌时按同样的按法在这里识别，只认两短，一长与一短不作数。 车掌的铃由车掌值乘识别，车上没有驾驶员时两短同样交给
 * {@link #buzz}。同一名乘务员 {@value #COOLDOWN_MILLIS}
 * 毫秒内的重复呼叫只转给信号员一次，其间沿用上一次的结果，免得信号员被刷屏；上一次没人接时不合并，车开近了马上再叫就能叫到。
 */
public final class SignallerCalls {

  /** 同一名乘务员两次呼叫至少相隔多久（毫秒）才再转给信号员。 */
  static final long COOLDOWN_MILLIS = 5_000L;

  /**
   * 一次呼叫的结果。
   *
   * @param result 有没有人接；{@code repeated} 时是上一次的结果
   * @param repeated 冷却期内的重复呼叫：没有再转给信号员
   */
  public record Outcome(CallResult result, boolean repeated) {

    public Outcome {
      Objects.requireNonNull(result, "result");
    }

    /** 这次新转给了信号员、有人接：呼叫者那边响一声（冷却期内的重复呼叫不响）。 */
    public boolean rings() {
      return result.status().reached() && !repeated;
    }
  }

  /** 两短铃之后弹出选原因的对话框。 */
  @FunctionalInterface
  public interface Ask {
    /**
     * @param zone 接呼叫的控制区名
     * @param waitingAtEntry 列车正停在人工控制的控制区入口等信号员排列进路
     */
    void open(String zone, boolean waitingAtEntry);
  }

  /** 把结果告诉呼叫者。 */
  @FunctionalInterface
  public interface Reply {
    /**
     * @param result 有没有人接，没人接的原因
     * @param ring 响一声（见 {@link Outcome#rings()}）
     */
    void tell(CallResult result, boolean ring);
  }

  /** 最近一次转给信号员的呼叫。 */
  private record LastCall(long at, CallResult result, CallReason reason) {}

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
   * 车上没有车掌时驾驶员按了一下铃（丢弃键）。
   *
   * @param at 按下的时刻（客户端时钟的读数）
   * @param longTicks 按住多久算一长（与车掌的铃同一配置）
   * @param doubleTicks 两次短按相隔多久以内算两短
   */
  public void press(UUID driver, long at, int longTicks, int doubleTicks) {
    presses.computeIfAbsent(driver, id -> BuzzerPress.forKey(longTicks, doubleTicks)).press(at);
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
   * 把呼叫转给信号员；冷却期内不再转，沿用上一次的结果。冷却期内写明了别的原因照转（信号员那边换上新原因）。信号员模块出错时当作没人接。
   *
   * @param caller 呼叫的乘务员
   * @param role 驾驶员或车掌
   * @param trainName 乘务员值乘的列车（当前车名）
   * @param reason 原因（两短铃呼叫为一般联系）
   * @param nowMillis 此刻（毫秒，单调时钟）
   */
  public Outcome call(
      UUID caller,
      CallerRole role,
      String trainName,
      CallReason reason,
      long nowMillis,
      SignallerLink link) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(role, "role");
    LastCall last = lastCalls.get(caller);
    CallReason given = reason == null ? CallReason.GENERAL : reason;
    boolean newReason = given != CallReason.GENERAL && last != null && last.reason() != given;
    if (last != null && cooling(last, nowMillis) && !newReason) {
      return new Outcome(last.result(), true);
    }
    CallResult result;
    try {
      result = (link != null ? link : SignallerLink.NONE).call(caller, role, trainName, given);
    } catch (RuntimeException ex) {
      warn.accept("转给信号员的呼叫处理失败: " + trainName + " " + ex);
      result = CallResult.of(CallResult.Status.UNAVAILABLE);
    }
    if (result.status().reached()) {
      lastCalls.put(caller, new LastCall(nowMillis, result, given));
    } else {
      lastCalls.remove(caller);
    }
    return new Outcome(result, false);
  }

  /**
   * 乘务员在车上按了两短铃（车掌也可点菜单的呼叫按钮）：行驶中直接按一般联系呼叫，免得对话框挡住手柄；停着时先问叫不叫得到，叫得到就弹框选原因（选了再调 {@link
   * #call}），叫不到直接说明原因、不弹框。
   *
   * @param caller 呼叫的乘务员
   * @param role 驾驶员或车掌
   * @param trainName 乘务员值乘的列车（当前车名）
   * @param stopped 列车停着
   * @param nowMillis 此刻（毫秒，单调时钟）
   * @param ask 停着且叫得到时弹框
   * @param reply 行驶中呼叫的结果，或停着时叫不到的原因
   */
  public void buzz(
      UUID caller,
      CallerRole role,
      String trainName,
      boolean stopped,
      long nowMillis,
      SignallerLink link,
      Ask ask,
      Reply reply) {
    if (!stopped) {
      Outcome outcome = call(caller, role, trainName, CallReason.GENERAL, nowMillis, link);
      reply.tell(outcome.result(), outcome.rings());
      return;
    }
    CallResult reach = probe(caller, trainName, link);
    if (!reach.status().reached()) {
      reply.tell(reach, false);
      return;
    }
    ask.open(reach.zones().isEmpty() ? "" : reach.zones().get(0), waiting(trainName, link));
  }

  /** 列车正停在人工控制的控制区入口等候（见 {@link SignallerLink#waiting}）。信号员模块出错时当作不在等。 */
  private boolean waiting(String trainName, SignallerLink link) {
    try {
      return (link != null ? link : SignallerLink.NONE).waiting(trainName);
    } catch (RuntimeException ex) {
      warn.accept("查询列车是否在控制区入口等候失败: " + trainName + " " + ex);
      return false;
    }
  }

  /**
   * 呼叫会不会有人接，不真的呼叫（见 {@link SignallerLink#probe}）。信号员模块出错时当作没人接。
   *
   * @param caller 要呼叫的乘务员
   * @param trainName 乘务员值乘的列车（当前车名）
   */
  public CallResult probe(UUID caller, String trainName, SignallerLink link) {
    Objects.requireNonNull(caller, "caller");
    try {
      return (link != null ? link : SignallerLink.NONE).probe(caller, trainName);
    } catch (RuntimeException ex) {
      warn.accept("查询信号员能否接呼叫失败: " + trainName + " " + ex);
      return CallResult.of(CallResult.Status.UNAVAILABLE);
    }
  }

  private static boolean cooling(LastCall last, long nowMillis) {
    long elapsed = nowMillis - last.at();
    return elapsed >= 0L && elapsed < COOLDOWN_MILLIS;
  }
}
