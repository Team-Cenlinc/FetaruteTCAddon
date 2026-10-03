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
}
