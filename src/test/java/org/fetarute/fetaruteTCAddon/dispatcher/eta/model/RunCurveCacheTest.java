package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 运行曲线按输入复用结果：命中与重算一致，调用方改数组不影响后续结果。 */
class RunCurveCacheTest {

  private static final SpeedCurve CURVE = new SpeedCurve(1.0, 1.2);

  @Test
  void repeatedInputsReturnSameTimesAndCallerCannotPoisonCache() {
    double[] lengths = {300, 200};
    double[] speeds = {12.0, 8.0};
    List<SpeedCeiling.Cap> caps = List.of(new SpeedCeiling.Cap(150, 200, 5.0));

    double[] first = RunCurve.nodeTimes(lengths, speeds, caps, 0.0, 0.0, CURVE);
    double[] expected = first.clone();
    first[1] = -1.0;
    lengths[0] = 10;

    double[] second = RunCurve.nodeTimes(new double[] {300, 200}, speeds, caps, 0.0, 0.0, CURVE);

    assertArrayEquals(expected, second, 0.0);
  }

  @Test
  void differentEntrySpeedIsNotServedFromCache() {
    double[] lengths = {300, 200};
    double[] speeds = {12.0, 8.0};

    double[] fromRest = RunCurve.nodeTimes(lengths, speeds, List.of(), 0.0, 0.0, CURVE);
    double[] moving = RunCurve.nodeTimes(lengths, speeds, List.of(), 12.0, 0.0, CURVE);

    assertNotEquals(fromRest[2], moving[2]);
  }
}
