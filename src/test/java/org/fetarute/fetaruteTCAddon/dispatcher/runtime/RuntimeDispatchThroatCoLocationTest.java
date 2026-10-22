package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * 咽喉原子准入与占用判定同一口径（{@link
 * org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CoLocationPolicy}）：出库去联挂的主干只不被它要挂的那辆车挡，别的车照常挡。
 */
class RuntimeDispatchThroatCoLocationTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void onlyTheExemptedPartnerOnTheThroatExitLetsTheTrunkLeaveTheDepot() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchServiceTest.ThroatFixture throat = RuntimeDispatchServiceTest.ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    holdExit(manager, throat, "FEEDER");
    RuntimeDispatchService service =
        new RuntimeDispatchServiceTest()
            .createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    assertFalse(admitted(service, throat, "TRUNK"), "没有豁免：停在咽喉出口的车挡着出库");

    manager.setCoLocationPolicy(
        (request, resource, claim) ->
            "TRUNK".equals(request.trainName()) && "FEEDER".equals(claim.trainName()));
    assertTrue(admitted(service, throat, "TRUNK"), debugMessages.toString());
    assertFalse(admitted(service, throat, "OTHER"), "豁免只给登记的那一对");
  }

  @Test
  void anyOtherTrainOnTheThroatExitStillBlocksTheTrunk() {
    List<String> debugMessages = new ArrayList<>();
    RuntimeDispatchServiceTest.ThroatFixture throat = RuntimeDispatchServiceTest.ppkThroatFixture();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    holdExit(manager, throat, "BYSTANDER");
    manager.setCoLocationPolicy(
        (request, resource, claim) ->
            "TRUNK".equals(request.trainName()) && "FEEDER".equals(claim.trainName()));
    RuntimeDispatchService service =
        new RuntimeDispatchServiceTest()
            .createMinimalService(manager, debugMessages, SmartDispatcherMode.ENFORCE);

    assertFalse(admitted(service, throat, "TRUNK"), "出口上是别的车：照常挡");
  }

  private static boolean admitted(
      RuntimeDispatchService service,
      RuntimeDispatchServiceTest.ThroatFixture throat,
      String train) {
    return service.smartDepotAdmissionAllowsSpawn(
        train, throat.graph(), throat.context(train, CorridorDirection.A_TO_B, false));
  }

  private static void holdExit(
      SimpleOccupancyManager manager,
      RuntimeDispatchServiceTest.ThroatFixture throat,
      String train) {
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    train,
                    Optional.empty(),
                    T0,
                    List.of(OccupancyResource.forNode(throat.exit())),
                    Map.of()))
            .allowed());
  }
}
