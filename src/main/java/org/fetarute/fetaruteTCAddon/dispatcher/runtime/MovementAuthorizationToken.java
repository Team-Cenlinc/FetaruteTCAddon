package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 一次成功 acquire 后生成的运动授权令牌。
 *
 * <p>destination 写入、发车与健康恢复不得依赖 TrainCarts 残留 destination；必须能追溯到当前 claim version 的 fresh acquire。
 */
public record MovementAuthorizationToken(
    String trainName,
    long claimVersion,
    Instant issuedAt,
    NodeId fromNode,
    NodeId toNode,
    List<OccupancyResource> resources,
    SignalAspect aspect,
    boolean active,
    Optional<String> committedDestination) {

  public MovementAuthorizationToken {
    trainName = Objects.requireNonNull(trainName, "trainName").trim();
    issuedAt = issuedAt == null ? Instant.now() : issuedAt;
    resources = resources == null ? List.of() : List.copyOf(resources);
    aspect = aspect == null ? SignalAspect.STOP : aspect;
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
      NodeId toNode,
      List<OccupancyResource> resources,
      SignalAspect aspect) {
    this(
        trainName,
        claimVersion,
        issuedAt,
        fromNode,
        toNode,
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
        toNode,
        resources,
        aspect,
        true,
        destinationName == null || destinationName.isBlank()
            ? Optional.empty()
            : Optional.of(destinationName.trim()));
  }
}
