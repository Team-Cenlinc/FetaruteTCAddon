package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.pids.PidsAccess;
import org.fetarute.fetaruteTCAddon.display.pids.PidsComposer;
import org.fetarute.fetaruteTCAddon.display.pids.PidsNearby;
import org.fetarute.fetaruteTCAddon.display.pids.PidsPlatformNode;
import org.fetarute.fetaruteTCAddon.display.pids.PidsPlatformSelection;
import org.fetarute.fetaruteTCAddon.display.pids.PidsScreenPages;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStickMenu;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /fta pids}：站台屏工具与管理。
 *
 * <ul>
 *   <li>{@code give <布局>} 领取安装纸，{@code stick} 领取配置棍（需管理权限或公司成员身份）
 *   <li>{@code list [页]}（需 {@value PidsAccess#MANAGE_PERMISSION}）、{@code info <屏幕>}、{@code menu
 *       <屏幕>}
 *   <li>{@code set <屏幕> station|platform|line|layout|page|appearance|mode
 *       ...}：配置棍菜单点击执行的就是这些命令，执行后重新发送菜单；{@code layout} 换主布局，{@code page} 增删组合翻页的布局（见 {@link
 *       PidsScreenPages}）
 *   <li>{@code remove <屏幕> [confirm]}：拆除；展示框还原为空，玩家拿回同布局的安装纸
 * </ul>
 *
 * <p>屏幕参数可写完整 ID 或前缀（至少 4 位）。按屏幕绑定的车站判断权限（查看信息也一样，见 {@link PidsAccess}）：有管理权限的可管全部，
 * 公司里有管理类角色的成员只能管本公司车站的屏幕；改绑车站时新车站也须归其管理。
 */
public final class FtaPidsCommand {

  private static final int PAGE_SIZE = 10;
  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaPidsCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 注册 {@code /fta pids} 全部子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> screens = screenSuggestions();
    SuggestionProvider<CommandSender> layouts = layoutSuggestions();

