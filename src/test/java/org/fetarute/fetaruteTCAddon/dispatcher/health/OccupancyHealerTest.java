package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link OccupancyHealer} 单元测试。 */
@DisplayName("OccupancyHealer 单元测试")
class OccupancyHealerTest {

  private OccupancyManager occupancyManager;
  private HealthAlertBus alertBus;
  private List<String> debugLogs;
  private OccupancyHealer healer;

  @BeforeEach
  void setUp() {
    occupancyManager = mock(OccupancyManager.class);
    alertBus = new HealthAlertBus();
    debugLogs = new ArrayList<>();
    healer = new OccupancyHealer(occupancyManager, alertBus, debugLogs::add);
  }

  /** 创建测试用的占用资源。 */
  private OccupancyResource nodeResource(String nodeId) {
    return OccupancyResource.forNode(NodeId.of(nodeId));
  }

  /** 创建测试用的占用记录。 */
  private OccupancyClaim claim(OccupancyResource resource, String trainName, Instant acquiredAt) {
    return new OccupancyClaim(
        resource, trainName, Optional.empty(), acquiredAt, Duration.ZERO, Optional.empty());
  }

  @Test
  @DisplayName("孤儿占用：列车不存在时释放占用")
  void cleanupOrphanOccupancy() {
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "ghost-train", now.minus(Duration.ofSeconds(10)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    // 活跃列车不包含 ghost-train
    OccupancyHealer.HealResult result = healer.heal(Set.of("active-train"), now);

    assertEquals(1, result.orphanCleaned(), "应清理 1 个孤儿占用");
    assertEquals(0, result.timeoutCleaned());
    verify(occupancyManager).releaseResource(eq(resource), eq(Optional.empty()));
  }

  @Test
  @DisplayName("超时占用：仅对离线列车生效（禁用孤儿清理时）")
  void cleanupTimeoutOccupancy() {
    healer.setOrphanCleanupEnabled(false);
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    // 11 分钟前获取的占用（默认超时 10 分钟）
    OccupancyClaim c = claim(resource, "train1", now.minus(Duration.ofMinutes(11)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    // train1 不在活跃列表，走超时清理分支
    OccupancyHealer.HealResult result = healer.heal(Set.of("other-train"), now);

    assertEquals(0, result.orphanCleaned());
    assertEquals(1, result.timeoutCleaned(), "应清理 1 个超时占用");
    verify(occupancyManager).releaseResource(eq(resource), eq(Optional.empty()));
  }

  @Test
  @DisplayName("正常占用：不满足条件时不释放")
  void normalOccupancyNotCleaned() {
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    // 5 分钟前获取（未超时），列车仍存活
    OccupancyClaim c = claim(resource, "train1", now.minus(Duration.ofMinutes(5)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    OccupancyHealer.HealResult result = healer.heal(Set.of("train1"), now);

    assertEquals(0, result.orphanCleaned());
    assertEquals(0, result.timeoutCleaned());
    assertFalse(result.hasChanges());
    verify(occupancyManager, never()).releaseResource(any(), any());
  }

  @Test
  @DisplayName("禁用孤儿清理：不清理孤儿占用")
  void orphanCleanupDisabled() {
    healer.setOrphanCleanupEnabled(false);
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "ghost-train", now.minus(Duration.ofSeconds(10)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    OccupancyHealer.HealResult result = healer.heal(Set.of("active-train"), now);

    assertEquals(0, result.orphanCleaned(), "禁用时不应清理孤儿占用");
    verify(occupancyManager, never()).releaseResource(any(), any());
  }

  @Test
  @DisplayName("禁用超时清理：不清理超时占用")
  void timeoutCleanupDisabled() {
    healer.setTimeoutCleanupEnabled(false);
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "train1", now.minus(Duration.ofMinutes(11)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    OccupancyHealer.HealResult result = healer.heal(Set.of("train1"), now);

    assertEquals(0, result.timeoutCleaned(), "禁用时不应清理超时占用");
    verify(occupancyManager, never()).releaseResource(any(), any());
  }

  @Test
  @DisplayName("自定义超时阈值：5分钟（仅离线列车）")
  void customTimeoutThreshold() {
    healer.setOrphanCleanupEnabled(false);
    healer.setOccupancyTimeout(Duration.ofMinutes(5));
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    // 6 分钟前获取（超过自定义阈值）
    OccupancyClaim c = claim(resource, "train1", now.minus(Duration.ofMinutes(6)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    OccupancyHealer.HealResult result = healer.heal(Set.of("other-train"), now);

    assertEquals(1, result.timeoutCleaned(), "应按自定义阈值清理");
  }

  @Test
  @DisplayName("告警发布：清理后发布告警")
  void alertPublished() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);

    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "ghost-train", now.minus(Duration.ofSeconds(10)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    healer.heal(Set.of("active-train"), now);

    assertEquals(1, alerts.size());
    HealthAlert alert = alerts.get(0);
    assertEquals(HealthAlert.AlertType.ORPHAN_OCCUPANCY, alert.type());
    assertEquals("ghost-train", alert.trainName());
    assertTrue(alert.autoFixed(), "应标记为已自动修复");
  }

  @Test
  @DisplayName("调试日志：清理时输出日志")
  void debugLogOutput() {
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "ghost-train", now.minus(Duration.ofSeconds(10)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    healer.heal(Set.of("active-train"), now);

    assertEquals(1, debugLogs.size());
    assertTrue(debugLogs.get(0).contains("ghost-train"));
    assertTrue(debugLogs.get(0).contains("孤儿"));
  }

  @Test
  @DisplayName("混合场景：在线列车超时不清理，仅清理孤儿占用")
  void mixedCleanup() {
    Instant now = Instant.now();
    OccupancyResource orphanResource = nodeResource("SURC:S:TEST:1");
    OccupancyResource timeoutResource = nodeResource("SURC:S:TEST:2");
    OccupancyClaim orphanClaim = claim(orphanResource, "ghost", now.minus(Duration.ofSeconds(10)));
    OccupancyClaim timeoutClaim =
        claim(timeoutResource, "train1", now.minus(Duration.ofMinutes(11)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(orphanClaim, timeoutClaim));

    OccupancyHealer.HealResult result = healer.heal(Set.of("train1"), now);

    assertEquals(1, result.orphanCleaned());
    assertEquals(0, result.timeoutCleaned());
    assertEquals(1, result.total());
    assertTrue(result.hasChanges());
    verify(occupancyManager, times(1)).releaseResource(any(), any());
  }

  @Test
  @DisplayName("空活跃列车集合：所有占用都是孤儿")
  void emptyActiveTrains() {
    Instant now = Instant.now();
    OccupancyResource resource = nodeResource("SURC:S:TEST:1");
    OccupancyClaim c = claim(resource, "train1", now.minus(Duration.ofSeconds(10)));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(c));

    OccupancyHealer.HealResult result = healer.heal(Set.of(), now);

    assertEquals(1, result.orphanCleaned());
  }

  @Test
  @DisplayName("null 参数处理：使用默认值")
  void nullParameters() {
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());

    // null activeTrains 应视为空集
    // null now 应使用当前时间
    assertDoesNotThrow(() -> healer.heal(null, null));
  }
}
