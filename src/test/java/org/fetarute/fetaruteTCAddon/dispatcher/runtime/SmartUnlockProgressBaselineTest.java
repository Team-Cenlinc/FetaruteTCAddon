package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * unlock 预约的 lastPassed 基线判定回归。
 *
 * <p>硬授权请求的 {@code DirectedTraversalContext} 把 lastPassedGraphNode 建成 {@code Optional.empty()}，
 * 由它派生的 canonical evidence 里该字段因此是 {@code "-"}。历史实现直接拿它去和进度表里的真实节点比较， 结果恒为“不等”——实服 2.5 小时里 9004 次
 * unlock 预约释放 claim 0、{@code canonical-progress-window-moved} 回滚 4342 次，死锁恢复完全失效。
 *
 * <p>本用例固定的是判定本身：缺失的基线只能表示“无法判断”，不得表示“列车已移动”。
 */
class SmartUnlockProgressBaselineTest {

  private static boolean hasRecordedBaseline(String baseline) {
    try {
      Method method =
          Arrays.stream(RuntimeDispatchService.class.getDeclaredMethods())
              .filter(m -> m.getName().equals("hasRecordedLastPassedBaseline"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("找不到 hasRecordedLastPassedBaseline"));
      method.setAccessible(true);
      Object reservation = reservationWithBaseline(baseline);
      return (boolean) method.invoke(null, reservation);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  /** 构造一个只关心 initialLastPassedGraphNode 的最小预约实例。 */
  private static Object reservationWithBaseline(String baseline)
      throws ReflectiveOperationException {
    Class<?> type =
        Arrays.stream(RuntimeDispatchService.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("SmartUnlockReservation"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("找不到 SmartUnlockReservation"));
    var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object[] args = new Object[constructor.getParameterCount()];
    Class<?>[] paramTypes = constructor.getParameterTypes();
    for (int i = 0; i < args.length; i++) {
      args[i] = defaultValue(paramTypes[i]);
    }
    // initialLastPassedGraphNode 是该 record 中唯一被本用例关心的字段；按声明顺序定位。
    var components = type.getRecordComponents();
    for (int i = 0; i < components.length; i++) {
      if (components[i].getName().equals("initialLastPassedGraphNode")) {
        args[i] = baseline;
      }
    }
    return constructor.newInstance(args);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return type == String.class ? "-" : null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == long.class) {
      return -1L;
    }
    if (type == int.class) {
      return -1;
    }
    return 0;
  }

  @Test
  void missingBaselineIsNotTreatedAsRecorded() {
    // 这三种都表示“创建预约时根本没记录到 lastPassed”，不得当作移动证据。
    assertFalse(hasRecordedBaseline("-"), "\"-\" 是未记录的占位值");
    assertFalse(hasRecordedBaseline(""), "空串是未记录");
    assertFalse(hasRecordedBaseline("   "), "空白是未记录");
  }

  @Test
  void realBaselineIsTreatedAsRecorded() {
    assertTrue(hasRecordedBaseline("SURC:ZKW:HHU:1:003"), "真实节点是有效基线，必须继续参与判定");
  }
}
