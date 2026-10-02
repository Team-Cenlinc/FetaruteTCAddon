package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 配置棍的聊天菜单、屏幕信息与拆除确认。
 *
 * <p>菜单每一项都是可点击的命令（{@code /fta pids set <屏幕> ...}），命令执行后重新发送菜单，玩家看到的始终是最新状态。 选中项为青色加粗，线路按线路色显示。
 */
public final class PidsStickMenu {

  /** 菜单里列出的附近车站个数（不含已绑定的）。 */
  private static final int NEARBY_STATIONS = 5;

  private final LocaleManager locale;

  public PidsStickMenu(LocaleManager locale) {
    this.locale = Objects.requireNonNull(locale, "locale");
  }

  /** 发送配置菜单。 */
  public void open(CommandSender sender, PidsService service, PidsScreen screen) {
    String set = "/fta pids set " + screen.id() + " ";
    sender.sendMessage(locale.component("pids.menu.header", headerValues(service, screen)));

    Component stations = label("pids.menu.station");
    List<PidsStationKey> candidates = new ArrayList<>();
    screen.station().ifPresent(candidates::add);
    service.nearby(screen.worldId(), screen.center()).stations().stream()
        .filter(station -> !candidates.contains(station))
        .limit(NEARBY_STATIONS)
        .forEach(candidates::add);
    for (PidsStationKey station : candidates) {
      stations =
          stations.append(
              button(
                  stationName(service, station),
                  screen.station().equals(Optional.of(station)),
                  null,
                  set + "station " + station.operatorCode() + " " + station.stationCode()));
    }
    stations = stations.append(suggest(locale.text("pids.menu.station-manual"), set + "station "));
    sender.sendMessage(stations);

    if (screen.station().isPresent()) {
      PidsStationKey station = screen.station().get();
      sender.sendMessage(platformRow(service, screen, station, set));
      sender.sendMessage(lineRow(service, screen, station, set));
    } else {
      sender.sendMessage(
          label("pids.menu.platform").append(locale.component("pids.menu.need-station")));
    }

    Component layouts = label("pids.menu.layout");
    for (PidsLayout layout : service.layouts().all()) {
      if (layout.tileRows() == screen.tileRows() && layout.tileCols() == screen.tileCols()) {
        layouts =
            layouts.append(
                button(
                    layout.name(),
                    layout.id().equals(screen.layoutId()),
                    null,
                    set + "layout " + layout.id()));
      }
    }
    sender.sendMessage(layouts);

    Component appearance = label("pids.menu.appearance");
    for (PidsScreen.Appearance value : PidsScreen.Appearance.values()) {
      String name = value.name().toLowerCase(Locale.ROOT);
      appearance =
          appearance.append(
              button(
                  locale.text("pids.menu.appearance-" + name),
                  screen.appearance() == value,
                  null,
                  set + "appearance " + name));
    }
    sender.sendMessage(appearance);

    boolean live = screen.mode() == PidsScreen.Mode.LIVE;
    sender.sendMessage(
        label("pids.menu.actions")
            .append(
                button(
                    locale.text(live ? "pids.menu.back-to-test-card" : "pids.menu.go-live"),
                    false,
                    null,
                    set + "mode " + (live ? "test" : "live")))
            .append(
                button(
                        locale.text("pids.menu.remove"),
                        false,
                        null,
                        "/fta pids remove " + screen.id())
                    .color(NamedTextColor.RED)));
  }

  /** 发送屏幕信息（配置棍左键、{@code /fta pids info}）。 */
  public void info(CommandSender sender, PidsService service, PidsScreen screen) {
    sender.sendMessage(locale.component("pids.info.header", headerValues(service, screen)));
    sender.sendMessage(
        locale.component(
            "pids.info.binding",
            Map.of(
                "station",
                screen
                    .station()
                    .map(station -> stationName(service, station) + "（" + station + "）")
                    .orElseGet(() -> locale.text("command.pids.list.unbound")),
                "platforms",
                platforms(screen),
                "lines",
                screen.lines().isEmpty()
                    ? locale.text("pids.menu.all")
                    : String.join("、", screen.lines()),
                "appearance",
                locale.text(
                    "pids.menu.appearance-"
                        + screen.appearance().name().toLowerCase(Locale.ROOT)))));
    sender.sendMessage(
        locale.component(
            "pids.info.location",
            Map.of(
                "world",
                Optional.ofNullable(Bukkit.getWorld(screen.worldId()))
                    .map(World::getName)
                    .orElse(screen.worldId().toString()),
                "x",
                Integer.toString(screen.anchor().x()),
                "y",
                Integer.toString(screen.anchor().y()),
                "z",
                Integer.toString(screen.anchor().z()),
                "facing",
                locale.text("pids.facing." + screen.facing().name().toLowerCase(Locale.ROOT)))));
  }

