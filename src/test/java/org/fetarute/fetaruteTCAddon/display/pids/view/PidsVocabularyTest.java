package org.fetarute.fetaruteTCAddon.display.pids.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.junit.jupiter.api.Test;

/** 站台屏文案：用到的每个键在默认语言文件里都有；分钟数、班次数与站名等占位符被替换。 */
class PidsVocabularyTest {

  @Test
  void everyKeyExistsInTheDefaultLanguageFile() throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (var reader =
        new InputStreamReader(
            PidsVocabularyTest.class.getClassLoader().getResourceAsStream("lang/zh_CN.yml"),
            StandardCharsets.UTF_8)) {
      lang.load(reader);
    }
    List<String> missing = new ArrayList<>();
    PidsVocabulary vocabulary =
        new PidsVocabulary(
            key -> {
              String value = lang.getString(key);
              if (value == null) {
                missing.add(key);
                return key;
              }
              return value;
            });

    vocabulary.labels();
    vocabulary.onTime();
    vocabulary.late(3);
    vocabulary.severelyLate(6);
    vocabulary.cancelled();
    vocabulary.arriving();
    vocabulary.passing();
    vocabulary.boarding();
    vocabulary.planned();
    vocabulary.terminating();
    vocabulary.outOfService();
    vocabulary.callHint();
    vocabulary.noMoreTrainsCall();
    vocabulary.onCall();
    for (RouteApi.OperationType type : RouteApi.OperationType.values()) {
      vocabulary.type(type);
    }
    for (PidsNotice notice : PidsNotice.values()) {
      vocabulary.noticeTitle(notice);
      vocabulary.noticeBody(notice);
    }
    vocabulary.lineStatusTitle();
    vocabulary.lineStatusLabels(false);
    vocabulary.lineStatusLabels(true);
    for (PidsLineStatus.Condition condition : PidsLineStatus.Condition.values()) {
      vocabulary.condition(condition);
    }
    vocabulary.lateUpTo(8);
    vocabulary.cancelledTrips(1);
    vocabulary.cancelledTrips(2);
    vocabulary.sectionClosed(new Names("主城湾", "Spawn Bay"), new Names("海兴", ""));
    vocabulary.maintenance();
    vocabulary.firstTrain("05:30");

    assertTrue(missing.isEmpty(), () -> "缺少文案: " + missing);
    assertEquals(
        new Names("部分班次取消", "Trips cancelled"),
        vocabulary.condition(PidsLineStatus.Condition.CANCELLATIONS));
    assertEquals(
        new Names("近 1 小时取消 1 班", "1 trip cancelled in the last hour"),
        vocabulary.cancelledTrips(1));
    assertEquals(
        new Names("近 1 小时取消 2 班", "2 trips cancelled in the last hour"),
        vocabulary.cancelledTrips(2));
    assertEquals(
        new Names("主城湾—海兴 暂停运营", "No service Spawn Bay – 海兴"),
        vocabulary.sectionClosed(new Names("主城湾", "Spawn Bay"), new Names("海兴", "")),
        "没有英文站名时写中文");
    assertEquals(new Names("晚点 3 分", "Late 3 min"), vocabulary.late(3));
    assertEquals(Optional.of("快速"), vocabulary.type(RouteApi.OperationType.RAPID));
    assertEquals(Optional.empty(), vocabulary.type(RouteApi.OperationType.NORMAL));
  }
}
