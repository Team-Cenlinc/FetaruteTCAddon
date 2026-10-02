package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 一次成功 acquire 后生成、并在相同物理授权下复用的运动授权令牌。
 *
 * <p>destination 写入、发车与健康恢复不得依赖 TrainCarts 残留 destination；必须能追溯到当前 hard authority。完全相同的 物理授权复用既有
 * token，不得仅因周期检查再次签发 claim version。
 *
 * <p>{@code destinationNode} 是交给 TrainCarts 寻路的远端目标；{@code authorityEndNode}/{@code
 * authorizedEdgeCount} 是本轮实际取得硬资源的连续前缀边界。两者不得互相替代：远端目标可以位于授权边界之外，但跟驰、Smart feedback 与信号发布只能把后者视为
 * Movement Authority。
 */
public record MovementAuthorizationToken(
    String trainName,
    long claimVersion,
    Instant issuedAt,
    NodeId fromNode,
    NodeId destinationNode,
    Optional<NodeId> authorityEndNode,
    int authorizedEdgeCount,
    List<OccupancyResource> resources,
    SignalAspect aspect,
    boolean active,
    Optional<String> committedDestination) {

  public MovementAuthorizationToken {
    trainName = Objects.requireNonNull(trainName, "trainName").trim();
    issuedAt = issuedAt == null ? Instant.now() : issuedAt;
    resources = resources == null ? List.of() : List.copyOf(resources);
    aspect = aspect == null ? SignalAspect.STOP : aspect;
    authorityEndNode = authorityEndNode == null ? Optional.empty() : authorityEndNode;
    authorizedEdgeCount = Math.max(0, authorizedEdgeCount);
    if (authorityEndNode.isEmpty() || authorizedEdgeCount == 0) {
      authorityEndNode = Optional.empty();
      authorizedEdgeCount = 0;
    }
    committedDestination =
        committedDestination == null ? Optional.empty() : committedDestination.map(String::trim);
    if (trainName.isBlank()) {
      throw new IllegalArgumentException("trainName 不能为空");
    }
  }

  public MovementAuthorizationToken(
      String trainName,
      long claimVersion,
      Instant issuedAt,
      NodeId fromNode,
      NodeId destinationNode,
      List<OccupancyResource> resources,
      SignalAspect aspect) {
    this(
        trainName,
        claimVersion,
        issuedAt,
        fromNode,
        destinationNode,
        Optional.empty(),
        0,
        resources,
        aspect,
        false,
        Optional.empty());
  }

  /** 构造携带明确硬授权边界、但尚未提交 destination 的 token。 */
  public MovementAuthorizationToken(
      String trainName,
      long claimVersion,
      Instant issuedAt,
      NodeId fromNode,
      NodeId destinationNode,
      Optional<NodeId> authorityEndNode,
      int authorizedEdgeCount,
      List<OccupancyResource> resources,
      SignalAspect aspect) {
    this(
        trainName,
        claimVersion,
        issuedAt,
        fromNode,
        destinationNode,
        authorityEndNode,
        authorizedEdgeCount,
        resources,
        aspect,
        false,
        Optional.empty());
  }

  /** 返回 destination 已提交后的 active token。 */
  public MovementAuthorizationToken activate(String destinationName) {
    return new MovementAuthorizationToken(
        trainName,
        claimVersion,
        issuedAt,
        fromNode,
        destinationNode,
        authorityEndNode,
        authorizedEdgeCount,
        resources,
        aspect,
        true,
        destinationName == null || destinationName.isBlank()
            ? Optional.empty()
            : Optional.of(destinationName.trim()));
  }

  /**
   * 保留 TrainCarts 现有 destination 作为可恢复等待证据，但不激活该 token。
   *
   * <p>用于 Smart Dispatcher recoverable hold：当前 hard authority 片段已释放，需要下一轮重新 acquire；但 destination
   * 本身未损坏， 诊断与恢复链路不应把它误判为缺失。
   */
  public MovementAuthorizationToken retainDestination(String destinationName) {
    Optional<String> retained =
        destinationName == null || destinationName.isBlank()
            ? committedDestination
            : Optional.of(destinationName.trim());
    return new MovementAuthorizationToken(
        trainName,
        claimVersion,
        issuedAt,
        fromNode,
        destinationNode,
        authorityEndNode,
        authorizedEdgeCount,
        resources,
        aspect,
        false,
        retained);
  }

  /**
   * 返回该 token 是否携带至少一条连续区间的真实硬授权边界。
   *
   * <p>远端 destination、非空资源列表或 active 状态都不能替代该证据。信号发布、发车与跟驰预测必须在此条件成立后，才可把 token 解释为可执行的 Movement
   * Authority。
   */
  public boolean hasPhysicalAuthorityBoundary() {
    return authorityEndNode.isPresent() && authorizedEdgeCount > 0;
  }
}
