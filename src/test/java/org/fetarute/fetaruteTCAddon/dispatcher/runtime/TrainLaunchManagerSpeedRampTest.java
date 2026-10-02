package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SpeedEnvelope;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 执行层与逐 tick 限速斜坡的衔接。 */
class TrainLaunchManagerSpeedRampTest {

  private static final double TICKS = 20.0;
  private static final TrainConfig EMU = new TrainConfig(TrainType.EMU, 0.8, 1.0);

  /** 进站窗口内的进站限速：保持约束，固定为 {@code bps}。 */
  private static SpeedEnvelope approachHold(double bps) {
    return SpeedEnvelope.empty().withHold(traveled -> bps);
  }

  @Test
  void contextFreeProceedDoesNotLiftAboveTheApproachHold() {
    // 实服 2026-09-27 SURC-MT-LP-5410：进站限速 10 bps 时过节点，推进放行把限速抬回 22.22 bps，下一拍信号 tick 再砍回 10。
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(10.0 / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), 10.0 / TICKS);

    signalTick(manager, train, limit, 22.22, approachHold(10.0));
    TrainLaunchManager.ControlApplicationResult result =
        contextFreeProceed(manager, train, limit, 22.22);

    assertEquals(10.0 / TICKS, limit.properties().getSpeedLimit(), 1.0e-9);
    assertEquals(10.0, result.finalTargetBps(), 1.0e-9);
    assertEquals("speed_ceiling_hold", result.finalLimiterSource());
    assertTrue(
        train.accelerateCalls == 0 || train.lastAccelerateTargetBpt <= 10.0 / TICKS + 1.0e-9,
        "推进放行不得把列车牵引到高于进站限速的速度");
  }

  @Test
  void contextFreeProceedHoldsTheRampedApproachValue() {
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    SpeedEnvelope envelope = SpeedEnvelope.empty().withHold(SpeedEnvelope.braking(40.0, 10.0, 1.0));
    double commanded = envelope.limitBps(0.0);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(commanded / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), commanded / TICKS);

    signalTick(manager, train, limit, 22.22, envelope);
    for (int tick = 0; tick < 20; tick++) {
      ramp.tick();
      train.speedBpt = limit.properties().getSpeedLimit();
    }
    double rampedBpt = limit.properties().getSpeedLimit();
    assertTrue(rampedBpt < commanded / TICKS - 0.02, "一秒后斜坡应已下调约 1 bps");

    contextFreeProceed(manager, train, limit, 22.22);

    double heldBpt = limit.properties().getSpeedLimit();
    assertTrue(heldBpt <= rampedBpt + 1.0e-12, "推进放行不得越过斜坡已下调到的值");
    assertTrue(heldBpt >= rampedBpt - SpeedLimitRamp.WRITE_STEP_BPT, "只应按进站曲线的当前值封顶");
    // 推进放行自己写了限速，斜坡须认下而不是当成外部改写退出。
    assertTrue(ramp.holdLimitBps(train).isPresent());
  }

  @Test
  void contextFreeProceedStillRelaunchesAfterASlowdownClears() {
    // 减速解除后信号 tick 只按加速度限幅逐拍上调限速、运动中不发 launch；过节点的推进放行是补牵引的来源，不能被周期命令值扣住。
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(10.0 / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), 10.0 / TICKS);
    // 前方远处还有一条限速边：登记了斜坡，但它此处不收紧，也不是保持约束。
    SpeedEnvelope farSlowEdge = SpeedEnvelope.empty().with(SpeedEnvelope.braking(500.0, 10.0, 1.0));

    signalTick(manager, train, limit, 10.0, farSlowEdge);
    signalTick(manager, train, limit, 22.22, farSlowEdge);
    assertEquals(1, ramp.size());
    assertTrue(limit.properties().getSpeedLimit() < 22.22 / TICKS, "信号 tick 的上调应被加速度限幅压住");

    TrainLaunchManager.ControlApplicationResult result =
        contextFreeProceed(manager, train, limit, 22.22);

    assertEquals(22.22 / TICKS, limit.properties().getSpeedLimit(), 1.0e-9);
    assertEquals(22.22 / TICKS, train.lastAccelerateTargetBpt, 1.0e-9, "推进放行应照旧补牵引到线路速度");
    assertEquals("none", result.finalLimiterSource());
  }

  @Test
  void contextFreeProceedKeepsOldBehaviourWithoutDispatchCommand() {
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(10.0 / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), 10.0 / TICKS);

    contextFreeProceed(manager, train, limit, 22.22);

    assertEquals(22.22 / TICKS, limit.properties().getSpeedLimit(), 1.0e-9);
  }

  @Test
  void stopReleasesTheRamp() {
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(10.0 / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), 10.0 / TICKS);
    signalTick(manager, train, limit, 22.22, approachHold(10.0));
    assertEquals(1, ramp.size());

    manager.applyControl(
        train,
        limit.properties(),
        SignalAspect.STOP,
        0.0,
        EMU,
        false,
        OptionalLong.of(60L),
        Optional.empty(),
        runtimeSettings(),
        StopControlMode.BRAKING_TO_PLANNED_STOP,
        SpeedEnvelope.empty());

    assertEquals(0, ramp.size());
  }

  @Test
  void onlyMovingTrainsWithConstraintsAreRegistered() {
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> () -> {});
    TrainLaunchManager manager = new TrainLaunchManager(ramp);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.0);
    RampTestSupport.MovingTrain stationary =
        new RampTestSupport.MovingTrain(limit.properties(), 0.0);
    stationary.moving = false;
    signalTick(manager, stationary, limit, 22.22, approachHold(10.0));
    assertEquals(0, ramp.size(), "静止列车不登记");

    RampTestSupport.SpeedLimitStore cruisingLimit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain cruising =
        new RampTestSupport.MovingTrain(cruisingLimit.properties(), 0.5);
    signalTick(manager, cruising, cruisingLimit, 10.0, SpeedEnvelope.empty());
    assertEquals(0, ramp.size(), "没有随距离收紧的约束时不登记");
  }

  private static TrainLaunchManager.ControlApplicationResult signalTick(
      TrainLaunchManager manager,
      RampTestSupport.MovingTrain train,
      RampTestSupport.SpeedLimitStore limit,
      double targetBps,
      SpeedEnvelope envelope) {
    return manager.applyControl(
        train,
        limit.properties(),
        SignalAspect.PROCEED,
        Math.min(targetBps, envelope.limitBps(0.0)),
        EMU,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(),
        StopControlMode.BRAKING_TO_PLANNED_STOP,
        envelope);
  }

  private static TrainLaunchManager.ControlApplicationResult contextFreeProceed(
      TrainLaunchManager manager,
      RampTestSupport.MovingTrain train,
      RampTestSupport.SpeedLimitStore limit,
      double targetBps) {
    return manager.applyControl(
        train,
        limit.properties(),
        SignalAspect.PROCEED,
        targetBps,
        EMU,
        true,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings());
  }

  private static ConfigManager.RuntimeSettings runtimeSettings() {
    return new ConfigManager.RuntimeSettings(
        20,
        10,
        2,
        1,
        1,
        3,
        4.0,
        6.0,
        3.5,
        true,
        SpeedCurveType.PHYSICS,
        1.0,
        0.0,
        0.2,
        60,
        true,
        true,
        2.0,
        8.0,
        0.0,
        1.0,
        1.0,
        3,
        true,
        10,
        Optional.empty(),
        false,
        10,
        Optional.empty(),
        false,
        10,
        Optional.empty());
  }
}
