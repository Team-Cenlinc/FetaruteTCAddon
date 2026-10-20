package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardPhase;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.BoardResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainLoad;
import org.junit.jupiter.api.Test;

/** 站牌行 1.9.0：结构化字段原样对外，1.8.0 构造器保持可用；1.14.0 起运行中列车带分行、回程支线的车带联挂。 */
class EtaApiBoardRowTest {

  @Test
  void structuredFieldsAreExposed() {
    Instant eta = Instant.parse("2026-09-30T12:03:00Z");
    BoardResult.BoardRow internal =
        new BoardResult.BoardRow(
            "L1",
            "SURN:L1:R1",
            "CCC",
            Optional.of("SURN:CCC"),
            "CCC",
            Optional.of("SURN:CCC"),
            "CCC",
            Optional.of("SURN:CCC"),
            "1",
            "Arriving",
            List.of(EtaReason.HOLD),
            eta,
            BoardPhase.ARRIVING,
            2,
            false,
            true,
            false,
            Optional.of("train-1"),
            OptionalLong.of(75L),
            false,
            List.of(),
            true,
            Optional.of(new TrainLoad(List.of(new TrainLoad.Car(4, 1), new TrainLoad.Car(4, 6)))),
            Optional.empty(),
            Optional.empty());
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)))
        .thenReturn(new BoardResult(List.of(internal)));

    EtaApi.BoardRow row =
        new EtaApiImpl(service).getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows().get(0);

    assertEquals(Optional.of(eta), row.eta());
    assertEquals(EtaApi.BoardPhase.ARRIVING, row.phase());
    assertEquals(2, row.stopSequence());
    assertFalse(row.passing());
    assertTrue(row.terminating());
    assertFalse(row.outOfService());
    assertEquals(Optional.of("train-1"), row.trainName());
    assertEquals(OptionalLong.of(75L), row.delaySeconds());
    assertEquals(List.of(EtaApi.Reason.HOLD), row.reasons());
    assertTrue(row.platformPlanned());
    assertFalse(row.platformPending());
    assertEquals(
        List.of(new EtaApi.CarLoad(4, 1), new EtaApi.CarLoad(4, 4)), row.cars(), "在座多于座位时按座位数计");
    assertEquals(java.util.OptionalInt.of(3), row.vacantSeats());
  }

  @Test
  void divisionIsAttachedOnlyWhenTheTrainDividesHereOrAhead() {
    org.fetarute.fetaruteTCAddon.coupler.DivisionForecast forecast =
        new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast(
            "SURN:L1:R1",
            4,
            "SURN:HHU",
            "壑湖",
            List.of(
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    4, false, Optional.of("SURN:NTA"), "南渡"),
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    4, true, Optional.empty(), "")));
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(
                List.of(
                    internalRow("SURN:L1:R1", 2, Optional.of("train-1")),
                    internalRow("SURN:L1:R1", 5, Optional.of("train-1")),
                    internalRow("SURN:L1:R2", 2, Optional.of("train-1")),
                    internalRow("SURN:L1:R1", 2, Optional.empty()))));
    EtaApiImpl api =
        new EtaApiImpl(
            service,
            name -> "TRAIN-1".equalsIgnoreCase(name) ? Optional.of(forecast) : Optional.empty());

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows();

    EtaApi.Division division = rows.get(0).division().orElseThrow();
    assertEquals(4, division.stopSequence());
    assertEquals("SURN:HHU", division.stationId());
    assertEquals("壑湖", division.station());
    assertEquals(
        List.of(
            new EtaApi.DivisionPart(4, false, Optional.of("SURN:NTA"), "南渡"),
            new EtaApi.DivisionPart(4, true, Optional.empty(), "")),
        division.parts());
    assertTrue(division.ahead(2));
    assertFalse(division.parts().get(1).inService());
    assertTrue(rows.get(1).division().isEmpty(), "分行站在本站之前：这里已经拆过了");
    assertTrue(rows.get(2).division().isEmpty(), "别的交路");
    assertTrue(rows.get(3).division().isEmpty(), "票据、预测没有车名");
  }

  /** 回程支线的车：主目的地是联挂后主干的终点，运营终点仍是联挂站。 */
  @Test
  void joiningIsExposedWithTheContinuedDestination() {
    BoardResult.BoardRow internal =
        internalRow(
            "SURN:L1:BB",
            2,
            Optional.of("train-1"),
            Optional.of(new BoardResult.Joining(5, "壑湖", Optional.of("SURN:HHU"))));
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(List.of(internal, internalRow("SURN:L1:R1", 2, Optional.empty()))));

    List<EtaApi.BoardRow> rows =
        new EtaApiImpl(service).getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows();

    assertEquals(Optional.of(new EtaApi.Joining(5, "SURN:HHU", "壑湖")), rows.get(0).joining());
    assertTrue(rows.get(1).joining().isEmpty());
  }

  /** 联挂的节数从联挂预测补上：回程支线的车写前方的联挂站，主干在本站联挂时写本站；主干还没开到联挂站时不写。 */
  @Test
  void joiningCarsComeFromTheJoinForecast() {
    org.fetarute.fetaruteTCAddon.coupler.JoinForecast feeder =
        new org.fetarute.fetaruteTCAddon.coupler.JoinForecast(
            "SURN:L1:BB", 5, "SURN:HHU", "壑湖", false, 4, 4, true);
    org.fetarute.fetaruteTCAddon.coupler.JoinForecast trunk =
        new org.fetarute.fetaruteTCAddon.coupler.JoinForecast(
            "SURN:L1:R1", 5, "SURN:HHU", "壑湖", true, 4, 2, false);
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(
                List.of(
                    internalRow(
                        "SURN:L1:BB",
                        2,
                        Optional.of("feeder"),
                        Optional.of(new BoardResult.Joining(5, "壑湖", Optional.of("SURN:HHU")))),
                    internalRow("SURN:L1:R1", 5, Optional.of("trunk")),
                    internalRow("SURN:L1:R1", 2, Optional.of("trunk")))));
    EtaApiImpl api =
        new EtaApiImpl(
            service,
            name -> Optional.empty(),
            trip -> Optional.empty(),
            name -> Optional.of("feeder".equals(name) ? feeder : trunk));

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)).rows();

    assertEquals(
        Optional.of(new EtaApi.Joining(5, "SURN:HHU", "壑湖", 4, 4, true, Optional.empty(), -1)),
        rows.get(0).joining());
    EtaApi.Joining here = rows.get(1).joining().orElseThrow();
    assertTrue(here.at(rows.get(1).stopSequence()), "主干在本站联挂");
    assertEquals(2, here.joiningCars());
    assertFalse(here.joiningStanding());
    assertTrue(rows.get(2).joining().isEmpty(), "主干在前方联挂：主干上的乘客不受影响，不写");
  }

  /** 还没出车的一班按表上联挂补节数：回程支线的行写前方站联挂，主干在联挂站的行写本站联挂；停在本站的运行中列车只认现场。 */
  @Test
  void plannedTripsJoinByTheTimetable() {
    org.fetarute.fetaruteTCAddon.coupler.JoinForecast feeder =
        new org.fetarute.fetaruteTCAddon.coupler.JoinForecast(
            "SURN:L1:BB", 5, "SURN:HHU", "壑湖", false, 1, 2, false);
    org.fetarute.fetaruteTCAddon.coupler.JoinForecast trunk =
        new org.fetarute.fetaruteTCAddon.coupler.JoinForecast(
            "SURN:L1:R1", 5, "SURN:HHU", "壑湖", true, 2, 1, false);
    BoardResult.BoardRow feederTicket =
        withServiceTrip(
            internalRow(
                "SURN:L1:BB",
                2,
                Optional.empty(),
                Optional.of(new BoardResult.Joining(5, "壑湖", Optional.of("SURN:HHU")))),
            "trip-feeder");
    BoardResult.BoardRow trunkTicket =
        withServiceTrip(internalRow("SURN:L1:R1", 5, Optional.empty()), "trip-trunk");
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)))
        .thenReturn(new BoardResult(List.of(feederTicket, trunkTicket)));
    EtaApiImpl api =
        new EtaApiImpl(
            service,
            name -> Optional.empty(),
            trip -> Optional.empty(),
            name -> Optional.empty(),
            trip -> Optional.of("trip-feeder".equals(trip) ? feeder : trunk));

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)).rows();

    EtaApi.Joining ahead = rows.get(0).joining().orElseThrow();
    assertEquals(1, ahead.ownCars());
    assertEquals(2, ahead.joiningCars());
    EtaApi.Joining here = rows.get(1).joining().orElseThrow();
    assertTrue(here.at(5), "主干还没出车：按表在本站联挂");
    assertEquals(2, here.ownCars());
  }

  /** 刚在本站拆开：解下的车那一行带拆开以后的车厢标识，终点照它自己这一班写，不按停站多的一段改写。 */
  @Test
  void aDividedRowKeepsItsOwnDestination() {
    org.fetarute.fetaruteTCAddon.coupler.DivisionForecast before = dividingAtHhu(1);
    org.fetarute.fetaruteTCAddon.coupler.DivisionForecast divided =
        before.dividedFor(before.parts().get(1)).orElseThrow();
    BoardResult.BoardRow ndo = internalRow("SURN:L1:BR", 0, Optional.of("detached"));
    BoardResult.BoardRow toNdo =
        new BoardResult.BoardRow(
            ndo.lineName(),
            ndo.routeId(),
            "南渡",
            Optional.of("SURN:NDO"),
            ndo.endRoute(),
            ndo.endRouteId(),
            ndo.endOperation(),
            ndo.endOperationId(),
            ndo.platform(),
            ndo.statusText(),
            ndo.reasons(),
            ndo.eta(),
            BoardPhase.AT_STATION,
            ndo.stopIndex(),
            false,
            false,
            false,
            ndo.trainName(),
            OptionalLong.empty(),
            false,
            List.of(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.of("trip-ndo"));
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)))
        .thenReturn(new BoardResult(List.of(toNdo)));

    EtaApi.BoardRow row =
        new EtaApiImpl(service, name -> Optional.of(divided))
            .getBoard("SURN", "HHU", null, Duration.ofMinutes(10))
            .rows()
            .get(0);

    assertEquals("南渡", row.destination(), "留下的一段停站更多，但这一行是解下的车自己的一班");
    EtaApi.Division division = row.division().orElseThrow();
    assertTrue(division.divided());
    assertEquals(0, division.stopSequence());
    assertTrue(division.parts().get(1).detached());
  }

  /** 还没出车的一班按车次标识查表上的分行；在运营终点分行、解下的一段接着载客时这一站不算终到，主目的地写那一段的去向。 */
  @Test
  void plannedTripsDivideAndADivisionAtTheTerminusIsNotATermination() {
    // 主干在壑湖（运营终点，停靠序号 4）分行：前 1 节开往南渡，后 1 节本站止
    org.fetarute.fetaruteTCAddon.coupler.DivisionForecast atTerminus =
        new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast(
            "SURN:L1:R1",
            4,
            "SURN:HHU",
            "壑湖",
            List.of(
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    1, true, Optional.of("SURN:NTA"), "南渡"),
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    1, false, Optional.empty(), "")),
            Optional.of("B201"));
    BoardResult.BoardRow branchFirstTrip = internalRow("SURN:L1:BR", 0, Optional.empty());
    branchFirstTrip = withServiceTrip(branchFirstTrip, "B201");
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(
                List.of(
                    branchFirstTrip,
                    terminatingRow(4, Optional.empty(), Optional.of("T101")),
                    terminatingRow(4, Optional.empty(), Optional.of("T102")),
                    terminatingRow(4, Optional.of("train-1"), Optional.empty()),
                    internalRow("SURN:L1:R1", 2, Optional.empty()),
                    withServiceTrip(internalRow("SURN:L1:BR", 0, Optional.empty()), "B202"))));
    EtaApiImpl api =
        new EtaApiImpl(
            service,
            name -> "train-1".equals(name) ? Optional.of(atTerminus) : Optional.empty(),
            trip -> "T101".equals(trip) ? Optional.of(atTerminus) : Optional.empty());

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "HHU", null, Duration.ofMinutes(10)).rows();

    assertEquals(5, rows.size(), "分行出去的那几节车接着跑的子交路首班不另列一行");
    assertEquals("SURN:L1:BR", rows.get(4).routeId(), "别的班照常列出");
    EtaApi.BoardRow planned = rows.get(0);
    assertTrue(planned.division().isPresent(), "表上那一班要拆：还没出车也写分行");
    assertFalse(planned.terminating(), "解下的一段接着开往南渡：本站不是终到");
    assertEquals("南渡", planned.destination());
    assertEquals(Optional.of("SURN:NTA"), planned.destinationId());
    assertEquals("HHU", planned.endOperation(), "运营终点仍是本交路自己的");

    EtaApi.BoardRow notSplit = rows.get(1);
    assertTrue(notSplit.division().isEmpty(), "表上那一班不拆");
    assertTrue(notSplit.terminating());
    assertEquals("HHU", notSplit.destination());

    EtaApi.BoardRow running = rows.get(2);
    assertFalse(running.terminating(), "运行中的车同样按分行改写");
    assertEquals("南渡", running.destination());

    assertTrue(rows.get(3).division().isEmpty(), "没有车次标识的行不查表");
  }

  /** 折返站上按下一趟列出、还在开来路上的车：现场预测还没算下一趟，按表上的下一趟写分行；车停在终点以后只认现场预测。中途分行时子交路首班同样不另列。 */
  @Test
  void anInboundTrainListedAsItsNextTripUsesThePlanUntilItStands() {
    org.fetarute.fetaruteTCAddon.coupler.DivisionForecast midway =
        new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast(
            "SURN:L1:R1",
            2,
            "SURN:CCC",
            "CCC",
            List.of(
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    1, true, Optional.of("SURN:NTA"), "南渡"),
                new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                    1, false, Optional.of("SURN:WYB"), "湾油埠")),
            Optional.of("B301"));
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(
                List.of(
                    withServiceTrip(internalRow("SURN:L1:R1", 2, Optional.of("inbound")), "T201"),
                    terminatingRow(2, Optional.of("standing"), Optional.of("T201")),
                    withServiceTrip(internalRow("SURN:L1:BR", 0, Optional.empty()), "B301"))));
    EtaApiImpl api =
        new EtaApiImpl(
            service,
            name -> Optional.empty(),
            trip -> "T201".equals(trip) ? Optional.of(midway) : Optional.empty());

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "CCC", null, Duration.ofMinutes(10)).rows();

    assertEquals(2, rows.size(), "本站分行：解下那几节车接着跑的子交路首班不另列");
    assertTrue(rows.get(0).division().isPresent(), "还在路上：按表上的下一趟");
    assertTrue(rows.get(1).division().isEmpty(), "已经停在终点：现场预测说不拆就不写");
  }

  /** 前方分行：主目的地写分行后停站更多的一段；一样多时照本交路的终点。 */
  @Test
  void aDividingTrainHeadsForThePartThatGoesFurther() {
    EtaService service = mock(EtaService.class);
    when(service.getBoard("SURN", "PPK", null, Duration.ofMinutes(10)))
        .thenReturn(
            new BoardResult(
                List.of(
                    internalRow("SURN:L1:R1", 2, Optional.of("further")),
                    internalRow("SURN:L1:R1", 2, Optional.of("equal")))));
    EtaApiImpl api =
        new EtaApiImpl(service, name -> Optional.of(dividingAtHhu("further".equals(name) ? 3 : 2)));

    List<EtaApi.BoardRow> rows = api.getBoard("SURN", "PPK", null, Duration.ofMinutes(10)).rows();

    assertEquals("南渡", rows.get(0).destination());
    assertEquals(Optional.of("SURN:NDO"), rows.get(0).destinationId());
    assertFalse(rows.get(0).terminating());
    assertEquals("NTA", rows.get(1).destination(), "停站一样多：照本交路的终点");
    assertEquals(
        List.of(2, 2),
        rows.get(1).division().orElseThrow().parts().stream()
            .map(EtaApi.DivisionPart::stops)
            .toList());
  }

  /** 在壑湖（停靠序号 4）分行：前 4 节留下、开到本交路终点（停 2 站），后 4 节解下开往南渡（停 {@code branchStops} 站）。 */
  private static org.fetarute.fetaruteTCAddon.coupler.DivisionForecast dividingAtHhu(
      int branchStops) {
    return new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast(
        "SURN:L1:R1",
        4,
        "SURN:HHU",
        "壑湖",
        List.of(
            new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                4, false, Optional.of("SURN:NTA"), "NTA", 2, Optional.of("SURN:L1:R1"), 4),
            new org.fetarute.fetaruteTCAddon.coupler.DivisionForecast.Part(
                4,
                true,
                Optional.of("SURN:NDO"),
                "南渡",
                branchStops,
                Optional.of("SURN:L1:BR"),
                0)));
  }

  private static BoardResult.BoardRow withServiceTrip(BoardResult.BoardRow row, String trip) {
    return new BoardResult.BoardRow(
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
        row.reasons(),
        row.eta(),
        row.phase(),
        row.stopIndex(),
        row.passing(),
        row.terminating(),
        row.outOfService(),
        row.trainName(),
        row.delaySeconds(),
        row.platformPending(),
        row.platformCandidates(),
        row.platformPlanned(),
        row.load(),
        row.joining(),
        Optional.of(trip));
  }

  private static BoardResult.BoardRow terminatingRow(
      int stopIndex, Optional<String> trainName, Optional<String> serviceTripId) {
    return new BoardResult.BoardRow(
        "L1",
        "SURN:L1:R1",
        "HHU",
        Optional.of("SURN:HHU"),
        "HHU",
        Optional.of("SURN:HHU"),
        "HHU",
        Optional.of("SURN:HHU"),
        "4",
        "Boarding",
        List.of(),
        Instant.parse("2026-10-15T12:03:00Z"),
        BoardPhase.AT_STATION,
        stopIndex,
        false,
        true,
        false,
        trainName,
        OptionalLong.empty(),
        false,
        List.of(),
        false,
        Optional.empty(),
        Optional.empty(),
        serviceTripId);
  }

  private static BoardResult.BoardRow internalRow(
      String routeId, int stopIndex, Optional<String> trainName) {
    return internalRow(routeId, stopIndex, trainName, Optional.empty());
  }

  private static BoardResult.BoardRow internalRow(
      String routeId,
      int stopIndex,
      Optional<String> trainName,
      Optional<BoardResult.Joining> joining) {
    return new BoardResult.BoardRow(
        "L1",
        routeId,
        "NTA",
        Optional.of("SURN:NTA"),
        "NTA",
        Optional.of("SURN:NTA"),
        "NTA",
        Optional.of("SURN:NTA"),
        "1",
        "3m",
        List.of(),
        Instant.parse("2026-10-15T12:03:00Z"),
        BoardPhase.EN_ROUTE,
        stopIndex,
        false,
        false,
        false,
        trainName,
        OptionalLong.empty(),
        false,
        List.of(),
        false,
        Optional.empty(),
        joining,
        Optional.empty());
  }

  @Test
  void everyInternalPhaseHasAnApiCounterpart() {
    for (BoardPhase phase : BoardPhase.values()) {
      assertEquals(phase.name(), EtaApi.BoardPhase.valueOf(phase.name()).name());
    }
  }

  @Test
  void legacyConstructorDefaultsTheNewFields() {
    EtaApi.BoardRow row = legacyRow(Optional.of("SURN:CCC"));

    assertEquals(0L, row.etaEpochMillis());
    assertTrue(row.eta().isEmpty());
    assertEquals(EtaApi.BoardPhase.EN_ROUTE, row.phase());
    assertEquals(-1, row.stopSequence());
    assertFalse(row.passing());
    assertFalse(row.terminating());
    assertFalse(row.outOfService());
    assertTrue(row.trainName().isEmpty());
    assertTrue(row.delaySeconds().isEmpty());
    assertTrue(row.division().isEmpty());
  }

  @Test
  void legacyConstructorInfersOutOfServiceFromTheDestinationId() {
    assertTrue(legacyRow(Optional.of("OUT_OF_SERVICE")).outOfService());
  }

  private static EtaApi.BoardRow legacyRow(Optional<String> destinationId) {
    return new EtaApi.BoardRow(
        "L1",
        "SURN:L1:R1",
        "CCC",
        destinationId,
        "CCC",
        Optional.empty(),
        "CCC",
        Optional.empty(),
        "1",
        "3m",
        List.of());
  }
}
