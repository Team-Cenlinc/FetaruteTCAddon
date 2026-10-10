package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 车掌紧急停车的制动：按减速度逐 tick 减速，不是当拍停住。 */
class GuardEmergencyBrakeActionTest {

  @Test
  void slowsDownByTheDecelerationEachTick() {
    // 20 格/秒、紧急制动 2 格/秒²：每 tick 减 0.1 格/秒，10 秒（200 tick）停稳。
    double speed = 20.0;
    int ticks = 0;
    while (speed > 0.0) {
      speed = GuardEmergencyBrakeAction.nextSpeed(speed, 2.0);
      ticks++;
    }
    assertEquals(200, ticks, 1);
    assertEquals(19.9, GuardEmergencyBrakeAction.nextSpeed(20.0, 2.0), 1e-9);
    assertEquals(0.0, GuardEmergencyBrakeAction.nextSpeed(0.05, 2.0), "不会减成负数");
  }

  @Test
  void neverSpeedsBackUp() {
    assertEquals(12.0, GuardEmergencyBrakeAction.brakingFrom(-1.0, 12.0), "第一 tick 取实际车速");
    assertEquals(0.0, GuardEmergencyBrakeAction.brakingFrom(15.0, 0.0), "被调度硬停车后不再推起来");
    assertEquals(9.0, GuardEmergencyBrakeAction.brakingFrom(9.0, 9.4), "实际略快时仍按算出的车速");
  }
}
