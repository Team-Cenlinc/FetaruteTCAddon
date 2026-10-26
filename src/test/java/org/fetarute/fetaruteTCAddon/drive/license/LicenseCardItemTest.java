package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("操作许可证证面")
class LicenseCardItemTest {

  private static final UUID PLAYER = UUID.fromString("3a988dae-ea58-374f-a756-10d056914b46");
  private static final Instant DAY = Instant.parse("2026-10-05T08:00:00Z");

  private static LicenseRecord held(String classId, int day) {
    return new LicenseRecord(PLAYER, "Sinapole", classId, DAY.plusSeconds(86_400L * day), "exam");
  }

  @Test
  @DisplayName("三个岗位各一栏：驾驶只印最高一级，没取得的写“未取得”，没开放也没取得的整栏不印")
  void oneSectionPerPost() {
    LicenseConfig config = LicenseConfig.defaults();
    List<LicenseCardItem.Section> sections =
        LicenseCardItem.sections(
            config,
            List.of(held("learner", 0), held("driver", 3), held("signaller", 18)),
            id -> "name:" + id);

    assertEquals(
        List.of(LicenseClass.Post.DRIVER, LicenseClass.Post.GUARD, LicenseClass.Post.SIGNALLER),
        sections.stream().map(LicenseCardItem.Section::post).toList());
    assertEquals(
        List.of(new LicenseCardItem.Entry("name:driver", DAY.plusSeconds(86_400L * 3))),
        sections.get(0).entries(),
        "见习不再单列");
    assertTrue(sections.get(1).entries().isEmpty());
    assertEquals("name:signaller", sections.get(2).entries().get(0).name());

    List<LicenseClass> closed = new ArrayList<>();
    for (LicenseClass license : config.classes()) {
      closed.add(
          license.post() == LicenseClass.Post.SIGNALLER
              ? new LicenseClass(
                  license.id(),
                  license.name(),
                  license.description(),
                  false,
                  license.requires(),
                  license.exam(),
                  license.examStops(),
                  license.minPoints(),
                  false,
                  false,
                  false,
                  license.trainingRuns(),
                  license.grants())
              : license);
    }
    LicenseConfig noSignaller =
        new LicenseConfig(true, 30, 10, 10, closed, TrainingConfig.defaults());
    assertEquals(
        List.of(LicenseClass.Post.DRIVER, LicenseClass.Post.GUARD),
        LicenseCardItem.sections(noSignaller, List.of(), id -> id).stream()
            .map(LicenseCardItem.Section::post)
            .toList());
  }

  /** 三个岗位都取得时证面第一页也放得下（成书一页 14 行）；每一行的文案两种语言都有。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void theFirstPageFitsAndEveryLineHasText(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    List<LicenseCardItem.Line> lines =
        LicenseCardItem.firstPage(
            "Sinapole",
            LicenseCardItem.number(PLAYER),
            LicenseCardItem.sections(
                LicenseConfig.defaults(),
                List.of(
                    held("learner", 0), held("driver", 3), held("guard", 4), held("signaller", 18)),
                id -> id));
    int rows = 0;
    for (LicenseCardItem.Line line : lines) {
      String text = lang.getString(line.key());
      assertTrue(text != null, localeTag + " 缺少 " + line.key());
      rows += text.split("\\\\n|\\n", -1).length;
    }
    assertTrue(rows <= LicenseCardItem.PAGE_LINES, "第一页 " + rows + " 行");
    for (String key : List.of("title", "author", "notice", "upgraded")) {
      assertTrue(lang.isString("drive.license.card." + key), localeTag + " 缺少 card." + key);
    }
  }
}
