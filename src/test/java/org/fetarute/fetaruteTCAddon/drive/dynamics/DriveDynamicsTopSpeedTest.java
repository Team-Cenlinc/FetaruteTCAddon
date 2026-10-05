package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.drive.DriveConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("能跑到的最高速度")
class DriveDynamicsTopSpeedTest {

  private static final double DT = 0.05;

  @Test
  @DisplayName("默认封顶在车辆最高速度；另行设定后跟到设定值，清掉后回到车辆最高速度")
  void topSpeedFollowsOverride() {
    DriveParams params = new DriveParams(DriveMode.MU, 1.1, 1.2, 22.0, 1.0);
    DriveDynamics dynamics = new DriveDynamics(params, DriveConfig.defaults());
    dynamics.reset(21.9);
    for (int i = 0; i < 100; i++) {
      dynamics.step(DT, Notch.P3, 40.0);
    }
    assertEquals(22.0, dynamics.speedBps(), 1.0e-9);

    dynamics.setTopSpeedBps(80.0 / 3.6);
    for (int i = 0; i < 100; i++) {
      dynamics.step(DT, Notch.P3, 40.0);
    }
    assertEquals(80.0 / 3.6, dynamics.speedBps(), 1.0e-9);

    dynamics.setTopSpeedBps(Double.NaN);
    assertEquals(22.0, dynamics.topSpeedBps(), 1.0e-9);
  }
}
