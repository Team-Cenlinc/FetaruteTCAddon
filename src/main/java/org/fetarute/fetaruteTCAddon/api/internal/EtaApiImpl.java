package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.coupler.DivisionForecast;
import org.fetarute.fetaruteTCAddon.coupler.JoinForecast;
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

  /** 运行中列车的分行预测（按车名查）。 */
  private final Function<String, Optional<DivisionForecast>> divisions;

  /** 表上还没出车的一班的分行预测（按票据与预测行的车次标识查）。 */
  private final Function<String, Optional<DivisionForecast>> plannedDivisions;

  /** 运行中列车的联挂预测（按车名查）。 */
  private final Function<String, Optional<JoinForecast>> joinings;

  /** 表上还没出车的一班的联挂预测（按票据与预测行的车次标识查）。 */
  private final Function<String, Optional<JoinForecast>> plannedJoinings;

  public EtaApiImpl(EtaService etaService) {
    this(etaService, name -> Optional.empty());
  }

  /**
   * @param divisions 运行中列车的分行预测（按车名查，任意线程可调）
   */
  public EtaApiImpl(EtaService etaService, Function<String, Optional<DivisionForecast>> divisions) {
    this(etaService, divisions, trip -> Optional.empty());
  }

  /**
   * @param divisions 运行中列车的分行预测（按车名查，任意线程可调）
   * @param plannedDivisions 表上还没出车的一班的分行预测（按车次标识查，任意线程可调）
   */
  public EtaApiImpl(
      EtaService etaService,
      Function<String, Optional<DivisionForecast>> divisions,
      Function<String, Optional<DivisionForecast>> plannedDivisions) {
    this(etaService, divisions, plannedDivisions, name -> Optional.empty());
  }

  /**
   * @param divisions 运行中列车的分行预测（按车名查，任意线程可调）
   * @param plannedDivisions 表上还没出车的一班的分行预测（按车次标识查，任意线程可调）
   * @param joinings 运行中列车的联挂预测（按车名查，任意线程可调）
   */
  public EtaApiImpl(
      EtaService etaService,
      Function<String, Optional<DivisionForecast>> divisions,
      Function<String, Optional<DivisionForecast>> plannedDivisions,
      Function<String, Optional<JoinForecast>> joinings) {
    this(etaService, divisions, plannedDivisions, joinings, trip -> Optional.empty());
  }

  /**
   * @param divisions 运行中列车的分行预测（按车名查，任意线程可调）
   * @param plannedDivisions 表上还没出车的一班的分行预测（按车次标识查，任意线程可调）
   * @param joinings 运行中列车的联挂预测（按车名查，任意线程可调）
   * @param plannedJoinings 表上还没出车的一班的联挂预测（按车次标识查，任意线程可调）
   */
  public EtaApiImpl(
      EtaService etaService,
      Function<String, Optional<DivisionForecast>> divisions,
      Function<String, Optional<DivisionForecast>> plannedDivisions,
      Function<String, Optional<JoinForecast>> joinings,
      Function<String, Optional<JoinForecast>> plannedJoinings) {
    this.etaService = Objects.requireNonNull(etaService, "etaService");
    this.divisions = divisions == null ? name -> Optional.empty() : divisions;
    this.plannedDivisions = plannedDivisions == null ? trip -> Optional.empty() : plannedDivisions;
    this.joinings = joinings == null ? name -> Optional.empty() : joinings;
    this.plannedJoinings = plannedJoinings == null ? trip -> Optional.empty() : plannedJoinings;
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
      case HOLD -> Reason.HOLD;
      case OVERDUE -> Reason.OVERDUE;
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
    List<org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow> internal =
        board.rows().stream().filter(Objects::nonNull).toList();
    List<Optional<DivisionForecast>> divisions = internal.stream().map(this::divisionOf).toList();
    // 主干在本站分行的那一行已经写了解下那一段的去向：子交路首班就是这几节车，它的票据、预测行不再另列
    Set<String> continued = new HashSet<>();
    for (int i = 0; i < internal.size(); i++) {
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row = internal.get(i);
      divisions
          .get(i)
          .filter(found -> !found.divided() && found.stopIndex() == row.stopIndex())
          .flatMap(DivisionForecast::detachedTrip)
          .ifPresent(continued::add);
    }
    List<BoardRow> rows = new ArrayList<>();
    for (int i = 0; i < internal.size(); i++) {
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row = internal.get(i);
      if (row.trainName().isEmpty()
          && row.serviceTripId().filter(continued::contains).isPresent()) {
        continue;
      }
      Optional<DivisionForecast> division = divisions.get(i);
      Optional<DivisionForecast.Part> onward = onwardPart(row, division);
      rows.add(
          new BoardRow(
              row.lineName(),
              row.routeId(),
              onward.map(DivisionForecast.Part::destination).orElse(row.destination()),
              onward.flatMap(DivisionForecast.Part::destinationId).or(row::destinationId),
              row.endRoute(),
              row.endRouteId(),
              row.endOperation(),
              row.endOperationId(),
              row.platform(),
              row.statusText(),
              convertReasons(row.reasons()),
              row.eta().toEpochMilli(),
              convertPhase(row.phase()),
              row.stopIndex(),
              row.passing(),
              row.terminating() && onward.isEmpty(),
              row.outOfService(),
              row.trainName(),
              row.delaySeconds(),
              row.platformPending(),
              row.platformCandidates(),
              row.platformPlanned(),
              row.load()
                  .map(
                      load ->
                          load.cars().stream()
                              .map(car -> new EtaApi.CarLoad(car.seats(), car.occupied()))
                              .toList())
                  .orElse(List.of()),
              division.map(EtaApiImpl::division),
              joiningOf(row)));
    }
    return new BoardResult(List.copyOf(rows));
  }

  /**
   * 这一行的联挂：回程支线的行写前方的联挂站（站牌服务按交路给出），再从联挂预测补上节数；主干在本站联挂时写本站。运行中的车按现场预测；
   * 票据与预测行、折返站上按下一趟列出还没停到本站的车按表上那一班（与分行同一口径，见 {@link #divisionOf}）。
   */
  private Optional<EtaApi.Joining> joiningOf(
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row) {
    Optional<JoinForecast> forecast =
        row.trainName()
            .flatMap(joinings)
            .filter(found -> found.routeId().equalsIgnoreCase(row.routeId()));
    boolean standing =
        row.trainName().isPresent()
            && row.phase() == org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardPhase.AT_STATION;
    if (forecast.isEmpty() && !standing) {
      forecast =
          row.serviceTripId()
              .flatMap(plannedJoinings)
              .filter(found -> found.routeId().equalsIgnoreCase(row.routeId()));
    }
    if (row.joining().isPresent()) {
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.Joining ahead = row.joining().get();
      Optional<JoinForecast> cars =
          forecast.filter(found -> !found.trunk() && found.stopIndex() == ahead.stopIndex());
      return Optional.of(
          new EtaApi.Joining(
              ahead.stopIndex(),
              ahead.stationId().orElse(""),
              ahead.station(),
              cars.map(JoinForecast::ownCars).orElse(0),
              cars.map(JoinForecast::joiningCars).orElse(0),
              cars.map(JoinForecast::joiningStanding).orElse(false),
              ahead.continuesOn(),
              ahead.continuesFrom()));
    }
    return forecast
        .filter(found -> found.trunk() && found.stopIndex() == row.stopIndex())
        .map(
            here ->
                new EtaApi.Joining(
                    here.stopIndex(),
                    here.stationId(),
                    here.station(),
                    here.ownCars(),
                    here.joiningCars(),
                    here.joiningStanding(),
                    Optional.empty(),
                    -1));
  }

  /**
   * 列车分行时主目的地改写为主段（分行后开得更远的一段，{@link DivisionForecast#primary}）的去向；主段就是这一行原来的终点、或两段都不载客时为空。
   * 在运营终点分行、解下的一段接着载客时，主段就是解下的一段，本站也就不再是终到。
   */
  private static Optional<DivisionForecast.Part> onwardPart(
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row,
      Optional<DivisionForecast> division) {
    return division
        .filter(found -> !found.divided())
        .flatMap(DivisionForecast::primary)
        .filter(primary -> !primary.destinationId().equals(row.destinationId()));
  }

  /**
   * 这一行的列车在本站或前方分行：同一交路、分行站不在本站之前。运行中的车按车名查现场预测（按车上编组切）；票据与预测行按表上那一班。
   * 折返站上按下一趟列出、还在开来路上的车，现场预测要等这一趟跑完才算下一趟，这时也按表上的下一趟；车停在终点以后只认现场预测（编组切不开就不写）。
   */
  private Optional<DivisionForecast> divisionOf(
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row) {
    if (row.stopIndex() < 0) {
      return Optional.empty();
    }
    Optional<DivisionForecast> live =
        row.trainName().flatMap(divisions).filter(division -> covers(division, row));
    if (live.isPresent()
        || (row.trainName().isPresent()
            && row.phase() == org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardPhase.AT_STATION)) {
      return live;
    }
    return row.serviceTripId().flatMap(plannedDivisions).filter(division -> covers(division, row));
  }

  private static boolean covers(
      DivisionForecast division,
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult.BoardRow row) {
    return division.routeId().equalsIgnoreCase(row.routeId())
        && division.stopIndex() >= row.stopIndex();
  }

  private static EtaApi.Division division(DivisionForecast found) {
    return new EtaApi.Division(
        found.stopIndex(),
        found.stationId(),
        found.station(),
        found.parts().stream()
            .map(
                part ->
                    new EtaApi.DivisionPart(
                        part.cars(),
                        part.detached(),
                        part.destinationId(),
                        part.destination(),
                        part.stops(),
                        part.route(),
                        part.fromIndex()))
            .toList(),
        found.divided());
  }

  private static BoardPhase convertPhase(
      org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardPhase phase) {
    return switch (phase) {
      case FORECAST -> BoardPhase.FORECAST;
      case PENDING -> BoardPhase.PENDING;
      case EN_ROUTE -> BoardPhase.EN_ROUTE;
      case ARRIVING -> BoardPhase.ARRIVING;
      case AT_STATION -> BoardPhase.AT_STATION;
    };
  }
}