    manager.command(
        manager.commandBuilder("fta").literal("pids").handler(ctx -> help(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("give")
            .senderType(Player.class)
            .required("layout", StringParser.stringParser(), layouts)
            .handler(ctx -> give((Player) ctx.sender(), ctx.get("layout"))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("stick")
            .senderType(Player.class)
            .handler(ctx -> stick((Player) ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("list")
            .permission(PidsAccess.MANAGE_PERMISSION)
            .optional(
                "page",
                IntegerParser.integerParser(1),
                CommandSuggestionProviders.placeholder("<page>"))
            .handler(ctx -> list(ctx.sender(), ctx.<Integer>optional("page").orElse(1))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("info")
            .required("screen", StringParser.stringParser(), screens)
            .handler(
                ctx ->
                    withScreen(
                        ctx,
                        (service, screen) ->
                            new PidsStickMenu(locale()).info(ctx.sender(), service, screen))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("menu")
            .required("screen", StringParser.stringParser(), screens)
            .handler(
                ctx ->
                    withScreen(
                        ctx,
                        (service, screen) ->
                            new PidsStickMenu(locale()).open(ctx.sender(), service, screen))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("set")
            .required("screen", StringParser.stringParser(), screens)
            .literal("station")
            .required("operator", StringParser.stringParser(), stationOperatorSuggestions())
            .required("station", StringParser.stringParser(), stationSuggestions())
            .handler(this::setStation));
    // 线路运行状况屏可以不绑车站、只绑运营商。
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("set")
            .required("screen", StringParser.stringParser(), screens)
            .literal("operator")
            .required("operator", StringParser.stringParser(), operatorSuggestions())
            .handler(this::setOperator));
    registerSet(manager, screens, "platform", platformSuggestions(), this::togglePlatform);
    registerSet(manager, screens, "line", lineSuggestions(), this::toggleLine);
    registerSet(manager, screens, "layout", screenLayoutSuggestions(), this::changeLayout);
    registerSet(manager, screens, "page", pageSuggestions(), this::togglePage);
    registerSet(
        manager,
        screens,
        "appearance",
        SuggestionProvider.suggestingStrings("auto", "light", "dark"),
        this::changeAppearance);
    registerSet(
        manager,
        screens,
        "mode",
        SuggestionProvider.suggestingStrings("live", "test"),
        this::changeMode);
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("remove")
            .required("screen", StringParser.stringParser(), screens)
            .handler(
                ctx ->
                    withScreen(
                        ctx,
                        (service, screen) ->
                            new PidsStickMenu(locale()).promptRemove(ctx.sender(), screen))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("remove")
            .required("screen", StringParser.stringParser(), screens)
            .literal("confirm")
            .handler(ctx -> withScreen(ctx, (service, screen) -> remove(ctx, service, screen))));
  }

  /** 修改一项设置：解析出新屏幕记录（空表示已报错），保存后重新发送菜单。 */
  private interface Edit {
    Optional<PidsScreen> apply(
        CommandSender sender, PidsService service, PidsScreen screen, String value);
  }

  private void registerSet(
      CommandManager<CommandSender> manager,
      SuggestionProvider<CommandSender> screens,
      String field,
      SuggestionProvider<CommandSender> values,
      Edit edit) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("set")
            .required("screen", StringParser.stringParser(), screens)
            .literal(field)
            .required("value", StringParser.stringParser(), values)
            .handler(
                ctx ->
                    withScreen(
                        ctx,
                        (service, screen) -> {
                          String value = ctx.get("value");
                          edit.apply(ctx.sender(), service, screen, value)
                              .ifPresent(updated -> save(ctx.sender(), service, updated));
                        })));
  }

  private void help(CommandSender sender) {
    LocaleManager locale = locale();
    for (String key : List.of("header", "give", "stick", "list", "info", "bulletin")) {
      sender.sendMessage(locale.component("command.pids.help." + key));
    }
  }

  private void give(Player player, String layoutId) {
    LocaleManager locale = locale();
    Optional<PidsService> service = service(player);
    if (service.isEmpty()) {
      return;
    }
    if (!service.get().canUseTools(player)) {
      player.sendMessage(locale.component("command.pids.no-tools-permission"));
      return;
    }
    Optional<PidsLayout> layout = service.get().layouts().find(layoutId);
    if (layout.isEmpty()) {
      player.sendMessage(
          locale.component("command.pids.give.unknown-layout", Map.of("layout", layoutId)));
      return;
    }
    giveItem(player, service.get().items().installPaper(layout.get()));
    player.sendMessage(
        locale.component(
            "command.pids.give.success",
            Map.of(
                "layout",
                layout.get().name(),
                "size",
                layout.get().tileRows() + "×" + layout.get().tileCols())));
  }

  private void stick(Player player) {
    Optional<PidsService> service = service(player);
    if (service.isEmpty()) {
      return;
    }
    if (!service.get().canUseTools(player)) {
      player.sendMessage(locale().component("command.pids.no-tools-permission"));
      return;
    }
    giveItem(player, service.get().items().stick());
    player.sendMessage(locale().component("command.pids.stick.success"));
  }

  private void list(CommandSender sender, int page) {
    LocaleManager locale = locale();
    Optional<PidsService> service = service(sender);
    if (service.isEmpty()) {
      return;
    }
    List<PidsScreen> screens = service.get().screens();
    if (screens.isEmpty()) {
      sender.sendMessage(locale.component("command.pids.list.empty"));
      return;
    }
    int pages = (screens.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    int current = Math.min(page, pages);
    sender.sendMessage(
        locale.component(
            "command.pids.list.header",
            Map.of(
                "count",
                Integer.toString(screens.size()),
                "page",
                Integer.toString(current),
                "pages",
                Integer.toString(pages))));
    for (PidsScreen screen :
        screens.subList((current - 1) * PAGE_SIZE, Math.min(screens.size(), current * PAGE_SIZE))) {
      sender.sendMessage(
          locale
              .component(
                  "command.pids.list.entry",
                  Map.of(
                      "id",
                      PidsComposer.shortId(screen.id()),
                      "layout",
                      service.get().layoutNames(screen),
                      "size",
                      screen.tileRows() + "×" + screen.tileCols(),
                      "station",
                      screen
                          .station()
                          .map(PidsStationKey::toString)
                          .or(
                              () ->
                                  screen
                                      .operatorCode()
                                      .map(
                                          operator ->
                                              locale
                                                  .text("command.pids.list.operator-only")
                                                  .replace(
                                                      "<operator>",
                                                      service
                                                          .get()
                                                          .directory()
                                                          .operatorName(operator)
                                                          .map(PidsView.Names::primary)
                                                          .orElse(operator))
                                                  .replace("<code>", operator)))
                          .orElseGet(() -> locale.text("command.pids.list.unbound")),
                      "mode",
                      locale.text(
                          screen.mode() == PidsScreen.Mode.LIVE
                              ? "pids.menu.mode-live"
                              : "pids.menu.mode-test-card"),
                      "world",
                      Optional.ofNullable(Bukkit.getWorld(screen.worldId()))
                          .map(World::getName)
                          .orElse("?"),
                      "x",
                      Integer.toString(screen.anchor().x()),
                      "y",
                      Integer.toString(screen.anchor().y()),
                      "z",
                      Integer.toString(screen.anchor().z())))
              .clickEvent(
                  net.kyori.adventure.text.event.ClickEvent.runCommand(
                      "/fta pids info " + screen.id())));
    }
  }

  private void setStation(CommandContext<CommandSender> ctx) {
    withScreen(
        ctx,
        (service, screen) -> {
          CommandSender sender = ctx.sender();
          PidsStationKey station;
          try {
            station = new PidsStationKey(ctx.get("operator"), ctx.get("station"));
          } catch (IllegalArgumentException ex) {
            sender.sendMessage(
                locale()
                    .component(
                        "command.pids.set.invalid-value",
                        Map.of("value", ctx.get("operator") + " " + ctx.get("station"))));
            return;
          }
          List<String> platforms = service.platformsOf(screen.worldId(), station);
          if (platforms.isEmpty()
              && service.directory().stationName(station.toString()).isEmpty()) {
            sender.sendMessage(
                locale()
                    .component(
                        "command.pids.set.unknown-station", Map.of("station", station.toString())));
            return;
          }
          if (!service.canManage(sender, Optional.of(station))) {
            sender.sendMessage(locale().component("pids.menu.no-permission"));
            return;
          }
          Set<String> boundPlatforms =
              PidsPlatformSelection.nearest(
                  service.nearby(screen.worldId(), screen.center()).platforms().stream()
                      .map(PidsNearby.Platform::node)
                      .toList(),
                  station,
                  service.platformLimit(screen));
          save(
              sender,
              service,
              screen.withBinding(Optional.of(station), boundPlatforms, Set.of(), service.now()));
        });
  }

  /** 线路运行状况屏只绑运营商：清掉车站与站台，线路过滤恢复为全部。 */
  private void setOperator(CommandContext<CommandSender> ctx) {
    withScreen(
        ctx,
        (service, screen) -> {
          CommandSender sender = ctx.sender();
          String operator = ((String) ctx.get("operator")).trim().toUpperCase(Locale.ROOT);
          if (!service.isLineStatus(screen)) {
            sender.sendMessage(locale().component("command.pids.set.operator-line-status-only"));
            return;
          }
          if (operator.isEmpty() || service.directory().operatorName(operator).isEmpty()) {
            sender.sendMessage(
                locale()
                    .component("command.pids.set.unknown-operator", Map.of("operator", operator)));
            return;
          }
          if (!service.canManageOperator(sender, operator)) {
            sender.sendMessage(locale().component("pids.menu.no-permission"));
            return;
          }
          save(sender, service, screen.withOperator(operator, Set.of(), service.now()));
        });
  }

  /** 运营商代码补全：玩家能管理的公司的运营商。 */
  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          Optional<PidsService> service = plugin.getPidsService();
          if (service.isEmpty()) {
            return List.of("<operator>");
          }
          List<String> codes = service.get().manageableOperatorsForSuggestions(ctx.sender());
          return codes.isEmpty() ? List.of("<operator>") : codes;
        });
  }

  /** 按布局规则换选或增删站台（见 {@link PidsPlatformSelection}）。 */
  private Optional<PidsScreen> togglePlatform(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    String platform =
        PidsScreen.ALL.equalsIgnoreCase(value.trim())
            ? PidsScreen.ALL
            : PidsPlatformNode.normalize(value);
    OptionalInt limit = service.platformLimit(screen);
    PidsPlatformSelection.Result result =
        PidsPlatformSelection.select(screen.platforms(), platform, limit);
    switch (result.outcome()) {
      case NEED_PLATFORM -> {
        sender.sendMessage(locale().component("command.pids.set.need-platform"));
        return Optional.empty();
      }
      case TOO_MANY -> {
        sender.sendMessage(
            locale()
                .component(
                    "command.pids.set.too-many-platforms",
                    Map.of("max", Integer.toString(limit.orElse(0)))));
        return Optional.empty();
      }
      case OK -> {
        return Optional.of(
            screen.withBinding(
                screen.station(), result.platforms(), screen.lines(), service.now()));
      }
    }
    return Optional.empty();
  }

  private Optional<PidsScreen> toggleLine(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    return Optional.of(
        screen.withBinding(
            screen.station(),
            screen.platforms(),
            PidsScreen.toggle(screen.lines(), value.trim().toUpperCase(Locale.ROOT)),
            service.now()));
  }

  private Optional<PidsScreen> changeLayout(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    Optional<PidsLayout> layout =
        service.layouts().find(value).filter(found -> PidsScreenPages.fits(screen, found));
    if (layout.isEmpty()) {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.set.unknown-layout",
                  Map.of("layout", value, "size", screen.tileRows() + "×" + screen.tileCols())));
      return Optional.empty();
    }
    return Optional.of(
        screen.withLayouts(
            PidsScreenPages.withPrimary(screen, layout.get(), service.layouts()), service.now()));
  }

