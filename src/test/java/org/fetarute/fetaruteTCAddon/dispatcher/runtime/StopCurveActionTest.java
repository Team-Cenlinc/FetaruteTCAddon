package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCeiling;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.junit.jupiter.api.Test;

/** 停站刹车：沿自动运行的制动曲线一路刹到停车点，停车前缓解制动、平稳停住，不越过停车点。 */
class StopCurveActionTest {

  private static final double TICKS = 20.0;

  /** metro：常用制动 1.2，自动运行 0.75 倍。 */
  private static final SpeedCurve ATO = SpeedCurve.ato(1.1, 1.2, 0.75);

  private static final double SERVICE = 1.2;

  /** 每 tick 走足上一 tick 的指令速度时的逐 tick 速度（格/秒）与走过的总距离。 */
  private record Run(List<Double> speedsBps, double travelled, int ticks) {}

  /** 调度此刻给的限速：车站前按线路速度 22 格/秒算（车本来就不会超过它）。 */
  private static final double LIMIT_BPS = 22.0;

  private static Run run(double distance, double entryBps) {
    StopCurveAction.Stepper stepper =
        new StopCurveAction.Stepper(ATO, SERVICE, distance, StopCurveAction.MIN_CAP_BPS);
    stepper.start(entryBps / TICKS, LIMIT_BPS / TICKS);
    List<Double> speeds = new ArrayList<>();
    double travelled = 0.0;
    for (int tick = 1; tick <= 4000; tick++) {
      double moved = stepper.velocityBpt();
      travelled += moved;
      if (stepper.advance(moved)) {
        return new Run(speeds, travelled, tick);
      }
      speeds.add(stepper.velocityBpt() * TICKS);
    }
    throw new AssertionError("没有停下");
  }

  @Test
  void stopsAtThePointWithoutOvershooting() {
    for (double distance : new double[] {0.5, 3.0, 12.0, 40.0}) {
      Run run = run(distance, Math.min(9.0, Math.sqrt(2.0 * 0.9 * distance)));
      assertTrue(run.travelled() <= distance + 1.0e-9, distance + " 格：越过了 " + run.travelled());
      assertEquals(distance, run.travelled(), StopCurveAction.ARRIVED_BLOCKS, "停在停车点");
    }
  }

  @Test
  void theFirstTickDoesNotCrossThePointEither() {
    // 压上站牌时离停车点只剩 0.1 格、车速 6 格/秒（一 tick 0.3 格）：第一 tick 也不能照进站速度冲过去。
    Run run = run(0.1, 6.0);
    assertTrue(run.travelled() <= 0.1 + 1.0e-9, "越过了 " + run.travelled());
  }

  @Test
  void entersOnTheCurveAndFollowsItWithoutHarshBraking() {
    double distance = 30.0;
    double entry = SpeedCeiling.brakingLimitBps(ATO, 22.0, 0.0, distance); // 调度按同一条曲线把车送到这里
    Run run = run(distance, entry);
    double previous = entry;
    for (double speed : run.speedsBps()) {
      double decel = (previous - speed) * TICKS;
      assertTrue(decel <= SERVICE + 1.0e-6, "单 tick 减速度 " + decel + " 超过常用制动");
      previous = speed;
    }
    assertTrue(run.ticks() < 20 * 20, "30 格从曲线上刹停不该超过 20 秒: " + run.ticks());
  }

  @Test
  void theSpeedFollowsTheMeasuredMovementNotTheCommand() {
    // TrainCarts 按限速 2 格/秒截速：指令可以更高，但刹车要按实际速度算，否则晚刹、冲过停车点。
    StopCurveAction.Stepper stepper =
        new StopCurveAction.Stepper(ATO, SERVICE, 8.0, StopCurveAction.MIN_CAP_BPS);
    stepper.start(2.0 / TICKS, LIMIT_BPS / TICKS);
    double travelled = 0.0;
    for (int tick = 0; tick < 2000; tick++) {
      double moved = Math.min(stepper.velocityBpt(), 2.0 / TICKS);
      travelled += moved;
      if (stepper.advance(moved)) {
        break;
      }
    }
    assertTrue(travelled <= 8.0 + 1.0e-9, "越过了 " + travelled);
    assertEquals(8.0, travelled, StopCurveAction.ARRIVED_BLOCKS);
  }

  @Test
  void aSlowEntryClimbsBackToTheCurveInsteadOfCrawling() {
    // 调度按保守估计提前刹了：0.5 格/秒进站，前面还有 20 格。
    Run slow = run(20.0, 0.5);
    double peak = slow.speedsBps().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    assertTrue(peak > 3.0, "应先提速回到曲线上，而不是以 0.5 格/秒爬完: 最高 " + peak);
    assertTrue(slow.ticks() < 20 * 20, "不该爬 40 秒: " + slow.ticks());
  }

