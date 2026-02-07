package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
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
    TrainConfig config = new TrainConfig(TrainType.EMU, 1.0, 1.0);
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

  private static ConfigManager.RuntimeSettings runtimeSettings(
      double hysteresisBps, double accelFactor, double decelFactor) {
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

  private static final class FakeTrain implements RuntimeTrainHandle {
    private final TrainProperties properties;
    private final boolean moving;
    private final double speedBpt;

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
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {}

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
