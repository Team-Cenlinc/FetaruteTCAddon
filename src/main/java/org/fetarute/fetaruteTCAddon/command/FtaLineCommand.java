package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplate;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudLineBindingRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 线路管理命令入口：/fta line ...
 *
 * <p>Line 用于承载一个运营商下的“线路主数据”（线路名、类型、颜色、发车基准等），Route 则挂载在 Line 下描述具体运行图。
 *
 * <p>本命令与公司/运营商命令保持一致的权限模型：
 *
 * <ul>
 *   <li>管理员（fetarute.admin）可读写所有公司数据
 *   <li>公司成员可读公司数据
 *   <li>Owner/Manager 可写（create/set/delete）
 * </ul>
 */
public final class FtaLineCommand {

  private final FetaruteTCAddon plugin;
  private static final int SUGGESTION_LIMIT = 20;

  public FtaLineCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /**
   * 注册 /fta line 子命令。
   *
   * <p>当前包含：
   *
   * <ul>
   *   <li>create：创建线路（code 在 operator 范围内唯一）
   *   <li>list：列出线路（带 hover 详情）
   *   <li>info：查看线路详情
   *   <li>set：更新线路字段（不允许改 code）
   *   <li>delete：删除线路（需要 --confirm）
   * </ul>
   */
  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> operatorSuggestions = operatorSuggestions();
    SuggestionProvider<CommandSender> lineSuggestions = lineSuggestions();
    SuggestionProvider<CommandSender> templateTypeSuggestions =
        CommandSuggestionProviders.enumValues(HudTemplateType.class, "<type>");
    SuggestionProvider<CommandSender> templateNameSuggestions = templateNameSuggestions();

    SuggestionProvider<CommandSender> codeSuggestions = placeholderSuggestion("<code>");
    SuggestionProvider<CommandSender> nameSuggestions = placeholderSuggestion("\"<name>\"");
    SuggestionProvider<CommandSender> secondarySuggestions =
        placeholderSuggestion("\"<secondaryName>\"");
    SuggestionProvider<CommandSender> colorValueSuggestions =
        CommandSuggestionProviders.placeholder("<#RRGGBB>");
    SuggestionProvider<CommandSender> serviceValueSuggestions =
        CommandSuggestionProviders.enumValues(LineServiceType.class, "<service>");
    SuggestionProvider<CommandSender> statusValueSuggestions =
        CommandSuggestionProviders.enumValues(LineStatus.class, "<status>");

