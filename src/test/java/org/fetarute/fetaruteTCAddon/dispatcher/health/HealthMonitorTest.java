package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.junit.jupiter.api.Test;

/** {@link HealthMonitor} 单元测试。 */
class HealthMonitorTest {

  @Test
  void healNowRunsRuntimeOrphanCleanupForDestroyAllFallback() {
    RuntimeDispatchService dispatchService = mock(RuntimeDispatchService.class);
    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    ConfigManager configManager = mock(ConfigManager.class);
    when(dispatchService.cleanupOrphanOccupancyClaimsWithReport(any()))
        .thenReturn(new RuntimeDispatchService.CleanupResult(Instant.now(), 1, 2, 1));
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());

    HealthMonitor monitor =
        new HealthMonitor(
            dispatchService,
            occupancyManager,
            mock(DwellRegistry.class),
            configManager,
            message -> {},
            Set::of);

    HealthMonitor.CheckResult result = monitor.healNow();

    assertEquals(4, result.totalFixed());
    assertEquals(2, result.orphanCleaned(), "destroyall 后应把运行时孤儿占用计入修复结果");
    verify(dispatchService).cleanupOrphanOccupancyClaimsWithReport(Set.of());
  }
}
