package org.fetarute.fetaruteTCAddon.api.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GuardApi：派任务请求的链式设置与占位实现")
class GuardApiTaskRequestTest {

  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

  @Test
  @DisplayName("默认：从第一个停车的车站值乘到终点站、给玩家发提示、发奖励")
  void defaults() {
    GuardApi.TaskRequest request = GuardApi.TaskRequest.trip(TT, "R1-001", DAY);
    assertTrue(request.takeoverStation().isEmpty());
    assertEquals(-1, request.takeoverStopSequence());
    assertTrue(request.handoverStation().isEmpty());
    assertTrue(request.notifyPlayer());
    assertTrue(request.rewards());
    assertEquals("api", request.source());
  }

  @Test
  @DisplayName("每一步只改自己那一项；从可领取车次开始时带上它的停靠序号")
  void chaining() {
    GuardApi.TaskRequest request =
        GuardApi.TaskRequest.trip(TT, "R1-001", DAY)
            .takeoverAt("HHU")
            .handoverAt("KPO")
            .tagged("quest", Map.of("id", "7"))
            .notifyPlayer(false)
            .rewards(false);
    assertEquals(Optional.of("HHU"), request.takeoverStation());
    assertEquals(Optional.of("KPO"), request.handoverStation());
    assertEquals("quest", request.source());
    assertEquals(Map.of("id", "7"), request.metadata());
    assertFalse(request.notifyPlayer());
    assertFalse(request.rewards());
    assertEquals(Optional.empty(), request.handoverAt(" ").handoverStation());

    DriveApi.TaskOffer offer =
        new DriveApi.TaskOffer(
            TT, "R1-002", DAY, "R1", 4, Instant.EPOCH, Optional.empty(), false, Optional.empty());
    GuardApi.TaskRequest fromOffer = GuardApi.TaskRequest.of(offer, "LYM");
    assertEquals(4, fromOffer.takeoverStopSequence());
    assertEquals(Optional.of("LYM"), fromOffer.takeoverStation());
    assertEquals(-1, fromOffer.takeoverAt("LYM").takeoverStopSequence(), "换站后取第一次停靠");
  }

  @Test
  @DisplayName("占位实现：不可用、查询为空、派任务返回 DISABLED")
  void unavailable() {
    GuardApi api = GuardApi.UNAVAILABLE;
    assertFalse(api.enabled());
    assertTrue(api.taskOf(UUID.randomUUID()).isEmpty());
    assertTrue(api.dutyOf(UUID.randomUUID()).isEmpty());
    assertTrue(api.guardOf("T").isEmpty());
    assertEquals(List.of(), api.offersAt("HHU", Instant.EPOCH, java.time.Duration.ZERO, 5));
    assertFalse(api.abandon(UUID.randomUUID(), "x"));
    assertEquals(List.of(), api.records(UUID.randomUUID(), 5).join());
    assertTrue(GuardApi.TaskState.ON_DUTY.finished() == false);
    assertTrue(GuardApi.TaskState.EXPIRED.finished());
  }
}
