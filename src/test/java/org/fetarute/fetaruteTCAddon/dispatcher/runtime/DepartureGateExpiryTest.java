package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 发车许可锁的超时兜底。
 *
 * <p>这把锁只有一个获取方（AutoStation 的 {@code autostation_dwell}），释放写在 {@code AutoStationSignAction}
 * 的三个分支里；三个都没走到，锁就是永久的，而此前 {@code departureGates} 对**活着的**列车没有任何过期机制。
 *
 * <p>实服第十五轮：SURC-WS-LN-3176 于 21:11:52 取锁，门在 21:12:07 就正常关闭，锁却没还。 车停在 TPC 二站台挡住 {@code
 * NODE:SURC:SLL:TPC:2:004}，被挡快照 23 条，自己 28.9 分钟到站 0 次。 同期 WS 线产出 −57%，而 MT/DS 只掉
 * 14%/5%——全网看到的"崩溃"其实是一辆车掐住一条线。 库里最长配置停站 30 秒，而这把锁活了 1735 秒。
 */
class DepartureGateExpiryTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * 判别核心：**同一把锁，在余量之内与超时之后必须得到相反的结果**。
   *
   * <p>只断言"会超时"是不够的——那证明不了它不会截断正常停站，而正常停站被截断就是让车带着 未关的门开走。两个方向都要钉住。
   */
  @Test
  void gateSurvivesNormalDwellButExpiresWhenItOutlivesAnyPlausibleOne() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(logs, now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    assertTrue(service.hasDepartureGate("train-A"), "刚取到锁就该在");

    // 一：库里最长配置停站 30 秒；到 60 秒仍远在余量内，绝不许被截断。
    now.set(T0.plus(Duration.ofSeconds(60)));
    assertTrue(service.hasDepartureGate("train-A"), "正常停站时长内不得超时释放");

    // 二：超过 180 秒（最长停站的 6 倍）⇒ 这把锁已经烂在手里，必须还回去。
    now.set(T0.plus(Duration.ofSeconds(181)));
    assertFalse(service.hasDepartureGate("train-A"), "远超任何正常停站后必须释放");
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SMART_DEPARTURE_GATE_EXPIRED ")),
        () -> "超时释放必须留痕，否则又是一次无从归因：" + logs);
    assertTrue(
        logs.stream().anyMatch(line -> line.contains("heldSeconds=181")), () -> logs.toString());
  }

  /** 超时释放后再取锁，应重新从零计时，而不是沿用旧会话的时间。 */
  @Test
  void reacquiringAfterExpiryStartsAFreshWindow() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>(), now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    now.set(T0.plus(Duration.ofSeconds(181)));
    assertFalse(service.hasDepartureGate("train-A"));

    service.acquireDepartureGate("train-A", "session-2", "autostation_dwell");
    assertTrue(service.hasDepartureGate("train-A"), "新会话应重新计时");
    now.set(T0.plus(Duration.ofSeconds(181 + 60)));
    assertTrue(service.hasDepartureGate("train-A"), "新会话的 60 秒仍在余量内");
  }

  /** 正常释放路径不受影响：会话匹配即释放，且不应留下超时痕迹。 */
  @Test
  void normalReleaseStillWorksAndIsNotReportedAsExpiry() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(logs, now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    now.set(T0.plus(Duration.ofSeconds(20)));
    assertTrue(service.releaseDepartureGate("train-A", "session-1"), "会话匹配应释放成功");
    assertFalse(service.hasDepartureGate("train-A"));
    assertTrue(
        logs.stream().noneMatch(line -> line.startsWith("SMART_DEPARTURE_GATE_EXPIRED ")),
        () -> "正常释放不该被记成超时：" + logs);
  }
}
