package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.utils.LaunchFunction;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import org.junit.jupiter.api.Test;

/**
 * 按加速度下发的 launch 在限速反复变化时仍要按原加速度提速。
 *
 * <p>TrainCarts 的 {@code MemberActionLaunch} 发现 speedLimit 变化就从当前速度按原加速度重新规划一段曲线。调度周期每秒写一次限速，
 * 列车起步期间至少每秒重算一次。这里直接驱动 TrainCarts 的曲线函数复现重算：bezier 每段起点加速度为 0，反复重算后几乎不提速 （实服 2026-09-27 OFL→HAS 头
 * 50 格 26 秒）；线性曲线每次重算只丢一 tick 的加速，每秒一次影响很小，但若每一两 tick 一次（逐 tick
 * 斜坡在起步途中频繁写入）就只剩一半加速度，所以斜坡要等车速追近包络才写。
 */
class LaunchCurveReplanTest {

  /** 加速度 0.8 bps²（取值与车种无关）换算为 blocks/tick²。 */
  private static final double ACCEL_BPT2 = 0.8 / 400.0;

  /** 线路速度约 21 bps。 */
  private static final double TARGET_BPT = 1.063;

  @Test
  void accelerationLaunchConfigUsesLinearCurve() {
    LauncherConfig config = TrainCartsRuntimeHandle.accelerationLaunchConfig(ACCEL_BPT2);

    assertEquals(LaunchFunction.Linear.class, config.getFunction());
    assertEquals(ACCEL_BPT2, config.getAcceleration(), 1.0e-12);
  }

  @Test
  void linearCurveKeepsAcceleratingWhenReplannedEverySecond() {
    double speed = speedAfterReplanning(new LaunchFunction.Linear(), 20, 20);

    // 不重算时 20 秒应到 0.8 × 20 = 16 bps。重算那一 tick 的速度等于起点速度，每秒少一 tick 的加速，最多慢约 5%。
    assertTrue(speed * 20.0 > 0.8 * 20.0 * 0.9, "线性曲线重算后应基本保持原加速度，实际 " + speed * 20.0);
    assertTrue(speed * 20.0 <= 0.8 * 20.0 + 0.5);
  }

  @Test
  void linearCurveLosesHalfTheAccelerationWhenReplannedEveryTwoTicks() {
    // 逐 tick 斜坡若在起步途中每一两 tick 写一次限速，线性曲线也只剩一半加速度：斜坡因此要等车速追近包络才写。
    double speed = speedAfterReplanning(new LaunchFunction.Linear(), 20, 2);

    assertEquals(0.8 * 20.0 / 2.0, speed * 20.0, 0.5, "每 2 tick 重算一次，20 秒应只到约 8 bps");
  }

  @Test
  void bezierCurveStallsWhenReplannedEverySecond() {
    double bezier = speedAfterReplanning(new LaunchFunction.Bezier(), 20, 20);
    double linear = speedAfterReplanning(new LaunchFunction.Linear(), 20, 20);

    assertTrue(bezier * 20.0 < 5.0, "bezier 反复从零加速度起步，20 秒后应仍远低于线路速度，实际 " + bezier * 20.0);
    assertTrue(bezier < linear / 3.0);
  }

  /**
   * 从静止按 {@link #ACCEL_BPT2} 发车，每 {@code intervalTicks} tick 从当前速度重新规划一次（与 {@code
   * MemberActionLaunch} 在 speedLimit 变化时的重算相同），返回 {@code seconds} 秒后的速度（blocks/tick）。
   */
  private static double speedAfterReplanning(
      LaunchFunction function, int seconds, int intervalTicks) {
    LauncherConfig config = new LauncherConfig();
    config.setAcceleration(ACCEL_BPT2);
    double speed = 0.0;
    for (int elapsed = 0; elapsed < seconds * 20; elapsed += intervalTicks) {
      function.setMinimumVelocity(0.0);
      function.setMaximumVelocity(TARGET_BPT);
      function.setVelocityRange(speed, TARGET_BPT);
      function.configure(config);
      double previous = 0.0;
      for (int tick = 0; tick < intervalTicks; tick++) {
        double distance = function.getDistance(tick);
        speed = tick == 0 ? distance : distance - previous;
        previous = distance;
      }
    }
    return speed;
  }
}
