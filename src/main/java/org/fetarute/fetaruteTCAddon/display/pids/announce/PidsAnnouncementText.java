package org.fetarute.fetaruteTCAddon.display.pids.announce;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.PidsRow;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsText;

/**
 * 站台广播的文字。文案在语言文件 {@code pids.announce.*}，站名、线路色与站台屏同一来源（{@link PidsDirectory}）。
 *
 * <p>每条都带站台号（站台待定时写“站台待定”，站台未知时省略），ActionBar 与聊天一样，乘客据此判断与自己有没有关系。本站终到与回库车单独成句，提醒勿上车。
 */
final class PidsAnnouncementText {

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
  private static final String UNKNOWN_PLATFORM = "-";

  /** 线路不在目录里时色块用的颜色。 */
  private static final TextColor FALLBACK_LINE_COLOR = NamedTextColor.GRAY;

  private final PidsDirectory directory;
  private final Function<String, String> text;
  private final ZoneId zone;

  /**
   * @param directory 名称目录
   * @param text 语言文件原文（键 → MiniMessage 文本）
   * @param zone 显示时刻用的时区
   */
  PidsAnnouncementText(PidsDirectory directory, Function<String, String> text, ZoneId zone) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.text = Objects.requireNonNull(text, "text");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  /** 一条广播的文字。 */
  Component render(PidsAnnouncement announcement) {
    PidsRow row = announcement.row();
    TagResolver resolver =
        TagResolver.resolver(
            Placeholder.component("platform", platform(row)),
            Placeholder.component("line", line(row)),
            Placeholder.unparsed("destination", destination(row)),
            Placeholder.unparsed("station", stationName(announcement.station())),
            Placeholder.unparsed("time", CLOCK.format(row.expectedAt().atZone(zone))),
            Placeholder.unparsed(
                "minutes", String.valueOf(Lateness.minutes(row.delaySeconds().orElse(0L)))),
            Placeholder.unparsed("new", row.platform()),
            Placeholder.unparsed("from", announcement.previousPlatform().orElse("-")));
    return MINI_MESSAGE.deserialize(text.apply(key(announcement)), resolver);
  }

  /** 聊天广播超出上限时合成的一条。 */
  Component overflow(PidsStationKey station, int count) {
    return MINI_MESSAGE.deserialize(
        text.apply("pids.announce.overflow"),
        TagResolver.resolver(
            Placeholder.unparsed("station", stationName(station)),
            Placeholder.unparsed("count", String.valueOf(count))));
  }

  private static String key(PidsAnnouncement announcement) {
    PidsRow row = announcement.row();
    return switch (announcement.kind()) {
      case ARRIVING -> {
        if (row.outOfService()) {
          yield "pids.announce.arriving-out-of-service";
        }
        yield row.terminating() ? "pids.announce.arriving-terminating" : "pids.announce.arriving";
      }
      case PASSING -> "pids.announce.passing";
      case CANCELLED -> "pids.announce.cancelled";
      case DELAYED -> "pids.announce.delayed";
      case PLATFORM_CHANGED -> "pids.announce.platform-changed";
    };
  }

  private Component platform(PidsRow row) {
    if (row.platformPending()) {
      return MINI_MESSAGE.deserialize(text.apply("pids.announce.platform-pending"));
    }
    String platform = row.platform();
    if (platform.isBlank() || UNKNOWN_PLATFORM.equals(platform)) {
      return Component.empty();
    }
    return MINI_MESSAGE.deserialize(
        text.apply("pids.announce.platform"), Placeholder.unparsed("platform", platform));
  }

  /** 线路色块紧跟线路代码，与站台屏色牌同一颜色。 */
  private Component line(PidsRow row) {
    String operator = row.routeId().split(":", 2)[0];
    return directory
        .line(operator, row.lineName())
        .map(
            style ->
                Component.text("█", TextColor.color(style.color()))
                    .append(Component.text(style.code(), NamedTextColor.WHITE)))
        .orElseGet(
            () ->
                Component.text("█", FALLBACK_LINE_COLOR)
                    .append(Component.text(row.lineName(), NamedTextColor.WHITE)));
  }

  private String destination(PidsRow row) {
    return row.destinationId()
        .flatMap(directory::stationName)
        .map(names -> PidsText.compactSeparators(names.primary()))
        .orElse(row.destination());
  }

  private String stationName(PidsStationKey station) {
    return directory
        .stationName(station.toString())
        .map(names -> PidsText.compactSeparators(names.primary()))
        .orElse(station.stationCode());
  }
}
