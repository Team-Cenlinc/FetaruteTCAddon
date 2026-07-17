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
 *   <li>登记已完成终到流程的列车（标识其位置与终到站）
 *   <li>为调度器提供候选列车查询（按等待时间/优先级排序）
 *   <li>提供快照供调试与 UI 展示
 * </ul>
 *
 * <h3>terminalKey 匹配规则</h3>
 *
 * <p>查找候选列车时使用 {@link TerminalKeyResolver#matches(String, String)} 进行匹配，支持：
 *
 * <ul>
 *   <li><b>精确匹配</b>：完整 NodeId 相同
 *   <li><b>站点匹配</b>：同一站点不同站台可复用
 * </ul>
 *
 * @see TerminalKeyResolver
 */
public final class LayoverRegistry {

  private final ConcurrentMap<String, LayoverCandidate> candidates = new ConcurrentHashMap<>();

  /**
   * 注册一列可复用的待命列车。
   *
   * <p>同名候选若已经被票据认领，只刷新位置、就绪时间与 tag 快照，不清除其 dispatch attempt；周期性 READY 注册不能打断正在提交的折返事务。
   *
   * @param trainName 列车名
   * @param terminalKey 终到站标识（用于分组匹配，建议使用 {@link TerminalKeyResolver#toTerminalKey(NodeId)} 生成）
   * @param locationNodeId 当前所在节点 ID（站台或 Siding）
   * @param readyAt 就绪时间（关门完成时间）
   * @param tags 列车当前的 tag 快照（用于后续恢复/属性判断）
   */
  public synchronized void register(
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

    candidates.compute(
        trainName,
        (unused, existing) ->
            new LayoverCandidate(
                trainName,
                terminalKey,
                locationNodeId,
                readyAt,
                Map.copyOf(tags),
                existing == null ? Optional.empty() : existing.dispatchAttempt()));
  }

  /**
   * 注销列车（通常在分配任务或销毁时调用）。
   *
   * @param trainName 列车名
   */
  public synchronized void unregister(String trainName) {
    if (trainName != null) {
      candidates.remove(trainName);
    }
  }

  /**
   * 在折返提交期间迁移候选列车名。
   *
   * <p>候选会保留原来的终到位置、readyAt 与 tags；目标名称已存在时拒绝且恢复旧条目，避免授权 owner 已迁移但重试池仍指向不存在的 TrainCarts 名称。
   *
   * @param currentTrainName 当前列车名
   * @param nextTrainName 新列车名
   * @return 成功迁移，或两个名称相同时返回 {@code true}
   */
  public synchronized boolean rename(String currentTrainName, String nextTrainName) {
    if (currentTrainName == null
        || currentTrainName.isBlank()
        || nextTrainName == null
        || nextTrainName.isBlank()) {
      return false;
    }
    if (currentTrainName.equals(nextTrainName)) {
      return true;
    }
    LayoverCandidate candidate = candidates.remove(currentTrainName);
    if (candidate == null) {
      return false;
    }
    LayoverCandidate migrated =
        new LayoverCandidate(
            nextTrainName,
            candidate.terminalKey(),
            candidate.locationNodeId(),
            candidate.readyAt(),
            candidate.tags(),
            candidate.dispatchAttempt());
    LayoverCandidate collision = candidates.putIfAbsent(nextTrainName, migrated);
    if (collision != null) {
      candidates.put(currentTrainName, candidate);
      return false;
    }
    return true;
  }

  /**
   * 为一张票据原子认领待命列车，并固定本次折返的目标列车名。
   *
   * <p>同一票据重试会返回原 attempt，因而不会反复生成 UUID 或在失败路径持续改名；另一张票据不能抢占已经进入 handoff 事务的候选。认领本身不代表授权已提交，若
   * handoff 在修改任何占用前被拒绝，调用方可用 {@link #releaseDispatchAttempt(String, String)} 释放认领。
   *
   * @return 当前票据拥有的稳定 attempt；列车不存在或已被另一票据认领时返回 empty
   */
  public synchronized Optional<DispatchAttempt> claimDispatch(
      String trainName, String ticketId, String targetTrainName) {
    if (trainName == null
        || trainName.isBlank()
        || ticketId == null
        || ticketId.isBlank()
        || targetTrainName == null
        || targetTrainName.isBlank()) {
      return Optional.empty();
    }
    Optional<LayoverCandidate> attemptOwner = findDispatchAttemptOwnerInternal(ticketId);
    if (attemptOwner.isPresent()) {
      LayoverCandidate owner = attemptOwner.get();
      if (!owner.trainName().equals(trainName)) {
        return Optional.empty();
      }
      return owner.dispatchAttempt();
    }
    LayoverCandidate candidate = candidates.get(trainName);
    if (candidate == null) {
      return Optional.empty();
    }
    if (candidate.dispatchAttempt().isPresent()) {
      DispatchAttempt existing = candidate.dispatchAttempt().get();
      return existing.ticketId().equals(ticketId) ? Optional.of(existing) : Optional.empty();
    }
    DispatchAttempt attempt = new DispatchAttempt(ticketId, targetTrainName);
    candidates.put(trainName, candidate.withDispatchAttempt(Optional.of(attempt)));
    return Optional.of(attempt);
  }

  /**
   * 查找已认领指定票据的唯一候选列车。
   *
   * <p>票据认领在注册表内全局唯一，调用方应优先重试这里返回的 owner，而不是继续按 FIFO 尝试其他候选。查询按稳定 ticketId 进行，因此候选在 TrainCarts
   * 改名后仍可被定位。
   *
   * @param ticketId 稳定票据 ID
   * @return 持有该票据 dispatch attempt 的候选；尚未认领时返回 empty
   */
  public synchronized Optional<LayoverCandidate> findDispatchAttemptOwner(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return Optional.empty();
    }
    return findDispatchAttemptOwnerInternal(ticketId);
  }

  /** 仅在 handoff 尚未改变占用事实时释放票据认领。 */
  public synchronized boolean releaseDispatchAttempt(String trainName, String ticketId) {
    if (trainName == null || ticketId == null) {
      return false;
    }
    LayoverCandidate candidate = candidates.get(trainName);
    if (candidate == null || candidate.dispatchAttempt().isEmpty()) {
      return false;
    }
    if (!candidate.dispatchAttempt().get().ticketId().equals(ticketId)) {
      return false;
    }
    candidates.put(trainName, candidate.withDispatchAttempt(Optional.empty()));
    return true;
  }

  /**
   * 判断指定票据是否已进入不可由普通清理路径打断的折返提交事务。
   *
   * <p>查询遍历候选值而不是依赖列车名，因此候选在 TrainCarts 改名后仍能按稳定 ticketId 找到。该状态只表示 handoff 已被认领；只有显式释放 attempt
   * 或成功提交后注销候选，调用方才可以清理对应 pending 票据。
   *
   * @param ticketId 稳定票据 ID
   * @return 任一候选持有该票据的 dispatch attempt 时返回 {@code true}
   */
  public synchronized boolean hasDispatchAttemptForTicket(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return false;
    }
    return findDispatchAttemptOwnerInternal(ticketId).isPresent();
  }

  private Optional<LayoverCandidate> findDispatchAttemptOwnerInternal(String ticketId) {
    return candidates.values().stream()
        .filter(
            candidate ->
                candidate
                    .dispatchAttempt()
                    .filter(attempt -> attempt.ticketId().equals(ticketId))
                    .isPresent())
        .findFirst();
  }

  /**
   * 查找指定终到站的可用候选列车。
   *
   * <p>使用 {@link TerminalKeyResolver#matches(String, String)} 进行匹配，支持同站不同站台复用。
   *
   * <p>按等待时间从长到短排序（FIFO，防饿死）。
   *
   * @param terminalKey 目标终到站标识（通常为目标线路的首站 NodeId）
   * @return 匹配的候选列车列表，按 readyAt 升序排列
   */
  public List<LayoverCandidate> findCandidates(String terminalKey) {
    if (terminalKey == null) {
      return List.of();
    }
    return candidates.values().stream()
        .filter(c -> TerminalKeyResolver.matches(c.terminalKey(), terminalKey))
        .sorted(Comparator.comparing(LayoverCandidate::readyAt))
        .toList();
  }

  /**
   * 根据列车名获取候选记录。
   *
   * @param trainName 列车名
   * @return 候选记录，若不存在则返回 empty
   */
  public Optional<LayoverCandidate> get(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(candidates.get(trainName));
  }

  /**
   * 返回全量快照（调试用）。
   *
   * @return 所有候选列车的不可变列表
   */
  public List<LayoverCandidate> snapshot() {
    return List.copyOf(candidates.values());
  }

  /**
   * 待命候选列车记录。
   *
   * @param trainName 列车名
   * @param terminalKey 终到站标识
   * @param locationNodeId 当前所在节点 ID
   * @param readyAt 就绪时间
   * @param tags 列车 tag 快照
   * @param dispatchAttempt 已认领的折返事务；READY 候选为空
   */
  public record LayoverCandidate(
      String trainName,
      String terminalKey,
      NodeId locationNodeId,
      Instant readyAt,
      Map<String, String> tags,
      Optional<DispatchAttempt> dispatchAttempt) {

    public LayoverCandidate(
        String trainName,
        String terminalKey,
        NodeId locationNodeId,
        Instant readyAt,
        Map<String, String> tags) {
      this(trainName, terminalKey, locationNodeId, readyAt, tags, Optional.empty());
    }

    public LayoverCandidate {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(terminalKey, "terminalKey");
      Objects.requireNonNull(locationNodeId, "locationNodeId");
      Objects.requireNonNull(readyAt, "readyAt");
      tags = tags == null ? Map.of() : Map.copyOf(tags);
      dispatchAttempt = dispatchAttempt == null ? Optional.empty() : dispatchAttempt;
    }

    private LayoverCandidate withDispatchAttempt(Optional<DispatchAttempt> attempt) {
      return new LayoverCandidate(trainName, terminalKey, locationNodeId, readyAt, tags, attempt);
    }
  }

  /** 一次折返事务的稳定身份。 */
  public record DispatchAttempt(String ticketId, String targetTrainName) {
    public DispatchAttempt {
      Objects.requireNonNull(ticketId, "ticketId");
      Objects.requireNonNull(targetTrainName, "targetTrainName");
      if (ticketId.isBlank() || targetTrainName.isBlank()) {
        throw new IllegalArgumentException("ticketId 与 targetTrainName 不能为空");
      }
    }
  }
}
