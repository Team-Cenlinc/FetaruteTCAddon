package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.call.CallApi;
import org.fetarute.fetaruteTCAddon.call.CallCatalog;
import org.fetarute.fetaruteTCAddon.call.CallRules;
import org.fetarute.fetaruteTCAddon.call.CallService;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 叫车 API 把叫车服务的判定与叫车单换成对外的写法。 */
class CallApiImplTest {

  private static final UUID LINE = UUID.randomUUID();

  private static CallCatalog.CallDirection direction() {
    return new CallCatalog.CallDirection(
        "WS|NTA|1",
        "SURC",
        LINE,
        "WS",
        RoutePatternType.RAPID,
        new PidsStationKey("SURC", "NTA"),
        Set.of("1"),
        List.of());
  }

  @Test
  void anAvailableDirectionReportsTheCallsEta() {
    CallApi.Direction out =
        CallApiImpl.direction(
            new CallService.CallOption(
                direction(),
                new CallRules.Verdict(CallRules.Outcome.AVAILABLE, OptionalInt.empty(), 0L),
                Optional.of(new CallService.CallPlan(OptionalInt.of(6)))));

    assertEquals(CallApi.Availability.AVAILABLE, out.availability());
    assertEquals(OptionalInt.of(6), out.minutes());
    assertEquals("RAPID", out.pattern());
    assertEquals("NTA", out.destinationStation());
    assertEquals(Set.of("1"), out.platforms());
    assertEquals(LINE, out.lineId());
  }

  @Test
  void anUnavailableDirectionReportsTheOtherTrainsMinutes() {
    CallApi.Direction out =
        CallApiImpl.direction(
            new CallService.CallOption(
                direction(),
                new CallRules.Verdict(CallRules.Outcome.NEXT_TRAIN_SOON, OptionalInt.of(3), 0L),
                Optional.of(new CallService.CallPlan(OptionalInt.of(6)))));

    assertEquals(CallApi.Availability.NEXT_TRAIN_SOON, out.availability());
    assertEquals(OptionalInt.of(3), out.minutes());
    assertEquals(
        CallApi.Availability.LINE_LIMIT, CallApiImpl.availability(CallRules.Outcome.LINE_LIMIT));
    assertEquals(
        CallApi.Availability.NO_SOURCE, CallApiImpl.availability(CallRules.Outcome.NO_SOURCE));
  }

  @Test
  void snapshotsBecomeCallViews() {
    UUID id = UUID.randomUUID();
    UUID player = UUID.randomUUID();
    Instant at = Instant.parse("2026-10-10T12:00:00Z");
    CallApi.CallView pending =
        CallApiImpl.view(
            new CallService.CallSnapshot(
                id,
                Optional.of(player),
                new PidsStationKey("SURC", "PPK"),
                Optional.of("WS|NTA|1"),
                false,
                Optional.empty(),
                Optional.of(at)));

    assertEquals(CallApi.State.PENDING, pending.state());
    assertEquals(Optional.of(player), pending.playerId());
    assertEquals("PPK", pending.stationCode());
    assertEquals(Optional.of(at), pending.createdAt());
    assertEquals(
        CallApi.State.DISPATCHED,
        CallApiImpl.view(
                new CallService.CallSnapshot(
                    id,
                    Optional.empty(),
                    new PidsStationKey("SURC", "PPK"),
                    Optional.empty(),
                    true,
                    Optional.of("Train-1"),
                    Optional.empty()))
            .state());
  }
}
