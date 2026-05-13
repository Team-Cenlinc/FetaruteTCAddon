package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerRelation;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictReleaseHint;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 信号计算诊断跟踪器。
 *
 * <p>该类只输出诊断日志，不参与信号判定。输出条件刻意收窄到红/黄、短窗口翻转、无 blocker 红/黄等异常边界，避免每 tick 刷屏。
 */
public final class SignalComputationTrace {

  private static final long TICK_MILLIS = 50L;
  private static final long FLIP_WINDOW_TICKS = 2L;
  private static final ConcurrentMap<String, LastSignal> LAST_SIGNALS = new ConcurrentHashMap<>();
  private static volatile Consumer<String> globalLogger = message -> {};

  private SignalComputationTrace() {}

  /** 信号计算入口来源。 */
  public enum Source {
    EVENT,
    PERIODIC_TICK,
    PROGRESS_TRIGGER,
    DEPARTURE_GATE,
    HEALTH,
    AUTHORIZATION,
    MOVEMENT_AUTHORITY,
    CONTROL,
    OCCUPANCY
  }

  /** movement token 诊断状态。 */
  public enum TokenState {
    NONE,
    PENDING,
    ACTIVE,
    INVALID
  }

  /** 设置全局诊断 logger，供没有实例 logger 的占用层使用。 */
  public static void configureLogger(Consumer<String> logger) {
    globalLogger = logger != null ? logger : message -> {};
  }

  /** 创建一条 trace。 */
  public static Builder builder(
      String trainName, String rawTrainName, Source source, SignalAspect newAspect) {
    return new Builder(trainName, rawTrainName, source, newAspect);
  }

  /** 输出一条已收集的 trace。 */
  public static void emit(Builder builder, Consumer<String> logger) {
    if (builder == null) {
      return;
    }
    builder.emit(logger);
  }

  /** 用全局 logger 输出。 */
  public static void emit(Builder builder) {
    emit(builder, globalLogger);
  }

  /** trace builder。 */
  public static final class Builder {
    private final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    private final List<String> blockers = new ArrayList<>();
    private final String canonicalName;
    private final SignalAspect newAspect;
    private final Source source;
    private final long tick;
    private SignalAspect previousAspect;
    private boolean hasBlockers;
    private boolean hasDistanceOnlyConstraint;

    private Builder(String trainName, String rawTrainName, Source source, SignalAspect newAspect) {
      this.canonicalName = TrainNameNormalizer.normalizeKey(trainName);
      this.newAspect = newAspect == null ? SignalAspect.STOP : newAspect;
      this.source = source == null ? Source.PERIODIC_TICK : source;
      this.tick = currentTick();
      field("trainName", trainName);
      field("canonicalName", canonicalName);
      field("rawTrainName", rawTrainName);
      field("source", this.source.name());
      field("tick", tick);
      field("newAspect", this.newAspect.name());
    }

    public Builder previousAspect(SignalAspect aspect) {
      previousAspect = aspect;
      field("previousAspect", aspect == null ? "null" : aspect.name());
      return this;
    }

    public Builder primaryReason(String reason) {
      field("primaryReason", reason);
      return this;
    }

    public Builder field(String key, Object value) {
      if (key == null || key.isBlank()) {
        return this;
      }
      fields.put(key, value == null ? "-" : String.valueOf(value));
      return this;
    }

    public Builder nodes(NodeId currentNode, NodeId nextNode) {
      field("currentNode", currentNode == null ? "-" : currentNode.value());
      field("nextNode", nextNode == null ? "-" : nextNode.value());
      return this;
    }

    public Builder progress(
        long progressVersion,
        int routeIndexBefore,
        int routeIndexAfter,
        Optional<NodeId> lastPassedBefore,
        Optional<NodeId> lastPassedAfter) {
      field("progressVersion", progressVersion);
      field("routeIndexBefore", routeIndexBefore);
      field("routeIndexAfter", routeIndexAfter);
      field("lastPassedGraphNodeBefore", formatNode(lastPassedBefore));
      field("lastPassedGraphNodeAfter", formatNode(lastPassedAfter));
      return this;
    }

