package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("卡住的时间阶梯")
class DriverRescueLadderTest {

  @Test
  @DisplayName("各档边界")
  void stages() {
    DriverRecovery r = DriverRecovery.defaults();
    assertEquals(DriverRescueLadder.Stage.NONE, DriverRescueLadder.stage(59, r));
    assertEquals(DriverRescueLadder.Stage.WARN, DriverRescueLadder.stage(60, r));
    assertEquals(DriverRescueLadder.Stage.ATO, DriverRescueLadder.stage(120, r));
    assertEquals(DriverRescueLadder.Stage.HANDBACK, DriverRescueLadder.stage(180, r));
    assertEquals(DriverRescueLadder.Stage.RESCUE, DriverRescueLadder.stage(300, r));
  }

  @Test
  @DisplayName("配置：阶梯不递增时整组回退默认值")
  void ladderMustIncrease() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("rescue-warn-seconds: 10\nrescue-ato-seconds: 5\nmax-task-minutes: 30");
    List<String> warnings = new ArrayList<>();
    DriverRecovery r = DriverRecovery.from(yaml, warnings::add);
    assertEquals(60, r.warnSeconds());
    assertEquals(120, r.atoSeconds());
    assertEquals(30, r.maxTaskMinutes());
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("卡住计时：走过两格清零，被扣住时不累计")
  void stuckCounter() {
    double[] odometer = {0.0};
    long[] clock = {0L};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T", null, () -> odometer[0], () -> clock[0]);
    for (int i = 0; i < 400; i++) {
      link.tickStuck(false);
    }
    assertEquals(20, link.stuckSeconds());
    for (int i = 0; i < 400; i++) {
      link.tickStuck(true);
    }
    assertEquals(20, link.stuckSeconds(), "表定停站、调度扣车不算");
    assertEquals(20, link.heldSeconds(), "被扣住的时间另计（太久就交还给自动运行）");
    link.setLadderStage(DriverRescueLadder.Stage.WARN);
    odometer[0] = 2.5;
    link.tickStuck(false);
    assertEquals(0, link.stuckSeconds());
    assertEquals(DriverRescueLadder.Stage.NONE, link.ladderStage());
  }

  @Test
  @DisplayName("ATO 发车确认：确认后放行，超时也放行并记一次")
  void atoDepartureConfirmation() {
    long[] clock = {1000L};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> clock[0]);
    assertEquals(false, link.holdDeparture(300), "人工驾驶不扣");
    link.setMode(DrivingMode.ATO);
    assertEquals(true, link.holdDeparture(300));
    assertEquals(true, link.departurePending());
    clock[0] += 20;
    assertEquals(true, link.confirmDeparture());
    assertEquals(false, link.holdDeparture(300));
    assertEquals(false, link.departurePending());

