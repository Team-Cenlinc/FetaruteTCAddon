package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 单个列车/票据的 ETA 结果。
 *
 * <p>该结构为 HUD/内部占位符提供强类型输出，避免 UI 自行拼接逻辑。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>{@link #arriving()} 采用你们约定的语义：Approaching 等价于 Arriving。
 *   <li>{@link #etaEpochMillis()} 为目标到达时间的 epoch millis（便于 UI 自行格式化）。
 *   <li>{@link #travelSec()}/{@link #dwellSec()}/{@link #waitSec()} 为分解项（调试/未来 delay）。
 * </ul>
 */
public record EtaResult(
    boolean arriving,
    String statusText,
    long etaEpochMillis,
    int etaMinutesRounded,
    int travelSec,
    int dwellSec,
    int waitSec,
    List<EtaReason> reasons,
    EtaConfidence confidence) {

  public EtaResult {
    Objects.requireNonNull(statusText, "statusText");
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    Objects.requireNonNull(confidence, "confidence");
  }

  public Instant eta() {
    return Instant.ofEpochMilli(etaEpochMillis);
  }

  public static EtaResult unavailable(String statusText, List<EtaReason> reasons) {
    return new EtaResult(
        false,
        statusText == null ? "N/A" : statusText,
        0L,
        -1,
        0,
        0,
        0,
        reasons,
        EtaConfidence.LOW);
  }
}
