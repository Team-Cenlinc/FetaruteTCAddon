package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.MotionPlanner.MotionInput;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.MotionPlanner.MotionOutput;
import org.junit.jupiter.api.Test;

/** MotionPlanner 单元测试。 */
class MotionPlannerTest {

  @Test
  void plan_noConstraint_returnsTargetSpeed() {
    MotionInput input = MotionInput.simple(0.0, 8.0, 0.8, 1.0);
    MotionOutput output = MotionPlanner.plan(input);

    assertEquals(8.0, output.recommendedSpeedBps(), 0.01);
    assertFalse(output.shouldBrake());
  }

  @Test
  void plan_atConstraint_limitsSpeed() {
    // 距离约束点 0 blocks，应该限速到约束速度
    MotionInput input = MotionInput.withConstraint(8.0, 8.0, 0.8, 1.0, 0, 0.0);
    MotionOutput output = MotionPlanner.plan(input);

    assertEquals(0.0, output.recommendedSpeedBps(), 0.01);
  }

  @Test
  void plan_nearConstraint_calculatesPhysicsLimit() {
    // 距离约束点 100 blocks，减速度 1.0 bps²
    // v = √(2 * 1.0 * 100) = √200 ≈ 14.14 bps
    MotionInput input = MotionInput.withConstraint(8.0, 20.0, 0.8, 1.0, 100, 0.0);
    MotionOutput output = MotionPlanner.plan(input);

    assertTrue(output.recommendedSpeedBps() <= 14.2);
    assertTrue(output.recommendedSpeedBps() >= 14.1);
  }

  @Test
  void plan_withConstraintSpeed_calculatesCorrectLimit() {
    // 距离约束点 50 blocks，约束速度 4.0 bps，减速度 1.0 bps²
    // v = √(4² + 2 * 1.0 * 50) = √(16 + 100) = √116 ≈ 10.77 bps
    MotionInput input = MotionInput.withConstraint(6.0, 12.0, 0.8, 1.0, 50, 4.0);
    MotionOutput output = MotionPlanner.plan(input);

    assertTrue(output.recommendedSpeedBps() <= 10.8);
    assertTrue(output.recommendedSpeedBps() >= 10.7);
  }

  @Test
  void plan_shouldBrake_whenNeedToSlowDown() {
    // 当前 10 bps，但只能跑 5 bps
    MotionInput input = MotionInput.withConstraint(10.0, 12.0, 0.8, 1.0, 10, 0.0);
    MotionOutput output = MotionPlanner.plan(input);

    assertTrue(output.shouldBrake());
  }

  @Test
  void distanceForSpeedChange_accelerating() {
    // 从 0 加速到 8 bps，加速度 0.8 bps²
    // d = v² / (2·a) = 64 / 1.6 = 40 blocks
    double distance = MotionPlanner.distanceForSpeedChange(0.0, 8.0, 0.8);
    assertEquals(40.0, distance, 0.01);
  }

  @Test
  void distanceForSpeedChange_decelerating() {
    // 从 10 减速到 0，减速度 1.0 bps²
    // d = v² / (2·a) = 100 / 2 = 50 blocks
    double distance = MotionPlanner.distanceForSpeedChange(10.0, 0.0, 1.0);
    assertEquals(50.0, distance, 0.01);
  }

  @Test
  void maxReachableSpeed_shortDistance() {
    // 短距离：从 0 加速，目标减速到 0
    // 距离 20 blocks，加速度 0.8，减速度 1.0
    double vMax = MotionPlanner.maxReachableSpeed(0.0, 0.0, 20.0, 0.8, 1.0);

    // 验证：以此速度能在 20 blocks 内完成加减速
    double accelDist = MotionPlanner.distanceForSpeedChange(0.0, vMax, 0.8);
    double decelDist = MotionPlanner.distanceForSpeedChange(vMax, 0.0, 1.0);
    assertTrue(accelDist + decelDist <= 20.1); // 允许少量误差
  }

  @Test
  void smoothTransition_accelerating() {
    // 当前 4 bps，目标 8 bps，加速度 0.8 bps²
    // 1 tick = 0.05s，deltaV = 0.8 * 0.05 = 0.04 bps
    double result = MotionPlanner.smoothTransition(4.0, 8.0, 0.8, 1.0, 1);
    assertEquals(4.04, result, 0.01);
  }

  @Test
  void smoothTransition_decelerating() {
    // 当前 8 bps，目标 4 bps，减速度 1.0 bps²
    // 1 tick = 0.05s，deltaV = 1.0 * 0.05 = 0.05 bps
    double result = MotionPlanner.smoothTransition(8.0, 4.0, 0.8, 1.0, 1);
    assertEquals(7.95, result, 0.01);
  }

  @Test
  void plan_nullInput_returnsMaintain() {
    MotionOutput output = MotionPlanner.plan(null);
    assertEquals(0.0, output.recommendedSpeedBps(), 0.01);
    assertFalse(output.shouldBrake());
  }
}
