package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.bukkit.block.BlockFace;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeTrainControllerTest {

  @Test
  void stopSignalAppliesStopControl() {
    RuntimeTrainController controller = new RuntimeTrainController();
    TagStore tags =
        new TagStore(
            "train-stop",
            "FTA_LAST_SPEED_CMD_BPS=15.0",
            "FTA_LAST_SPEED_CMD_AT=" + System.currentTimeMillis());
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    when(train.currentSpeedBlocksPerTick()).thenReturn(15.0 / 20.0);
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);

    controller.applyControl(
        train,
        tags.properties(),
        SignalAspect.STOP,
        0.0,
        config,
        false,
        OptionalLong.of(0L),
        Optional.empty(),
        runtimeSettings());

    ArgumentCaptor<Double> speedCaptor = ArgumentCaptor.forClass(Double.class);
    verify(tags.properties()).setSpeedLimit(speedCaptor.capture());
    assertEquals(0.0, speedCaptor.getValue(), 1.0e-6);
    verify(train).stop();
  }

  @Test
  void forceRelaunchAppliesSpeedLimitAndDirection() {
    RuntimeTrainController controller = new RuntimeTrainController();
    TrainProperties properties = mock(TrainProperties.class);
    RuntimeTrainHandle train = mock(RuntimeTrainHandle.class);
    TrainConfig config = new TrainConfig(TrainType.EMU, 2.0, 1.0);

    controller.forceRelaunch(train, properties, BlockFace.NORTH, 10.0, config);

    verify(properties).setSpeedLimit(10.0 / 20.0);
    verify(train).forceRelaunch(BlockFace.NORTH, 10.0 / 20.0, 2.0 / 400.0);
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
}
