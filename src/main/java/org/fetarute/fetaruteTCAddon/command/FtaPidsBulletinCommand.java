package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.display.pids.PidsComposer;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinDialog;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinForm;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /fta pids bulletin}：站台屏公告。
 *
 * <ul>
 *   <li>{@code new <公司> <运营商>} 打开对话框发布；{@code edit <公告>} 打开对话框修改（带原内容）
 *   <li>{@code list [页]} 列出可管理的公告（生效中、未开始与已结束），{@code info <公告>} 查看详情与各种屏幕上的页数
 *   <li>{@code remove <公告> [confirm]} 撤下并删除
 * </ul>
 *
 * <p>公告参数可写完整编号或前缀（至少 4 位）。有 {@code fetarute.pids.manage} 的可管理全部公告，公司里有管理类角色的成员只能管理本公司的。
 * 提交时先校验填的内容，再按本运营商在用的站台屏布局排版，任何一种布局上标题或正文放不下都不发布，带着原内容重新打开对话框。
 */
public final class FtaPidsBulletinCommand {

  private static final int PAGE_SIZE = 8;
  private static final int SUGGESTION_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaPidsBulletinCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** 注册 {@code /fta pids bulletin} 全部子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> bulletins = bulletinSuggestions();
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .handler(ctx -> help(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("new")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions())
            .required("operator", StringParser.stringParser(), operatorSuggestions())
            .handler(
                ctx -> create((Player) ctx.sender(), ctx.get("company"), ctx.get("operator"))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("list")
            .optional(
                "page",
                IntegerParser.integerParser(1),
                CommandSuggestionProviders.placeholder("<page>"))
            .handler(ctx -> list(ctx.sender(), ctx.<Integer>optional("page").orElse(1))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("info")
            .required("bulletin", StringParser.stringParser(), bulletins)
            .handler(
                ctx ->
                    withBulletin(
                        ctx.sender(),
                        ctx.get("bulletin"),
                        (service, bulletin) -> info(ctx.sender(), service, bulletin))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("edit")
            .senderType(Player.class)
            .required("bulletin", StringParser.stringParser(), bulletins)
            .handler(
                ctx ->
                    withBulletin(
                        ctx.sender(),
                        ctx.get("bulletin"),
                        (service, bulletin) -> edit((Player) ctx.sender(), bulletin))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("remove")
            .required("bulletin", StringParser.stringParser(), bulletins)
            .handler(
                ctx ->
                    withBulletin(
                        ctx.sender(),
                        ctx.get("bulletin"),
                        (service, bulletin) -> promptRemove(ctx.sender(), bulletin))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("pids")
            .literal("bulletin")
            .literal("remove")
            .required("bulletin", StringParser.stringParser(), bulletins)
            .literal("confirm")
            .handler(
                ctx ->
                    withBulletin(
                        ctx.sender(),
                        ctx.get("bulletin"),
                        (service, bulletin) -> remove(ctx.sender(), service, bulletin))));
  }

  private void help(CommandSender sender) {
    for (String key : List.of("header", "new", "list", "edit")) {
      sender.sendMessage(locale().component("command.pids.bulletin.help." + key));
    }
  }

  // ---- 发布与修改 ----

  private void create(Player player, String companyArg, String operatorArg) {
    Optional<PidsService> service = service(player);
    Optional<StorageProvider> provider = CommandStorageProviders.readyProvider(player, plugin);
    if (service.isEmpty() || provider.isEmpty()) {
      return;
    }
    CompanyQueryService query = new CompanyQueryService(provider.get());
    Optional<Company> company = query.findCompany(companyArg.trim());
    if (company.isEmpty()) {
      player.sendMessage(
          locale().component("command.company.info.not-found", Map.of("company", companyArg)));
      return;
    }
    if (!service.get().canManageCompany(player, company.get().id())) {
      player.sendMessage(locale().component("command.pids.bulletin.no-permission"));
      return;
    }
    Optional<Operator> operator =
        query
            .findOperator(company.get().id(), operatorArg.trim())
            .filter(found -> found.companyId().equals(company.get().id()));
    if (operator.isEmpty()) {
      player.sendMessage(
          locale()
              .component(
                  "command.pids.bulletin.unknown-operator",
                  Map.of("operator", operatorArg, "company", company.get().code())));
      return;
    }
    PidsBulletinDialog.Target target =
        new PidsBulletinDialog.Target(
            company.get().id(),
            company.get().name(),
            operator.get().code(),
            operator.get().name(),
            Optional.empty());
    openDialog(player, target, PidsBulletinForm.empty(), List.of());
  }

  private void edit(Player player, PidsBulletin bulletin) {
    Optional<StorageProvider> provider = CommandStorageProviders.readyProvider(player, plugin);
    if (provider.isEmpty()) {
      return;
    }
    String companyName =
        provider.get().companies().findById(bulletin.companyId()).map(Company::name).orElse("?");
    String operatorName =
        provider
            .get()
            .operators()
            .findByCompanyAndCode(bulletin.companyId(), bulletin.operatorCode())
            .map(Operator::name)
            .orElse(bulletin.operatorCode());
    PidsBulletinDialog.Target target =
        new PidsBulletinDialog.Target(
            bulletin.companyId(),
            companyName,
            bulletin.operatorCode(),
            operatorName,
            Optional.of(bulletin.id()));
    openDialog(player, target, PidsBulletinForm.of(bulletin, zone()), List.of());
  }

  private void openDialog(
      Player player,
      PidsBulletinDialog.Target target,
      PidsBulletinForm form,
      List<Component> problems) {
    FetaruteTCAddon owner = plugin;
    PidsBulletinDialog.open(
        plugin,
        locale(),
        player,
        target,
        form,
        problems,
        (clicked, filled) -> new FtaPidsBulletinCommand(owner).submit(clicked, target, filled));
  }

  /**
   * 对话框提交：重新检查权限，校验内容，排版检查，保存并发回执；有问题时带着原内容重新打开对话框。
   *
   * <p>由对话框按钮回调在主线程调用，每次现取当前的服务实例（{@code /fta reload} 之后提交也走新实例）。
   */
  void submit(Player player, PidsBulletinDialog.Target target, PidsBulletinForm form) {
    Optional<PidsService> service = service(player);
    Optional<StorageProvider> provider = CommandStorageProviders.readyProvider(player, plugin);
    if (service.isEmpty() || provider.isEmpty()) {
      return;
    }
    if (!service.get().canManageCompany(player, target.companyId())) {
      player.sendMessage(locale().component("command.pids.bulletin.no-permission"));
      return;
    }
    Optional<Operator> operator =
        provider.get().operators().findByCompanyAndCode(target.companyId(), target.operatorCode());
    if (operator.isEmpty()) {
      player.sendMessage(
          locale()
              .component(
                  "command.pids.bulletin.unknown-operator",
                  Map.of("operator", target.operatorCode(), "company", target.companyName())));
      return;
    }
    Optional<PidsBulletin> existing = Optional.empty();
    if (target.editing().isPresent()) {
      existing = service.get().bulletins().find(target.editing().get());
      if (existing.isEmpty()) {
        player.sendMessage(locale().component("command.pids.bulletin.gone"));
        return;
      }
    }

    UUID operatorId = operator.get().id();
    StorageProvider storage = provider.get();
    Instant now = service.get().now();
    List<PidsBulletinForm.Problem> problems = new ArrayList<>();
    Optional<PidsBulletinForm.Parsed> parsed =
        form.parse(
            code -> storage.stations().findByOperatorAndCode(operatorId, code).isPresent(),
            code -> storage.lines().findByOperatorAndCode(operatorId, code).isPresent(),
            now,
            zone(),
            problems);
    if (parsed.isEmpty()) {
      openDialog(player, target, form, render(problems));
      return;
    }
    PidsBulletin bulletin = build(player, target, parsed.get(), existing, now);
    List<PidsService.BulletinFit> fits = service.get().bulletinFits(bulletin);
    for (PidsService.BulletinFit fit : fits) {
      Map<String, String> layout = Map.of("layout", fit.layout().name());
      if (fit.result().titleTruncated()) {
        problems.add(new PidsBulletinForm.Problem("title-overflow", layout));
      }
      if (fit.result().bodyTruncated()) {
        problems.add(new PidsBulletinForm.Problem("body-overflow", layout));
      }
    }
    if (!problems.isEmpty()) {
      openDialog(player, target, form, render(problems));
      return;
    }
    if (!service.get().saveBulletin(bulletin)) {
      player.sendMessage(locale().component("command.pids.bulletin.failed"));
      return;
    }
    player.sendMessage(
        locale()
            .component(
                existing.isPresent()
                    ? "command.pids.bulletin.updated"
                    : "command.pids.bulletin.published",
                Map.of(
                    "id", PidsComposer.shortId(bulletin.id()),
                    "title", bulletin.title().primary())));
    sendSummary(player, bulletin, fits);
  }

  private static PidsBulletin build(
      Player player,
      PidsBulletinDialog.Target target,
      PidsBulletinForm.Parsed parsed,
      Optional<PidsBulletin> existing,
      Instant now) {
    if (existing.isPresent()) {
      return existing
          .get()
          .edited(
              parsed.stations(),
              parsed.lines(),
              parsed.level(),
              parsed.title(),
              parsed.body(),
              parsed.startsAt(),
              parsed.endsAt(),
              now);
    }
    return new PidsBulletin(
        UUID.randomUUID(),
        target.companyId(),
        target.operatorCode(),
        parsed.stations(),
        parsed.lines(),
        parsed.level(),
        parsed.title(),
        parsed.body(),
        parsed.startsAt(),
        parsed.endsAt(),
        Optional.of(player.getUniqueId()),
        now,
        now);
  }

  /** 问题渲染成对话框里的红字。 */
  private List<Component> render(List<PidsBulletinForm.Problem> problems) {
    List<Component> lines = new ArrayList<>();
    for (PidsBulletinForm.Problem problem : problems) {
      Map<String, String> values = problem.values();
      if (values.containsKey("field")) {
        values =
            Map.of(
                "field",
                locale().text("pids.bulletin.dialog.field." + values.get("field")),
                "max",
                values.getOrDefault("max", ""));
      }
      lines.add(locale().component("pids.bulletin.error." + problem.key(), values));
    }
    return lines;
  }

  // ---- 列表、详情与撤下 ----

  private void list(CommandSender sender, int page) {
    Optional<PidsService> service = service(sender);
    if (service.isEmpty()) {
      return;
    }
    Predicate<UUID> manageable = service.get().manageableCompanies(sender);
    List<PidsBulletin> visible =
        service.get().bulletins().all().stream()
            .filter(bulletin -> manageable.test(bulletin.companyId()))
            .toList();
    if (visible.isEmpty()) {
      sender.sendMessage(locale().component("command.pids.bulletin.list.empty"));
      return;
    }
    int pages = (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    int current = Math.min(page, pages);
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.list.header",
                Map.of(
                    "count", Integer.toString(visible.size()),
                    "page", Integer.toString(current),
                    "pages", Integer.toString(pages))));
    Instant now = service.get().now();
    for (PidsBulletin bulletin :
        visible.subList((current - 1) * PAGE_SIZE, Math.min(visible.size(), current * PAGE_SIZE))) {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.bulletin.list.entry",
                  Map.of(
                      "id", PidsComposer.shortId(bulletin.id()),
                      "status", statusText(bulletin, now),
                      "level", levelText(bulletin),
                      "operator", bulletin.operatorCode(),
                      "title", bulletin.title().primary(),
                      "period", period(bulletin)))
              .clickEvent(ClickEvent.runCommand(command("info", bulletin))));
    }
  }

  private void info(CommandSender sender, PidsService service, PidsBulletin bulletin) {
    Instant now = service.now();
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.info.header",
                Map.of(
                    "id", PidsComposer.shortId(bulletin.id()),
                    "level", levelText(bulletin),
                    "status", statusText(bulletin, now))));
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.info.title",
                Map.of(
                    "title", bulletin.title().primary(),
                    "secondary", dash(bulletin.title().secondary()))));
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.info.body",
                Map.of("body", dash(bulletin.body().primary().replace('\n', ' ')))));
    if (!bulletin.body().secondary().isEmpty()) {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.bulletin.info.body-secondary",
                  Map.of("body", bulletin.body().secondary().replace('\n', ' '))));
    }
    sendSummary(sender, bulletin, service.bulletinFits(bulletin));
  }

  /** 范围、时段、各种屏幕上的页数与“修改”“撤下”按钮：发布回执与详情共用。 */
  private void sendSummary(
      CommandSender sender, PidsBulletin bulletin, List<PidsService.BulletinFit> fits) {
    LocaleManager locale = locale();
    sender.sendMessage(
        locale.component(
            "command.pids.bulletin.info.scope",
            Map.of(
                "operator", bulletin.operatorCode(),
                "stations",
                    bulletin.stations().isEmpty()
                        ? locale.text("command.pids.bulletin.all-stations")
                        : String.join("、", bulletin.stations()),
                "lines",
                    bulletin.lines().isEmpty()
                        ? locale.text("command.pids.bulletin.all-lines")
                        : String.join("、", bulletin.lines()))));
    sender.sendMessage(
        locale.component("command.pids.bulletin.info.period", Map.of("period", period(bulletin))));
    for (PidsService.BulletinFit fit : fits) {
      sender.sendMessage(
          locale.component(
              "command.pids.bulletin.info.fit",
              Map.of(
                  "layout",
                  fit.layout().name(),
                  "pages",
                  locale.text(
                      fit.result().pages().size() > 1
                          ? "command.pids.bulletin.two-pages"
                          : "command.pids.bulletin.one-page"))));
    }
    Component actions =
        locale
            .component("command.pids.bulletin.button.edit")
            .clickEvent(ClickEvent.runCommand(command("edit", bulletin)))
            .append(Component.space())
            .append(
                locale
                    .component("command.pids.bulletin.button.remove")
                    .clickEvent(ClickEvent.runCommand(command("remove", bulletin))));
    sender.sendMessage(actions);
  }

  private void promptRemove(CommandSender sender, PidsBulletin bulletin) {
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.remove.confirm",
                Map.of(
                    "id", PidsComposer.shortId(bulletin.id()),
                    "title", bulletin.title().primary()))
            .append(
                locale()
                    .component("command.pids.bulletin.remove.confirm-button")
                    .clickEvent(ClickEvent.runCommand(command("remove", bulletin) + " confirm"))));
  }

  private void remove(CommandSender sender, PidsService service, PidsBulletin bulletin) {
    if (!service.removeBulletin(bulletin)) {
      sender.sendMessage(locale().component("command.pids.bulletin.failed"));
      return;
    }
    sender.sendMessage(
        locale()
            .component(
                "command.pids.bulletin.remove.done",
                Map.of(
                    "id", PidsComposer.shortId(bulletin.id()),
                    "title", bulletin.title().primary())));
  }

  /** 解析公告参数、检查能否管理这条公告，再执行。 */
  private void withBulletin(
      CommandSender sender, String raw, BiConsumer<PidsService, PidsBulletin> action) {
    Optional<PidsService> service = service(sender);
    if (service.isEmpty()) {
      return;
    }
    List<PidsBulletin> matches = service.get().bulletins().matchIdOrPrefix(raw);
    if (matches.size() > 1) {
      sender.sendMessage(
          locale()
              .component(
                  "command.pids.bulletin.ambiguous",
                  Map.of("bulletin", raw, "count", Integer.toString(matches.size()))));
      return;
    }
    if (matches.isEmpty()) {
      sender.sendMessage(
          locale().component("command.pids.bulletin.not-found", Map.of("bulletin", raw)));
      return;
    }
    PidsBulletin bulletin = matches.get(0);
    if (!service.get().canManageCompany(sender, bulletin.companyId())) {
      sender.sendMessage(locale().component("command.pids.bulletin.no-permission"));
      return;
    }
    action.accept(service.get(), bulletin);
  }

  // ---- 文字 ----

  private String statusText(PidsBulletin bulletin, Instant now) {
    return locale()
        .text(
            "command.pids.bulletin.status." + bulletin.status(now).name().toLowerCase(Locale.ROOT));
  }

  private String levelText(PidsBulletin bulletin) {
    return locale()
        .text(
            bulletin.important() ? "pids.bulletin.level.important" : "pids.bulletin.level.normal");
  }

  /** 时段：开始留空写“立即”，结束留空写“长期”。 */
  private String period(PidsBulletin bulletin) {
    ZoneId zone = zone();
    String from =
        bulletin
            .startsAt()
            .map(at -> PidsBulletinForm.TIME.format(at.atZone(zone)))
            .orElseGet(() -> locale().text("command.pids.bulletin.from-now"));
    String until =
        bulletin
            .endsAt()
            .map(at -> PidsBulletinForm.TIME.format(at.atZone(zone)))
            .orElseGet(() -> locale().text("command.pids.bulletin.until-removed"));
    return locale()
        .text("command.pids.bulletin.period")
        .replace("<from>", from)
        .replace("<until>", until);
  }

  private static String dash(String value) {
    return value.isEmpty() ? "-" : value;
  }

  private static String command(String action, PidsBulletin bulletin) {
    return "/fta pids bulletin " + action + " " + bulletin.id();
  }

  /** 时刻按服务器时区显示与解析。 */
  private static ZoneId zone() {
    return ZoneId.systemDefault();
  }

  private Optional<PidsService> service(CommandSender sender) {
    Optional<PidsService> service = plugin.getPidsService();
    if (service.isEmpty()) {
      sender.sendMessage(locale().component("command.pids.unavailable"));
    }
    return service;
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  // ---- 补全 ----

  /** 可发布公告的公司：有管理权限时为全部公司，否则为玩家有管理类角色的公司。 */
  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = FtaPidsCommand.prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<company>");
          }
          Optional<PidsService> service = plugin.getPidsService();
          Optional<StorageProvider> provider = CommandStorageProviders.providerIfReady(plugin);
          if (service.isEmpty() || provider.isEmpty()) {
            return suggestions;
          }
          Predicate<UUID> manageable = service.get().manageableCompanies(ctx.sender());
          provider.get().companies().listAll().stream()
              .filter(company -> company.code().toLowerCase(Locale.ROOT).startsWith(prefix))
              .filter(company -> manageable.test(company.id()))
              .map(Company::code)
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = FtaPidsCommand.prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<operator>");
          }
          Optional<StorageProvider> provider = CommandStorageProviders.providerIfReady(plugin);
          Optional<String> company = ctx.optional("company");
          if (provider.isEmpty() || company.isEmpty()) {
            return suggestions;
          }
          new CompanyQueryService(provider.get())
              .findCompany(company.get().trim())
              .ifPresent(
                  found ->
                      provider.get().operators().listByCompany(found.id()).stream()
                          .map(Operator::code)
                          .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
                          .limit(SUGGESTION_LIMIT)
                          .forEach(suggestions::add));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> bulletinSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = FtaPidsCommand.prefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isEmpty()) {
            suggestions.add("<bulletin>");
          }
          plugin.getPidsService().stream()
              .flatMap(
                  service -> {
                    Predicate<UUID> manageable = service.manageableCompanies(ctx.sender());
                    return service.bulletins().all().stream()
                        .filter(bulletin -> manageable.test(bulletin.companyId()));
                  })
              .map(bulletin -> PidsComposer.shortId(bulletin.id()))
              .filter(id -> id.startsWith(prefix))
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }
}