    public Builder request(OccupancyRequest request) {
      if (request == null) {
        field("requestPurpose", "-");
        field("requestResourceCount", 0);
        field("movementRequiredResources", 0);
        field("protectiveRetainResources", 0);
        field("queuePositionResources", 0);
        field("holdOnlyResources", 0);
        return this;
      }
      int movement = 0;
      int protective = 0;
      int queue = 0;
      int hold = 0;
      for (OccupancyResource resource : request.resourceList()) {
        ResourceIntent intent = request.intentFor(resource);
        if (intent == ResourceIntent.MOVEMENT_REQUIRED) {
          movement++;
        } else if (intent == ResourceIntent.PROTECTIVE_RETAIN) {
          protective++;
        } else if (intent == ResourceIntent.QUEUE_POSITION) {
          queue++;
        } else if (intent == ResourceIntent.HOLD_ONLY) {
          hold++;
        }
      }
      field("requestPurpose", request.purpose());
      field("requestResourceCount", request.resourceList().size());
      field("movementRequiredResources", movement);
      field("protectiveRetainResources", protective);
      field("queuePositionResources", queue);
      field("holdOnlyResources", hold);
      field("conflictClearingEvidenceKind", formatEvidenceKinds(request));
      field("conflictClearingEvidenceSource", formatEvidenceSources(request));
      field(
          "conflictClearingPromotedToPurpose",
          request.purpose().name().equals("CONFLICT_CLEARING"));
      field(
          "conflictClearingPromotionReason",
          request.purpose().name().equals("CONFLICT_CLEARING")
              ? "verified-conflict-release"
              : evidenceSkippedReason(request));
      field("releaseHintVerified", hasVerifiedReleaseHint(request));
      request.directedContext().ifPresent(this::directedContext);
      return this;
    }

    public Builder directedContext(DirectedTraversalContext context) {
      if (context == null) {
        return this;
      }
      field("requestId", context.requestId());
      field("directedSource", context.source());
      field("directedOccupancyVersion", context.occupancyVersion());
      field("directedProgressVersion", context.progressVersion());
      field("routeId", context.routeId().map(Object::toString).orElse("-"));
      field("currentIndex", context.currentIndex());
      field("directedCurrentNode", formatNode(context.currentNode()));
      field("lastPassedGraphNode", formatNode(context.lastPassedGraphNode()));
      field("effectiveFromNode", formatNode(context.effectiveFromNode()));
      field("effectiveToNode", formatNode(context.effectiveToNode()));
      field("expandedPathNodes", formatNodeList(context.expandedPathNodes()));
      field("directedEdges", context.directedEdges());
      field("singleConflictDirections", context.singleConflictDirections());
      field("switcherPathSignatures", context.switcherPathSignatures());
      field("authorityTokenId", context.authorityTokenId().orElse("-"));
      return this;
    }

    public Builder decision(OccupancyDecision decision, OccupancyRequest request) {
      if (decision == null) {
        field("decisionAllowed", "-");
        field("decisionReason", "-");
        field("blockerCount", 0);
        return this;
      }
      field("decisionAllowed", decision.allowed());
      field("decisionReason", decision.reason());
      field("decisionSignal", decision.signal());
      field("blockerCount", decision.blockers().size());
      field("canEnterConflictRelease", decision.conflictRelease());
      field("canEnterReleaseLeader", decision.conflictRelease());
      hasBlockers = !decision.blockers().isEmpty();
      for (OccupancyClaim claim : decision.blockers()) {
        if (claim == null || claim.resource() == null) {
          continue;
        }
        ResourceIntent intent =
            request == null
                ? ResourceIntent.MOVEMENT_REQUIRED
                : request.intentFor(claim.resource());
        BlockerRelation relation =
            request == null
                ? BlockerRelation.HARD_OCCUPANCY
                : BlockerClassifier.classify(request, claim.resource(), claim);
        blockers.add(
            claim.resource().kind()
                + ":"
                + claim.resource().key()
                + " owner="
                + claim.trainName()
                + " ownerCanonical="
                + TrainNameNormalizer.normalizeKey(claim.trainName())
                + " relation="
                + relation
                + " intent="
                + intent
                + " role="
                + claim.role());
      }
      return this;
    }

    public Builder distances(
        OptionalLong distanceToBlocker,
        OptionalLong distanceToCaution,
        OptionalLong distanceToApproach,
        OptionalLong distanceToAuthorityEnd,
        String authorityEndResource,
        int authorizedEdgeCount) {
      field("distanceToBlocker", formatLong(distanceToBlocker));
      field("distanceToCaution", formatLong(distanceToCaution));
      field("distanceToApproach", formatLong(distanceToApproach));
      field("distanceToAuthorityEnd", formatLong(distanceToAuthorityEnd));
      field("authorityEndResource", authorityEndResource);
      field("authorizedEdgeCount", authorizedEdgeCount);
      hasDistanceOnlyConstraint =
          !hasBlockers
              && (present(distanceToCaution)
                  || present(distanceToApproach)
                  || present(distanceToAuthorityEnd));
      return this;
    }