    var nameFlag =
        CommandFlag.<CommandSender>builder("name")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "name", StringParser.quotedStringParser())
                    .suggestionProvider(nameSuggestions))
            .build();
    var secondaryFlag =
        CommandFlag.<CommandSender>builder("secondary")
            .withAliases("s")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "secondary", StringParser.quotedStringParser())
                    .suggestionProvider(secondarySuggestions))
            .build();
    var serviceFlag =
        CommandFlag.<CommandSender>builder("service")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "service", StringParser.stringParser())
                    .suggestionProvider(serviceValueSuggestions))
            .build();
    var colorFlag =
        CommandFlag.<CommandSender>builder("color")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "color", StringParser.stringParser())
                    .suggestionProvider(colorValueSuggestions))
            .build();
    var statusFlag =
        CommandFlag.<CommandSender>builder("status")
            .withComponent(
                CommandComponent.<CommandSender, String>builder(
                        "status", StringParser.stringParser())
                    .suggestionProvider(statusValueSuggestions))
            .build();
    var freqBaselineFlag =
        CommandFlag.<CommandSender>builder("freqBaseline")
            .withAliases("f")
            .withComponent(
                CommandComponent.<CommandSender, Integer>builder(
                        "freqBaseline", IntegerParser.integerParser())
                    .suggestionProvider(CommandSuggestionProviders.placeholder("<seconds>")))
            .build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("create")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("code", StringParser.stringParser(), codeSuggestions)
            .required("name", StringParser.quotedStringParser(), nameSuggestions)
            .optional("secondaryName", StringParser.quotedStringParser(), secondarySuggestions)
            .flag(serviceFlag)
            .flag(colorFlag)
            .flag(statusFlag)
            .flag(freqBaselineFlag)
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

                  // 解析 company/operator，并按“Owner/Manager 或管理员”校验写入权限。
                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String code = ((String) ctx.get("code")).trim();
                  String name = ((String) ctx.get("name")).trim();
                  String secondaryName =
                      ctx.optional("secondaryName").map(String.class::cast).orElse(null);

                  // 线路 code 在 operator 范围内必须唯一：提前检查以返回更友好的错误提示。
                  if (provider.lines().findByOperatorAndCode(operator.id(), code).isPresent()) {
                    sender.sendMessage(
                        locale.component("command.line.create.exists", Map.of("code", code)));
                    return;
                  }

                  // 枚举字段用字符串 flag 输入：此处显式校验并给出字段级别报错，避免 silent fallback。
                  var flags = ctx.flags();
                  String serviceRaw = flags.getValue(serviceFlag, LineServiceType.METRO.name());
                  Optional<LineServiceType> serviceOpt =
                      parseEnum(LineServiceType.class, serviceRaw, LineServiceType.METRO);
                  if (serviceOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.line.enum-invalid",
                            Map.of("field", "service", "value", String.valueOf(serviceRaw))));
                    return;
                  }
                  String statusRaw = flags.getValue(statusFlag, LineStatus.ACTIVE.name());
                  Optional<LineStatus> statusOpt =
                      parseEnum(LineStatus.class, statusRaw, LineStatus.ACTIVE);
                  if (statusOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.line.enum-invalid",
                            Map.of("field", "status", "value", String.valueOf(statusRaw))));
                    return;
                  }
                  String color = flags.getValue(colorFlag, null);
                  Integer freqBaseline = flags.getValue(freqBaselineFlag, null);

                  Instant now = Instant.now();
                  Line line =
                      new Line(
                          UUID.randomUUID(),
                          code,
                          operator.id(),
                          name,
                          Optional.ofNullable(secondaryName),
                          serviceOpt.get(),
                          Optional.ofNullable(color).map(String::trim).filter(s -> !s.isBlank()),
                          statusOpt.get(),
                          Optional.ofNullable(freqBaseline),
                          Map.of(),
                          now,
                          now);
                  provider.lines().save(line);
                  sender.sendMessage(
                      locale.component(
                          "command.line.create.success",
                          Map.of("code", line.code(), "operator", operator.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  sender.sendMessage(
                      locale.component(
                          "command.line.list.header",
                          Map.of("company", company.code(), "operator", operator.code())));
                  // list 读取仅依赖 operatorId，避免多余 join；hover 用于补充展示字段。
                  List<Line> lines = provider.lines().listByOperator(operator.id());
                  if (lines.isEmpty()) {
                    sender.sendMessage(locale.component("command.line.list.empty"));
                    return;
                  }
                  for (Line line : lines) {
                    String secondary = line.secondaryName().filter(s -> !s.isBlank()).orElse("-");
                    String color = line.color().filter(s -> !s.isBlank()).orElse("-");
                    String freqBaseline =
                        line.spawnFreqBaselineSec().map(String::valueOf).orElse("-");
                    sender.sendMessage(
                        locale
                            .component(
                                "command.line.list.entry",
                                Map.of(
                                    "code",
                                    line.code(),
                                    "name",
                                    line.name(),
                                    "status",
                                    line.status().name()))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locale.component(
                                        "command.line.list.hover-entry",
                                        Map.of(
                                            "code",
                                            line.code(),
                                            "name",
                                            line.name(),
                                            "secondary",
                                            secondary,
                                            "service",
                                            locale.enumText(
                                                "enum.line-service-type", line.serviceType()),
                                            "color",
                                            color,
                                            "status",
                                            line.status().name(),
                                            "freq_baseline",
                                            freqBaseline)))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("info")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  sender.sendMessage(
                      locale.component(
                          "command.line.info.header",
                          Map.of(
                              "operator",
                              operator.code(),
                              "code",
                              line.code(),
                              "name",
                              line.name())));
                  sender.sendMessage(
                      locale.component(
                          "command.line.info.service",
                          Map.of(
                              "service",
                              locale.enumText("enum.line-service-type", line.serviceType()))));
                  sender.sendMessage(
                      locale.component(
                          "command.line.info.status", Map.of("status", line.status().name())));
                  sender.sendMessage(
                      locale.component(
                          "command.line.info.color",
                          Map.of("color", line.color().filter(s -> !s.isBlank()).orElse("-"))));
                  sender.sendMessage(
                      locale.component(
                          "command.line.info.spawn",
                          Map.of(
                              "freq_baseline",
                              line.spawnFreqBaselineSec().map(String::valueOf).orElse("-"))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("set")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .flag(nameFlag)
            .flag(secondaryFlag)
            .flag(serviceFlag)
            .flag(colorFlag)
            .flag(statusFlag)
            .flag(freqBaselineFlag)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  var flags = ctx.flags();
                  // 无参数的 set 非常常见（误触/复制），这里显式提示避免“看起来成功但没变更”的误解。
                  boolean any =
                      flags.hasFlag(nameFlag)
                          || flags.hasFlag(secondaryFlag)
                          || flags.hasFlag(serviceFlag)
                          || flags.hasFlag(colorFlag)
                          || flags.hasFlag(statusFlag)
                          || flags.hasFlag(freqBaselineFlag);
                  if (!any) {
                    sender.sendMessage(locale.component("command.line.set.noop"));
                    return;
                  }

                  String name = flags.getValue(nameFlag, line.name());
                  if (name != null) {
                    name = name.trim();
                  }
                  if (name == null || name.isBlank()) {
                    name = line.name();
                  }
                  String secondary =
                      flags.getValue(secondaryFlag, line.secondaryName().orElse(null));
                  if (secondary != null) {
                    secondary = secondary.trim();
                  }
                  String serviceRaw = flags.getValue(serviceFlag, line.serviceType().name());
                  Optional<LineServiceType> serviceOpt =
                      parseEnum(LineServiceType.class, serviceRaw, line.serviceType());
                  if (serviceOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.line.enum-invalid",
                            Map.of("field", "service", "value", String.valueOf(serviceRaw))));
                    return;
                  }
                  String statusRaw = flags.getValue(statusFlag, line.status().name());
                  Optional<LineStatus> statusOpt =
                      parseEnum(LineStatus.class, statusRaw, line.status());
                  if (statusOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.line.enum-invalid",
                            Map.of("field", "status", "value", String.valueOf(statusRaw))));
                    return;
                  }
                  String color = flags.getValue(colorFlag, line.color().orElse(null));
                  if (color != null) {
                    color = color.trim();
                  }
                  Integer freqBaseline =
                      flags.getValue(freqBaselineFlag, line.spawnFreqBaselineSec().orElse(null));

                  Line updated =
                      new Line(
                          line.id(),
                          line.code(),
                          line.operatorId(),
                          name,
                          Optional.ofNullable(secondary),
                          serviceOpt.get(),
                          Optional.ofNullable(color).filter(s -> !s.isBlank()),
                          statusOpt.get(),
                          Optional.ofNullable(freqBaseline),
                          line.metadata(),
                          line.createdAt(),
                          Instant.now());
                  provider.lines().save(updated);
                  sender.sendMessage(
                      locale.component("command.line.set.success", Map.of("code", line.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("delete")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .flag(confirmFlag)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  if (!ctx.flags().isPresent(confirmFlag)) {
                    sender.sendMessage(
                        plugin.getLocaleManager().component("command.common.confirm-required"));
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  CompanyQueryService query = new CompanyQueryService(provider);

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  // 线路删除属于危险操作，命令层已要求 --confirm；数据库侧会 cascade 清理下游（如 routes）。
                  provider.lines().delete(line.id());
                  sender.sendMessage(
                      locale.component("command.line.delete.success", Map.of("code", line.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("hud")
            .literal("set")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("type", StringParser.stringParser(), templateTypeSuggestions)
            .required("template", StringParser.stringParser(), templateNameSuggestions)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  String typeRaw = ((String) ctx.get("type")).trim();
                  Optional<HudTemplateType> typeOpt = HudTemplateType.parse(typeRaw);
                  if (typeOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.hud.invalid-type", Map.of("type", typeRaw)));
                    return;
                  }
                  HudTemplateType type = typeOpt.get();
                  String templateName = ((String) ctx.get("template")).trim();
                  Optional<HudTemplate> templateOpt =
                      provider
                          .hudTemplates()
                          .findByCompanyAndName(company.id(), type, templateName);
                  if (templateOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.line.hud.template-not-found",
                            Map.of("type", type.name(), "name", templateName)));
                    return;
                  }
                  HudTemplate template = templateOpt.get();
                  provider
                      .hudLineBindings()
                      .save(
                          new HudLineBindingRepository.LineBinding(
                              line.id(), type, template.id(), java.time.Instant.now()));
                  reloadTemplateCache();
                  sender.sendMessage(
                      locale.component(
                          "command.line.hud.set.success",
                          Map.of(
                              "line", line.code(), "type", type.name(), "name", template.name())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("hud")
            .literal("clear")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
            .required("type", StringParser.stringParser(), templateTypeSuggestions)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  String typeRaw = ((String) ctx.get("type")).trim();
                  Optional<HudTemplateType> typeOpt = HudTemplateType.parse(typeRaw);
                  if (typeOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.hud.invalid-type", Map.of("type", typeRaw)));
                    return;
                  }
                  HudTemplateType type = typeOpt.get();
                  provider.hudLineBindings().delete(line.id(), type);
                  reloadTemplateCache();
                  sender.sendMessage(
                      locale.component(
                          "command.line.hud.clear.success",
                          Map.of("line", line.code(), "type", type.name())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("line")
            .literal("hud")
            .literal("show")
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("operator", StringParser.stringParser(), operatorSuggestions)
            .required("line", StringParser.stringParser(), lineSuggestions)
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

                  Optional<Company> companyOpt =
                      query.findCompany(((String) ctx.get("company")).trim());
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found",
                            Map.of("company", ((String) ctx.get("company")).trim())));
                    return;
                  }
                  Company company = companyOpt.get();
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }
                  Optional<Operator> operatorOpt =
                      query.findOperator(company.id(), ((String) ctx.get("operator")).trim());
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found",
                            Map.of("operator", ((String) ctx.get("operator")).trim())));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  String lineArg = ((String) ctx.get("line")).trim();
                  Optional<Line> lineOpt = query.findLine(operator.id(), lineArg);
                  if (lineOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.line.not-found", Map.of("line", lineArg)));
                    return;
                  }
                  Line line = lineOpt.get();

                  List<HudLineBindingRepository.LineBinding> bindings =
                      provider.hudLineBindings().listByLine(line.id());
                  sender.sendMessage(
                      locale.component(
                          "command.line.hud.show.header",
                          Map.of("line", line.code(), "count", String.valueOf(bindings.size()))));
                  if (bindings.isEmpty()) {
                    sender.sendMessage(locale.component("command.line.hud.show.empty"));
                    return;
                  }
                  for (HudLineBindingRepository.LineBinding binding : bindings) {
                    String name =
                        provider
                            .hudTemplates()
                            .findById(binding.templateId())
                            .map(HudTemplate::name)
                            .orElse("-");
                    sender.sendMessage(
                        locale.component(
                            "command.line.hud.show.entry",
                            Map.of("type", binding.type().name(), "name", name)));
                  }
                }));
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

  /**
   * company 参数补全：按权限过滤可见范围，避免泄露其他公司的 code。
   *
   * <p>空输入时会附带一个占位符，提示用户参数形态。
   */
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

  /**
   * operator 参数补全：依赖 company 参数，并按权限过滤。
   *
   * <p>注意：补全阶段不创建 PlayerIdentity，避免“仅按 TAB 就写入数据库”的副作用。
   */
  private SuggestionProvider<CommandSender> operatorSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<operator>");
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          if (companyArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          if (companyArg.isBlank()) {
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
          provider.operators().listByCompany(company.id()).stream()
              .map(Operator::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  /**
   * line 参数补全：依赖 company/operator 参数，并按权限过滤。
   *
   * <p>返回的 code 需满足前缀匹配，且数量限制在 {@link #SUGGESTION_LIMIT} 以内。
   */
  private SuggestionProvider<CommandSender> lineSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<line>");
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          Optional<String> companyArgOpt = ctx.optional("company").map(String.class::cast);
          Optional<String> operatorArgOpt = ctx.optional("operator").map(String.class::cast);
          if (companyArgOpt.isEmpty() || operatorArgOpt.isEmpty()) {
            return suggestions;
          }
          String companyArg = companyArgOpt.get().trim();
          String operatorArg = operatorArgOpt.get().trim();
          if (companyArg.isBlank() || operatorArg.isBlank()) {
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
          Optional<Operator> operatorOpt = query.findOperator(company.id(), operatorArg);
          if (operatorOpt.isEmpty()) {
            return suggestions;
          }
          provider.lines().listByOperator(operatorOpt.get().id()).stream()
              .map(Line::code)
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(code -> !code.isBlank())
              .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
              .distinct()
              .limit(SUGGESTION_LIMIT)
              .forEach(suggestions::add);
          return suggestions;
        });
  }

  /**
   * template 名称补全：依赖 company/type 参数，并按权限过滤。
   *
   * <p>仅返回与输入前缀匹配的模板名称。
   */
  private SuggestionProvider<CommandSender> templateNameSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<template>");
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

  private void reloadTemplateCache() {
    if (plugin.getHudTemplateService() != null) {
      plugin.getHudTemplateService().reload();
    }
  }

  /** 将 Cloud 的输入 token 规范化为小写前缀，用于前缀过滤。 */
  private static String normalizePrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 判断 sender 是否可读取公司，但不创建身份。
   *
   * <p>用于 Tab 补全阶段：若身份尚未建立则返回 false，避免补全触发写操作。
   */
  private boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId);
  }

  /** 列出 sender 可见的公司 code 列表，供补全使用。 */
  private List<String> listCompanyCodes(CommandSender sender, String prefix) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      return List.of();
    }
    StorageProvider provider = providerOpt.get();
    Stream<Company> companies;
    if (sender.hasPermission("fetarute.admin")) {
      companies = provider.companies().listAll().stream();
    } else if (sender instanceof Player player) {
      Optional<PlayerIdentity> identityOpt =
          provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
      if (identityOpt.isEmpty()) {
        return List.of();
      }
      List<CompanyMember> memberships =
          provider.companyMembers().listMemberships(identityOpt.get().id());
      if (memberships.isEmpty()) {
        return List.of();
      }
      companies =
          memberships.stream()
              .map(CompanyMember::companyId)
              .distinct()
              .map(provider.companies()::findById)
              .flatMap(Optional::stream);
    } else {
      return List.of();
    }
    return companies
        .map(Company::code)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(code -> !code.isBlank())
        .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .limit(SUGGESTION_LIMIT)
        .toList();
  }

  /**
   * 将字符串解析为枚举；允许传入默认值。
   *
   * <p>命令层显式校验枚举的原因：
   *
   * <ul>
   *   <li>避免 silent fallback 造成“看似成功但写入了默认值”的误解
   *   <li>错误提示能定位到具体字段（service/status 等）
   * </ul>
   */
  private <T extends Enum<T>> Optional<T> parseEnum(
      Class<T> enumClass, String raw, T defaultValue) {
    if (raw == null) {
      return Optional.ofNullable(defaultValue);
    }
    String token = raw.trim();
    if (token.isEmpty()) {
      return Optional.ofNullable(defaultValue);
    }
    try {
      return Optional.of(Enum.valueOf(enumClass, token.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
