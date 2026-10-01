package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 站牌（PIDS/列表）输出：一组按站点聚合的 ETA 行。
 *
 * <p>该结果仅反映运行中列车、已生成票据与未出票预测，不等同于完整时刻表。
 */
public record BoardResult(List<BoardRow> rows) {

  public BoardResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  /**
   * 站牌行。
   *
   * @param lineName 列车到本站时所属线路的代码
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @param destination 主目的地显示名（运营终点，回库车越过运营终点后为“回库”）
   * @param destinationId 主目的地 ID
   * @param endRoute 线路终点（EOR）显示名
   * @param endRouteId 线路终点 ID
   * @param endOperation 运营终点（EOP）显示名
   * @param endOperationId 运营终点 ID
   * @param platform 站台号；无法解析时为 {@code -}
   * @param statusText 状态文本（英文短语，供命令与旧消费者显示）
   * @param reasons 诊断标签
   * @param eta 预计到达或通过本站的时刻；已在站时为查询时刻
   * @param phase 所处阶段
   * @param stopIndex 本站停靠序号（交路节点的 0 起下标）
   * @param passing 本站通过不停
   * @param terminating 本站是运营终点（乘客在此下车，车次不再载客）
   * @param outOfService 本站已越过运营终点，列车在回库途中
   * @param trainName 运行中列车的列车名；票据与预测为空
   * @param delaySeconds 按表运行时相对计划的偏差（正数为晚点）：运行中为到达本站，已在站为发车，未发车为起点发车；不按表运行时为空
   */
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
      List<EtaReason> reasons,
      Instant eta,
      BoardPhase phase,
      int stopIndex,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      OptionalLong delaySeconds) {
    public BoardRow {
      Objects.requireNonNull(lineName, "lineName");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(destination, "destination");
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      Objects.requireNonNull(endRoute, "endRoute");
      endRouteId = endRouteId == null ? Optional.empty() : endRouteId;
      Objects.requireNonNull(endOperation, "endOperation");
      endOperationId = endOperationId == null ? Optional.empty() : endOperationId;
      Objects.requireNonNull(platform, "platform");
      Objects.requireNonNull(statusText, "statusText");
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      Objects.requireNonNull(eta, "eta");
      Objects.requireNonNull(phase, "phase");
      trainName = trainName == null ? Optional.empty() : trainName;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    }
  }
}
