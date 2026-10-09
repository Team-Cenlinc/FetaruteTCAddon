package org.fetarute.fetaruteTCAddon.call;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/**
 * 一条还没派出的叫车（存库用）：重启后按它重新排车、按原编号出票。
 *
 * <p>只存“谁在哪个车站、哪块屏、叫了哪个方向”：车源与站台在恢复时重新安排，重启前排的车源此刻未必还在。
 *
 * @param id 叫车编号（也是叫车票的编号）
 * @param playerId 叫车的玩家
 * @param station 车站
 * @param screenPlatforms 叫车时屏幕绑定的站台；空表示全站（统屏或命令）
 * @param directionKey 方向（{@link CallCatalog.CallDirection#key()}）
 * @param lineId 线路
 * @param createdAt 叫车时刻（超时从它算）
 * @param etaSeconds 叫车时估的到站秒数；估不出时为空
 */
public record PendingCallRecord(
    UUID id,
    UUID playerId,
    PidsStationKey station,
    Set<String> screenPlatforms,
    String directionKey,
    UUID lineId,
    Instant createdAt,
    OptionalInt etaSeconds) {

  public PendingCallRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(directionKey, "directionKey");
    Objects.requireNonNull(lineId, "lineId");
    Objects.requireNonNull(createdAt, "createdAt");
    screenPlatforms = screenPlatforms == null ? Set.of() : Set.copyOf(screenPlatforms);
    etaSeconds = etaSeconds == null ? OptionalInt.empty() : etaSeconds;
  }
}
