package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverInterrupt;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.RecordingControlAuthority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 驾驶员控制的列车：绕过 applyControl 的停车与限速通道改为通知驾驶侧。 */
@DisplayName("RuntimeTrainController 驾驶员控制分流")
class RuntimeTrainControllerDriverGateTest {

  private final TrainProperties properties = mock(TrainProperties.class);
  private final RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
  private final RecordingControlAuthority authority =
      new RecordingControlAuthority().control(properties);
  private final RuntimeTrainController controller =
      new RuntimeTrainController(new TrainLaunchManager(new SpeedLimitRamp(), authority));

  RuntimeTrainControllerDriverGateTest() {
    when(properties.getTrainName()).thenReturn("drv");
    when(properties.getTags()).thenReturn(List.of());
    when(train.properties()).thenReturn(properties);
  }

  @Test
  @DisplayName("stopNow 改为常用制动停车请求")
  void stopNowBecomesServiceStop() {
    controller.stopNow(train);

    verify(train, never()).stop();
    assertEquals(List.of(DriverInterrupt.SERVICE_STOP), authority.interrupts());
  }

  @Test
  @DisplayName("stopHard 改为紧急制动请求，不写限速、不清动作队列")
  void stopHardBecomesEmergency() {
    controller.stopHard(train, properties);

    verify(properties, never()).setSpeedLimit(anyDouble());
    verify(train, never()).stopHard();
    assertEquals(List.of(DriverInterrupt.EMERGENCY), authority.interrupts());
  }

  @Test
  @DisplayName("临时限速与强制重发车都跳过")
  void temporaryLimitAndRelaunchAreSkipped() {
    RuntimeTrainHandle relaunched = mock(RuntimeTrainHandle.class);

    controller.setTemporarySpeedLimit(properties, 0.1);
    controller.forceRelaunch(
        relaunched, properties, BlockFace.NORTH, 10.0, new TrainConfig(TrainType.EMU, 1.0, 1.0));

    verify(properties, never()).setSpeedLimit(anyDouble());
    verifyNoInteractions(relaunched);
  }
}
