package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 出车编组的车长：与运行时量在线列车同一公式，各节中心跨度按直道出车间距算。 */
class SpawnPatternLengthTest {

  /** 三节 10 格、车钩各 0.25：中心跨度 2 × (5 + 0.5 + 5) = 21，两端各补半车长加一格（12），两个连接处各 0.25 → 33.5，取整 34。 */
  @Test
  void aThreeCarTrainIsMeasuredLikeTheRuntimeDoes() {
    assertEquals(
        OptionalLong.of(34L),
        SpawnPatternLength.of(List.of(10.0, 10.0, 10.0), List.of(0.25, 0.25, 0.25)));
  }

  /** 一节原版矿车（1 格）：两端各补 1.5 → 3。 */
  @Test
  void aSingleCartGetsTheEndPadding() {
    assertEquals(OptionalLong.of(3L), SpawnPatternLength.of(List.of(1.0), List.of(0.25)));
  }

  @Test
  void mismatchedOrEmptyInputsAreUnknown() {
    assertEquals(OptionalLong.empty(), SpawnPatternLength.of(List.of(), List.of()));
    assertEquals(OptionalLong.empty(), SpawnPatternLength.of(List.of(10.0, 10.0), List.of(0.25)));
  }
}
