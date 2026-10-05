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
  @DisplayName("默认两级：教程考自由驾驶证，路考调度驾驶证且要先有自由驾驶证")
  void defaults() {
    LicenseConfig config = LicenseConfig.defaults();
    LicenseClass free = config.find("free").orElseThrow();
    LicenseClass dispatch = config.find("DISPATCH").orElseThrow();
    assertEquals(LicenseClass.Exam.TUTORIAL, free.exam());
    assertEquals(List.of(DrivePermissions.BASE), free.grants());
    assertEquals(LicenseClass.Exam.DISPATCH, dispatch.exam());
    assertEquals(List.of("free"), dispatch.requires());
    assertEquals(List.of(DrivePermissions.DRIVER), dispatch.grants());
    assertEquals(1, config.levelOf("free"));
    assertEquals(2, config.levelOf("dispatch"));
    assertEquals(0, config.levelOf("nope"));
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
