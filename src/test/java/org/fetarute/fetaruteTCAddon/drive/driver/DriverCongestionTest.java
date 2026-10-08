package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("挡住后车的提醒")
class DriverCongestionTest {

  private static final DriverRecovery RECOVERY = DriverRecovery.defaults();

  @Test
  @DisplayName("只算被驾驶员列车直接挡住的车，从它挡上那一刻起算，不按后车整段被扣的时长")
  void timedFromWhenTheDriverStartedBlocking() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    Map<String, CongestionProtection.Hold> behindOthers =
        Map.of("B", new CongestionProtection.Hold(Duration.ofSeconds(100), Set.of("X")));
    Map<DriverCongestion.Blocking, Instant> since =
        DriverCongestion.trackBlocking(behindOthers, Set.of("D"), Map.of(), t0);
    assertEquals(0L, DriverCongestion.blockedBehindSeconds(since, "D", t0));

    // B 先被 X 扣了 100 秒，此刻转到驾驶员列车 D 后面；B 的扣车时刻不因换阻挡者重置
    Map<String, CongestionProtection.Hold> behindDriver =
        Map.of(
            "B", new CongestionProtection.Hold(Duration.ofSeconds(110), Set.of("D")),
            "C", new CongestionProtection.Hold(Duration.ofSeconds(200), Set.of("B")),
            "E", new CongestionProtection.Hold(Duration.ofSeconds(140), Set.of("D", "X")),
            "D", new CongestionProtection.Hold(Duration.ofSeconds(500), Set.of("D")));
    since = DriverCongestion.trackBlocking(behindDriver, Set.of("D"), since, t0.plusSeconds(10));
    assertEquals(0L, DriverCongestion.blockedBehindSeconds(since, "D", t0.plusSeconds(10)), "刚挡上");
    since = DriverCongestion.trackBlocking(behindDriver, Set.of("D"), since, t0.plusSeconds(40));
    assertEquals(30L, DriverCongestion.blockedBehindSeconds(since, "D", t0.plusSeconds(40)));
    assertEquals(0L, DriverCongestion.blockedBehindSeconds(since, "Z", t0.plusSeconds(40)));
    assertEquals(0L, DriverCongestion.blockedBehindSeconds(since, null, t0.plusSeconds(40)));

    // 驾驶员列车停站、待命期间不计时：重新挡上从头算
    since = DriverCongestion.trackBlocking(behindDriver, Set.of(), since, t0.plusSeconds(50));
    since = DriverCongestion.trackBlocking(behindDriver, Set.of("D"), since, t0.plusSeconds(60));
    assertEquals(0L, DriverCongestion.blockedBehindSeconds(since, "D", t0.plusSeconds(60)));
  }

  @Test
  @DisplayName("按时长分档")
  void stages() {
    assertEquals(DriverCongestion.Stage.NONE, DriverCongestion.stage(59, RECOVERY));
    assertEquals(DriverCongestion.Stage.WARN, DriverCongestion.stage(60, RECOVERY));
    assertEquals(DriverCongestion.Stage.ATO, DriverCongestion.stage(120, RECOVERY));
  }

  @Test
  @DisplayName("链路上每档只报一次；不再挡住时清零；转 ATO 推迟后下次再报")
  void linkReportsEachStageOnce() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "D", null, () -> 0.0, () -> 0L);

    assertEquals(DriverCongestion.Stage.WARN, link.updateBlocking(65, RECOVERY));
    assertEquals(65L, link.warnedBlockingSeconds());
    assertEquals(DriverCongestion.Stage.NONE, link.updateBlocking(90, RECOVERY));
    assertEquals(DriverCongestion.Stage.ATO, link.updateBlocking(125, RECOVERY));
    link.deferCongestionStage();
    assertEquals(DriverCongestion.Stage.ATO, link.updateBlocking(130, RECOVERY));
    assertEquals(DriverCongestion.Stage.NONE, link.updateBlocking(0, RECOVERY));
    assertEquals(0L, link.warnedBlockingSeconds());
    assertEquals(DriverCongestion.Stage.WARN, link.updateBlocking(61, RECOVERY));
  }

  @Test
  @DisplayName("配置：告警线须小于强制线，否则回退默认值")
  void config() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString("congestion-warn-seconds: 30\ncongestion-ato-seconds: 90");
    DriverRecovery parsed = DriverRecovery.from(yaml, message -> {});
    assertEquals(30, parsed.congestionWarnSeconds());
    assertEquals(90, parsed.congestionAtoSeconds());

    yaml.loadFromString("congestion-warn-seconds: 100\ncongestion-ato-seconds: 90");
    List<String> warnings = new ArrayList<>();
    DriverRecovery fallback = DriverRecovery.from(yaml, warnings::add);
    assertEquals(RECOVERY.congestionWarnSeconds(), fallback.congestionWarnSeconds());
    assertEquals(RECOVERY.congestionAtoSeconds(), fallback.congestionAtoSeconds());
    assertEquals(1, warnings.size());
  }
}
