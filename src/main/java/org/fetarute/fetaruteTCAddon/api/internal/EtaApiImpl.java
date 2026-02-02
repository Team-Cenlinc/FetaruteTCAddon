package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;

/**
 * EtaApi 内部实现：桥接到 EtaService。
 *
 * <p>仅供内部使用，外部插件应通过 {@link org.fetarute.fetaruteTCAddon.api.FetaruteApi} 访问。
 */
public final class EtaApiImpl implements EtaApi {

  private static final Duration DEFAULT_BOARD_HORIZON = Duration.ofMinutes(10);

  private final EtaService etaService;

  public EtaApiImpl(EtaService etaService) {
    this.etaService = Objects.requireNonNull(etaService, "etaService");
  }

  @Override
  public EtaApi.EtaResult getForTrain(String trainName, Target target) {
    if (trainName == null) {
      return new EtaApi.EtaResult(false, "N/A", 0L, -1, 0, 0, 0, List.of(), Confidence.LOW);
    }
    EtaTarget internalTarget = convertTarget(target);
    org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult result =
        etaService.getForTrain(trainName, internalTarget);
    return convertResult(result);
  }

  @Override
  public EtaApi.EtaResult getForTicket(String ticketId) {
    if (ticketId == null) {
      return new EtaApi.EtaResult(false, "N/A", 0L, -1, 0, 0, 0, List.of(), Confidence.LOW);
    }
    org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult result =
        etaService.getForTicket(ticketId);
    return convertResult(result);
  }

  @Override
  public BoardResult getBoard(
      String operator, String stationCode, String lineId, Duration horizon) {
    Duration useHorizon = horizon == null ? DEFAULT_BOARD_HORIZON : horizon;
    org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult board =
        etaService.getBoard(operator, stationCode, lineId, useHorizon);
    return convertBoard(board);
  }

  @Override
  public BoardResult getBoard(String stationId, String lineId, Duration horizon) {
    Duration useHorizon = horizon == null ? DEFAULT_BOARD_HORIZON : horizon;
    org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult board =
        etaService.getBoard(stationId, lineId, useHorizon);
    return convertBoard(board);
  }

  @Override
  public Optional<RuntimeSnapshot> getRuntimeSnapshot(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    Optional<TrainRuntimeSnapshot> snapOpt = etaService.getRuntimeSnapshot(trainName);
    if (snapOpt.isEmpty()) {
      return Optional.empty();
    }
    TrainRuntimeSnapshot snap = snapOpt.get();
    return Optional.of(
        new RuntimeSnapshot(
            trainName,
            snap.worldId(),
            snap.routeId().value(),
            snap.routeIndex(),
            snap.currentNodeId().map(node -> node.value()),
            snap.lastPassedNodeId().map(node -> node.value())));
  }

  @Override
  public Collection<String> listSnapshotTrainNames() {
    return List.copyOf(etaService.snapshotTrainNames());
  }

  private EtaTarget convertTarget(Target target) {
    if (target == null || target instanceof Target.NextStop) {
      return EtaTarget.nextStop();
    }
    if (target instanceof Target.Station station) {
      return new EtaTarget.Station(station.stationId());
    }
    if (target instanceof Target.PlatformNode node) {
      return new EtaTarget.PlatformNode(
          org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of(node.nodeId()));
    }
    return EtaTarget.nextStop();
  }

  private EtaApi.EtaResult convertResult(
      org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult result) {
    return new EtaApi.EtaResult(
        result.arriving(),
        result.statusText(),
        result.etaEpochMillis(),
        result.etaMinutesRounded(),
        result.travelSec(),
        result.dwellSec(),
        result.waitSec(),
        convertReasons(result.reasons()),
        convertConfidence(result.confidence()));
  }

  private List<Reason> convertReasons(List<EtaReason> reasons) {
    if (reasons == null || reasons.isEmpty()) {
      return List.of();
    }
    List<Reason> out = new ArrayList<>();
    for (EtaReason reason : reasons) {
      if (reason == null) {
        continue;
      }
      out.add(convertReason(reason));
    }
    return List.copyOf(out);
  }

  private Reason convertReason(EtaReason reason) {
    return switch (reason) {
      case NO_VEHICLE -> Reason.NO_VEHICLE;
      case NO_ROUTE -> Reason.NO_ROUTE;
      case NO_TARGET -> Reason.NO_TARGET;
      case NO_PATH -> Reason.NO_PATH;
      case THROAT -> Reason.THROAT;
      case SINGLELINE -> Reason.SINGLELINE;
      case PLATFORM -> Reason.PLATFORM;
      case DEPOT_GATE -> Reason.DEPOT_GATE;
      case WAIT -> Reason.WAIT;
    };
  }

  private Confidence convertConfidence(EtaConfidence confidence) {
    if (confidence == null) {
      return Confidence.LOW;
    }
    return switch (confidence) {
      case HIGH -> Confidence.HIGH;
      case MED -> Confidence.MED;
      case LOW -> Confidence.LOW;
    };
  }

  private BoardResult convertBoard(org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult board) {
    if (board == null) {
      return new BoardResult(List.of());
    }
    List<BoardRow> rows = new ArrayList<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row : board.rows()) {
      if (row == null) {
        continue;
      }
      rows.add(
          new BoardRow(
              row.lineName(),
              row.routeId(),
              row.destination(),
              row.destinationId(),
              row.endRoute(),
              row.endRouteId(),
              row.endOperation(),
              row.endOperationId(),
              row.platform(),
              row.statusText(),
              convertReasons(row.reasons())));
    }
    return new BoardResult(List.copyOf(rows));
  }
}
