package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  /**
   * 超时释放**不得依赖读取路径**——清扫必须独立生效。
   *
   * <p>第十六轮实服证明了这一点：SURC-WS-LN-7686 卡在 {@code DEPARTURE_GATE_HOLD} 超过 1026 秒 （23:14 之后到站 0 次），而
   * {@code SMART_DEPARTURE_GATE_EXPIRED} 全场为 0。jar 确实带着超时代码 （核对过 jar 常量池与运行时指纹 {@code
   * gitCommit=0b2b57f}），锁也只在 23:13:37 取过一次、 牌子此后再没触发。唯一自洽的解释是 {@code handleSignalTick} 对这辆车提前
   * return 了， 从没走到 {@code hasDepartureGate}。
   *
   * <p>把超时做成读触发，等于假设"卡住的车仍会被正常读取"——而卡住恰恰意味着某条路径不再执行。 本用例**只调清扫入口，一次都不调
   * hasDepartureGate**，因此能判别这个区别。
   */
  @Test
  void sweepExpiresGateWithoutAnyReadOnThatTrain() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(logs, now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    now.set(T0.plus(Duration.ofSeconds(181)));

    // 只走周期清扫（列车仍在活跃集合里，因此不会被"车已消失"那条分支顺带删掉）。
    service.cleanupOrphanOccupancyClaimsWithReport(java.util.Set.of("train-A"));

    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SMART_DEPARTURE_GATE_EXPIRED ")),
        () -> "清扫必须能独立释放，否则超时只在'车还正常'时才生效：" + logs);
    assertFalse(service.hasDepartureGate("train-A"), "清扫之后锁应当已经不在");
  }

  /** 清扫不得误伤仍在余量内的正常停站。 */
  @Test
  void sweepLeavesGatesThatAreStillWithinTheWindow() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(logs, now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    now.set(T0.plus(Duration.ofSeconds(60)));

    service.cleanupOrphanOccupancyClaimsWithReport(java.util.Set.of("train-A"));

    assertTrue(service.hasDepartureGate("train-A"), "正常停站时长内不得被清扫掉");
    assertTrue(
        logs.stream().noneMatch(line -> line.startsWith("SMART_DEPARTURE_GATE_EXPIRED ")),
        () -> logs.toString());
  }

  /**
   * 笔记本合盖休眠后，发车门锁不得集体过期——它并没有真的握了那么久。
   *
   * <p>第二十一轮实服：用户合盖七分钟，唤醒后 {@code SMART_DEPARTURE_GATE_EXPIRED} 从唤醒前的 0 变成 <b>9</b>。九把锁同时过期，不是因为握了
   * 180 秒，而是因为墙钟跳了。 合盖休眠是**常规操作**，不是偶发异常。
   *
   * <p>两个方向一起钉：冻结的那段不计数，但冻结**前**已握的时间不得被抄掉。 只钉前者的话，把锁整个重置也能蒙混过关。
   */
  @Test
  void freezeRebaseKeepsGatesButPreservesTimeHeldBeforeIt() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    List<String> logs = new ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(logs, now::get);

    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");
    // 冻结前已经真实握了 120 秒（未超过 180）。
    now.set(T0.plus(Duration.ofSeconds(120)));
    assertTrue(service.hasDepartureGate("train-A"), "前置：120 秒仍在余量内");

    // 合盖休眠 600 秒；调度层识别后调用重基。
    service.rebaseAfterFreeze(Duration.ofSeconds(600));
    now.set(T0.plus(Duration.ofSeconds(120 + 600)));
    assertTrue(service.hasDepartureGate("train-A"), "冻结的 600 秒不得计入持锁时长——否则每把锁都会在唤醒瞬间集体过期");
    assertTrue(
        logs.stream().anyMatch(l -> l.startsWith("SMART_RUNTIME_CLOCK_DISCONTINUITY ")),
        () -> "重基必须留痕：" + logs);

    // 冻结前那 120 秒必须还在：再过 61 秒（120+61=181 > 180）就该过期。
    now.set(T0.plus(Duration.ofSeconds(120 + 600 + 61)));
    assertFalse(service.hasDepartureGate("train-A"), "冻结前已握的 120 秒不得被抄掉");
  }

  /**
   * 时钟跳变检测必须在**读取触发的过期之前**生效，且同一次冻结只补偿一遍。
   *
   * <p>第二十二轮实服：补偿只接在健康监控的 tick 上，而发车门锁还会在 {@link RuntimeDispatchService#hasDepartureGate}
   * <b>读取时</b>过期——那条路径由另一个定时任务 （{@code RuntimeSignalMonitor}）驱动，而 Bukkit 不保证两个任务的顺序。实测：空洞
   * 16:46→16:50 之后，四把锁在 16:50:06–07 同时过期（heldSeconds 218/221/229/233， 恰好等于空洞时长）——健康监控的补偿根本没赶上。
   *
   * <p><b>用例必须全程按生产节奏推进。</b>我写错了两次：先是最后一段一下跳 61 秒， 后是前置一下跳 120 秒——两次都被当成冻结又补了一遍，于是门锁永远不过期。 生产里 tick
   * 是秒级的，不会出现连续大跳。
   */
  @Test
  void schedulerTickObservationCompensatesBeforeReadTriggeredExpiryAndOnlyOnce() {
    AtomicReference<Instant> now = new AtomicReference<>(T0);
    RuntimeDispatchService service = TestServices.minimal(new ArrayList<>(), now::get);

    service.observeSchedulerTick(T0);
    service.acquireDepartureGate("train-A", "session-1", "autostation_dwell");

    // 真实握锁 120 秒，按 10 秒一拍推进（低于 15 秒阈值，不会误判）。
    tickFor(service, now, 0, 120, 10);
    assertTrue(service.hasDepartureGate("train-A"), "前置：120 秒仍在余量内");

    // 合盖 600 秒。唤醒后的第一次 observeSchedulerTick 就该补偿。
    now.set(T0.plus(Duration.ofSeconds(720)));
    assertEquals(600L, service.observeSchedulerTick(now.get()).toSeconds(), "应当检测出 600 秒冻结");
    assertTrue(service.hasDepartureGate("train-A"), "读取触发的过期必须看到已补偿的时间，否则门锁会在唤醒瞬间集体过期");

    // 另一个任务紧接着也调一次：不得再补偿一遍。
    assertEquals(Duration.ZERO, service.observeSchedulerTick(now.get()), "同一次冻结不得被两个任务各补一遍");

    // 冻结前那 120 秒仍然算数：再跑 70 秒（120+70=190 > 180）就该过期。
    tickFor(service, now, 720, 70, 10);
    assertFalse(service.hasDepartureGate("train-A"), "冻结前已握的 120 秒不得被抄掉");
  }

  /** 按生产节奏推进时钟：每 {@code stepSeconds} 一拍，每拍都告诉调度器已经跳动。 */
  private static void tickFor(
      RuntimeDispatchService service,
      AtomicReference<Instant> now,
      long fromSeconds,
      long spanSeconds,
      long stepSeconds) {
    for (long elapsed = stepSeconds; elapsed <= spanSeconds; elapsed += stepSeconds) {
      now.set(T0.plus(Duration.ofSeconds(fromSeconds + elapsed)));
      service.observeSchedulerTick(now.get());
    }
  }
}
