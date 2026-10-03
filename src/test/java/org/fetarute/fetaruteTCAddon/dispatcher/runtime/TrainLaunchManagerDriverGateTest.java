package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.RecordingControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 驾驶员控制的列车：执行层只把决定交给驾驶员，不写限速、不发车。 */
@DisplayName("TrainLaunchManager 驾驶员控制分流")
class TrainLaunchManagerDriverGateTest {

  private final TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);

  private static TrainProperties properties(String name) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(name);
    when(properties.hasTags()).thenReturn(false);
    when(properties.getTags()).thenReturn(List.of());
    return properties;
  }

  private static RuntimeTrainHandle train(TrainProperties properties, double speedBps) {
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.properties()).thenReturn(properties);
    when(train.isValid()).thenReturn(true);
    when(train.isMoving()).thenReturn(speedBps > 0.0);
    when(train.currentSpeedBlocksPerTick()).thenReturn(speedBps / 20.0);
    return train;
  }

  @Test
  @DisplayName("PROCEED：发布指令，不写限速、不发车，并报告发车已接受")
  void proceedPublishesDirectiveWithoutTouchingTheTrain() {
    TrainProperties properties = properties("drv-1");
    RuntimeTrainHandle train = train(properties, 0.0);
    RecordingControlAuthority authority = new RecordingControlAuthority().control(properties);
    TrainLaunchManager manager = new TrainLaunchManager(new SpeedLimitRamp(), authority);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            properties,
            SignalAspect.PROCEED,
            12.0,
            config,
            true,
            OptionalLong.empty(),
            Optional.empty(),
            runtimeSettings());

    verify(properties, never()).setSpeedLimit(anyDouble());
    verify(train, never()).stop();
    verify(train, never()).stopHard();
    DriverDirective directive = authority.lastDirective();
    assertEquals(SignalAspect.PROCEED, directive.aspect());
    assertEquals(12.0, directive.permittedBps(), 1.0e-6);
    assertTrue(directive.allowLaunch());
    assertTrue(result.launchCommandAccepted(), "起步交给驾驶员，调度层不应反复重试发车");
    assertEquals("driver", result.finalLimiterSource());
  }

  @Test
  @DisplayName("STOP 硬停：容许速度为 0，发车未接受，列车本身不被停住")
  void hardStopPublishesZeroPermitted() {
    TrainProperties properties = properties("drv-2");
    RuntimeTrainHandle train = train(properties, 10.0);
    RecordingControlAuthority authority = new RecordingControlAuthority().control(properties);
    TrainLaunchManager manager = new TrainLaunchManager(new SpeedLimitRamp(), authority);

    TrainLaunchManager.ControlApplicationResult result =
        manager.applyControl(
            train,
            properties,
            SignalAspect.STOP,
            0.0,
            config,
            false,
            OptionalLong.of(0L),
            Optional.empty(),
            runtimeSettings(),
            StopControlMode.HARD_STOP);

    verify(properties, never()).setSpeedLimit(anyDouble());
    verify(train, never()).stop();
    verify(train, never()).stopHard();
    DriverDirective directive = authority.lastDirective();
    assertTrue(directive.isStop());
    assertEquals(StopControlMode.HARD_STOP, directive.stopMode());
    assertEquals(0.0, directive.permittedBps(), 1.0e-9);
    assertFalse(result.launchCommandAccepted());
    assertEquals("driver_hard_stop", result.finalLimiterSource());
  }

  @Test
  @DisplayName("STOP 有距离：指令带上到停车点的距离")
  void stopWithDistanceCarriesTheDistance() {
    TrainProperties properties = properties("drv-3");
    RuntimeTrainHandle train = train(properties, 8.0);
    RecordingControlAuthority authority = new RecordingControlAuthority().control(properties);
    TrainLaunchManager manager = new TrainLaunchManager(new SpeedLimitRamp(), authority);

    manager.applyControl(
        train,
        properties,
        SignalAspect.STOP,
        0.0,
        config,
        false,
        OptionalLong.of(60L),
        Optional.empty(),
        runtimeSettings());

    DriverDirective directive = authority.lastDirective();
    assertEquals(OptionalLong.of(60L), directive.distanceBlocks());
    assertFalse(directive.allowLaunch());
  }

  @Test
  @DisplayName("不由驾驶员控制的车照常写限速，也不发布指令")
  void automaticTrainIsUnchanged() {
    TrainProperties properties = properties("auto-1");
    RuntimeTrainHandle train = train(properties, 10.0);
    RecordingControlAuthority authority = new RecordingControlAuthority();
    TrainLaunchManager manager = new TrainLaunchManager(new SpeedLimitRamp(), authority);

    manager.applyControl(
        train,
        properties,
        SignalAspect.PROCEED,
        10.0,
        config,
        false,
        OptionalLong.empty(),
        Optional.empty(),
        runtimeSettings());

    verify(properties).setSpeedLimit(anyDouble());
    assertTrue(authority.directives().isEmpty());
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
