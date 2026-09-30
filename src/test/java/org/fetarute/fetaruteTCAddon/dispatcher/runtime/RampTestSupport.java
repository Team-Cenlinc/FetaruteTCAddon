package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.block.BlockFace;

/** 限速斜坡测试共用的替身：会记住 speedLimit 与 tags 的 TrainProperties，以及可调速度的运行中列车。 */
final class RampTestSupport {

  private RampTestSupport() {}

  static SpeedLimitStore speedLimitStore(double initialBpt) {
    return new SpeedLimitStore(initialBpt);
  }

  /** 记住 speedLimit 与 tags 的属性替身。 */
  static final class SpeedLimitStore {
    private final TrainProperties properties = mock(TrainProperties.class);
    private final List<String> tags = new ArrayList<>();
    private double speedLimitBpt;
    private int writes;

    private SpeedLimitStore(double initialBpt) {
      this.speedLimitBpt = initialBpt;
      lenient().when(properties.getTrainName()).thenReturn("ramp-test");
      lenient().when(properties.getSpeedLimit()).thenAnswer(inv -> speedLimitBpt);
      lenient()
          .doAnswer(
              inv -> {
                speedLimitBpt = Math.max(0.0, inv.getArgument(0, Double.class));
                writes++;
                return null;
              })
          .when(properties)
          .setSpeedLimit(anyDouble());
      lenient().when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      lenient().when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      lenient()
          .doAnswer(
              inv -> {
                for (Object arg : inv.getArguments()) {
                  if (arg instanceof String s && !s.isBlank()) {
                    tags.add(s);
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      lenient()
          .doAnswer(
              inv -> {
                for (Object arg : inv.getArguments()) {
                  if (arg instanceof String s) {
                    tags.remove(s);
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    TrainProperties properties() {
      return properties;
    }

    int writes() {
      return writes;
    }
  }

  /** 速度与运行状态可由测试直接改的列车句柄。 */
  static class MovingTrain implements RuntimeTrainHandle {
    private final TrainProperties properties;
    double speedBpt;
    boolean moving = true;
    int accelerateCalls;
    double lastAccelerateTargetBpt = Double.NaN;

    /** 身上是否挂着别的 TrainCarts 动作；默认与接口一致（报告不了按“有”处理）。 */
    boolean foreignAction = true;

    MovingTrain(TrainProperties properties, double speedBpt) {
      this.properties = properties;
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
    public void stop() {
      speedBpt = 0.0;
      moving = false;
    }

    @Override
    public void launch(double targetBlocksPerTick, double accelBlocksPerTickSquared) {}

    @Override
    public void accelerateTo(double targetBlocksPerTick, double accelBlocksPerTickSquared) {
      accelerateCalls++;
      lastAccelerateTargetBpt = targetBlocksPerTick;
    }

    @Override
    public boolean hasForeignAction() {
      return foreignAction;
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
    public Optional<BlockFace> forwardDirection() {
      return Optional.empty();
    }

    @Override
    public void reverse() {}
  }
}
