package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 站牌（PIDS/列表）输出：一组按站点聚合的 ETA 行。
 *
 * <p>该结果仅反映运行中列车、已生成票据与未出票预测，不等同于完整时刻表。
 */
public record BoardResult(List<BoardRow> rows) {

  public BoardResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public record BoardRow(
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String endRoute,
      Optional<String> endRouteId,
      String endOperation,
      Optional<String> endOperationId,
      String platform,
      String statusText,
      List<EtaReason> reasons) {
    public BoardRow {
      Objects.requireNonNull(lineName, "lineName");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(destination, "destination");
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      Objects.requireNonNull(destinationId, "destinationId");
      Objects.requireNonNull(endRoute, "endRoute");
      endRouteId = endRouteId == null ? Optional.empty() : endRouteId;
      Objects.requireNonNull(endRouteId, "endRouteId");
      Objects.requireNonNull(endOperation, "endOperation");
      endOperationId = endOperationId == null ? Optional.empty() : endOperationId;
      Objects.requireNonNull(endOperationId, "endOperationId");
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(statusText, "statusText");
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }
}