  /** 增删组合翻页的布局（见 {@link PidsScreenPages#togglePage}）。 */
  private Optional<PidsScreen> togglePage(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    PidsScreenPages.Edit edit = PidsScreenPages.togglePage(screen, value, service.layouts());
    String key =
        switch (edit.outcome()) {
          case OK -> null;
          case UNKNOWN_LAYOUT -> "command.pids.set.unknown-layout";
          case PRIMARY -> "command.pids.set.page-is-primary";
          case PRIMARY_NOT_COMBINABLE -> "command.pids.set.page-primary-not-combinable";
          case NOT_COMBINABLE -> "command.pids.set.page-not-combinable";
        };
    if (key != null) {
      sender.sendMessage(
          locale()
              .component(
                  key,
                  Map.of(
                      "layout",
                      value,
                      "primary",
                      service.layoutOf(screen).map(PidsLayout::id).orElse(screen.layoutId()),
                      "size",
                      screen.tileRows() + "×" + screen.tileCols())));
      return Optional.empty();
    }
    return Optional.of(screen.withLayouts(edit.layoutIds(), service.now()));
  }

  private Optional<PidsScreen> changeAppearance(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    return parseEnum(sender, PidsScreen.Appearance.class, value)
        .map(appearance -> screen.withAppearance(appearance, service.now()));
  }

