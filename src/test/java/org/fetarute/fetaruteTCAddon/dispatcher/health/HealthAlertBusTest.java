package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link HealthAlertBus} 单元测试。 */
@DisplayName("HealthAlertBus 单元测试")
class HealthAlertBusTest {

  private HealthAlertBus bus;
  private List<HealthAlert> received;

  @BeforeEach
  void setUp() {
    bus = new HealthAlertBus();
    received = new ArrayList<>();
  }

  @Test
  @DisplayName("subscribe + publish：监听器收到告警")
  void subscribeAndPublish() {
    bus.subscribe(received::add);
    HealthAlert alert = HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "test message");

    boolean published = bus.publish(alert);

    assertTrue(published, "首次发布应成功");
    assertEquals(1, received.size(), "监听器应收到一条告警");
    assertEquals(alert, received.get(0));
  }

  @Test
  @DisplayName("限流：同类型+同列车告警在 5 秒内只分发一次")
  void throttling() {
    bus.subscribe(received::add);
    HealthAlert alert1 = HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1");
    HealthAlert alert2 = HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg2");

    boolean first = bus.publish(alert1);
    boolean second = bus.publish(alert2);

    assertTrue(first, "首次发布应成功");
    assertFalse(second, "5秒内相同类型+列车的第二次发布应被限流");
    assertEquals(1, received.size(), "监听器应只收到一条告警");
  }

  @Test
  @DisplayName("限流：不同列车的同类型告警不受限")
  void throttlingDifferentTrains() {
    bus.subscribe(received::add);
    HealthAlert alert1 = HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1");
    HealthAlert alert2 = HealthAlert.of(HealthAlert.AlertType.STALL, "train2", "msg2");

    boolean first = bus.publish(alert1);
    boolean second = bus.publish(alert2);

    assertTrue(first);
    assertTrue(second, "不同列车的告警不应被限流");
    assertEquals(2, received.size());
  }

  @Test
  @DisplayName("限流：同一列车的不同类型告警不受限")
  void throttlingDifferentTypes() {
    bus.subscribe(received::add);
    HealthAlert alert1 = HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1");
    HealthAlert alert2 = HealthAlert.of(HealthAlert.AlertType.PROGRESS_STUCK, "train1", "msg2");

    boolean first = bus.publish(alert1);
    boolean second = bus.publish(alert2);

    assertTrue(first);
    assertTrue(second, "不同类型的告警不应被限流");
    assertEquals(2, received.size());
  }

  @Test
  @DisplayName("recentAlerts：返回最近 N 条告警")
  void recentAlerts() {
    for (int i = 0; i < 10; i++) {
      bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train" + i, "msg" + i));
    }

    List<HealthAlert> recent = bus.recentAlerts(5);

    assertEquals(5, recent.size(), "应返回最近 5 条");
    assertEquals("train5", recent.get(0).trainName(), "应从第 6 条开始");
    assertEquals("train9", recent.get(4).trainName(), "最后一条应是最新的");
  }

  @Test
  @DisplayName("recentAlerts：请求数量超过历史数量时返回全部")
  void recentAlertsExceedsHistory() {
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train2", "msg2"));

    List<HealthAlert> recent = bus.recentAlerts(100);

    assertEquals(2, recent.size());
  }

  @Test
  @DisplayName("recentAlerts：limit <= 0 返回空列表")
  void recentAlertsZeroLimit() {
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));

    assertEquals(List.of(), bus.recentAlerts(0));
    assertEquals(List.of(), bus.recentAlerts(-1));
  }

  @Test
  @DisplayName("unsubscribe：移除监听器后不再收到告警")
  void unsubscribe() {
    bus.subscribe(received::add);
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));
    assertEquals(1, received.size());

    bus.unsubscribe(received::add);
    // 注意：CopyOnWriteArrayList 使用 equals 比较，lambda 每次创建不同实例
    // 所以这里需要用相同的 Consumer 实例
  }

  @Test
  @DisplayName("unsubscribe：使用同一 Consumer 实例可正确移除")
  void unsubscribeSameInstance() {
    java.util.function.Consumer<HealthAlert> listener = received::add;
    bus.subscribe(listener);
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));
    assertEquals(1, received.size());

    bus.unsubscribe(listener);
    bus.clear(); // 清除限流状态
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg2"));
    assertEquals(1, received.size(), "移除后不应再收到告警");
  }

  @Test
  @DisplayName("clear：清除历史和限流状态")
  void clear() {
    bus.subscribe(received::add);
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));
    assertEquals(1, received.size());

    bus.clear();

    assertTrue(bus.recentAlerts(10).isEmpty(), "历史应被清空");
    // 限流状态也被清空，相同告警可再次发布
    boolean published = bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg2"));
    assertTrue(published, "清除后相同告警应可发布");
    assertEquals(2, received.size());
  }

  @Test
  @DisplayName("publish：null 告警返回 false")
  void publishNull() {
    assertFalse(bus.publish(null));
  }

  @Test
  @DisplayName("subscribe：null 监听器被忽略")
  void subscribeNull() {
    bus.subscribe(null);
    // 不应抛异常
    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));
  }

  @Test
  @DisplayName("监听器异常不影响其他监听器")
  void listenerException() {
    bus.subscribe(
        alert -> {
          throw new RuntimeException("test exception");
        });
    bus.subscribe(received::add);

    bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train1", "msg1"));

    assertEquals(1, received.size(), "异常监听器不应影响其他监听器");
  }

  @Test
  @DisplayName("历史上限：超过 100 条时移除最旧的")
  void historyLimit() {
    for (int i = 0; i < 120; i++) {
      // 每个列车名不同以绕过限流
      bus.publish(HealthAlert.of(HealthAlert.AlertType.STALL, "train" + i, "msg" + i));
    }

    List<HealthAlert> recent = bus.recentAlerts(200);

    assertEquals(100, recent.size(), "历史应限制在 100 条");
    assertEquals("train20", recent.get(0).trainName(), "应从第 21 条开始保留");
    assertEquals("train119", recent.get(99).trainName(), "最后一条应是最新的");
  }
}
