package org.fetarute.fetaruteTCAddon.drive.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabConfig;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveMode;
import org.fetarute.fetaruteTCAddon.drive.dynamics.DriveParams;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.drive.setup.SetupTimings;
import org.fetarute.fetaruteTCAddon.drive.setup.TrainSetup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("教程从驾驶会话取的快照，以及教程文案齐全")
class TutorialSnapshotsTest {

  private static DriveSession session(TrainSetup setup, CabSystems cab) {
    List<ItemStack> hotbar = new ArrayList<>();
    for (int i = 0; i < Notch.SLOT_COUNT; i++) {
      hotbar.add(mock(ItemStack.class));
    }
    return new DriveSession(
        UUID.randomUUID(),
        "Steve",
        new SeatBinding("T1", 0, 0),
        new DriveParams(DriveMode.MU, 1.0, 1.0, 22.0, 0.5),
        DriveConfig.defaults(),
        hotbar,
        setup,
        cab);
  }

  @Test
  @DisplayName("standard 级非调度列车：一键启动、停着、手柄在 N、换向在前进")
  void standardSession() {
    DriveSession session =
        session(new TrainSetup(PowerSupply.PTG5, SetupTimings.defaults()), CabSystems.disabled());
    TutorialSnapshot snapshot = TutorialSnapshots.of(session, 0L);
    assertEquals(DriveConfig.defaults().level().setupMode(), snapshot.setupMode());
    assertFalse(snapshot.dispatch());
    assertFalse(snapshot.ato());
    assertFalse(snapshot.setupReady());
    assertFalse(snapshot.cab());
    assertTrue(snapshot.brakeTestPassed(), "没有车上系统时制动试验视为通过");
    assertFalse(snapshot.parkingApplied());
    assertEquals(Notch.N, snapshot.handle());
    assertEquals(ReverserPosition.FORWARD, snapshot.reverser());
    assertTrue(snapshot.stopped());
    assertNull(snapshot.stationHint());

    session.selector().force(Notch.P2);
    session.setDoorOpen(true, true);
    TutorialSnapshot moved = TutorialSnapshots.of(session, 0L);
    assertEquals(Notch.P2, moved.handle(), "取手柄位置，不论牵引是否被封锁");
    assertTrue(moved.doorsOpen());
  }

  @Test
  @DisplayName("simulation 级车上系统：停放制动施加、制动试验未做")
  void simulationCab() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), true, 900, false, 0);
    TutorialSnapshot snapshot =
        TutorialSnapshots.of(
            session(TrainSetup.alwaysReady(PowerSupply.PTG5, SetupTimings.defaults()), cab), 0L);
    assertTrue(snapshot.cab());
    assertTrue(snapshot.setupReady());
    assertTrue(snapshot.manualCompressor());
    assertFalse(snapshot.compressorOn());
    assertTrue(snapshot.parkingApplied());
    assertFalse(snapshot.brakeTestPassed());
  }

  @Test
  @DisplayName("调度列车：有控制链路即为调度列车，默认人工驾驶")
  void dispatchSession() {
    DriveSession session =
        session(
            TrainSetup.alwaysReady(PowerSupply.PTG5, SetupTimings.defaults()),
            CabSystems.disabled());
    session.attachDriverLink(new DriverLink(UUID.randomUUID(), "T1", null, () -> 0.0, () -> 0L));
    TutorialSnapshot snapshot = TutorialSnapshots.of(session, 0L);
    assertTrue(snapshot.dispatch());
    assertFalse(snapshot.ato());
    assertFalse(snapshot.signalAcknowledgePending());
    assertFalse(snapshot.departurePending());
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("每一步（含调度列车的说法）与每条情境提示都有聊天与副标题文案")
  void everyStepAndTipHasText(String localeTag) throws Exception {
    YamlConfiguration lang;
    try (InputStream stream =
        TutorialSnapshotsTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang = new YamlConfiguration();
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    TutorialSnapshot free = TutorialSnapshot.builder().build();
    TutorialSnapshot dispatch = TutorialSnapshot.builder().dispatch(true).build();
    for (TutorialStep step : TutorialStep.values()) {
      for (String key : List.of(step.key(free), step.key(dispatch))) {
        for (String suffix : List.of(".chat", ".subtitle")) {
          String path = "drive.tutorial.steps." + key + suffix;
          assertTrue(lang.isString(path), localeTag + " 缺少 " + path);
        }
      }
    }
    for (DriveTip tip : DriveTip.values()) {
      for (String suffix : List.of(".chat", ".subtitle")) {
        String path = "drive.tutorial.tips." + tip.key() + suffix;
        assertTrue(lang.isString(path), localeTag + " 缺少 " + path);
      }
    }
    for (String key :
        List.of(
            "offer",
            "started",
            "step",
            "tip",
            "buttons.skip",
            "buttons.continue",
            "buttons.finish",
            "doors-unavailable",
            "finished",
            "finished-subtitle",
            "interrupted",
            "command.already-running",
            "command.armed",
            "command.stopped",
            "command.dismissed",
            "command.reset",
            "command.not-running",
            "command.invalid")) {
      assertTrue(lang.isString("drive.tutorial." + key), localeTag + " 缺少 drive.tutorial." + key);
    }
  }
}
