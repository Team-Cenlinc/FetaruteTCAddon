package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockerClassifierInterlockingTest {

  @Test
  void physicalInterlockingClaimsRemainHardAcrossMovementAndProtectiveRequests() {
    OccupancyResource zone = OccupancyResource.forConflict("interlocking:zone-a");
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    List<ResourceIntent> requestIntents =
        List.of(
            ResourceIntent.MOVEMENT_REQUIRED,
            ResourceIntent.PROTECTIVE_RETAIN,
            ResourceIntent.HOLD_ONLY);
    List<ClaimRole> claimRoles =
        List.of(
            ClaimRole.MOVEMENT_REQUIRED,
            ClaimRole.PROTECTIVE_RETAIN,
            ClaimRole.HOLD_ONLY,
            ClaimRole.PHYSICAL_FOOTPRINT,
            ClaimRole.UNLOCK_RESERVATION);
    for (ResourceIntent requestIntent : requestIntents) {
      OccupancyRequest request =
          new OccupancyRequest(
              "requester",
              Optional.empty(),
              now.plusSeconds(1),
              List.of(zone),
              Map.of(),
              Map.of(),
              0,
              AuthorizationPurpose.RUNTIME_MOVE,
              Map.of(),
              Map.of(zone, requestIntent));
      for (ClaimRole role : claimRoles) {
        OccupancyClaim claim =
            new OccupancyClaim(
                zone, "incumbent", Optional.empty(), now, Duration.ZERO, Optional.empty(), role);

        assertEquals(
            BlockerRelation.HARD_OCCUPANCY, BlockerClassifier.classify(request, zone, claim));
      }
    }
  }
}