  @Test
  void theFinalStopEasesOffInsteadOfCreeping() {
    Run run = run(6.0, SpeedCeiling.brakingLimitBps(ATO, 22.0, 0.0, 6.0));
    List<Double> speeds = run.speedsBps();
    // 不再以一个固定的微调速度挪近：速度一路下降。
    for (int i = 1; i < speeds.size(); i++) {
      assertTrue(speeds.get(i) <= speeds.get(i - 1) + 1.0e-9, "tick " + i + " 速度回升");
    }
    // 停车前缓解制动：最后半秒的减速度明显低于满值，最后一 tick 的速度已经很低，不是一下子刹死。
    int n = speeds.size();
    double lateDecel = (speeds.get(n - 11) - speeds.get(n - 1)) * TICKS / 10.0;
    assertTrue(lateDecel < 0.7 * ATO.decelBps2(), "最后半秒的平均减速度 " + lateDecel);
    assertTrue(speeds.get(n - 1) < 0.3, "停住前的速度 " + speeds.get(n - 1));
    // 冲动不大：相邻两 tick 的减速度变化不超过 0.1 格/秒²（2 格/秒³）。
    for (int i = 2; i < n; i++) {
      double a1 = (speeds.get(i - 2) - speeds.get(i - 1)) * TICKS;
      double a2 = (speeds.get(i - 1) - speeds.get(i)) * TICKS;
      assertTrue(Math.abs(a2 - a1) <= 0.1, "tick " + i + " 减速度从 " + a1 + " 跳到 " + a2);
    }
    // 最后一格不再慢慢挪：约 2 秒内走完。
    int lastBlockTicks = 0;
    double travelled = 0.0;
    for (int i = n - 1; i >= 0 && travelled < 1.0; i--) {
      travelled += speeds.get(i) / TICKS;
      lastBlockTicks++;
    }
    assertTrue(lastBlockTicks <= 45, "最后一格用了 " + lastBlockTicks + " tick");
  }

  @Test
  void slightlyBelowTheCurveItCoastsInsteadOfAccelerating() {
    // 压牌前按整格向下取整估停车点，压上站牌时可能比曲线略慢：惰行等曲线降下来，不先加速再刹。
    double distance = 12.0;
    double entry = 0.9 * SpeedCeiling.brakingLimitBps(ATO, 22.0, 0.0, distance);
    Run run = run(distance, entry);
    for (double speed : run.speedsBps()) {
      assertTrue(speed <= entry + 1.0e-9, "加速到了 " + speed + "，进站 " + entry);
    }
    assertEquals(distance, run.travelled(), StopCurveAction.ARRIVED_BLOCKS);
  }

  @Test
  void endsWhenTheTrainIsBlocked() {
    StopCurveAction.Stepper stepper =
        new StopCurveAction.Stepper(ATO, SERVICE, 10.0, StopCurveAction.MIN_CAP_BPS);
    stepper.start(3.0 / TICKS, LIMIT_BPS / TICKS);
    boolean done = false;
    int ticks = 0;
    while (!done && ticks < 100) {
      done = stepper.advance(0.0);
      ticks++;
    }
    assertTrue(done, "车一直不动：结束、交给停稳判定");
    assertTrue(ticks <= 25, "不该一直挂着: " + ticks);
  }

  /**
   * 区块卸载时保存：重新加载后按保存时的余下距离、速度与制动曲线接着走，与没被卸载的动作逐 tick 相同，停在同一处。
   *
   * <p>只验证本类自己的那部分状态；父类（发车、方向）由 TrainCarts 的基类序列化器读写，它依赖服务器内部类，单测里跑不了。
   */
  @Test
  void savedStateContinuesTheSameStop() throws Exception {
    StopCurveAction original = new StopCurveAction(ATO, SERVICE, 20.0, BlockFace.SELF);
    original.stepper().start(6.0 / TICKS, LIMIT_BPS / TICKS);
    for (int tick = 0; tick < 30; tick++) {
      original.stepper().advance(original.stepper().velocityBpt());
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      StopCurveAction.Serializer.writeState(out, original.stepper());
    }

    StopCurveAction loaded;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      loaded = StopCurveAction.Serializer.readState(in);
    }

    assertEquals(original.remainingBlocks(), loaded.remainingBlocks(), 0.0);
    assertEquals(original.stepper().velocityBpt(), loaded.stepper().velocityBpt(), 0.0);
    for (int tick = 0; tick < 2000; tick++) {
      boolean originalDone = original.stepper().advance(original.stepper().velocityBpt());
      boolean loadedDone = loaded.stepper().advance(loaded.stepper().velocityBpt());
      assertEquals(originalDone, loadedDone, "tick " + tick);
      assertEquals(original.stepper().velocityBpt(), loaded.stepper().velocityBpt(), 1e-15);
      if (originalDone) {
        assertEquals(original.remainingBlocks(), loaded.remainingBlocks(), 1e-12);
        return;
      }
    }
    throw new AssertionError("没有停下");
  }

  @Test
  void estimatedTicksMatchesAnIdealRun() {
    double distance = 15.0;
    double entry = SpeedCeiling.brakingLimitBps(ATO, 22.0, 0.0, distance);
    StopCurveAction.Stepper template =
        new StopCurveAction.Stepper(ATO, SERVICE, distance, StopCurveAction.MIN_CAP_BPS);
    assertEquals(
        run(distance, entry).ticks(),
        StopCurveAction.Stepper.estimateTicks(template, entry / TICKS, LIMIT_BPS / TICKS));
  }
}
