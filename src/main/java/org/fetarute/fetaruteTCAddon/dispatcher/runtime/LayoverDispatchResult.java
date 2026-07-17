package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Optional;

/**
 * 折返派发事务结果。
 *
 * <p>成功结果必须使用 {@link #trainName()} 中的 committed owner 写入发车后的生命周期标签；折返可能在提交期间更新 TrainCarts
 * 列车名，继续使用候选旧名称会让 {@code FTA_OP_TRIPS}/{@code FTA_SPAWN_GROUP} 等状态静默丢失。
 *
 * @param dispatched TrainCarts 已接受 launch action，且候选已完成提交
 * @param trainName 失败时尽量提供当前安全 owner，尚无候选时为空；成功时恒为已提交的新列车名
 * @param reason 稳定诊断原因
 */
public record LayoverDispatchResult(boolean dispatched, Optional<String> trainName, String reason) {

  public LayoverDispatchResult {
    trainName =
        trainName == null
            ? Optional.empty()
            : trainName.map(String::trim).filter(value -> !value.isBlank());
    reason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
    if (dispatched && trainName.isEmpty()) {
      throw new IllegalArgumentException("成功的折返派发结果必须包含 committed trainName");
    }
  }

  public static LayoverDispatchResult success(String trainName) {
    return new LayoverDispatchResult(true, Optional.ofNullable(trainName), "dispatched");
  }

  public static LayoverDispatchResult failed(String trainName, String reason) {
    return new LayoverDispatchResult(false, Optional.ofNullable(trainName), reason);
  }

  public static LayoverDispatchResult failed(String reason) {
    return new LayoverDispatchResult(false, Optional.empty(), reason);
  }
}