    clock[0] += 1000;
    assertEquals(true, link.holdDeparture(300), "新的一次停站重新等确认");
    int checks = 0;
    do {
      clock[0] += 20;
      checks++;
    } while (link.holdDeparture(300) && checks < 100);
    assertEquals(15, checks, "等满 300 tick 自动放行");
    assertEquals(1, link.lateDepartures());
  }

  @Test
  @DisplayName("ATO 确认发车的时限只算出站放行着的时间：门控关上的那几秒不计，关多久都一样")
  void atoDepartureTimeoutCountsOnlyOpenTime() {
    long[] clock = {1000L};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> clock[0]);
    link.setMode(DrivingMode.ATO);
    // 放行 200 tick（10 次询问）
    assertEquals(true, link.holdDeparture(300));
    for (int i = 0; i < 10; i++) {
      clock[0] += 20;
      assertEquals(true, link.holdDeparture(300));
    }
    // 门控关上 2 秒（站台不问），再放行
    clock[0] += 40;
    assertEquals(true, link.holdDeparture(300), "关上的 2 秒不计");
    // 门控关上 1 分钟，再放行
    clock[0] += 1200;
    assertEquals(true, link.holdDeparture(300), "关多久都不计，也不当作新的一次停站");
    for (int i = 0; i < 4; i++) {
      clock[0] += 20;
      assertEquals(true, link.holdDeparture(300));
    }
    clock[0] += 20;
    assertEquals(false, link.holdDeparture(300), "放行着累计满 300 tick 才放行");
    assertEquals(1, link.lateDepartures());
  }

  @Test
  @DisplayName("ATO 已给的确认在出站门控关上期间一直有效：再放行时一问就走，不记迟确认")
  void atoConfirmationSurvivesAClosedGate() {
    long[] clock = {1000L};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> clock[0]);
    link.setMode(DrivingMode.ATO);
    assertEquals(true, link.holdDeparture(300));
    clock[0] += 10;
    assertEquals(true, link.confirmDeparture());
    // 确认后门控关上 10 秒：站台不问
    clock[0] += 200;
    assertEquals(true, link.departureConfirmed(), "门控关着也显示已确认");
    assertEquals(false, link.departurePrompt(), "不再提示确认");
    assertEquals(false, link.holdDeparture(300), "再放行即发车");
    assertEquals(0, link.lateDepartures());
  }

  @Test
  @DisplayName("ATO 门控关上超过提示时间后确认的：同样记着，再放行一问就走")
  void atoConfirmationGivenWhileTheGateIsClosed() {
    long[] clock = {1000L};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> clock[0]);
    link.setMode(DrivingMode.ATO);
    assertEquals(true, link.holdDeparture(300));
    clock[0] += 200;
    link.openDepartureArm();
    assertEquals(true, link.departurePrompt());
    assertEquals(true, link.confirmDeparture());
    clock[0] += 200;
    assertEquals(false, link.holdDeparture(300));
    assertEquals(0, link.lateDepartures());
  }

  @Test
  @DisplayName("站台开始新的一次停站：上一站没放行就结束的计时与确认作废（原地折返、停站被接手）")
  void aNewStationStopStartsAFreshHold() {
    long[] clock = {1000L};
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> clock[0]);
    link.setMode(DrivingMode.ATO);
    assertEquals(true, link.holdDeparture(300));
    for (int i = 0; i < 14; i++) {
      clock[0] += 20;
      assertEquals(true, link.holdDeparture(300));
    }
    assertEquals(true, link.confirmDeparture());

    link.stationStopStarted();
    clock[0] += 20;
    assertEquals(true, link.holdDeparture(300), "旧的确认作废");
    clock[0] += 20;
    assertEquals(true, link.holdDeparture(300), "旧的 280 tick 不接着算");
    assertEquals(0, link.lateDepartures());
  }

  @Test
  @DisplayName("ATO 提前确认：停站将尽时确认，停站一结束站台一问就放行；列车动过即作废")
  void atoDepartureConfirmedInAdvance() {
    long[] clock = {1000L};
    double[] odometer = {0.0};
    DriverLink link =
        new DriverLink(UUID.randomUUID(), "T", null, () -> odometer[0], () -> clock[0]);
    link.setMode(DrivingMode.ATO);
    assertEquals(false, link.departurePrompt(), "停站将尽之前不提示");
    assertEquals(false, link.confirmDeparture());

    link.openDepartureArm();
    assertEquals(true, link.departurePrompt());
    assertEquals(true, link.confirmDeparture());
    assertEquals(false, link.departurePrompt());
    assertEquals(true, link.departureConfirmed());
    assertEquals(false, link.confirmDeparture(), "已确认的不再算");

    clock[0] += 200;
    assertEquals(false, link.holdDeparture(300), "停站一结束就放行");
    assertEquals(0, link.lateDepartures());
    link.openDepartureArm();
    assertEquals(false, link.departurePrompt(), "放行后原地不再提示");

    clock[0] += 400;
    link.openDepartureArm();
    assertEquals(true, link.departurePrompt(), "终点原地折返：过一阵原地再停站照常接受提前确认");
    assertEquals(true, link.confirmDeparture());
    clock[0] += 200;
    assertEquals(false, link.holdDeparture(300), "原地折返的下一趟也一问就放行");

    odometer[0] = 500.0;
    clock[0] += 2000;
    link.openDepartureArm();
    assertEquals(true, link.confirmDeparture());
    odometer[0] = 503.0;
    assertEquals(false, link.departureConfirmed(), "确认后列车动过：作废");
    clock[0] += 200;
    assertEquals(true, link.holdDeparture(300), "下一站照常等确认");
  }
}
