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
  @DisplayName("四个岗位各一栏：驾驶只印最高一级，没取得的写“未取得”，没开放也没取得的整栏不印")
  void oneSectionPerPost() {
    LicenseConfig config = LicenseConfig.defaults();
    List<LicenseCardItem.Section> sections =
        LicenseCardItem.sections(
            config,
            List.of(held("learner", 0), held("driver", 3), held("signaller", 18)),
            id -> "name:" + id);

    assertEquals(
        List.of(
            LicenseClass.Post.DRIVER,
            LicenseClass.Post.GUARD,
            LicenseClass.Post.SIGNALLER,
            LicenseClass.Post.STATION_MASTER),
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
        List.of(
            LicenseClass.Post.DRIVER, LicenseClass.Post.GUARD, LicenseClass.Post.STATION_MASTER),
        LicenseCardItem.sections(noSignaller, List.of(), id -> id).stream()
            .map(LicenseCardItem.Section::post)
            .toList());
  }

  @Test
  @DisplayName("取得的岗位写已取得与日期，只有驾驶另写第几级")
  void heldPostsShowTheDate() {
    List<LicenseCardItem.Section> sections =
        List.of(
            new LicenseCardItem.Section(
                LicenseClass.Post.DRIVER, List.of(new LicenseCardItem.Entry("第 2 级", DAY))),
            new LicenseCardItem.Section(
                LicenseClass.Post.GUARD, List.of(new LicenseCardItem.Entry("车掌", DAY))),
            new LicenseCardItem.Section(LicenseClass.Post.SIGNALLER, List.of()));

    List<String> keys =
        LicenseCardItem.firstPage("Sinapole", "FTA-1", sections).stream()
            .skip(4)
            .map(LicenseCardItem.Line::key)
            .toList();

    assertEquals(
        List.of(
            "drive.license.card.post.driver",
            "drive.license.card.entry-name",
            "drive.license.card.held",
            "drive.license.card.post.guard",
            "drive.license.card.held",
            "drive.license.card.post.signaller",
            "drive.license.card.none"),
        keys);
  }

  private static int rows(
      YamlConfiguration lang, String localeTag, List<LicenseCardItem.Line> lines) {
    int rows = 0;
    for (LicenseCardItem.Line line : lines) {
      String text = lang.getString(line.key());
      assertTrue(text != null, localeTag + " 缺少 " + line.key());
      rows += text.split("\\\\n|\\n", -1).length;
    }
    return rows;
  }

  /** 四个岗位都取得时证面第一页也放得下（成书一页 14 行）；每一行的文案两种语言都有。 */
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
                    held("learner", 0),
                    held("driver", 3),
                    held("guard", 4),
                    held("signaller", 18),
                    held("station-master", 20)),
                id -> id));
    assertTrue(rows(lang, localeTag, lines) <= LicenseCardItem.PAGE_LINES, "第一页放得下");
    for (String key : List.of("title", "author", "notice", "upgraded")) {
      assertTrue(lang.isString("drive.license.card." + key), localeTag + " 缺少 card." + key);
    }
  }
}
