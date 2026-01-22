package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.util.List;
import java.util.Objects;

/**
 * 站牌（PIDS/列表）输出：一组按站点聚合的 ETA 行。
 *
 * <p>该结果仅反映运行中列车与“已生成但未发车”的票据，不等同于完整时刻表。
 */
public record BoardResult(List<BoardRow> rows) {

  public BoardResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public record BoardRow(
      String lineName,
      String destination,
      String platform,
      String statusText,
      List<EtaReason> reasons) {
    public BoardRow {
      Objects.requireNonNull(lineName, "lineName");
      Objects.requireNonNull(destination, "destination");
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(statusText, "statusText");
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }
}
