package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.junit.jupiter.api.Test;

class TrainPropertyGuardTest {

  @Test
  void applyDisablesSlowdownRaisesTheLimitAndRemembersTheOriginals() {
    FakeTrain train = new FakeTrain(true, 0.4);

    TrainPropertyGuard.apply(train.properties, 22.0);

    assertFalse(train.slowingDown);
    assertEquals(22.0 / 20.0, train.speedLimit, 1e-12);
    assertEquals(Optional.of("true"), tag(train, TrainPropertyGuard.TAG_SLOWDOWN));
    assertEquals(Optional.of("0.4"), tag(train, TrainPropertyGuard.TAG_SPEED_LIMIT));
  }

  @Test
  void applyDoesNotLowerAnAlreadyHigherLimit() {
    FakeTrain train = new FakeTrain(false, 2.0);

    TrainPropertyGuard.apply(train.properties, 22.0);

    assertEquals(2.0, train.speedLimit, 0.0);
  }

  @Test
  void restoreBringsBackTheOriginalsAndClearsTheTags() {
    FakeTrain train = new FakeTrain(true, 0.4);
    TrainPropertyGuard.apply(train.properties, 22.0);

    TrainPropertyGuard.restore(train.properties);

    assertTrue(train.slowingDown);
    assertEquals(0.4, train.speedLimit, 1e-12);
    assertTrue(tag(train, TrainPropertyGuard.TAG_SLOWDOWN).isEmpty());
    assertTrue(tag(train, TrainPropertyGuard.TAG_SPEED_LIMIT).isEmpty());
  }

  @Test
  void restoreLeavesTheLimitTheLineGaveDuringTheSession() {
    FakeTrain train = new FakeTrain(true, 0.4);
    TrainPropertyGuard.apply(train.properties, 22.0);

    TrainPropertyGuard.restore(train.properties, OptionalDouble.of(0.25));

    assertEquals(0.25, train.speedLimit, 1e-12);
    assertTrue(tag(train, TrainPropertyGuard.TAG_SPEED_LIMIT).isEmpty());
  }

  @Test
  void aSecondApplyKeepsTheEarlierOriginalsSoACrashedSessionCannotPoisonThem() {
    FakeTrain train = new FakeTrain(true, 0.4);
    TrainPropertyGuard.apply(train.properties, 22.0);
    // 会话中途崩溃：标签随列车保存，属性停在调整后的值。再次驾驶时不能把调整后的值当作原值。
    TrainPropertyGuard.apply(train.properties, 25.0);

    assertEquals(Optional.of("true"), tag(train, TrainPropertyGuard.TAG_SLOWDOWN));
    assertEquals(Optional.of("0.4"), tag(train, TrainPropertyGuard.TAG_SPEED_LIMIT));

    TrainPropertyGuard.restore(train.properties);

    assertTrue(train.slowingDown);
    assertEquals(0.4, train.speedLimit, 1e-12);
  }

  @Test
  void restoreWithoutEverApplyingChangesNothing() {
    FakeTrain train = new FakeTrain(true, 0.4);

    TrainPropertyGuard.restore(train.properties);

    assertTrue(train.slowingDown);
    assertEquals(0.4, train.speedLimit, 0.0);
  }

  private static Optional<String> tag(FakeTrain train, String key) {
    return TrainTagHelper.readTagValue(train.properties, key);
  }

  private static final class FakeTrain {
    final TrainProperties properties = mock(TrainProperties.class);
    final List<String> tags = new ArrayList<>();
    boolean slowingDown;
    double speedLimit;

    FakeTrain(boolean slowingDown, double speedLimit) {
      this.slowingDown = slowingDown;
      this.speedLimit = speedLimit;
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      doAnswer(
              inv -> {
                for (Object arg : inv.getArguments()) {
                  if (arg instanceof String[] values) {
                    tags.addAll(List.of(values));
                  } else if (arg instanceof String value) {
                    tags.add(value);
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      doAnswer(
              inv -> {
                for (Object arg : inv.getArguments()) {
                  if (arg instanceof String[] values) {
                    tags.removeAll(List.of(values));
                  } else if (arg instanceof String value) {
                    tags.remove(value);
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
      when(properties.isSlowingDown()).thenAnswer(inv -> this.slowingDown);
      doAnswer(
              inv -> {
                this.slowingDown = inv.getArgument(0);
                return null;
              })
          .when(properties)
          .setSlowingDown(anyBoolean());
      when(properties.getSpeedLimit()).thenAnswer(inv -> this.speedLimit);
      doAnswer(
              inv -> {
                this.speedLimit = inv.getArgument(0);
                return null;
              })
          .when(properties)
          .setSpeedLimit(anyDouble());
    }
  }
}
