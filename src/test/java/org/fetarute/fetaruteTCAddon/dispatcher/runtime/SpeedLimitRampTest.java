package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SpeedEnvelope;
import org.junit.jupiter.api.Test;

class SpeedLimitRampTest {

  private static final double TICKS = 20.0;

  /** 进站限速这类保持约束：此处固定为 {@code bps}。 */
  private static SpeedEnvelope hold(double bps) {
    return SpeedEnvelope.empty().withHold(traveled -> bps);
  }

  @Test
  void lowersLimitAlongEnvelopeInSmallStepsAndStopsAtEndSpeed() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    SpeedEnvelope envelope = SpeedEnvelope.empty().with(SpeedEnvelope.braking(60.0, 10.0, 1.0));
    double commandedBps = envelope.limitBps(0.0);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(commandedBps / TICKS);
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), commandedBps / TICKS);

    ramp.arm(train, limit.properties(), commandedBps, envelope, 400);
    List<Double> written = new ArrayList<>();
    for (int tick = 0; tick < 200; tick++) {
      driver.tick();
      double current = limit.properties().getSpeedLimit();
      if (written.isEmpty() || current != written.get(written.size() - 1)) {
        written.add(current);
      }
      // 列车跟着限速走：实际速度就是限速。
      train.speedBpt = current;
    }

    assertTrue(written.size() > 20, "应分多次小步下调，实际写入 " + written.size() + " 次");
    double previous = commandedBps / TICKS;
    for (double value : written) {
      assertTrue(value <= previous + 1.0e-12, "斜坡不得抬高限速");
      assertTrue(previous - value <= 0.01, "单次下调不应超过 0.2 bps，实际 " + (previous - value) * TICKS);
      previous = value;
    }
    assertEquals(10.0 / TICKS, limit.properties().getSpeedLimit(), SpeedLimitRamp.WRITE_STEP_BPT);
    assertTrue(limit.properties().getSpeedLimit() >= 10.0 / TICKS - 1.0e-9, "不得压到终点限速以下");
  }

  @Test
  void deadReckoningDoesNotTrustVelocityAboveTheSpeedLimit() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    SpeedEnvelope envelope = SpeedEnvelope.empty().with(SpeedEnvelope.braking(80.0, 0.0, 1.0));
    double commandedBps = 10.0;
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(commandedBps / TICKS);
    // 速度向量残留在 22.2 bps，但实际位移被 10 bps 的限速截住。
    RampTestSupport.MovingTrain train =
        new RampTestSupport.MovingTrain(limit.properties(), 22.22 / TICKS);

    ramp.arm(train, limit.properties(), commandedBps, envelope, 400);
    for (int tick = 0; tick < 40; tick++) {
      driver.tick();
    }

    // 两秒按 10 bps 走 20 格：包络 = √(2·1·60) ≈ 10.95 > 10，不应下调；按残留向量推算会走 44 格而误压到 ≈ 8.5。
    assertEquals(commandedBps / TICKS, limit.properties().getSpeedLimit(), 1.0e-12);
  }

  @Test
  void neverRaisesTheLimit() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.4);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.4);

    ramp.arm(train, limit.properties(), 8.0, SpeedEnvelope.empty().with(traveled -> 30.0), 100);
    for (int tick = 0; tick < 10; tick++) {
      driver.tick();
    }

    assertEquals(0.4, limit.properties().getSpeedLimit(), 1.0e-12);
    assertEquals(0, limit.writes());
  }

  @Test
  void refusesEmptyEnvelope() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.5);

    ramp.arm(train, limit.properties(), 10.0, SpeedEnvelope.empty(), 100);

    assertEquals(0, ramp.size());
    assertTrue(driver.tick == null || driver.stopped, "没有可下调的约束时不应开着时钟");
  }

  @Test
  void yieldsWhenSpeedLimitIsWrittenElsewhere() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.7);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.7);
    ramp.arm(
        train,
        limit.properties(),
        14.0,
        SpeedEnvelope.empty().withHold(SpeedEnvelope.braking(40.0, 0.0, 1.0)),
        100);

    limit.properties().setSpeedLimit(0.0);
    int writesBefore = limit.writes();
    driver.tick();

    assertEquals(0, ramp.size());
    assertEquals(writesBefore, limit.writes());
    assertTrue(driver.stopped, "登记表清空后应停掉时钟");
    assertTrue(ramp.holdLimitBps(train).isEmpty());
  }

  @Test
  void releasesWhenTrainStopsAndExpiresWithoutRefresh() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore stopping = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain stoppingTrain =
        new RampTestSupport.MovingTrain(stopping.properties(), 0.5);
    RampTestSupport.SpeedLimitStore cruising = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain cruisingTrain =
        new RampTestSupport.MovingTrain(cruising.properties(), 0.5);
    ramp.arm(stoppingTrain, stopping.properties(), 10.0, hold(10.0), 100);
    ramp.arm(cruisingTrain, cruising.properties(), 10.0, hold(10.0), 3);

    stoppingTrain.moving = false;
    driver.tick();
    assertTrue(ramp.holdLimitBps(stoppingTrain).isEmpty());
    assertEquals(OptionalDouble.of(10.0), ramp.holdLimitBps(cruisingTrain));

    driver.tick();
    driver.tick();
    driver.tick();
    assertTrue(ramp.holdLimitBps(cruisingTrain).isEmpty());
    assertTrue(driver.stopped);
  }

  @Test
  void holdLimitIgnoresBrakingConstraintsThatAreNotHolds() {
    // 前方限速边越过即失效，不能拿来挡推进放行；只有登记为保持约束的进站限速才算。
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.5);

    ramp.arm(
        train,
        limit.properties(),
        10.0,
        SpeedEnvelope.empty().with(SpeedEnvelope.braking(0.0, 8.0, 1.0)),
        100);
    assertEquals(1, ramp.size());
    assertTrue(ramp.holdLimitBps(train).isEmpty());

    ramp.arm(
        train,
        limit.properties(),
        10.0,
        SpeedEnvelope.empty().withHold(traveled -> Double.POSITIVE_INFINITY),
        100);
    assertTrue(ramp.holdLimitBps(train).isEmpty(), "保持约束此处不收紧时不设限");
  }

  @Test
  void holdLimitIsTheHoldItselfNotTheWrittenLimit() {
    // 已写入 8 bps（可能来自边限速或上调限幅的滞后）：推进放行只被保持约束 12 bps 封顶，既不放开也不压回 8。
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.4);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.4);

    ramp.arm(train, limit.properties(), 8.0, hold(12.0), 100);
    assertEquals(12.0, ramp.holdLimitBps(train).orElseThrow(), 1.0e-9);

    ramp.arm(train, limit.properties(), 8.0, hold(6.0), 100);
    assertEquals(6.0, ramp.holdLimitBps(train).orElseThrow(), 1.0e-9);
  }

  @Test
  void failingEntryIsDroppedWithoutStallingOtherTrains() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore brokenLimit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain broken =
        new RampTestSupport.MovingTrain(brokenLimit.properties(), 0.5);
    RampTestSupport.SpeedLimitStore healthyLimit = RampTestSupport.speedLimitStore(0.7);
    RampTestSupport.MovingTrain healthy =
        new RampTestSupport.MovingTrain(healthyLimit.properties(), 0.7);
    ramp.arm(
        broken,
        brokenLimit.properties(),
        10.0,
        SpeedEnvelope.empty()
            .with(
                traveled -> {
                  throw new IllegalStateException("group unloaded");
                }),
        100);
    ramp.arm(
        healthy,
        healthyLimit.properties(),
        14.0,
        SpeedEnvelope.empty().with(SpeedEnvelope.braking(40.0, 0.0, 1.0)),
        100);

    for (int tick = 0; tick < 20; tick++) {
      driver.tick();
      healthy.speedBpt = healthyLimit.properties().getSpeedLimit();
    }

    assertEquals(1, ramp.size(), "出错的那辆应被撤销，另一辆保留");
    assertTrue(healthyLimit.properties().getSpeedLimit() < 0.7, "另一辆的斜坡应照常下调");
  }

  @Test
  void staysInertWithoutClock() {
    SpeedLimitRamp ramp = new SpeedLimitRamp(tick -> null);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.5);

    ramp.arm(train, limit.properties(), 10.0, hold(8.0), 100);

    assertEquals(0, ramp.size());
    assertTrue(ramp.holdLimitBps(train).isEmpty());
  }

  @Test
  void acknowledgeWriteAcceptsLowerValuesOnly() {
    ManualDriver driver = new ManualDriver();
    SpeedLimitRamp ramp = new SpeedLimitRamp(driver);
    RampTestSupport.SpeedLimitStore limit = RampTestSupport.speedLimitStore(0.5);
    RampTestSupport.MovingTrain train = new RampTestSupport.MovingTrain(limit.properties(), 0.5);
    ramp.arm(train, limit.properties(), 10.0, hold(8.0), 100);

    limit.properties().setSpeedLimit(0.4);
    ramp.acknowledgeWrite(train, limit.properties());
    assertEquals(8.0, ramp.holdLimitBps(train).orElseThrow(), 1.0e-9);

    limit.properties().setSpeedLimit(0.6);
    ramp.acknowledgeWrite(train, limit.properties());
    assertFalse(ramp.holdLimitBps(train).isPresent());
  }

  private static final class ManualDriver implements SpeedLimitRamp.TickDriver {
    private Runnable tick;
    private boolean stopped;

    @Override
    public Runnable start(Runnable tick) {
      this.tick = tick;
      this.stopped = false;
      return () -> stopped = true;
    }

    private void tick() {
      if (tick != null && !stopped) {
        tick.run();
      }
    }
  }
}
