package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;

/**
 * 列车当前的扣停（非例行停车，或例行停车已超时）。
 *
 * <p>由 {@link EtaService#currentHold} 统一判定：ETA 顺延与公开 API 扣停事件读的是同一个结果，口径不会分叉。
 *
 * @param since 本次扣停开始时刻（跨停车状态替换连续计算；停站超时为超时开始的时刻）
 * @param reasonCode 当前停车原因代码
 * @param detail 诊断明细
 * @param blockers 当前阻塞资源与持有者
 * @param overstay true 表示例行停车（停站/门控）超出了正常站内用时
 */
public record TrainHold(
    Instant since,
    String reasonCode,
    String detail,
    List<RuntimeStopState.Blocker> blockers,
    boolean overstay) {

  public TrainHold {
    Objects.requireNonNull(since, "since");
    reasonCode = reasonCode == null ? "UNKNOWN" : reasonCode;
    detail = detail == null ? "" : detail;
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
  }
}
