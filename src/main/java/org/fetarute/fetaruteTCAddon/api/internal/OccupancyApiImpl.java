package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.occupancy.OccupancyApi;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;

/**
 * OccupancyApi 内部实现：桥接到 OccupancyManager。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class OccupancyApiImpl implements OccupancyApi {

  private final OccupancyManager occupancyManager;

  public OccupancyApiImpl(OccupancyManager occupancyManager) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
  }

  @Override
  public boolean isNodeOccupied(UUID worldId, String nodeId) {
    if (nodeId == null) {
      return false;
    }
    return occupancyManager.isNodeOccupied(NodeId.of(nodeId));
  }

  @Override
  public boolean isEdgeOccupied(UUID worldId, String edgeId) {
    if (edgeId == null) {
      return false;
    }
    // 解析 edgeId（格式：nodeA<->nodeB）
    EdgeId edge = parseEdgeId(edgeId);
    if (edge == null) {
      return false;
    }
    OccupancyResource resource = OccupancyResource.forEdge(edge);
    return occupancyManager.getClaim(resource).isPresent();
  }

  @Override
  public Optional<OccupancyClaim> getNodeOccupant(UUID worldId, String nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }

    Collection<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim> claims =
        occupancyManager.snapshotClaims();
    for (var claim : claims) {
      if (claim.resource().kind() == ResourceKind.NODE && nodeId.equals(claim.resource().key())) {
        return Optional.of(convertClaim(claim, worldId));
      }
    }
    return Optional.empty();
  }

  @Override
  public Collection<OccupancyClaim> listClaims(UUID worldId) {
    // 当前实现不按世界分隔，返回所有
    return listAllClaims();
  }

  @Override
  public Collection<OccupancyClaim> listAllClaims() {
    List<OccupancyClaim> result = new ArrayList<>();

    Collection<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim> claims =
        occupancyManager.snapshotClaims();
    for (var claim : claims) {
      result.add(convertClaim(claim, null));
    }

    return List.copyOf(result);
  }

  @Override
  public Collection<QueueSnapshot> listQueues(UUID worldId) {
    List<QueueSnapshot> result = new ArrayList<>();

    if (occupancyManager instanceof OccupancyQueueSupport queueSupport) {
      Instant now = Instant.now();
      for (OccupancyQueueSnapshot queue : queueSupport.snapshotQueues()) {
        result.add(convertQueue(queue, now));
      }
    }

    return List.copyOf(result);
  }

  @Override
  public int totalOccupiedCount() {
    return occupancyManager.snapshotClaims().size();
  }

  private OccupancyClaim convertClaim(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim claim,
      UUID worldId) {
    ResourceType resourceType =
        switch (claim.resource().kind()) {
          case NODE -> ResourceType.NODE;
          case EDGE -> ResourceType.EDGE;
          case CONFLICT -> ResourceType.CONFLICT;
        };

    // OccupancyClaim 没有 signalAtClaim 字段，使用 UNKNOWN
    Signal signal = Signal.UNKNOWN;

    return new OccupancyClaim(
        claim.trainName(),
        resourceType,
        claim.resource().key(),
        worldId,
        claim.acquiredAt(),
        signal);
  }

  private QueueSnapshot convertQueue(OccupancyQueueSnapshot queue, Instant now) {
    ResourceType resourceType =
        switch (queue.resource().kind()) {
          case NODE -> ResourceType.NODE;
          case EDGE -> ResourceType.EDGE;
          case CONFLICT -> ResourceType.CONFLICT;
        };

    List<QueueEntry> entries = new ArrayList<>();
    int position = 1;
    for (OccupancyQueueEntry entry : queue.entries()) {
      int waitingSec =
          entry.firstSeen() != null
              ? (int) Duration.between(entry.firstSeen(), now).getSeconds()
              : 0;
      entries.add(new QueueEntry(entry.trainName(), position++, Math.max(0, waitingSec)));
    }

    return new QueueSnapshot(queue.resource().key(), resourceType, List.copyOf(entries));
  }

  private EdgeId parseEdgeId(String edgeIdStr) {
    if (edgeIdStr == null || edgeIdStr.isBlank()) {
      return null;
    }
    // 格式：nodeA<->nodeB
    int sep = edgeIdStr.indexOf("<->");
    if (sep < 0) {
      return null;
    }
    String a = edgeIdStr.substring(0, sep);
    String b = edgeIdStr.substring(sep + 3);
    if (a.isBlank() || b.isBlank()) {
      return null;
    }
    return EdgeId.undirected(NodeId.of(a), NodeId.of(b));
  }
}
