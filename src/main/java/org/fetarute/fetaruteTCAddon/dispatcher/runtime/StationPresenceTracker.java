package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 列车“在站”记录：从到站（进度已推进到该站）到发车许可发出为止。
 *
 * <p>只供显示与估算使用（HUD 状态、ETA 停站判断），<b>不参与控车</b>。控车侧的停站判据仍是 {@link
 * DwellRegistry#remainingSeconds}，两者语义不同，不能互相顶替：
 *
 * <ul>
 *   <li>停站计时要等列车停稳若干 tick 后才开始，而进度在那之前就已推进到本站。两者之间的空档里 HUD 若只看停站计时，
 *       会按“已推进的下一站”显示一闪——这就是“到站后闪一下报下一站”。
 *   <li>停站计时到期后还要关门、过发车门控，车仍在站台上；只看计时会把这段显示成“临时停车”。
 * </ul>
 *
 * <p>记录以交路索引为锚：调用方拿列车当前进度索引来问，索引变了（已经开往下一站）自然失效， 即使漏了一次发车事件也不会把“在站”一直挂着。
 */
public final class StationPresenceTracker implements StationStopObserver {

  private final ConcurrentMap<String, Presence> presence = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, StopRecord> lastStops = new ConcurrentHashMap<>();

  @Override
  public void onStationArrival(StationStopEvent event) {
    if (event == null || event.trainName().isBlank()) {
      return;
    }
    String key = key(event.trainName());
    presence.put(
        key, new Presence(event.stopIndex(), event.nodeId(), event.routeKey(), event.at()));
    lastStops.put(key, StopRecord.of(true, event));
  }

  @Override
  public void onStationDeparture(StationStopEvent event) {
    if (event == null || event.trainName().isBlank()) {
      return;
    }
    String key = key(event.trainName());
    presence.remove(key);
    lastStops.put(key, StopRecord.of(false, event));
  }

  @Override
  public void onTrainReleased(String trainName, String reason) {
    if (trainName != null && !trainName.isBlank()) {
      presence.remove(key(trainName));
      lastStops.remove(key(trainName));
    }
  }

  /**
   * 列车最近一次实际到站或发车（供时刻表偏差计算）。
   *
   * @param trainName 列车名（大小写不敏感）
   * @return 最近一次停靠事件；尚无记录或列车已离开管辖时为空
   */
  public Optional<StopRecord> lastStop(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(lastStops.get(key(trainName)));
  }

  /**
   * 列车是否仍停在当前交路、当前进度索引对应的车站上（已到站、尚未拿到发车许可）。
   *
   * @param trainName 列车名（大小写不敏感）
   * @param routeKey 列车当前交路 key（{@code RouteId.value()}）
   * @param routeIndex 列车当前进度索引
   * @return 在站时返回 true
   */
  public boolean isAtStation(String trainName, String routeKey, int routeIndex) {
    return presenceAt(trainName, routeKey, routeIndex).isPresent();
  }

  /**
   * 返回与当前交路、当前进度索引一致的在站记录。
   *
   * <p>交路 key 也要比：折返复用会换交路而不经过 AutoStation 发车，旧记录的索引可能恰好等于新交路的索引。
   *
   * @param trainName 列车名（大小写不敏感）
   * @param routeKey 列车当前交路 key
   * @param routeIndex 列车当前进度索引
   * @return 在站记录；不在站、换了交路或索引已变化时为空
   */
  public Optional<Presence> presenceAt(String trainName, String routeKey, int routeIndex) {
    if (trainName == null || trainName.isBlank() || routeKey == null) {
      return Optional.empty();
    }
    Presence current = presence.get(key(trainName));
    if (current == null
        || current.stopIndex() != routeIndex
        || !current.routeKey().equalsIgnoreCase(routeKey.trim())) {
      return Optional.empty();
    }
    return Optional.of(current);
  }

  /** 只保留仍在网的列车，兜底清理漏掉释放事件的记录。 */
  public void retain(Set<String> activeTrainNames) {
    if (activeTrainNames == null || activeTrainNames.isEmpty()) {
      presence.clear();
      lastStops.clear();
      return;
    }
    Set<String> keys = ConcurrentHashMap.newKeySet();
    for (String name : activeTrainNames) {
      if (name != null && !name.isBlank()) {
        keys.add(key(name));
      }
    }
    presence.keySet().retainAll(keys);
    lastStops.keySet().retainAll(keys);
  }

  private static String key(String trainName) {
    return trainName.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 一次在站记录。
   *
   * @param stopIndex 到站时的交路索引
   * @param nodeId 停靠节点
   * @param routeKey 交路 key
   * @param arrivedAt 到站时间（调度层时钟）
   */
  public record Presence(int stopIndex, String nodeId, String routeKey, Instant arrivedAt) {}

  /**
   * 一次实际停靠事实。
   *
   * @param arrival true 为到站，false 为发车
   * @param stopIndex 交路索引
   * @param nodeId 实际停靠的节点（DYNAMIC 为选中的股道）
   * @param routeKey 交路 key
   * @param routeUuid 交路 UUID（仅 code 定义的交路为空）
   * @param at 发生时刻（调度层时钟）
   */
  public record StopRecord(
      boolean arrival,
      int stopIndex,
      String nodeId,
      String routeKey,
      Optional<UUID> routeUuid,
      Instant at) {
    static StopRecord of(boolean arrival, StationStopEvent event) {
      return new StopRecord(
          arrival,
          event.stopIndex(),
          event.nodeId(),
          event.routeKey(),
          event.routeUuid(),
          event.at());
    }
  }
}