    public Builder token(
        boolean movementInhibited,
        TokenState tokenState,
        Long tokenClaimVersion,
        boolean destinationPresent,
        String destination) {
      field("movementInhibited", movementInhibited);
      field("movementTokenState", tokenState == null ? TokenState.NONE : tokenState);
      field("tokenClaimVersion", tokenClaimVersion == null ? "-" : tokenClaimVersion);
      field("destinationPresent", destinationPresent);
      field("destination", destination);
      return this;
    }

    public Builder emit(Consumer<String> logger) {
      Consumer<String> out = logger != null ? logger : globalLogger;
      LastSignal previous =
          canonicalName == null || canonicalName.isBlank() ? null : LAST_SIGNALS.get(canonicalName);
      SignalAspect effectivePrevious =
          previousAspect != null ? previousAspect : previous == null ? null : previous.aspect();
      if (!fields.containsKey("previousAspect")) {
        field("previousAspect", effectivePrevious == null ? "null" : effectivePrevious.name());
      }
      boolean recentFlip =
          previous != null
              && effectivePrevious != null
              && effectivePrevious != newAspect
              && Math.abs(tick - previous.tick()) <= FLIP_WINDOW_TICKS
              && (isRestrictive(effectivePrevious) || isRestrictive(newAspect));
      boolean shouldEmit =
          isRestrictive(newAspect)
              || recentFlip
              || (isRestrictive(newAspect) && !hasBlockers)
              || (isCautionLike(newAspect) && hasDistanceOnlyConstraint);
      if (canonicalName != null && !canonicalName.isBlank()) {
        LAST_SIGNALS.put(canonicalName, new LastSignal(newAspect, tick));
      }
      if (!shouldEmit) {
        return this;
      }
      fields.put("aspectTransition", formatAspect(effectivePrevious) + "->" + newAspect.name());
      fields.put("debugRecentFlipWithin2Ticks", String.valueOf(recentFlip));
      fields.put("blockers", blockers.isEmpty() ? "[]" : blockers.toString());
      out.accept("SignalTrace " + formatFields(fields));
      return this;
    }
  }

  private static boolean isRestrictive(SignalAspect aspect) {
    return aspect == SignalAspect.STOP || isCautionLike(aspect);
  }

  private static boolean isCautionLike(SignalAspect aspect) {
    return aspect == SignalAspect.CAUTION || aspect == SignalAspect.PROCEED_WITH_CAUTION;
  }

  private static boolean present(OptionalLong value) {
    return value != null && value.isPresent();
  }

  private static String formatLong(OptionalLong value) {
    return present(value) ? String.valueOf(value.getAsLong()) : "-";
  }

  private static String formatNode(Optional<NodeId> node) {
    return node != null && node.isPresent() ? node.get().value() : "-";
  }

  private static String formatNodeList(List<NodeId> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return "[]";
    }
    List<String> values = new ArrayList<>();
    for (NodeId node : nodes) {
      values.add(node == null ? "-" : node.value());
    }
    return values.toString();
  }

  private static String formatEvidenceKinds(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return "-";
    }
    return request.conflictReleaseHints().values().stream()
        .filter(java.util.Objects::nonNull)
        .map(hint -> hint.conflictKey() + ":" + hint.kind())
        .toList()
        .toString();
  }

  private static String formatEvidenceSources(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return "-";
    }
    return request.conflictReleaseHints().values().stream()
        .filter(java.util.Objects::nonNull)
        .map(hint -> hint.conflictKey() + ":" + hint.source())
        .toList()
        .toString();
  }

  private static boolean hasVerifiedReleaseHint(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null && hint.verifiedFor(hint.conflictKey())) {
        return true;
      }
    }
    return false;
  }

  private static String evidenceSkippedReason(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return "-";
    }
    if (!hasVerifiedReleaseHint(request)) {
      return "topology-exit-hint-only";
    }
    return "not-promoted";
  }

  private static String formatAspect(SignalAspect aspect) {
    return aspect == null ? "null" : aspect.name();
  }

  private static String formatFields(Map<String, String> fields) {
    StringBuilder builder = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      if (!first) {
        builder.append(' ');
      }
      builder.append(entry.getKey()).append('=').append(sanitize(entry.getValue()));
      first = false;
    }
    return builder.toString();
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
  }

  private static long currentTick() {
    return System.currentTimeMillis() / TICK_MILLIS;
  }

  private record LastSignal(SignalAspect aspect, long tick) {}
}