  private Optional<PidsScreen> changeMode(
      CommandSender sender, PidsService service, PidsScreen screen, String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    PidsScreen.Mode mode;
    if ("live".equals(normalized)) {
      mode = PidsScreen.Mode.LIVE;
    } else if ("test".equals(normalized)) {
      mode = PidsScreen.Mode.TEST_CARD;
    } else {
      sender.sendMessage(
          locale().component("command.pids.set.invalid-value", Map.of("value", value)));
      return Optional.empty();
    }
    // 线路运行状况屏绑车站或运营商都行，其余屏要绑车站。
    boolean lineStatus = service.isLineStatus(screen);
    if (mode == PidsScreen.Mode.LIVE
        && (lineStatus ? screen.operatorCode().isEmpty() : screen.station().isEmpty())) {
      sender.sendMessage(
          locale()
              .component(
                  lineStatus ? "command.pids.set.need-binding" : "command.pids.set.need-station"));
      return Optional.empty();
    }
    if (mode == PidsScreen.Mode.LIVE
        && screen.platforms().isEmpty()
        && service.platformLimit(screen).isPresent()) {
      sender.sendMessage(locale().component("command.pids.set.need-platform"));
      return Optional.empty();
    }
    return Optional.of(screen.withMode(mode, service.now()));
  }

