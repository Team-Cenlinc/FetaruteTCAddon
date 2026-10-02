package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchTestFixtures.testConfigView;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/**
 * 跟驰信号随到前车距离的三段契约。
 *
 * <p>此前最远那一段无条件返回 {@code PROCEED_WITH_CAUTION}：只要前向扫描（最多 12 条边，按实服边长可达数百 blocks） 里出现任何一辆车，后车就被钉在
 * caution 速度上。实服里 {@code caution-speed-bps} 为 10，而线路边限速多为 16.7 / 22.2 bps，等于对每个后车掉速一半以上，且这占了已发布信号的
 * 45%。
 *
 * <p>本用例把三段都钉住：近距离仍必须降级，远距离不得施加额外限制。改动只涉及最远那一段， STOP 与 CAUTION 的距离阈值未动。
 */
class ForwardTrainDistanceSignalTest {

  private static SignalAspect aspectFor(long distanceBlocks, double speedBps) {
    try {
      Method method =
          RuntimeDispatchService.class.getDeclaredMethod(
              "distanceToForwardTrainSignal",
              long.class,
              double.class,
              double.class,
              ConfigManager.RuntimeSettings.class,
              SignalAspect.class);
      method.setAccessible(true);
      return (SignalAspect)
          method.invoke(
              null,
              distanceBlocks,
              speedBps,
              1.0,
              testConfigView(20, 20.0).runtimeSettings(),
              SignalAspect.PROCEED);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  @Test
  void veryCloseForwardTrainStopsTheFollower() {
    // 制动距离 = 10²/(2*1) = 50；stopThreshold = 50 + followingStopMargin。
    assertEquals(SignalAspect.STOP, aspectFor(10L, 10.0));
  }

  @Test
  void forwardTrainInsideCautionThresholdStillDowngrades() {
    // 落在 stop 与 caution 阈值之间：降级必须照常发生，这条路径不能因为放宽最远段而丢失。
    assertEquals(SignalAspect.CAUTION, aspectFor(58L, 10.0));
  }

  @Test
  void farForwardTrainImposesNoRestriction() {
    // 远超 caution 阈值：前车不应再施加任何限制，否则后车会以 caution 速度长时间爬行。
    assertEquals(SignalAspect.PROCEED, aspectFor(400L, 10.0));
  }

  @Test
  void stationaryFollowerWithDistantTrainAlsoProceeds() {
    // 静止列车制动距离为 0，阈值只剩配置余量；远处前车同样不得限制它起步。
    assertEquals(SignalAspect.PROCEED, aspectFor(200L, 0.0));
  }
}
