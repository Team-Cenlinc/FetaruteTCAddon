package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.RecordingControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrainLaunchManagerTest {

  @Test
  void applyControlLimitsSpeedCommandStep() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-1",
            "FTA_LAST_SPEED_CMD_BPS=0.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 0.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 2.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        20.0,
        config,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    double appliedBpt = speedCaptor.getValue();
    assertTrue(appliedBpt < 0.05, "速度命令步长应被限幅，实际 bpt=" + appliedBpt);
  }

  @Test
  void speedCommandReferenceIsRememberedWithoutWritingTags() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-memory");
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 0.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 2.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    for (double target : new double[] {0.0, 20.0}) {
      manager.applyControl(
          train,
          tags.properties(),
          SignalAspect.PROCEED,
          target,
          config,
          false,
          OptionalLong.empty(),
          Optional.empty(),
          runtime);
    }

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties(), org.mockito.Mockito.times(2)).setSpeedLimit(speedCaptor.capture());
    assertTrue(speedCaptor.getAllValues().get(1) < 0.05, "第二次命令应按内存里记下的上一命令限幅");
    assertTrue(
        tags.tags.stream().noneMatch(tag -> tag.startsWith("FTA_LAST_SPEED_CMD")),
        "速度命令参照不应再写进 tag：" + tags.tags);
  }

  @Test
  void applyControlDisablesTrainCartsSlowdown() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-slowdown");
    when(tags.properties().isSlowingDownNone()).thenReturn(false);
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 22.2 / 20.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 1.2),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(tags.properties()).setSlowingDown(false);
  }

  @Test
  void applyControlLeavesSlowdownAloneWhenAlreadyDisabled() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-no-slowdown");
    when(tags.properties().isSlowingDownNone()).thenReturn(true);
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 22.2 / 20.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 1.2),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(tags.properties(), never()).setSlowingDown(anyBoolean());
  }

  @Test
  void applyControlKeepsPreviousCommandWithinHysteresis() {
    TrainLaunchManager manager = new TrainLaunchManager();
    long now = System.currentTimeMillis() - 1000L;
    TagStore tags =
        new TagStore("train-2", "FTA_LAST_SPEED_CMD_BPS=5.0", "FTA_LAST_SPEED_CMD_AT=" + now);
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 5.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 2.0, 2.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.2, 1.0, 1.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        5.05,
        config,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    double appliedBpt = speedCaptor.getValue();
    double expectedBpt = 5.0 / 20.0;
    assertTrue(Math.abs(appliedBpt - expectedBpt) < 1.0e-6, "迟滞应保持上一命令速度");
  }

  @Test
  void applyControlAppliesLowerSpeedLimitWithoutDecelerationRateLimit() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-lower",
            "FTA_LAST_SPEED_CMD_BPS=20.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 20.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 2.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            false,
            OptionalLong.empty(),
            Optional.empty(),
            runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());

    assertEquals(8.0 / 20.0, speedCaptor.getValue(), 1.0e-6);
    assertEquals(8.0, result.finalTargetBps(), 1.0e-6);
  }

  @Test
  void applyControlUsesLaunchActionForMovingDeceleration() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-decel",
            "FTA_LAST_SPEED_CMD_BPS=20.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.isMoving()).thenReturn(true);
    when(train.currentSpeedBlocksPerTick()).thenReturn(20.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 2.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        8.0,
        config,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);

    ArgumentCaptor<Double> targetCaptor = ArgumentCaptor.forClass(Double.class);
    ArgumentCaptor<Double> accelCaptor = ArgumentCaptor.forClass(Double.class);
    verify(train).accelerateTo(targetCaptor.capture(), accelCaptor.capture());

    assertEquals(8.0 / 20.0, targetCaptor.getValue(), 1.0e-6);
    assertEquals(2.0 / 400.0, accelCaptor.getValue(), 1.0e-6);
  }

  @Test
  void applyControlBypassesAccelerationLimitWhenLaunchAllowed() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-3",
            "FTA_LAST_SPEED_CMD_BPS=0.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), false, 0.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.empty(),
            runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    double appliedBpt = speedCaptor.getValue();
    assertTrue(appliedBpt >= 0.39, "发车场景应下发接近目标速度，避免起步龟速");
    assertTrue(result.launchCommandAccepted());
  }

  @Test
  void applyControlReportsRejectedLaunchRequest() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-rejected");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        new FakeTrain(tags.properties(), false, 0.0) {
          @Override
          public boolean requestLaunchWithFallback(
              Optional<org.bukkit.block.BlockFace> fallbackDirection,
              double targetBlocksPerTick,
              double accelBlocksPerTickSquared) {
            attempts.incrementAndGet();
            return false;
          }
        };
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.empty(),
            runtime);
    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        8.0,
        config,
        true,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);

    assertFalse(result.launchCommandAccepted());
    assertEquals(2, attempts.get(), "被 TrainCarts 拒绝的动作不应消耗 launch cooldown");
    assertFalse(TrainTagHelper.readTagValue(tags.properties(), "FTA_LAST_LAUNCH_AT").isPresent());
  }

  @Test
  void identicalStationaryAuthorizationsIssueOneLaunchUntilPhysicalProgress() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-stationary-authority");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        new FakeTrain(tags.properties(), false, 0.0) {
          @Override
          public boolean requestLaunchWithFallback(
              Optional<org.bukkit.block.BlockFace> fallbackDirection,
              double targetBlocksPerTick,
              double accelBlocksPerTickSquared) {
            attempts.incrementAndGet();
            return true;
          }
        };
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0, 0);

    for (int i = 0; i < 1_000; i++) {
      TrainLaunchManager.ControlApplicationResult result =
          manager.applyControl(
              train,
              tags.properties(),
              SignalAspect.PROCEED,
              8.0,
              config,
              true,
              OptionalLong.empty(),
              Optional.empty(),
              runtime);
      assertTrue(result.launchCommandAccepted());
    }

    assertEquals(1, attempts.get(), "同一静止授权不能在每次重评估中重新写入 launch action");
  }

  /**
   * 硬停清空了 TrainCarts 动作队列，冷却保护的那次 launch 已不存在：冷却期内重新放行必须当拍发车。
   *
   * <p>否则这一拍发不了车，而之后信号不再变化、不会再请求发车，静止列车就停在 PROCEED 下等健康监控补发。
   */
  @Test
  void hardStopClearsLaunchCooldownSoTheNextAuthorizationLaunches() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-hard-stop-relaunch");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        new FakeTrain(tags.properties(), false, 0.0) {
          @Override
          public boolean requestLaunchWithFallback(
              Optional<org.bukkit.block.BlockFace> fallbackDirection,
              double targetBlocksPerTick,
              double accelBlocksPerTickSquared) {
            attempts.incrementAndGet();
            return true;
          }
        };
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    // 冷却取 60 秒：断言不受两次调用之间的墙钟间隔影响。
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0, 20 * 60);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        8.0,
        config,
        true,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);
    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.STOP,
        0.0,
        config,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtime,
        StopControlMode.HARD_STOP);
    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.empty(),
            runtime);

    assertTrue(result.launchCommandAccepted(), "硬停后冷却期内的放行没有发车");
    assertEquals(2, attempts.get());
  }

  /** 没有硬停时冷却照旧生效：静止列车在冷却期内收到另一条放行，不重写 launch。 */
  @Test
  void launchCooldownStillAppliesWithoutHardStop() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-cooldown-kept");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        new FakeTrain(tags.properties(), false, 0.0) {
          @Override
          public boolean requestLaunchWithFallback(
              Optional<org.bukkit.block.BlockFace> fallbackDirection,
              double targetBlocksPerTick,
              double accelBlocksPerTickSquared) {
            attempts.incrementAndGet();
            return true;
          }
        };
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    // 冷却取 60 秒：断言不受两次调用之间的墙钟间隔影响。
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0, 20 * 60);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        8.0,
        config,
        true,
        OptionalLong.empty(),
        Optional.empty(),
        runtime);
    // 兜底方向不同 → 命令签名不同，不会被"相同授权已接受"的去重吸收，只能由冷却挡住。
    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.of(org.bukkit.block.BlockFace.NORTH),
            runtime);

    assertFalse(result.launchCommandAccepted());
    assertEquals(1, attempts.get(), "冷却期内不应再写 launch");
  }

  /** 静止列车的可脚本化句柄：是否在动、TrainCarts 是否接受每一次 launch 都由测试指定。 */
  private static RuntimeTrainHandle scriptedTrain(
      TrainProperties properties,
      AtomicBoolean moving,
      AtomicInteger attempts,
      Deque<Boolean> responses) {
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.properties()).thenReturn(properties);
    when(train.isValid()).thenReturn(true);
    when(train.isMoving()).thenAnswer(inv -> moving.get());
    when(train.currentSpeedBlocksPerTick()).thenAnswer(inv -> moving.get() ? 0.4 : 0.0);
    when(train.requestLaunchWithFallback(any(), anyDouble(), anyDouble()))
        .thenAnswer(
            inv -> {
              attempts.incrementAndGet();
              Boolean response = responses.poll();
              return response == null || response;
            });
    return train;
  }

  private static TrainLaunchManager.ControlApplicationResult proceed(
      TrainLaunchManager manager,
      RuntimeTrainHandle train,
      TrainProperties properties,
      boolean allowLaunch) {
    return manager.applyControl(
        train,
        properties,
        SignalAspect.PROCEED,
        8.0,
        new TrainConfig(TrainType.EMU, 0.8, 1.0),
        allowLaunch,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0, 20 * 60));
  }

  private static void softStop(
      TrainLaunchManager manager, RuntimeTrainHandle train, TrainProperties properties) {
    manager.applyControl(
        train,
        properties,
        SignalAspect.STOP,
        0.0,
        new TrainConfig(TrainType.EMU, 0.8, 1.0),
        false,
        OptionalLong.of(0L),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0, 20 * 60));
  }

  private static boolean launchOwed(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, "FTA_LAUNCH_OWED").isPresent();
  }

  /**
   * 普通 STOP 停到 0 会清掉正在执行的 launch，但冷却还在：冷却期内的放行发不出去，记欠账；冷却过后即使信号不再变化也补发。
   *
   * <p>否则调度层只在信号变化那一拍要求发车，之后再也不会要求，车停在 PROCEED 下等健康监控补发。
   */
  @Test
  void cooldownRefusedLaunchIsRetriedOnceTheCooldownExpires() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-owed-cooldown");
    AtomicBoolean moving = new AtomicBoolean(false);
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        scriptedTrain(tags.properties(), moving, attempts, new ArrayDeque<>());

    assertTrue(proceed(manager, train, tags.properties(), true).launchCommandAccepted());
    softStop(manager, train, tags.properties());
    TrainLaunchManager.ControlApplicationResult refused =
        proceed(manager, train, tags.properties(), true);

    assertFalse(refused.launchCommandAccepted());
    assertEquals(1, attempts.get());
    assertTrue(launchOwed(tags.properties()), "冷却挡住的发车应记欠账");

    // 冷却期内信号不变：仍不发。
    proceed(manager, train, tags.properties(), false);
    assertEquals(1, attempts.get());

    TrainTagHelper.writeTag(tags.properties(), "FTA_LAST_LAUNCH_AT", "1");
    TrainLaunchManager.ControlApplicationResult retried =
        proceed(manager, train, tags.properties(), false);

    assertTrue(retried.launchCommandAccepted(), "冷却过后信号不变也应补发");
    assertEquals(2, attempts.get());
    assertFalse(launchOwed(tags.properties()), "发车被接受后应销账");
  }

  /** TrainCarts 拒绝的 launch 不耗冷却，下一拍接着补发。 */
  @Test
  void rejectedLaunchIsRetriedOnTheNextCycle() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-owed-rejected");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        scriptedTrain(
            tags.properties(),
            new AtomicBoolean(false),
            attempts,
            new ArrayDeque<>(List.of(false, true)));

    assertFalse(proceed(manager, train, tags.properties(), true).launchCommandAccepted());
    assertTrue(launchOwed(tags.properties()));

    assertTrue(proceed(manager, train, tags.properties(), false).launchCommandAccepted());
    assertEquals(2, attempts.get());
    assertFalse(launchOwed(tags.properties()));
  }

  /** 欠账之后来了 STOP：调度层已撤销放行，之后信号不变时不得替它补发。 */
  @Test
  void stopCancelsAnOwedLaunch() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-owed-stop");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        scriptedTrain(
            tags.properties(),
            new AtomicBoolean(false),
            attempts,
            new ArrayDeque<>(List.of(false)));

    proceed(manager, train, tags.properties(), true);
    assertTrue(launchOwed(tags.properties()));
    softStop(manager, train, tags.properties());

    assertFalse(launchOwed(tags.properties()), "STOP 应销账");
    proceed(manager, train, tags.properties(), false);
    assertEquals(1, attempts.get(), "STOP 之后信号不变不应补发");
  }

  /** 车已经动起来（别的路径发了车）：欠账作废。 */
  @Test
  void physicalMovementCancelsAnOwedLaunch() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-owed-moving");
    AtomicBoolean moving = new AtomicBoolean(false);
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        scriptedTrain(tags.properties(), moving, attempts, new ArrayDeque<>(List.of(false)));

    proceed(manager, train, tags.properties(), true);
    assertTrue(launchOwed(tags.properties()));
    moving.set(true);
    proceed(manager, train, tags.properties(), false);

    assertFalse(launchOwed(tags.properties()), "物理移动应销账");
    moving.set(false);
    proceed(manager, train, tags.properties(), false);
    assertEquals(1, attempts.get(), "销账后静止下来不应再补发");
  }

  /** 驾驶员接管后起步归驾驶员：交还自动运行时不能拿接管前的欠账替他发车。 */
  @Test
  void driverTakeoverCancelsAnOwedLaunch() {
    TagStore tags = new TagStore("train-owed-driver");
    AtomicInteger attempts = new AtomicInteger();
    RuntimeTrainHandle train =
        scriptedTrain(
            tags.properties(),
            new AtomicBoolean(false),
            attempts,
            new ArrayDeque<>(List.of(false)));
    TrainLaunchManager automatic = new TrainLaunchManager();
    TrainLaunchManager driven =
        new TrainLaunchManager(
            new SpeedLimitRamp(), new RecordingControlAuthority().control(tags.properties()));

    proceed(automatic, train, tags.properties(), true);
    assertTrue(launchOwed(tags.properties()));
    proceed(driven, train, tags.properties(), false);

    assertFalse(launchOwed(tags.properties()), "驾驶员接管应销账");
    proceed(automatic, train, tags.properties(), false);
    assertEquals(1, attempts.get(), "交还自动运行后不应补发接管前的欠账");
  }

  /** 硬停一次遍历清掉待发车标记、欠账与冷却，不逐项扫 tag。 */
  @Test
  void hardStopClearsLaunchBookkeepingInOneTagPass() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-hard-stop-bookkeeping",
            "FTA_PENDING_LAUNCH_COMMAND=sig",
            "FTA_LAUNCH_OWED=true",
            "FTA_LAST_LAUNCH_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train =
        scriptedTrain(
            tags.properties(), new AtomicBoolean(false), new AtomicInteger(), new ArrayDeque<>());

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.STOP,
        0.0,
        new TrainConfig(TrainType.EMU, 0.8, 1.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0, 20 * 60),
        StopControlMode.HARD_STOP);

    verify(tags.properties(), times(1)).removeTags(any(String[].class));
    assertFalse(
        TrainTagHelper.readTagValue(tags.properties(), "FTA_PENDING_LAUNCH_COMMAND").isPresent());
    assertFalse(launchOwed(tags.properties()));
    assertFalse(TrainTagHelper.readTagValue(tags.properties(), "FTA_LAST_LAUNCH_AT").isPresent());
  }

  @Test
  void applyControlTreatsAlreadyMovingTrainAsAcceptedLaunch() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = new TagStore("train-already-moving");
    RuntimeTrainHandle train = new FakeTrain(tags.properties(), true, 4.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.PROCEED,
            8.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.empty(),
            runtime);

    assertTrue(result.launchCommandAccepted());
  }

  @Test
  void applyControlStopBypassesDecelerationRateLimit() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-4",
            "FTA_LAST_SPEED_CMD_BPS=15.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.isMoving()).thenReturn(true);
    when(train.currentSpeedBlocksPerTick()).thenReturn(15.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.STOP,
        0.0,
        config,
        false,
        OptionalLong.of(2L),
        Optional.empty(),
        runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    double appliedBpt = speedCaptor.getValue();
    assertEquals(2.0 / 20.0, appliedBpt, 1.0e-6, "STOP 应按剩余距离下压到制动曲线限速");
    verify(train).accelerateTo(2.0 / 20.0, 1.0 / 400.0);
    verify(train, never()).stop();
  }

  @Test
  void applyControlStopStopsAtConstraintPoint() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-stop-zero",
            "FTA_LAST_SPEED_CMD_BPS=15.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.currentSpeedBlocksPerTick()).thenReturn(15.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.STOP,
        0.0,
        config,
        false,
        OptionalLong.of(0L),
        Optional.empty(),
        runtime);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());

    assertEquals(0.0, speedCaptor.getValue(), 1.0e-6);
    verify(train).stop();
  }

  @Test
  void hardStopDoesNotAccelerateToEvenWhenDistancePresent() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-hard-stop",
            "FTA_LAST_SPEED_CMD_BPS=15.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    FakeTrain train = new FakeTrain(tags.properties(), true, 15.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);
    ConfigManager.RuntimeSettings runtime = runtimeSettings(0.0, 1.0, 1.0);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            tags.properties(),
            SignalAspect.STOP,
            0.0,
            config,
            false,
            OptionalLong.of(20L),
            Optional.empty(),
            runtime,
            StopControlMode.HARD_STOP);

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertEquals(0.0, speedCaptor.getValue(), 1.0e-6);
    assertEquals(1, train.hardStopCalls);
    assertEquals(0, train.accelerateCalls);
    assertEquals("hard_stop", result.finalLimiterSource());
  }

  /**
   * STOP 制动曲线不高于当前车速：离停车点还远时只保持车速，不借 STOP 加速；进入曲线后照曲线限速。
   *
   * <p>沿已持有授权刹车（{@link HeldAuthorityBraking}）一被拒就交出很远的停车距离，靠的就是这一条。车速 15 bps、减速度 1：500 格处曲线
   * √1000≈31.6，限速取 15；20 格处曲线 √40≈6.3。
   */
  @Test
  void stopCurveHoldsSpeedUntilItBitesAndNeverAccelerates() {
    ArgumentCaptor<Double> far = ArgumentCaptor.forClass(Double.class);
    TagStore farTags = stopCurve(500L);
    verify(farTags.properties()).setSpeedLimit(far.capture());
    assertEquals(15.0 / 20.0, far.getValue(), 1.0e-9);

    ArgumentCaptor<Double> near = ArgumentCaptor.forClass(Double.class);
    TagStore nearTags = stopCurve(20L);
    verify(nearTags.properties()).setSpeedLimit(near.capture());
    assertEquals(Math.sqrt(40.0) / 20.0, near.getValue(), 1.0e-9);
  }

  private static TagStore stopCurve(long distanceBlocks) {
    TagStore tags =
        new TagStore(
            "train-braking-" + distanceBlocks,
            "FTA_LAST_SPEED_CMD_BPS=15.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    FakeTrain train = new FakeTrain(tags.properties(), true, 15.0 / 20.0);
    new TrainLaunchManager()
        .applyControl(
            train,
            tags.properties(),
            SignalAspect.STOP,
            0.0,
            new TrainConfig(TrainType.EMU, 1.0, 1.0),
            false,
            OptionalLong.of(distanceBlocks),
            Optional.empty(),
            runtimeSettings(0.0, 1.0, 1.0));
    return tags;
  }

  /**
   * 驶过慢速边后目标回升：列车身上没有别的动作时补一次牵引，速度上限直接给到目标、由 launch 按加速度爬升。
   *
   * <p>TrainCarts 列车不会因为 speedLimit 调高就自己加速（2026-09-27 实服 WS LWN→SWN 全段 8 格/秒）。
   */
  @Test
  void movingTrainBelowItsTargetResumesTractionWhenNothingElseIsQueued() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = slowTrainTags("train-resume");
    RuntimeTrainHandle train = movingAt(8.0, false);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(train).accelerateTo(22.2 / 20.0, 1.0 / 400.0);
    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertEquals(22.2 / 20.0, speedCaptor.getValue(), 1.0e-9);
  }

  /** 停站等待、居中等动作还在队列里时不补牵引：launch 会排在它后面，等于绕过发车门控。 */
  @Test
  void movingTrainWithAnotherActionQueuedIsNotGivenTraction() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = slowTrainTags("train-foreign");
    RuntimeTrainHandle train = movingAt(8.0, true);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(train, never()).accelerateTo(anyDouble(), anyDouble());
    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertTrue(speedCaptor.getValue() < 22.2 / 20.0, "不补牵引时速度上限仍按命令限幅逐步抬升");
  }

  /** 车速比限速只低 0.15 格/秒（不到 1%）也补牵引：牵引目标就是限速本身，否则车会一直比编表曲线慢一截。 */
  @Test
  void movingTrainSlightlyBelowItsLimitIsPulledUpToTheLimit() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-near",
            "FTA_LAST_SPEED_CMD_BPS=22.2",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = movingAt(22.05, false);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(train).accelerateTo(org.mockito.ArgumentMatchers.eq(22.2 / 20.0), anyDouble());
  }

  /** 车速已贴住限速（差 0.01 格/秒，低于补牵引门槛）：不再下发动作。 */
  @Test
  void movingTrainAtItsLimitGetsNoNewAction() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-at-limit",
            "FTA_LAST_SPEED_CMD_BPS=22.2",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = movingAt(22.19, false);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    verify(train, never()).accelerateTo(anyDouble(), anyDouble());
  }

  /** 补牵引时限速不按迟滞留在旧命令上：旧命令 22.1、目标 22.2，差值小于 0.15 的迟滞也要写到 22.2。 */
  @Test
  void tractionBypassesHysteresisSoTheLimitIsTheTarget() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags =
        new TagStore(
            "train-hysteresis",
            "FTA_LAST_SPEED_CMD_BPS=22.1",
            "FTA_LAST_SPEED_CMD_AT=" + (System.currentTimeMillis() - 1000L));
    RuntimeTrainHandle train = movingAt(22.1, false);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.15, 1.0, 1.0));

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertEquals(22.2 / 20.0, speedCaptor.getValue(), 1.0e-9);
    verify(train).accelerateTo(org.mockito.ArgumentMatchers.eq(22.2 / 20.0), anyDouble());
  }

  /** 报告不了动作队列的实现按"有别的动作"处理，保持只在信号变化时补牵引的旧行为。 */
  @Test
  void handlesThatCannotReportTheirActionsKeepTheOldBehaviour() {
    TrainLaunchManager manager = new TrainLaunchManager();
    TagStore tags = slowTrainTags("train-legacy");
    FakeTrain train = new FakeTrain(tags.properties(), true, 8.0 / 20.0);

    manager.applyControl(
        train,
        tags.properties(),
        SignalAspect.PROCEED,
        22.2,
        new TrainConfig(TrainType.EMU, 1.0, 2.0),
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings(0.0, 1.0, 1.0));

    assertEquals(0, train.accelerateCalls);
  }

  private static TagStore slowTrainTags(String trainName) {
    return new TagStore(
        trainName,
        "FTA_LAST_SPEED_CMD_BPS=8.0",
        "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
  }

  private static RuntimeTrainHandle movingAt(double speedBps, boolean foreignAction) {
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.isMoving()).thenReturn(true);
    when(train.currentSpeedBlocksPerTick()).thenReturn(speedBps / 20.0);
    when(train.hasForeignAction()).thenReturn(foreignAction);
    return train;
  }

  private static ConfigManager.RuntimeSettings runtimeSettings(
      double hysteresisBps, double accelFactor, double decelFactor) {
    return runtimeSettings(hysteresisBps, accelFactor, decelFactor, 10);
  }

  private static ConfigManager.RuntimeSettings runtimeSettings(
      double hysteresisBps, double accelFactor, double decelFactor, int launchCooldownTicks) {
    return new ConfigManager.RuntimeSettings(
        20,
        launchCooldownTicks,
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
        hysteresisBps,
        accelFactor,
        decelFactor,
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

  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String trainName, String... initial) {
      this.tags = new ArrayList<>(List.of(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.getTrainName()).thenReturn(trainName);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s && !s.isBlank()) {
                      tags.add(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      lenient()
          .doAnswer(
              inv -> {
                Object[] args = inv.getArguments();
                if (args != null) {
                  for (Object arg : args) {
                    if (arg instanceof String s) {
                      tags.remove(s);
                    }
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    private TrainProperties properties() {
      return properties;
    }
  }

  private static class FakeTrain implements RuntimeTrainHandle {
    private final TrainProperties properties;
    private final boolean moving;
    private final double speedBpt;
    private int hardStopCalls;
    private int accelerateCalls;

    private FakeTrain(TrainProperties properties, boolean moving, double speedBpt) {
      this.properties = properties;
      this.moving = moving;
      this.speedBpt = speedBpt;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isMoving() {
      return moving;
    }

    @Override
    public double currentSpeedBlocksPerTick() {
      return speedBpt;
    }

    @Override
    public UUID worldId() {
      return new UUID(0L, 0L);
    }

    @Override
    public TrainProperties properties() {
      return properties;
    }

    @Override
    public void stop() {}

    @Override
    public void stopHard() {
      hardStopCalls++;
    }

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {}

    @Override
    public void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      accelerateCalls++;
    }

    @Override
    public void destroy() {}

    @Override
    public void setRouteIndex(int index) {}

    @Override
    public void setRouteId(String routeId) {}

    @Override
    public void setDestination(String destination) {}

    @Override
    public Optional<org.bukkit.block.BlockFace> forwardDirection() {
      return Optional.empty();
    }

    @Override
    public void reverse() {}
  }
}