  /** 拆除前确认。 */
  public void promptRemove(CommandSender sender, PidsScreen screen) {
    String command = "/fta pids remove " + screen.id() + " confirm";
    sender.sendMessage(
        locale
            .component("pids.remove.confirm", Map.of("id", PidsComposer.shortId(screen.id())))
            .append(
                locale
                    .component("pids.remove.confirm-button")
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(
                        HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))));
  }

  private Component platformRow(
      PidsService service, PidsScreen screen, PidsStationKey station, String set) {
    Component row = label("pids.menu.platform");
    List<String> platforms = service.platformsOf(screen.worldId(), station);
    if (platforms.isEmpty()) {
      return row.append(locale.component("pids.menu.no-platforms"));
    }
    // 站台屏与多站台屏左上角写站台号，不能选“全部”；“全部”只给车站统屏
    if (service.platformLimit(screen).isEmpty()) {
      row =
          row.append(
              button(
                  locale.text("pids.menu.all"),
                  screen.platforms().isEmpty(),
                  null,
                  set + "platform all"));
    }
    for (String platform : platforms) {
      row =
          row.append(
              button(
                  platform,
                  screen.platforms().contains(platform),
                  null,
                  set + "platform " + platform));
    }
    return row;
  }

  private Component lineRow(
      PidsService service, PidsScreen screen, PidsStationKey station, String set) {
    Component row = label("pids.menu.line");
    List<PidsView.LineChip> lines = service.directory().linesServing(station);
    if (lines.isEmpty()) {
      return row.append(locale.component("pids.menu.no-lines"));
    }
    row =
        row.append(
            button(locale.text("pids.menu.all"), screen.lines().isEmpty(), null, set + "line all"));
    for (PidsView.LineChip line : lines) {
      row =
          row.append(
              button(
                  line.code(),
                  screen.lines().contains(line.code().toUpperCase(Locale.ROOT)),
                  TextColor.color(line.color()),
                  set + "line " + line.code()));
    }
    return row;
  }

  private Map<String, String> headerValues(PidsService service, PidsScreen screen) {
    return Map.of(
        "id",
        PidsComposer.shortId(screen.id()),
        "layout",
        service.layoutOf(screen).map(PidsLayout::name).orElse(screen.layoutId()),
        "size",
        screen.tileRows() + "×" + screen.tileCols(),
        "mode",
        locale.text(
            screen.mode() == PidsScreen.Mode.LIVE
                ? "pids.menu.mode-live"
                : "pids.menu.mode-test-card"));
  }

  private String platforms(PidsScreen screen) {
    return platformsText(locale, screen);
  }

  /** “全部站台”或“站台 1、4”。 */
  static String platformsText(LocaleManager locale, PidsScreen screen) {
    return screen.platforms().isEmpty()
        ? locale.text("pids.test-card.all-platforms")
        : locale
            .text("pids.test-card.platforms")
            .replace("<platforms>", String.join("、", screen.platforms()));
  }

  private Component label(String key) {
    return locale.component(key).append(Component.text("：", NamedTextColor.GRAY));
  }

  /** 选中项青色加粗；未选中时用给定颜色（线路色）或灰色。点击执行命令。 */
  private static Component button(String text, boolean selected, TextColor color, String command) {
    TextColor shown = selected ? NamedTextColor.AQUA : color == null ? NamedTextColor.GRAY : color;
    Component button = Component.text("[" + text + "]", shown);
    if (selected) {
      button = button.decorate(TextDecoration.BOLD);
    }
    return Component.space()
        .append(
            button
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY))));
  }

  /** 点击后把命令填进输入框，由玩家补全参数。 */
  private static Component suggest(String text, String command) {
    return Component.space()
        .append(
            Component.text("[" + text + "]", NamedTextColor.DARK_AQUA)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY))));
  }

  /** 车站中文名；名称目录还没有时用站码。 */
  static String stationName(PidsService service, PidsStationKey station) {
    return service
        .directory()
        .stationName(station.toString())
        .map(PidsView.Names::primary)
        .orElse(station.stationCode());
  }
}
