package org.fetarute.fetaruteTCAddon.drive.crew;

import java.util.UUID;

/**
 * 乘务员与信号员之间的联系：驾驶员或车掌呼叫信号员（车上没有另一名乘务员时，两短铃就是呼叫信号员；也可用命令）。
 *
 * <p>本接口不依赖信号员包；信号员模块启用后由它实现。只在主线程调用。
 */
public interface SignallerLink {

  /** 没有信号员模块：呼叫无人接。 */
  SignallerLink NONE =
      (caller, role, trainName, reason) -> CallResult.of(CallResult.Status.UNAVAILABLE);

  /**
   * 呼叫这列车所在（或正接近、在入口等候）的控制区的信号员。信号员要按原因选对回复。
   *
   * @param caller 呼叫的玩家
   * @param role 驾驶员或车掌
   * @param trainName 列车（当前车名）
   * @param reason 原因
   * @return 有没有人接；没人接时说明原因，调用方据此提示呼叫者
   */
  CallResult call(UUID caller, CallerRole role, String trainName, CallReason reason);

  /**
   * 呼叫会不会有人接，不真的呼叫：两短铃弹出选原因的对话框之前先问，叫不到时直接说明原因、不弹框。
   *
   * @param caller 要呼叫的玩家
   * @param trainName 列车（当前车名）
   * @return 有人接时为 {@code CALLED}（这名乘务员已在等回复时为 {@code PENDING}）与那片控制区；否则同 {@link #call} 的没人接的原因
   */
  default CallResult probe(UUID caller, String trainName) {
    return CallResult.of(CallResult.Status.UNAVAILABLE);
  }

  /**
   * 列车此刻正停在人工控制的控制区入口，等信号员排列进路。驾驶员与车掌的侧边栏据此提示怎么呼叫信号员。
   *
   * @param trainName 列车（当前车名）
   */
  default boolean waiting(String trainName) {
    return false;
  }
}
