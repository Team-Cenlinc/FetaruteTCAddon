package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 站台屏的一行。
 *
 * <p>只描述事实，不含显示文字：状态词、颜色、倒计时由渲染侧按当前时间与外观决定。
 *
 * @param status 状态
 * @param lineName 列车到本站时所属线路的代码
 * @param routeId 交路 ID（{@code 运营商:线路:交路}）
 * @param destination 主目的地（站码；回库车越过运营终点后为“回库”）
 * @param destinationId 主目的地 ID（{@code 运营商:站码}），用于查站名
 * @param platform 站台号；无法解析或站台待定时为 {@code -}
 * @param expectedAt 预计到达或通过本站的时刻；已在站为快照时刻；取消行为计划到达
 * @param delaySeconds 相对计划的偏差（正数为晚点）；不按表运行或已取消时为空
 * @param stopSequence 本站停靠序号（交路节点的 0 起下标）；未知时为 -1
 * @param passing 本站通过不停
 * @param terminating 本站是运营终点
 * @param outOfService 列车在回库途中
 * @param trainName 运行中列车的列车名；票据、预测与取消行为空
 * @param platformPending 站台待定：本站是动态站台停靠，列车还没有选台
 * @param platformCandidates 站台待定时可能停靠的站台，按站台号升序；站台已定或候选未知时为空
 */
public record PidsRow(
    Status status,
    String lineName,
    String routeId,
    String destination,
    Optional<String> destinationId,
    String platform,
    Instant expectedAt,
    OptionalLong delaySeconds,
    int stopSequence,
    boolean passing,
    boolean terminating,
    boolean outOfService,
    Optional<String> trainName,
    boolean platformPending,
    List<String> platformCandidates) {

  public PidsRow {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(lineName, "lineName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(destination, "destination");
    destinationId = destinationId == null ? Optional.empty() : destinationId;
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(expectedAt, "expectedAt");
    delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    trainName = trainName == null ? Optional.empty() : trainName;
    platformCandidates = platformCandidates == null ? List.of() : List.copyOf(platformCandidates);
  }

  /** 站台已定的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        false,
        List.of());
  }

  /** 列车会停在这些站台之一：站台已定时看站台号，待定时看候选。 */
  public boolean mayUse(Collection<String> platforms) {
    return platforms.contains(platform)
        || (platformPending && platformCandidates.stream().anyMatch(platforms::contains));
  }

  /** 行状态，按离本站由远到近排列，取消单列。 */
  public enum Status {
    /** 计划：尚未出票的班次。 */
    PLANNED,
    /** 已出票、尚未发车。 */
    PENDING,
    /** 运行中。 */
    EN_ROUTE,
    /** 即将进站或通过。 */
    ARRIVING,
    /** 停在本站。 */
    BOARDING,
    /** 时刻表已取消，本站不再停。 */
    CANCELLED
  }
}
