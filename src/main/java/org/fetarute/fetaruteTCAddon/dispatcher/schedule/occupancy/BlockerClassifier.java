package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Optional;

/**
 * 将占用 blocker 归类为前后车、对向冲突或硬占用。
 *
 * <p>当前实现只依赖请求/claim 已携带的资源意图与走廊方向，避免在 hot path 内做额外寻路。运行时若能提供更完整的 route progress，可在该类上继续扩展。
 */
public final class BlockerClassifier {

  private BlockerClassifier() {}

  public static BlockerRelation classify(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    if (request == null || resource == null || claim == null) {
      return BlockerRelation.HARD_OCCUPANCY;
    }
    if (TrainNameNormalizer.sameLogicalTrain(request.trainName(), claim.trainName())) {
      return BlockerRelation.SELF;
    }
    ResourceIntent intent = request.intentFor(resource);
    if (!intent.hardAuthority()) {
      return BlockerRelation.STALE_PROTECTIVE_CLAIM;
    }
    if (resource.kind() == ResourceKind.CONFLICT) {
      if (resource.key().startsWith("switcher:")) {
        return BlockerRelation.SWITCHER_CONFLICT;
      }
      if (isSingleCorridorConflict(resource)) {
        Optional<CorridorDirection> requestDirection = requestDirection(request, resource);
        Optional<CorridorDirection> claimDirection = claim.corridorDirection();
        if (requestDirection.isEmpty() || claimDirection.isEmpty()) {
          return BlockerRelation.UNKNOWN_SINGLE_CONFLICT;
        }
        return requestDirection.get() == claimDirection.get()
            ? BlockerRelation.SAME_DIRECTION_FRONT
            : BlockerRelation.OPPOSITE_SINGLE_CONFLICT;
      }
    }
    return BlockerRelation.HARD_OCCUPANCY;
  }

  public static boolean isHardMovementBlocker(BlockerRelation relation) {
    return relation == BlockerRelation.HARD_OCCUPANCY
        || relation == BlockerRelation.OPPOSITE_SINGLE_CONFLICT
        || relation == BlockerRelation.UNKNOWN_SINGLE_CONFLICT
        || relation == BlockerRelation.SWITCHER_CONFLICT
        || relation == BlockerRelation.SAME_DIRECTION_FRONT;
  }

  private static Optional<CorridorDirection> requestDirection(
      OccupancyRequest request, OccupancyResource resource) {
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction == null || direction == CorridorDirection.UNKNOWN) {
      return Optional.empty();
    }
    return Optional.of(direction);
  }

  private static boolean isSingleCorridorConflict(OccupancyResource resource) {
    return resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }
}
