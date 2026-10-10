package org.fetarute.fetaruteTCAddon.api.call;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * 叫车 API（1.14.0）：查询车站上能叫车的方向与此刻能不能叫，查询叫车单。
 *
 * <p>叫车由玩家在站台屏或 {@code /fta call} 发起，本 API 不代玩家叫车。叫车的进展用 {@code
 * org.fetarute.fetaruteTCAddon.api.event} 里的 {@code TrainCall*} 事件跟进：叫车、派出、到站、没派出就结束。
 * 管理员加开的车（{@code /fta depot spawn … --extra}）不是叫车，不在本 API 与这些事件里。
 *
 * <p>线程：{@link #directionsAt} 只能在服务器主线程调用（要排车源），否则抛出 {@link IllegalStateException}；{@link
 * #callOf}、{@link #callsOf} 可在任意线程调用。所有返回值都是不可变快照。
 *
 * <p>叫车功能未加载时 {@link #enabled()} 为 {@code false}，查询返回空。
 */
public interface CallApi {

  /** 叫车功能是否可用。 */
  boolean enabled();

  /**
   * 车站上能叫车的方向（与叫车对话框同一口径，只列开放叫车的线路），及此刻能不能叫。不看个人冷却。主线程。
   *
   * @param operatorCode 运营商代码（不分大小写）
   * @param stationCode 站码（不分大小写）
   */
  List<Direction> directionsAt(String operatorCode, String stationCode);

  /** 叫车单：还没派出的，或已派出、叫来的车还在的；查不到时为空。 */
  Optional<CallView> callOf(UUID callId);

  /** 玩家还没派出的叫车，与本次运行中派出、叫来的车还在的叫车。 */
  List<CallView> callsOf(UUID playerId);

  /** 一个方向此刻能不能叫。 */
  enum Availability {
    /** 可以叫车。 */
    AVAILABLE,
    /** 没有能派的车。 */
    NO_SOURCE,
    /** 同方向已有叫来的车在路上或叫车还没派出。 */
    ALREADY_CALLED,
    /** 下一班很快就到，或叫来的车比下一班早不了多少。 */
    NEXT_TRAIN_SOON,
    /** 线路上叫来的车已达上限。 */
    LINE_LIMIT
  }

  /**
   * 车站上能叫车的一个方向：同线路、同运营类型、同终点、同站台。
   *
   * @param key 方向标识（叫车单里的 {@link CallView#directionKey()} 与它一致）
   * @param lineId 线路
   * @param lineCode 线路代码
   * @param pattern 运营类型（交路的 {@code pattern_type}，如 {@code LOCAL}、{@code RAPID}）
   * @param destinationOperator 终点的运营商代码
   * @param destinationStation 终点站码
   * @param platforms 本站站台（股道号）；不限股道时为空
   * @param availability 此刻能不能叫
   * @param minutes 能叫时为叫车后约几分钟到站；已有叫来的车或下一班很快就到时为那辆车几分钟后到；其余为空
   */
  record Direction(
      String key,
      UUID lineId,
      String lineCode,
      String pattern,
      String destinationOperator,
      String destinationStation,
      Set<String> platforms,
      Availability availability,
      OptionalInt minutes) {

    public Direction {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(availability, "availability");
      platforms = platforms == null ? Set.of() : Set.copyOf(platforms);
      minutes = minutes == null ? OptionalInt.empty() : minutes;
    }
  }

  /** 叫车单的状态。 */
  enum State {
    /** 还没派出。 */
    PENDING,
    /** 已派出：叫来的车在路上（或已到站、正跑完全程）。 */
    DISPATCHED
  }

  /**
   * 一张叫车单。
   *
   * @param id 叫车编号
   * @param playerId 叫车的玩家；已派出的叫车在重启后查不到叫车的玩家，此时为空
   * @param operatorCode 叫车车站的运营商代码
   * @param stationCode 叫车的站码
   * @param directionKey 方向标识（{@link Direction#key()}）；已派出时为空
   * @param state 状态
   * @param trainName 叫来的车；还没派出时为空
   * @param createdAt 叫车时刻；已派出时为空
   */
  record CallView(
      UUID id,
      Optional<UUID> playerId,
      String operatorCode,
      String stationCode,
      Optional<String> directionKey,
      State state,
      Optional<String> trainName,
      Optional<Instant> createdAt) {

    public CallView {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(state, "state");
      playerId = playerId == null ? Optional.empty() : playerId;
      directionKey = directionKey == null ? Optional.empty() : directionKey;
      trainName = trainName == null ? Optional.empty() : trainName;
      createdAt = createdAt == null ? Optional.empty() : createdAt;
    }
  }

  /** 叫车功能未加载时的占位实现。 */
  CallApi UNAVAILABLE =
      new CallApi() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public List<Direction> directionsAt(String operatorCode, String stationCode) {
          return List.of();
        }

        @Override
        public Optional<CallView> callOf(UUID callId) {
          return Optional.empty();
        }

        @Override
        public List<CallView> callsOf(UUID playerId) {
          return List.of();
        }
      };
}
