package org.fetarute.fetaruteTCAddon.api.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriveApi.TaskRequest 链式设置")
class DriveApiTaskRequestTest {

  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

  @Test
  @DisplayName("默认：开到终点站、人工驾驶、给玩家发提示")
  void defaults() {
    DriveApi.TaskRequest request = DriveApi.TaskRequest.trip(TT, "R1-001", DAY);

    assertTrue(request.boardStation().isEmpty());
    assertEquals(-1, request.boardStopSequence());
    assertTrue(request.alightStation().isEmpty());
    assertEquals(DriveApi.Mode.MANUAL, request.mode());
    assertFalse(request.depotPickup());
    assertTrue(request.notifyPlayer());
    assertEquals("api", request.source());
  }

  @Test
  @DisplayName("每一步只改自己那一项")
  void chaining() {
    DriveApi.TaskRequest request =
        DriveApi.TaskRequest.trip(TT, "R1-001", DAY)
            .boardAt("AAA")
            .alightAt("CCC")
            .mode(DriveApi.Mode.ATO)
            .depotPickup(true)
            .tagged("typewriter", Map.of("quest", "tutorial"))
            .notifyPlayer(false);

    assertEquals(Optional.of("AAA"), request.boardStation());
    assertEquals(Optional.of("CCC"), request.alightStation());
    assertEquals(DriveApi.Mode.ATO, request.mode());
    assertTrue(request.depotPickup());
    assertEquals("typewriter", request.source());
    assertEquals(Map.of("quest", "tutorial"), request.metadata());
    assertFalse(request.notifyPlayer());
    assertTrue(request.alightAt(" ").alightStation().isEmpty(), "空白站码等于不写");
  }

  @Test
  @DisplayName("从可领取的车次开始")
  void fromOffer() {
    DriveApi.TaskOffer offer =
        new DriveApi.TaskOffer(
            TT, "R1-002", DAY, "R1", 3, Instant.EPOCH, Optional.empty(), false, Optional.empty());

    DriveApi.TaskRequest request = DriveApi.TaskRequest.of(offer, "BBB");

    assertEquals("R1-002", request.tripCode());
    assertEquals(Optional.of("BBB"), request.boardStation());
    assertEquals(3, request.boardStopSequence(), "从这一次停靠接班");
    assertEquals(3, request.alightAt("CCC").mode(DriveApi.Mode.ATO).boardStopSequence());
    assertEquals(-1, request.boardAt("BBB").boardStopSequence(), "改接班站后按站码找");
  }
}
