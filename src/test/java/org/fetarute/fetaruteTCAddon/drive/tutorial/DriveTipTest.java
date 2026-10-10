package org.fetarute.fetaruteTCAddon.drive.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.drive.hud.DriverStationHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶中的情境提示")
class DriveTipTest {

  private static TutorialSnapshot.Builder manualDispatch() {
    return TutorialSnapshot.builder().dispatch(true);
  }

  @Test
  @DisplayName("信号变严、进站对标、开关门、发车信号只对人工驾驶的调度列车")
  void stationAndSignalTips() {
    assertEquals(
        Optional.of(DriveTip.SIGNAL_ACKNOWLEDGE),
        DriveTip.firstDue(manualDispatch().signalAcknowledgePending(true).build(), Set.of()));
    for (DriverStationHint.Kind kind :
        new DriverStationHint.Kind[] {
          DriverStationHint.Kind.APPROACH,
          DriverStationHint.Kind.ON_MARK,
          DriverStationHint.Kind.MOVE_UP,
          DriverStationHint.Kind.OVERRUN
        }) {
      assertTrue(DriveTip.STOP_MARK.due(manualDispatch().stationHint(kind).build()), kind.name());
    }
    assertTrue(
        DriveTip.OPEN_DOORS.due(
            manualDispatch()
                .stationHint(DriverStationHint.Kind.OPEN_DOORS)
                .doorsRequired(true)
                .build()));
    assertFalse(
        DriveTip.OPEN_DOORS.due(
            manualDispatch().stationHint(DriverStationHint.Kind.OPEN_DOORS).build()),
        "本站不开门时不提示开门");
    assertTrue(
        DriveTip.CLOSE_DOORS.due(
            manualDispatch().stationHint(DriverStationHint.Kind.CLOSE_DOORS).build()));
    assertTrue(
        DriveTip.DEPARTURE.due(
            manualDispatch().stationHint(DriverStationHint.Kind.DEPART).build()));
    assertFalse(
        DriveTip.DEPARTURE.due(
            manualDispatch().stationHint(DriverStationHint.Kind.WAIT_DEPARTURE).build()));

    TutorialSnapshot freeTrain =
        TutorialSnapshot.builder()
            .signalAcknowledgePending(true)
            .stationHint(DriverStationHint.Kind.DEPART)
            .build();
    assertEquals(Optional.empty(), DriveTip.firstDue(freeTrain, Set.of()), "非调度列车没有这些提示");
    TutorialSnapshot ato =
        manualDispatch()
            .ato(true)
            .signalAcknowledgePending(true)
            .stationHint(DriverStationHint.Kind.APPROACH)
            .build();
    assertEquals(Optional.empty(), DriveTip.firstDue(ato, Set.of()), "ATO 自己停车，不提示对标与信号");
  }

  @Test
  @DisplayName("ATO 确认发车只在 ATO 下；警惕装置对任何列车（simulation 级）")
  void atoAndVigilance() {
    assertEquals(
        Optional.of(DriveTip.ATO_CONFIRM),
        DriveTip.firstDue(manualDispatch().ato(true).departurePending(true).build(), Set.of()));
    assertFalse(DriveTip.ATO_CONFIRM.due(manualDispatch().departurePending(true).build()));
    assertEquals(
        Optional.of(DriveTip.VIGILANCE),
        DriveTip.firstDue(
            TutorialSnapshot.builder().cab(true).vigilanceWarning(true).build(), Set.of()));
    assertFalse(DriveTip.VIGILANCE.due(TutorialSnapshot.builder().vigilanceWarning(true).build()));
  }

  @Test
  @DisplayName("出过的提示不再出，同时满足时按先后只出一条")
  void shownTipsAreSkipped() {
    TutorialSnapshot both =
        manualDispatch()
            .signalAcknowledgePending(true)
            .stationHint(DriverStationHint.Kind.APPROACH)
            .build();
    assertEquals(Optional.of(DriveTip.SIGNAL_ACKNOWLEDGE), DriveTip.firstDue(both, Set.of()));
    assertEquals(
        Optional.of(DriveTip.STOP_MARK),
        DriveTip.firstDue(both, EnumSet.of(DriveTip.SIGNAL_ACKNOWLEDGE)));
    assertEquals(Optional.empty(), DriveTip.firstDue(both, DriveTip.all()));
  }

  @Test
  @DisplayName("人工驾驶第一次与车掌同车时说明一次（ATO 自己起步，不出），车上有车掌时不出开关门的提示")
  void guardAboard() {
    for (DriverStationHint.Kind kind :
        new DriverStationHint.Kind[] {
          DriverStationHint.Kind.GUARD_DOORS, DriverStationHint.Kind.GUARD_SIGNAL
        }) {
      assertEquals(
          Optional.of(DriveTip.GUARD_ABOARD),
          DriveTip.firstDue(manualDispatch().stationHint(kind).build(), Set.of()),
          kind.name());
      assertFalse(DriveTip.GUARD_ABOARD.due(manualDispatch().ato(true).stationHint(kind).build()));
    }
    assertEquals(
        Optional.empty(),
        DriveTip.firstDue(
            manualDispatch()
                .stationHint(DriverStationHint.Kind.GUARD_DOORS)
                .doorsRequired(true)
                .build(),
            EnumSet.of(DriveTip.GUARD_ABOARD)),
        "车门归车掌：开门提示不出");
    assertFalse(
        DriveTip.GUARD_ABOARD.due(
            TutorialSnapshot.builder().stationHint(DriverStationHint.Kind.GUARD_DOORS).build()),
        "非调度列车没有车掌");
  }

  @Test
  @DisplayName("提示的键各不相同（持久数据标记靠它区分）")
  void keysAreUnique() {
    Set<String> keys = new java.util.HashSet<>();
    for (DriveTip tip : DriveTip.values()) {
      assertTrue(keys.add(tip.key()), tip.name());
    }
  }

  @Test
  @DisplayName("持久数据标记名不随文案键改名：出过的信号确认提示不会再出一次")
  void storageKeysStayStable() {
    assertEquals("signal-acknowledge", DriveTip.SIGNAL_ACKNOWLEDGE.key());
    assertEquals("signal-confirm", DriveTip.SIGNAL_ACKNOWLEDGE.storageKey());
    Set<String> keys = new java.util.HashSet<>();
    for (DriveTip tip : DriveTip.values()) {
      assertTrue(keys.add(tip.storageKey()), tip.name());
    }
  }
}
