package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶证配置")
class LicenseConfigTest {

  @Test
  @DisplayName("默认两级：教程考见习驾驶证，路考正式驾驶证且要先有见习驾驶证")
  void defaults() {
    LicenseConfig config = LicenseConfig.defaults();
    LicenseClass learner = config.find("learner").orElseThrow();
    LicenseClass driver = config.find("DRIVER").orElseThrow();
    assertEquals("见习驾驶证", learner.name());
    assertEquals("正式驾驶证", driver.name());
    assertEquals(LicenseClass.Exam.TUTORIAL, learner.exam());
    assertEquals(List.of(DrivePermissions.BASE), learner.grants());
    assertEquals(LicenseClass.Exam.ROAD_TEST, driver.exam());
    assertEquals(List.of("learner"), driver.requires());
    assertEquals(List.of(DrivePermissions.DRIVER), driver.grants());
    assertEquals(1, config.levelOf("learner"));
    assertEquals(2, config.levelOf("driver"));
    assertEquals(0, config.levelOf("free"), "旧 ID 不再是等级");
    assertEquals(0, learner.trainingRuns());
    assertEquals(1, driver.trainingRuns());
    assertEquals(TrainingConfig.defaults(), config.training());
  }

  @Test
  @DisplayName("按配置解析：等级按声明顺序编号，认不出的考试方式跳过并提示，requires 指向不存在的等级时提示")
  void parse() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(
        """
        license:
          enabled: true
          exam-window-minutes: 20
          retry-cooldown-minutes: 5
          training:
            drill: false
            drill-max-delay-seconds: 60
            max-delay-seconds: 30
            handback-delay-seconds: 300
            routes: [" prac-1 ", ""]
          classes:
            basic:
              name: 见习证
              exam: tutorial
              grants: [fetarute.drive]
            broken:
              exam: written
            pro:
              name: 正式证
              requires: [basic, ghost]
              exam: dispatch
              training-runs: 3
              exam-stops: 5
              min-points: 80
              allow-overrun: true
              grants: [fetarute.drive.driver]
        """);
    List<String> warnings = new ArrayList<>();
    LicenseConfig config =
        LicenseConfig.from(yaml.getConfigurationSection("license"), warnings::add);
    assertEquals(20, config.examWindowMinutes());
    assertEquals(5, config.retryCooldownMinutes());
    assertEquals(List.of("basic", "pro"), config.classes().stream().map(LicenseClass::id).toList());
    LicenseClass pro = config.find("pro").orElseThrow();
    assertEquals(5, pro.examStops());
    assertEquals(80, pro.minPoints());
    assertTrue(pro.allowOverrun());
    assertFalse(pro.allowEmergency());
    assertEquals(2, config.levelOf("pro"));
    assertEquals(3, pro.trainingRuns());
    assertEquals(0, config.find("basic").orElseThrow().trainingRuns());
    TrainingConfig training = config.training();
    assertFalse(training.drill());
    assertEquals(60, training.drillMaxDelaySeconds());
    assertEquals(60, training.maxDelaySeconds(), "撤演练的阈值不低于不安排演练的阈值");
    assertEquals(300, training.handbackDelaySeconds());
    assertEquals(List.of("PRAC-1"), training.routes());
    assertTrue(training.allowsRoute("prac-1"));
    assertFalse(training.allowsRoute("MT-3"));
    assertFalse(training.allowsRoute(null));
    assertTrue(TrainingConfig.defaults().allowsRoute("MT-3"), "没配练习线路时用正式车次");
    assertTrue(warnings.stream().anyMatch(w -> w.contains("broken")));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("ghost")));
  }

  @Test
  @DisplayName("证号由 UUID 得出，同一名玩家永远相同")
  void cardNumberStable() {
    UUID id = UUID.fromString("3a988dae-ea58-374f-a756-10d056914b46");
    assertEquals("FTA-3A988DAE", LicenseCardItem.number(id));
    assertEquals(LicenseCardItem.number(id), LicenseCardItem.number(id));
  }
}
