package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * TrainApi 内部实现：桥接到运行时服务。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class TrainApiImpl implements TrainApi {

  private final TrainSnapshotStore snapshotStore;
  private final RouteProgressRegistry progressRegistry;
  private final RouteDefinitionCache routeDefinitions;
  private final EtaService etaService;

  public TrainApiImpl(
      TrainSnapshotStore snapshotStore,
      RouteProgressRegistry progressRegistry,
      RouteDefinitionCache routeDefinitions,
      EtaService etaService) {
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
    this.routeDefinitions = routeDefinitions;
    this.etaService = etaService;
  }

  @Override
  public Collection<TrainSnapshot> listActiveTrains(UUID worldId) {
    if (worldId == null) {
      return List.of();
    }

    List<TrainSnapshot> result = new ArrayList<>();
    for (var entry : snapshotStore.snapshot().entrySet()) {
      TrainRuntimeSnapshot snap = entry.getValue();
      if (worldId.equals(snap.worldId())) {
        result.add(convertSnapshot(entry.getKey(), snap));
      }
    }
    return List.copyOf(result);
  }

  @Override
  public Collection<TrainSnapshot> listAllActiveTrains() {
    List<TrainSnapshot> result = new ArrayList<>();
    for (var entry : snapshotStore.snapshot().entrySet()) {
      result.add(convertSnapshot(entry.getKey(), entry.getValue()));
    }
    return List.copyOf(result);
  }

  @Override
  public Optional<TrainSnapshot> getTrainSnapshot(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    return snapshotStore.getSnapshot(trainName).map(snap -> convertSnapshot(trainName, snap));
  }

  @Override
  public int activeTrainCount() {
    return snapshotStore.snapshot().size();
  }

  @Override
  public int activeTrainCount(UUID worldId) {
    if (worldId == null) {
      return 0;
    }
    return (int)
        snapshotStore.snapshot().values().stream()
            .filter(snap -> worldId.equals(snap.worldId()))
            .count();
  }

  private TrainSnapshot convertSnapshot(String trainName, TrainRuntimeSnapshot snap) {
    // 获取路线信息
    Optional<String> routeCode = Optional.empty();
    if (routeDefinitions != null) {
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(snap.routeUuid());
      routeCode = routeOpt.map(r -> r.id().value());
    }

    // 获取进度信息
    Optional<String> nextNode = Optional.empty();
    var progressOpt = progressRegistry.get(trainName);
    if (progressOpt.isPresent()) {
      Optional<NodeId> nextTarget = progressOpt.get().nextTarget();
      if (nextTarget.isPresent()) {
        nextNode = Optional.of(nextTarget.get().value());
      }
    }

    // 转换信号
    Signal signal = convertSignal(snap.signalAspect().orElse(null));

    // 获取 ETA
    Optional<EtaInfo> eta = Optional.empty();
    if (etaService != null) {
      try {
        EtaResult etaResult = etaService.getForTrain(trainName, EtaTarget.nextStop());
        if (etaResult.etaEpochMillis() > 0) {
          eta =
              Optional.of(
                  new EtaInfo(
                      nextNode.orElse(""),
                      Optional.empty(), // 站点名需要额外查询
                      etaResult.etaEpochMillis(),
                      etaResult.etaMinutesRounded(),
                      etaResult.arriving(),
                      etaResult.waitSec() > 60));
        }
      } catch (Exception e) {
        // ETA 计算失败，忽略
      }
    }

    return new TrainSnapshot(
        trainName,
        snap.worldId(),
        snap.routeId().value(),
        routeCode,
        snap.currentNodeId().map(NodeId::value),
        nextNode,
        snap.currentSpeedBps().orElse(0.0),
        signal,
        snap.edgeProgressRatio(),
        snap.updatedAt(),
        eta);
  }

  private Signal convertSignal(SignalAspect aspect) {
    if (aspect == null) {
      return Signal.UNKNOWN;
    }
    return switch (aspect) {
      case PROCEED -> Signal.PROCEED;
      case CAUTION -> Signal.CAUTION;
      case PROCEED_WITH_CAUTION -> Signal.PROCEED_WITH_CAUTION;
      case STOP -> Signal.STOP;
    };
  }
}
