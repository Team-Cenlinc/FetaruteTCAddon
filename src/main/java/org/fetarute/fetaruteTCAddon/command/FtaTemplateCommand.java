package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplate;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * HUD 模板命令入口：/fta template ...
 *
 * <p>支持 create/edit/define/info/list/delete，模板编辑使用书与笔完成。
 */
public final class FtaTemplateCommand {

  private static final int SUGGESTION_LIMIT = 20;
  private static final int BOOK_MAX_LINES_PER_PAGE = 11;
  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final FetaruteTCAddon plugin;
  private final NamespacedKey bookMarkerKey;
  private final NamespacedKey bookTypeKey;
  private final NamespacedKey bookCompanyKey;
  private final NamespacedKey bookNameKey;
  private final NamespacedKey bookDefinedAtKey;

  public FtaTemplateCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bookMarkerKey = new NamespacedKey(plugin, "template_editor_marker");
    this.bookTypeKey = new NamespacedKey(plugin, "template_type");
    this.bookCompanyKey = new NamespacedKey(plugin, "template_company");
    this.bookNameKey = new NamespacedKey(plugin, "template_name");
    this.bookDefinedAtKey = new NamespacedKey(plugin, "template_defined_at");
  }

  /** 注册 /fta template 子命令。 */
  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> typeSuggestions =
        CommandSuggestionProviders.enumValues(HudTemplateType.class, "<type>");
    SuggestionProvider<CommandSender> nameSuggestions = placeholderSuggestion("<name>");
    SuggestionProvider<CommandSender> templateSuggestions = templateNameSuggestions();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("create")
            .senderType(Player.class)
            .required("type", StringParser.stringParser(), typeSuggestions)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("name", StringParser.stringParser(), nameSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  String typeRaw = ((String) ctx.get("type")).trim();
                  Optional<HudTemplateType> typeOpt = HudTemplateType.parse(typeRaw);
                  if (typeOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.template.invalid-type", Map.of("type", typeRaw)));
                    return;
                  }
                  HudTemplateType type = typeOpt.get();
                  String name = ((String) ctx.get("name")).trim();
                  if (name.isBlank()) {
                    sender.sendMessage(locale.component("command.template.invalid-name"));
                    return;
                  }
                  if (provider
                      .hudTemplates()
                      .findByCompanyAndName(company.id(), type, name)
                      .isPresent()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.create.exists",
                            Map.of("type", type.name(), "name", name)));
                    return;
                  }
                  String content = resolveDefaultTemplate(locale, type);
                  Instant now = Instant.now();
                  HudTemplate template =
                      new HudTemplate(
                          UUID.randomUUID(), company.id(), type, name, content, now, now);
                  provider.hudTemplates().save(template);
                  reloadTemplateCache();
                  sender.sendMessage(
                      locale.component(
                          "command.template.create.success",
                          Map.of("type", type.name(), "name", name)));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("edit")
            .senderType(Player.class)
            .required("type", StringParser.stringParser(), typeSuggestions)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("name", StringParser.stringParser(), templateSuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  HudTemplateType type =
                      HudTemplateType.parse(((String) ctx.get("type")).trim())
                          .orElse(HudTemplateType.BOSSBAR);
                  String name = ((String) ctx.get("name")).trim();
                  Optional<HudTemplate> templateOpt =
                      provider.hudTemplates().findByCompanyAndName(company.id(), type, name);
                  if (templateOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.not-found",
                            Map.of("type", type.name(), "name", name)));
                    return;
                  }
                  HudTemplate template = templateOpt.get();
                  ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
                  if (book.getItemMeta() instanceof BookMeta meta) {
                    persistBookContext(meta, type, company.code(), name);
                    meta.displayName(
                        locale.component(
                            "command.template.editor.book.name",
                            Map.of("type", type.name(), "name", name)));
                    meta.lore(
                        List.of(
                            locale.component("command.template.editor.book.lore-1"),
                            locale.component(
                                "command.template.editor.book.lore-2",
                                Map.of("company", company.code(), "name", name))));
                    meta.pages(toComponents(buildTemplatePages(locale, template.content(), type)));
                    book.setItemMeta(meta);
                  }
                  var leftovers = sender.getInventory().addItem(book);
                  if (!leftovers.isEmpty()) {
                    sender.getWorld().dropItemNaturally(sender.getLocation(), book);
                    sender.sendMessage(locale.component("command.template.editor.give.dropped"));
                    return;
                  }
                  sender.sendMessage(locale.component("command.template.editor.give.success"));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("define")
            .senderType(Player.class)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  Optional<List<String>> linesOpt = readBookLines(locale, sender);
                  if (linesOpt.isEmpty()) {
                    return;
                  }
                  List<String> lines = linesOpt.get();
                  if (lines.isEmpty()) {
                    sender.sendMessage(locale.component("command.template.define.book.empty"));
                    return;
                  }
                  ItemStack item = sender.getInventory().getItemInMainHand();
                  BookMeta meta = (BookMeta) item.getItemMeta();
                  TemplateBookContext context = readBookContext(meta);
                  if (context == null) {
                    sender.sendMessage(locale.component("command.template.define.book.invalid"));
                    return;
                  }
                  CompanyQueryService query = new CompanyQueryService(provider);
                  Optional<Company> companyOpt = query.findCompany(context.companyCode());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", context.companyCode())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<HudTemplateType> typeOpt = HudTemplateType.parse(context.type());
                  if (typeOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.invalid-type", Map.of("type", context.type())));
                    return;
                  }
                  HudTemplateType type = typeOpt.get();
                  Optional<HudTemplate> templateOpt =
                      provider
                          .hudTemplates()
                          .findByCompanyAndName(company.id(), type, context.name());
                  if (templateOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.not-found",
                            Map.of("type", type.name(), "name", context.name())));
                    return;
                  }
                  HudTemplate template = templateOpt.get();
                  String content = String.join("\n", lines);
                  if (content.isBlank()) {
                    sender.sendMessage(locale.component("command.template.define.book.empty"));
                    return;
                  }
                  Instant now = Instant.now();
                  HudTemplate updated =
                      new HudTemplate(
                          template.id(),
                          template.companyId(),
                          template.type(),
                          template.name(),
                          content,
                          template.createdAt(),
                          now);
                  provider.hudTemplates().save(updated);
                  reloadTemplateCache();
                  persistDefinedAt(meta, now);
                  item.setItemMeta(meta);
                  sender.sendMessage(
                      locale.component(
                          "command.template.define.success",
                          Map.of("type", type.name(), "name", template.name())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("info")
            .required("type", StringParser.stringParser(), typeSuggestions)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("name", StringParser.stringParser(), templateSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  HudTemplateType type =
                      HudTemplateType.parse(((String) ctx.get("type")).trim())
                          .orElse(HudTemplateType.BOSSBAR);
                  String name = ((String) ctx.get("name")).trim();
                  Optional<HudTemplate> templateOpt =
                      provider.hudTemplates().findByCompanyAndName(company.id(), type, name);
                  if (templateOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.not-found",
                            Map.of("type", type.name(), "name", name)));
                    return;
                  }
                  HudTemplate template = templateOpt.get();
                  sender.sendMessage(
                      locale.component(
                          "command.template.info.header",
                          Map.of("type", template.type().name(), "name", template.name())));
                  sender.sendMessage(
                      locale.component(
                          "command.template.info.entry",
                          Map.of(
                              "company",
                              company.code(),
                              "updated",
                              template.updatedAt().toString())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("list")
            .required("company", StringParser.stringParser(), companySuggestions)
            .optional("type", StringParser.stringParser(), typeSuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<String> typeArgOpt = ctx.optional("type").map(String.class::cast);
                  Optional<HudTemplateType> typeOpt = typeArgOpt.flatMap(HudTemplateType::parse);
                  List<HudTemplate> templates =
                      typeOpt.isPresent()
                          ? provider
                              .hudTemplates()
                              .listByCompanyAndType(company.id(), typeOpt.get())
                          : provider.hudTemplates().listByCompany(company.id());
                  sender.sendMessage(
                      locale.component(
                          "command.template.list.header",
                          Map.of(
                              "company",
                              company.code(),
                              "count",
                              String.valueOf(templates.size()))));
                  if (templates.isEmpty()) {
                    sender.sendMessage(locale.component("command.template.list.empty"));
                    return;
                  }
                  for (HudTemplate template : templates) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.list.entry",
                            Map.of("type", template.type().name(), "name", template.name())));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("template")
            .literal("delete")
            .senderType(Player.class)
            .required("type", StringParser.stringParser(), typeSuggestions)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("name", StringParser.stringParser(), templateSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  LocaleManager locale = plugin.getLocaleManager();
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(locale.component("command.common.confirm-required"));
                    return;
                  }
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  CompanyQueryService query = new CompanyQueryService(provider);
                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  HudTemplateType type =
                      HudTemplateType.parse(((String) ctx.get("type")).trim())
                          .orElse(HudTemplateType.BOSSBAR);
                  String name = ((String) ctx.get("name")).trim();
                  Optional<HudTemplate> templateOpt =
                      provider.hudTemplates().findByCompanyAndName(company.id(), type, name);
                  if (templateOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.template.not-found",
                            Map.of("type", type.name(), "name", name)));
                    return;
                  }
                  provider.hudTemplates().delete(templateOpt.get().id());
                  reloadTemplateCache();
                  sender.sendMessage(
                      locale.component(
                          "command.template.delete.success",
                          Map.of("type", type.name(), "name", name)));
                }));
  }

  private void persistBookContext(
      BookMeta meta, HudTemplateType type, String company, String name) {
    PersistentDataContainer container = meta.getPersistentDataContainer();
    container.set(bookMarkerKey, PersistentDataType.BYTE, (byte) 1);
    container.set(bookTypeKey, PersistentDataType.STRING, type.name());
    container.set(bookCompanyKey, PersistentDataType.STRING, company);
    container.set(bookNameKey, PersistentDataType.STRING, name);
  }

  private void persistDefinedAt(BookMeta meta, Instant now) {
    meta.getPersistentDataContainer()
        .set(bookDefinedAtKey, PersistentDataType.LONG, now.toEpochMilli());
  }

  private TemplateBookContext readBookContext(BookMeta meta) {
    if (meta == null) {
      return null;
    }
    PersistentDataContainer container = meta.getPersistentDataContainer();
    Byte marker = container.get(bookMarkerKey, PersistentDataType.BYTE);
    if (marker == null || marker == 0) {
      return null;
    }
    String type = container.get(bookTypeKey, PersistentDataType.STRING);
    String company = container.get(bookCompanyKey, PersistentDataType.STRING);
    String name = container.get(bookNameKey, PersistentDataType.STRING);
    if (type == null || company == null || name == null) {
      return null;
    }
    Long definedAt = container.get(bookDefinedAtKey, PersistentDataType.LONG);
    return new TemplateBookContext(type, company, name, Optional.ofNullable(definedAt));
  }

  private Optional<List<String>> readBookLines(LocaleManager locale, Player sender) {
    ItemStack item = sender.getInventory().getItemInMainHand();
    if (item.getType() == Material.AIR) {
      sender.sendMessage(locale.component("command.template.define.book.missing"));
      return Optional.empty();
    }
    if (!(item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.WRITTEN_BOOK)) {
      sender.sendMessage(locale.component("command.template.define.book.invalid"));
      return Optional.empty();
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      sender.sendMessage(locale.component("command.template.define.book.invalid"));
      return Optional.empty();
    }
    List<Component> pages = meta.pages();
    if (pages.isEmpty()) {
      return Optional.of(List.of());
    }
    List<String> lines = new ArrayList<>();
    for (Component page : pages) {
      String pageText = page == null ? "" : PLAIN_TEXT.serialize(page);
      if (pageText.isEmpty()) {
        continue;
      }
      String normalized = pageText.replace("\r\n", "\n").replace('\r', '\n');
      for (String rawLine : normalized.split("\n", -1)) {
        lines.add(rawLine);
      }
    }
    return Optional.of(lines);
  }

  private List<String> buildTemplatePages(
      LocaleManager locale, String content, HudTemplateType type) {
    if (content != null && !content.isBlank()) {
      return splitPages(java.util.Arrays.asList(content.split("\n", -1)), BOOK_MAX_LINES_PER_PAGE);
    }
    if (locale != null) {
      List<String> template = locale.stringList("command.template.editor.book.empty-template");
      if (!template.isEmpty()) {
        return splitPages(template, BOOK_MAX_LINES_PER_PAGE);
      }
    }
    List<String> rendered = new ArrayList<>();
    rendered.add("# FetaruteTCAddon HUD Template");
    rendered.add("# type: " + type.name());
    rendered.add("#");
    rendered.add("# 使用 {placeholder} 写模板文本");
    rendered.add("# 保存后运行 /fta template define");
    return splitPages(rendered, BOOK_MAX_LINES_PER_PAGE);
  }

  private List<String> splitPages(List<String> lines, int maxLinesPerPage) {
    List<String> pages = new ArrayList<>();
    StringBuilder page = new StringBuilder();
    int count = 0;
    for (String line : lines) {
      if (count >= maxLinesPerPage) {
        pages.add(page.toString());
        page = new StringBuilder();
        count = 0;
      }
      if (page.length() > 0) {
        page.append('\n');
      }
      page.append(line == null ? "" : line);
      count++;
    }
    if (page.length() > 0) {
      pages.add(page.toString());
    }
    if (pages.isEmpty()) {
      pages.add("");
    }
    return pages;
  }

  private List<Component> toComponents(List<String> pages) {
    List<Component> components = new ArrayList<>(pages.size());
    for (String page : pages) {
      components.add(Component.text(page == null ? "" : page));
    }
    return components;
  }

  private String resolveDefaultTemplate(LocaleManager locale, HudTemplateType type) {
    if (type == HudTemplateType.BOSSBAR && locale != null) {
      String value = locale.text("display.hud.bossbar.template");
      if (!value.isBlank()) {
        return value;
      }
    }
    if (type == HudTemplateType.ACTIONBAR && locale != null) {
      String value = locale.text("display.hud.actionbar.template");
      if (!value.isBlank()) {
        return value;
      }
    }
    if (type == HudTemplateType.PLAYER_DISPLAY) {
      if (plugin.getHudDefaultTemplateService() != null) {
        Optional<String> template = plugin.getHudDefaultTemplateService().resolveTemplate(type);
        if (template.isPresent() && !template.get().isBlank()) {
          return template.get();
        }
      }
    }
    return "";
  }

  private void reloadTemplateCache() {
    HudTemplateService service = plugin.getHudTemplateService();
    if (service != null) {
      service.reload();
    }
  }

  /** 获取已就绪的 StorageProvider；未就绪时向用户输出统一错误文案。 */
  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  /** 仅在 StorageManager ready 的情况下返回 provider，避免命令线程触发初始化或抛异常。 */
  private Optional<StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  /** 判断 sender 是否具备读取指定公司的权限（公司成员或管理员）。 */
  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompany(sender, provider, companyId);
  }

  /** 判断 sender 是否具备管理指定公司的权限（Owner/Manager 或管理员）。 */
  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canManageCompany(sender, provider, companyId);
  }

  private SuggestionProvider<CommandSender> placeholderSuggestion(String placeholder) {
    return SuggestionProvider.suggestingStrings(placeholder);
  }

  private SuggestionProvider<CommandSender> companySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<company>");
          }
          suggestions.addAll(listCompanyCodes(ctx.sender(), prefix));
          return suggestions;
        });
  }

  private SuggestionProvider<CommandSender> templateNameSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<name>");
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          Optional<String> typeArgOpt = ctx.optional("type").map(String.class::cast);
          if (companyArgOpt.isEmpty() || typeArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          String typeRaw = typeArgOpt.get().trim();
          if (companyArg.isBlank() || typeRaw.isBlank()) {
            return suggestions;
          }
          Optional<HudTemplateType> typeOpt = HudTemplateType.parse(typeRaw);
          if (typeOpt.isEmpty()) {
            return suggestions;
          }
          StorageProvider provider = providerOpt.get();
          CompanyQueryService query = new CompanyQueryService(provider);
          Optional<Company> companyOpt = query.findCompany(companyArg);
          if (companyOpt.isEmpty()) {
            return suggestions;
          }
          Company company = companyOpt.get();
          if (!canReadCompanyNoCreateIdentity(ctx.sender(), provider, company.id())) {
            return suggestions;
          }
          provider.hudTemplates().listByCompanyAndType(company.id(), typeOpt.get()).stream()
              .map(HudTemplate::name)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(name -> !name.isBlank())
              .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  private List<String> listCompanyCodes(CommandSender sender, String prefix) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    List<String> results = new ArrayList<>();
    if (sender.hasPermission("fetarute.admin")) {
      provider.companies().listAll().stream()
          .map(Company::code)
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(code -> !code.isBlank())
          .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
          .limit(SUGGESTION_LIMIT)
          .forEach(results::add);
      return results;
    }
    if (!(sender instanceof Player player)) {
      return List.of();
    }
    Optional<PlayerIdentity> identityOpt =
        provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
    if (identityOpt.isEmpty()) {
      return List.of();
    }
    provider.companyMembers().listMemberships(identityOpt.get().id()).stream()
        .map(CompanyMember::companyId)
        .distinct()
        .map(provider.companies()::findById)
        .flatMap(Optional::stream)
        .map(Company::code)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(code -> !code.isBlank())
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .limit(SUGGESTION_LIMIT)
        .forEach(results::add);
    return results;
  }

  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  private String normalizePrefix(org.incendo.cloud.context.CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  private record TemplateBookContext(
      String type, String companyCode, String name, Optional<Long> definedAtEpochMilli) {}
}
