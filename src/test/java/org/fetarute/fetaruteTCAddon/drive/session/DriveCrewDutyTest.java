package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.crew.CrewDuty;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverControlRegistry;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSessionManager;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardTasks;
import org.fetarute.fetaruteTCAddon.drive.inventory.ClientClock;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveSounds;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("在岗登记：驾驶员、车掌与信号员互斥")
class DriveCrewDutyTest {

  private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

  private final CrewDuty crew = new CrewDuty();
  private final Set<UUID> driving = new HashSet<>();
  private final DriverTaskManager tasks = new DriverTaskManager(null, null);
  private GuardSessionManager guards;
  private Player player;
  private UUID id;

  @BeforeEach
  void setUp() {
    Plugin plugin = mock(Plugin.class);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("DriveCrewDutyTest"));
    when(plugin.getName()).thenReturn("fetarute");
    when(plugin.namespace()).thenReturn("fetarute");
    GuardSessionManager.DriverSide drivers = mock(GuardSessionManager.DriverSide.class);
    when(drivers.hotbarReady()).thenReturn(true);
    guards =
        new GuardSessionManager(
            plugin,
            new DriverControlRegistry(),
            new DriveSounds(),
            mock(LocaleManager.class),
            DriveConfig::defaults,
            () -> null,
            drivers,
            new ClientClock(),
            crew);
    guards.tasks().setBeforeClaim(task -> true);
    DriveSessionManager.registerCrew(crew, driving::contains, tasks, () -> guards);
    id = UUID.randomUUID();
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getName()).thenReturn("Sinapole");
  }

  private static DriverTaskManager.TaskSpec spec(String trip) {
    return new DriverTaskManager.TaskSpec(
        new TaskKey(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            trip,
            LocalDate.of(2026, 10, 20)),
        "R1",
        "SURC",
        "WSD",
        "镇西",
        "SURC:S:WSD:1",
        0,
        NOW,
        "train-" + trip,
        -1,
        null,
        null,
        false,
        "board",
        Map.of(),
        true);
  }

  @Test
  @DisplayName("没担任任何角色时谁都不拦")
  void freePlayer() {
    assertNull(DriveSessionManager.crewRefusal(crew, id));
    assertNull(DriveSessionManager.claimRefusalKey(crew, id));
  }

  @Test
  @DisplayName("自己的角色不拦自己：驾驶中、领着驾驶任务时仍按驾驶员自己的规矩处理")
  void ownRoleDoesNotRefuse() {
    driving.add(id);
    assertNull(DriveSessionManager.crewRefusal(crew, id));
    driving.clear();
    tasks.assign(player, spec("1001"), DrivingMode.MANUAL, true, NOW);
    assertNull(DriveSessionManager.claimRefusalKey(crew, id));
  }

  @Test
  @DisplayName("领了车掌任务、还没上岗：不能开始驾驶，也不能领驾驶任务")
  void claimedGuardTaskBlocksDriving() {
    assertEquals(
        GuardTasks.ClaimOutcome.CLAIMED,
        guards.tasks().register(id, "Sinapole", spec("2001"), false, NOW));
    assertEquals(
        DriveSessionManager.StartOutcome.GUARD_ON_DUTY, DriveSessionManager.crewRefusal(crew, id));
    assertEquals("drive.task.claim.guard-busy", DriveSessionManager.claimRefusalKey(crew, id));
  }

  @Test
  @DisplayName("领了驾驶任务、还没开始驾驶：不能上岗当车掌，也不能领车掌任务")
  void claimedDriverTaskBlocksGuard() {
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.assign(player, spec("3001"), DrivingMode.MANUAL, true, NOW));
    assertEquals(GuardSessionManager.StartOutcome.DRIVING, guards.start(player));
    assertEquals(GuardTasks.ClaimOutcome.DRIVING, guards.claim(player, spec("3002"), false));
  }

  @Test
  @DisplayName("驾驶中不能上岗当车掌，也不能领车掌任务")
  void drivingBlocksGuard() {
    driving.add(id);
    assertEquals(GuardSessionManager.StartOutcome.DRIVING, guards.start(player));
    assertEquals(GuardTasks.ClaimOutcome.DRIVING, guards.claim(player, spec("4001"), false));
  }

  @Test
  @DisplayName("值守信号楼时：不能开始驾驶、领驾驶任务、上岗当车掌、领车掌任务")
  void signallerBlocksEverything() {
    Set<UUID> signallers = new HashSet<>();
    crew.register(CrewDuty.Role.SIGNALLER, signallers::contains);
    signallers.add(id);

    assertEquals(
        DriveSessionManager.StartOutcome.SIGNALLER_ON_DUTY,
        DriveSessionManager.crewRefusal(crew, id));
    assertEquals("drive.task.claim.signaller-busy", DriveSessionManager.claimRefusalKey(crew, id));
    assertEquals(GuardSessionManager.StartOutcome.SIGNALLER_ON_DUTY, guards.start(player));
    assertEquals(
        GuardTasks.ClaimOutcome.SIGNALLER_ON_DUTY, guards.claim(player, spec("5001"), false));

    signallers.clear();
    assertNull(DriveSessionManager.crewRefusal(crew, id), "下岗后恢复");
  }

  @Test
  @DisplayName("车掌值乘没有接上时，车掌那一项不算在岗")
  void noGuardModule() {
    CrewDuty bare = new CrewDuty();
    DriveSessionManager.registerCrew(bare, driving::contains, tasks, () -> null);
    assertNull(DriveSessionManager.crewRefusal(bare, id));
  }
}
