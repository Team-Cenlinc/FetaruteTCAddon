package org.fetarute.fetaruteTCAddon.display.pids.announce;

import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.NOW;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.TPC;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.cancelled;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.row;
import static org.fetarute.fetaruteTCAddon.display.pids.announce.AnnounceFixtures.running;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSettings.BroadcastSettings;
import org.fetarute.fetaruteTCAddon.display.pids.PidsSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 用插件自带的 zh_CN 文案渲染，核对每条都带站台号、站名取名称目录。 */
class PidsAnnouncementTextTest {

  private static PidsAnnouncementText text;

  @BeforeAll
  static void loadLanguage() {
    YamlConfiguration zh =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                Objects.requireNonNull(
                    PidsAnnouncementTextTest.class
                        .getClassLoader()
                        .getResourceAsStream("lang/zh_CN.yml")),
                StandardCharsets.UTF_8));
    text =
        new PidsAnnouncementText(
            new AnnounceFixtures.Directory(), key -> zh.getString(key, key), ZoneOffset.ofHours(8));
  }

  private static Component render(PidsRow row) {
    List<PidsAnnouncement> active =
        PidsAnnouncement.active(
            new PidsSnapshot(TPC, NOW, List.of(row)), NOW, BroadcastSettings.DEFAULT);
    return text.render(active.get(0));
  }

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private static Optional<TextColor> colorOf(Component component, String content) {
    if (component instanceof TextComponent text && text.content().equals(content)) {
      return Optional.ofNullable(text.color());
    }
    return component.children().stream()
        .map(child -> colorOf(child, content))
        .flatMap(Optional::stream)
        .findFirst();
  }

  @Test
  void arrivingCarriesPlatformLineAndDestination() {
    Component line = render(running(PidsRow.Status.ARRIVING, "0366", 20));

    assertEquals("▶ 2 站台 █MT 开往 新笛矢·壑湖 的列车即将进站", plain(line));
    assertEquals(Optional.of(TextColor.color(0xD920D9)), colorOf(line, "█"), "色块是线路色");
  }

  @Test
  void unknownPlatformIsLeftOut() {
    PidsRow noPlatform =
        new PidsRow(
            PidsRow.Status.ARRIVING,
            "MT",
            "SURC:MT:MT-3N",
            "HHU",
            Optional.of("SURC:HHU"),
            "-",
            NOW.plusSeconds(20),
            OptionalLong.empty(),
            4,
            false,
            false,
            false,
            Optional.of("0366"));

    assertEquals("▶ █MT 开往 新笛矢·壑湖 的列车即将进站", plain(render(noPlatform)));
  }

  @Test
  void terminatingAndOutOfServiceTrainsWarnNotToBoard() {
    assertEquals(
        "▶ 2 站台 █MT 本站终到列车即将进站，请勿上车",
        plain(render(row(PidsRow.Status.ARRIVING, "0366", 20, 0, false, true, false))));
    assertEquals(
        "▶ 2 站台 回库列车即将进站，请勿上车",
        plain(render(row(PidsRow.Status.ARRIVING, "0366", 20, 0, false, false, true))));
    assertEquals(
        "⚠ 2 站台 有列车通过，请在安全线以内候车",
        plain(render(row(PidsRow.Status.ARRIVING, "0471", 20, 0, true, false, false))));
  }

  @Test
  void chatAnnouncementsNameTheStationAndTheTrip() {
    assertEquals("[大港城] 2 站台 █MT 21:45 开往 新笛矢·壑湖 的班次已取消", plain(render(cancelled(300))));
    assertEquals(
        "[大港城] 2 站台 █MT 开往 新笛矢·壑湖 的列车晚点约 6 分钟",
        plain(render(row(PidsRow.Status.EN_ROUTE, "0366", 600, 380, false, false, false))));
    assertEquals("[大港城] 另有 2 条站台通知，请留意站台屏。", plain(text.overflow(TPC, 2)));
  }
}
