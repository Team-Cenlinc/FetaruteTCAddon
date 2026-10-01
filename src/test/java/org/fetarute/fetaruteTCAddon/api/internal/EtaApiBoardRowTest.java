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
import org.junit.jupiter.api.Test;

/** 站牌行 1.9.0：结构化字段原样对外，1.8.0 构造器保持可用。 */
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
            OptionalLong.of(75L));
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
