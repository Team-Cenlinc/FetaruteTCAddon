package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.junit.jupiter.api.Test;

/**
 * 发车动作的逐 tick 推速与结束判定、停住检测门槛，以及区块卸载时的保存与恢复。
 *
 * <p>推速逻辑在与 TrainCarts 对象无关的 {@link CurveLaunchAction.Stepper} 里；动作本身只读限速、写前进力与校正方向（ {@code
 * MinecartGroup} 的静态初始化依赖服务器，单测里无法构造）。
 */
class CurveLaunchActionTest {

  private static final double ACCEL_BPS2 = 1.1;

  @Test
  void followsTheSharedCurveEachTickAndFinishesAtTheTarget() {
    CurveLaunchAction.Stepper stepper = new CurveLaunchAction.Stepper(ACCEL_BPS2, 0.5);
    stepper.start(0.0);

    double speed = 0.0;
    int ticks = 0;
    boolean done = false;
    while (!done && ticks < 2000) {
      done = stepper.advance(0.5);
      ticks++;
      speed = SpeedCurve.nextSpeedBps(ACCEL_BPS2, speed, 0.0, 10.0, 0.05);
      assertEquals(speed / 20.0, stepper.velocityBpt(), 1e-12, "第 " + ticks + " tick 应与共享曲线一致");
    }

    assertTrue(done, "应在到达目标时结束");
    assertEquals(0.5, stepper.velocityBpt(), 1e-12);
    // 0→10 格/秒：起步 1.5 秒 + 收尾 2.5 秒 + 中段，按 tick 推进约 11.4 秒。
    assertTrue(ticks > 200 && ticks < 260, "用时 " + ticks + " tick");
  }

  @Test
  void firstTickAlreadyAcceleratesSoIntervalChangesLoseNothing() {
    CurveLaunchAction.Stepper stepper = new CurveLaunchAction.Stepper(ACCEL_BPS2, 1.0);
    stepper.start(0.0);

    assertFalse(stepper.advance(1.0));
    assertEquals(0.3 * ACCEL_BPS2 / 400.0, stepper.velocityBpt(), 1e-12, "第一 tick 就按起步加速度提速");
  }

  @Test
  void endsWhenHeldByALowerSpeedLimit() {
    CurveLaunchAction.Stepper stepper = new CurveLaunchAction.Stepper(ACCEL_BPS2, 1.0);
    stepper.start(0.28);

    int ticks = 0;
    while (!stepper.advance(0.3)) {
      ticks++;
      assertTrue(ticks < 2000, "被限速贴住后应结束");
    }
    assertEquals(0.3, stepper.velocityBpt(), 1e-12);
  }

  @Test
  void slowingDownDropsToTheTargetOnTheFirstTick() {
    CurveLaunchAction.Stepper stepper = new CurveLaunchAction.Stepper(ACCEL_BPS2, 0.3);
    stepper.start(0.8);

    assertTrue(stepper.advance(1.0), "目标低于当前速度时第一 tick 就结束");
    assertEquals(0.3, stepper.velocityBpt(), 1e-12, "速度向量重置为目标值");
  }

  @Test
  void stallDetectionOnlyAfterTheTrainIsClearlyMoving() {
    CurveLaunchAction.Stepper stepper = new CurveLaunchAction.Stepper(ACCEL_BPS2, 1.0);
    stepper.start(0.0);
    stepper.advance(1.0);
    assertFalse(stepper.detectsStall(), "起动头几 tick 速度很小，车厢速度为 0 是正常的");

    stepper.start(0.02);
    assertTrue(stepper.detectsStall());
  }

  @Test
  void rejectsNonPositiveAcceleration() {
    assertThrows(IllegalArgumentException.class, () -> new CurveLaunchAction.Stepper(0.0, 1.0));
  }

  /**
   * 区块卸载时保存：重新加载后按保存时的速度与本段起点接着走，与没被卸载的动作逐 tick 相同。
   *
   * <p>只验证本类自己的那部分状态；父类（发车、方向）由 TrainCarts 的基类序列化器读写，它依赖服务器内部类，单测里跑不了。
   */
  @Test
  void savedStateContinuesTheSameCurve() throws Exception {
    CurveLaunchAction original = new CurveLaunchAction(ACCEL_BPS2, 1.0, BlockFace.SELF);
    original.stepper().start(0.1);
    for (int tick = 0; tick < 40; tick++) {
      original.stepper().advance(1.0);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      CurveLaunchAction.Serializer.writeState(out, original.stepper());
    }

    CurveLaunchAction loaded;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      loaded = CurveLaunchAction.Serializer.readState(in);
    }

    assertEquals(original.stepper().velocityBpt(), loaded.stepper().velocityBpt(), 0.0);
    assertEquals(original.stepper().phaseStartBps(), loaded.stepper().phaseStartBps(), 0.0);
    original.stepper().advance(1.0);
    loaded.stepper().advance(1.0);
    assertEquals(original.stepper().velocityBpt(), loaded.stepper().velocityBpt(), 1e-15);
  }
}
