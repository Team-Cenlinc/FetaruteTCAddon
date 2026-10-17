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

/** 站牌行 1.9.0：结构化字段原样对外，1.8.0 构造器保持可用；1.14.0 起运行中列车带分行。 */
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
            Optional.of(new TrainLoad(List.of(new TrainLoad.Car(4, 1), new TrainLoad.Car(4, 6)))));
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

  private static BoardResult.BoardRow internalRow(
      String routeId, int stopIndex, Optional<String> trainName) {
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
