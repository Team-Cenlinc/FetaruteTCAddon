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

    assertTrue(request.takeoverStation().isEmpty());
    assertEquals(-1, request.takeoverStopSequence());
    assertTrue(request.handoverStation().isEmpty());
    assertEquals(DriveApi.Mode.MANUAL, request.mode());
    assertFalse(request.depotPickup());
    assertTrue(request.notifyPlayer());
    assertTrue(request.rewards());
    assertEquals("api", request.source());
  }

  @Test
  @DisplayName("每一步只改自己那一项")
  void chaining() {
    DriveApi.TaskRequest request =
        DriveApi.TaskRequest.trip(TT, "R1-001", DAY)
            .takeoverAt("AAA")
            .handoverAt("CCC")
            .mode(DriveApi.Mode.ATO)
            .depotPickup(true)
            .tagged("typewriter", Map.of("quest", "tutorial"))
            .notifyPlayer(false)
            .rewards(false);

    assertEquals(Optional.of("AAA"), request.takeoverStation());
    assertEquals(Optional.of("CCC"), request.handoverStation());
    assertEquals(DriveApi.Mode.ATO, request.mode());
    assertTrue(request.depotPickup());
    assertEquals("typewriter", request.source());
    assertEquals(Map.of("quest", "tutorial"), request.metadata());
    assertFalse(request.notifyPlayer());
    assertFalse(request.rewards());
    assertTrue(request.handoverAt(" ").handoverStation().isEmpty(), "空白站码等于不写");
  }

  @Test
  @DisplayName("从可领取的车次开始")
  void fromOffer() {
    DriveApi.TaskOffer offer =
        new DriveApi.TaskOffer(
            TT, "R1-002", DAY, "R1", 3, Instant.EPOCH, Optional.empty(), false, Optional.empty());

    DriveApi.TaskRequest request = DriveApi.TaskRequest.of(offer, "BBB");

    assertEquals("R1-002", request.tripCode());
    assertEquals(Optional.of("BBB"), request.takeoverStation());
    assertEquals(3, request.takeoverStopSequence(), "从这一次停靠接班");
    assertEquals(3, request.handoverAt("CCC").mode(DriveApi.Mode.ATO).takeoverStopSequence());
    assertEquals(-1, request.takeoverAt("BBB").takeoverStopSequence(), "改接班站后按站码找");
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("1.12.0 前的旧名照常可用：接班站、交班站与停车结果")
  void legacyNamesStillWork() {
    DriveApi.TaskRequest request =
        DriveApi.TaskRequest.trip(TT, "R1-001", DAY).boardAt("AAA").alightAt("CCC");
    assertEquals(Optional.of("AAA"), request.boardStation());
    assertEquals(Optional.of("AAA"), request.takeoverStation());
    assertEquals(-1, request.boardStopSequence());
    assertEquals(Optional.of("CCC"), request.alightStation());
    assertEquals(Optional.of("CCC"), request.handoverStation());

    DriveApi.StationRef takeover = new DriveApi.StationRef("AAA", "A 站", 1);
    DriveApi.TaskView view =
        new DriveApi.TaskView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TT,
            "R1-001",
            DAY,
            "R1",
            takeover,
            Optional.empty(),
            Instant.EPOCH,
            DriveApi.Mode.MANUAL,
            DriveApi.TaskState.CLAIMED,
            Optional.empty(),
            false,
            "api",
            Map.of(),
            "",
            java.util.OptionalInt.empty(),
            Optional.empty());
    assertEquals(takeover, view.board());
    assertTrue(view.alight().isEmpty());

    for (DriveApi.StopOutcome outcome : DriveApi.StopOutcome.values()) {
      DriveApi.StopResult stop = new DriveApi.StopResult("A 站", 0.0, outcome, false, false);
      assertEquals(outcome.name(), stop.window().name(), "旧枚举与新枚举常量一一对应");
    }
  }
}