  private void remove(CommandContext<CommandSender> ctx, PidsService service, PidsScreen screen) {
    CommandSender sender = ctx.sender();
    Optional<PidsLayout> layout = service.layoutOf(screen);
    if (!service.remove(screen)) {
      sender.sendMessage(locale().component("pids.remove.failed"));
      return;
    }
    if (sender instanceof Player player) {
      layout.ifPresent(found -> giveItem(player, service.items().installPaper(found)));
    }
    sender.sendMessage(
        locale().component("pids.remove.done", Map.of("id", PidsComposer.shortId(screen.id()))));
  }

  private void save(CommandSender sender, PidsService service, PidsScreen updated) {
    if (!service.save(updated)) {
      sender.sendMessage(locale().component("command.pids.set.failed"));
      return;
    }
    if (sender instanceof Player) {
      new PidsStickMenu(locale()).open(sender, service, updated);
    } else {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.set.saved", Map.of("id", PidsComposer.shortId(updated.id()))));
    }
  }

  /** 解析屏幕参数、检查能否管理这块屏幕（查看信息也一样），再执行。 */
  private void withScreen(
      CommandContext<CommandSender> ctx, BiConsumer<PidsService, PidsScreen> action) {
    CommandSender sender = ctx.sender();
    Optional<PidsService> service = service(sender);
    if (service.isEmpty()) {
      return;
    }
    String raw = ctx.get("screen");
    List<PidsScreen> matches = service.get().matchIdOrPrefix(raw);
    if (matches.size() > 1) {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.ambiguous",
                  Map.of("screen", raw, "count", Integer.toString(matches.size()))));
      return;
    }
    if (matches.isEmpty()) {
      sender.sendMessage(locale().component("command.pids.not-found", Map.of("screen", raw)));
      return;
    }
    PidsScreen screen = matches.get(0);
    if (!service.get().canManage(sender, screen)) {
      sender.sendMessage(locale().component("pids.menu.no-permission"));
      return;
    }
    action.accept(service.get(), screen);
  }

  private <E extends Enum<E>> Optional<E> parseEnum(
      CommandSender sender, Class<E> type, String value) {
    try {
      return Optional.of(Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
      sender.sendMessage(
          locale().component("command.pids.set.invalid-value", Map.of("value", value)));
      return Optional.empty();
    }
  }

  private Optional<PidsService> service(CommandSender sender) {
    Optional<PidsService> service = plugin.getPidsService();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("command.pids.unavailable"));
    }
    return service;
  }

  private static void giveItem(Player player, org.bukkit.inventory.ItemStack item) {
    player
        .getInventory()
        .addItem(item)
        .values()
        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  private SuggestionProvider<CommandSender> screenSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<screen>");
          }
          boolean admin = ctx.sender().hasPermission(PidsAccess.MANAGE_PERMISSION);
          plugin.getPidsService().stream()
              .flatMap(
                  service -> {
                    if (admin) {
                      return service.screens().stream();
                    }
                    // 公司成员只补全本公司（车站或运营商所属）的屏幕；都没绑的只有管理员能管
                    Set<String> manageable =
                        Set.copyOf(service.manageableOperatorsForSuggestions(ctx.sender()));
                    return service.screens().stream()
                        .filter(
                            screen ->
                                screen.operatorCode().filter(manageable::contains).isPresent());
                  })
              .map(screen -> PidsComposer.shortId(screen.id()))
              .filter(id -> id.startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> layoutSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<layout>");
          }
          plugin.getPidsService().stream()
              .flatMap(service -> service.layouts().all().stream())
              .map(PidsLayout::id)
              .filter(id -> id.startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  /** 换主布局：与屏幕同尺寸的布局；还没输入屏幕时列全部。 */
  private SuggestionProvider<CommandSender> screenLayoutSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<layout>");
          }
          Optional<ScreenRef> ref = screenOf(ctx);
          plugin.getPidsService().stream()
              .flatMap(service -> service.layouts().all().stream())
              .filter(
                  layout ->
                      ref.map(found -> PidsScreenPages.fits(found.screen(), layout)).orElse(true))
              .map(PidsLayout::id)
              .filter(id -> id.startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  /** 组合翻页：可与主布局组合的同尺寸布局。 */
  private SuggestionProvider<CommandSender> pageSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<layout>");
          }
          screenOf(ctx).stream()
              .flatMap(
                  ref -> {
                    List<String> ids = new ArrayList<>(ref.screen().pageLayoutIds());
                    PidsScreenPages.candidates(ref.screen(), ref.service().layouts()).stream()
                        .map(PidsLayout::id)
                        .filter(id -> !ids.contains(id))
                        .forEach(ids::add);
                    return ids.stream();
                  })
              .filter(id -> id.startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  /** 绑定车站的运营商：屏幕所在世界调度图里的车站所属的、本人能管理的运营商，离屏幕近的优先。 */
  private SuggestionProvider<CommandSender> stationOperatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<operator>");
          }
          screenOf(ctx)
              .ifPresent(
                  ref -> {
                    Set<String> manageable =
                        Set.copyOf(ref.service().manageableOperatorsForSuggestions(ctx.sender()));
                    stationsNear(ref).stream()
                        .map(PidsStationKey::operatorCode)
                        .distinct()
                        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .filter(manageable::contains)
                        .limit(SUGGESTION_LIMIT)
                        .forEach(suggestions::add);
                  });
          return suggestions;
        });
  }

  /** 绑定车站的站码：屏幕所在世界调度图里这个运营商的车站，离屏幕近的优先；运营商须是本人能管理的。 */
  private SuggestionProvider<CommandSender> stationSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<station>");
          }
          Optional<String> operator = ctx.<String>optional("operator").map(String::trim);
          if (operator.isEmpty()) {
            return suggestions;
          }
          screenOf(ctx)
              .filter(
                  ref ->
                      ref.service()
                          .manageableOperatorsForSuggestions(ctx.sender())
                          .contains(operator.get().toUpperCase(Locale.ROOT)))
              .ifPresent(
                  ref ->
                      stationsNear(ref).stream()
                          .filter(
                              station -> station.operatorCode().equalsIgnoreCase(operator.get()))
                          .map(PidsStationKey::stationCode)
                          .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  /** 屏幕所在世界调度图里的车站，离屏幕由近到远。 */
  private static List<PidsStationKey> stationsNear(ScreenRef ref) {
    return ref.service().stationsByDistance(ref.screen().worldId(), ref.screen().center());
  }

  private SuggestionProvider<CommandSender> platformSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          List<String> suggestions = new ArrayList<>(List.of("<platform>", PidsScreen.ALL));
          screenOf(ctx)
              .ifPresent(
                  pair ->
                      pair.screen()
                          .station()
                          .ifPresent(
                              station ->
                                  suggestions.addAll(
                                      pair.service()
                                          .platformsOf(pair.screen().worldId(), station))));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          List<String> suggestions = new ArrayList<>(List.of("<line>", PidsScreen.ALL));
          screenOf(ctx)
              .ifPresent(
                  pair ->
                      pair.service().filterableLines(pair.screen()).stream()
                          .map(line -> line.code())
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  private record ScreenRef(PidsService service, PidsScreen screen) {}

  private Optional<ScreenRef> screenOf(CommandContext<CommandSender> ctx) {
    Optional<String> raw = ctx.optional("screen");
    return plugin
        .getPidsService()
        .flatMap(
            service ->
                raw.flatMap(service::findByIdOrPrefix)
                    .map(screen -> new ScreenRef(service, screen)));
  }

  /** 补全时正在输入的那一段（小写、去空白），站台屏与公告命令共用。 */
  static String prefix(CommandInput input) {
    return input == null ? "" : input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }
}
