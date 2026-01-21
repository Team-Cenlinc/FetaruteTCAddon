package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 管理处于 LAYOVER_READY 状态的可复用列车池。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>登记已完成终到流程的列车（标识其位置与终到站）。
 *   <li>为调度器提供候选列车查询（按等待时间/优先级排序）。
 *   <li>提供快照供调试与 UI 展示。
 * </ul>
 */
public final class LayoverRegistry {

  private final ConcurrentMap<String, LayoverCandidate> candidates = new ConcurrentHashMap<>();

  /**
   * 注册一列可复用的待命列车。
   *
   * @param trainName 列车名
   * @param terminalKey 终到站标识（用于分组匹配）
   * @param locationNodeId 当前所在节点 ID（站台或 Siding）
   * @param readyAt 就绪时间（关门完成时间）
   * @param tags 列车当前的 tag 快照（用于后续恢复/属性判断）
   */
  public void register(
      String trainName,
      String terminalKey,
      NodeId locationNodeId,
      Instant readyAt,
      Map<String, String> tags) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(terminalKey, "terminalKey");
    Objects.requireNonNull(locationNodeId, "locationNodeId");
    Objects.requireNonNull(readyAt, "readyAt");
    Objects.requireNonNull(tags, "tags");

    candidates.put(
        trainName,
        new LayoverCandidate(trainName, terminalKey, locationNodeId, readyAt, Map.copyOf(tags)));
  }

  /** 注销列车（通常在分配任务或销毁时调用）。 */
  public void unregister(String trainName) {
    if (trainName != null) {
      candidates.remove(trainName);
    }
  }

  /**
   * 查找指定终到站的可用候选列车。
   *
   * <p>按等待时间从长到短排序（FIFO，防饿死）。
   */
  public List<LayoverCandidate> findCandidates(String terminalKey) {
    if (terminalKey == null) {
      return List.of();
    }
    return candidates.values().stream()
        .filter(c -> c.terminalKey().equals(terminalKey))
        .sorted(Comparator.comparing(LayoverCandidate::readyAt))
        .toList();
  }

  public Optional<LayoverCandidate> get(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(candidates.get(trainName));
  }

  /** 返回全量快照（调试用）。 */
  public List<LayoverCandidate> snapshot() {
    return List.copyOf(candidates.values());
  }

  /** 待命候选列车记录。 */
  public record LayoverCandidate(
      String trainName,
      String terminalKey,
      NodeId locationNodeId,
      Instant readyAt,
      Map<String, String> tags) {
    public LayoverCandidate {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(terminalKey, "terminalKey");
      Objects.requireNonNull(locationNodeId, "locationNodeId");
      Objects.requireNonNull(readyAt, "readyAt");
      tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
  }
}
