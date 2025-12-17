package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.api.PlayerIdentityService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

/** 运营商管理命令：/fta operator ... */
public final class FtaOperatorCommand {

  private final FetaruteTCAddon plugin;

  public FtaOperatorCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    var nameFlag =
        CommandFlag.builder("name").withComponent(StringParser.quotedStringParser()).build();
    var secondaryFlag =
        CommandFlag.builder("secondary")
            .withAliases("secondaryName")
            .withComponent(StringParser.quotedStringParser())
            .build();
    var colorFlag = CommandFlag.builder("color").withComponent(StringParser.stringParser()).build();
    var priorityFlag =
        CommandFlag.builder("priority").withComponent(IntegerParser.integerParser()).build();
    var descFlag =
        CommandFlag.builder("desc")
            .withAliases("description")
            .withComponent(StringParser.quotedStringParser())
            .build();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("operator")
            .literal("create")
            .senderType(Player.class)
            .required("company", StringParser.stringParser())
            .required("code", StringParser.stringParser())
            .required("name", StringParser.quotedStringParser())
            .optional("secondaryName", StringParser.quotedStringParser())
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

                  String code = ((String) ctx.get("code")).trim();
                  String name = ((String) ctx.get("name")).trim();
                  String secondaryName =
                      ctx.optional("secondaryName").map(String.class::cast).orElse(null);

                  if (provider.operators().findByCompanyAndCode(company.id(), code).isPresent()) {
                    sender.sendMessage(
                        locale.component("command.operator.create.exists", Map.of("code", code)));
                    return;
                  }

                  Instant now = Instant.now();
                  Operator operator =
                      new Operator(
                          UUID.randomUUID(),
                          code,
                          company.id(),
                          name,
                          Optional.ofNullable(secondaryName),
                          Optional.empty(),
                          0,
                          Optional.empty(),
                          Map.of(),
                          now,
                          now);
                  provider.operators().save(operator);
                  sender.sendMessage(
                      locale.component(
                          "command.operator.create.success",
                          Map.of("code", operator.code(), "company", company.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("operator")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.stringParser())
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
                  if (!canReadCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  sender.sendMessage(
                      locale.component(
                          "command.operator.list.header", Map.of("company", company.code())));
                  var operators = provider.operators().listByCompany(company.id());
                  if (operators.isEmpty()) {
                    sender.sendMessage(locale.component("command.operator.list.empty"));
                    return;
                  }
                  for (Operator operator : operators) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.list.entry",
                            Map.of(
                                "code",
                                operator.code(),
                                "name",
                                operator.name(),
                                "priority",
                                String.valueOf(operator.priority()))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("operator")
            .literal("set")
            .senderType(Player.class)
            .required("company", StringParser.stringParser())
            .required("operator", StringParser.stringParser())
            .flag(nameFlag)
            .flag(secondaryFlag)
            .flag(colorFlag)
            .flag(priorityFlag)
            .flag(descFlag)
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

                  String operatorArg = ((String) ctx.get("operator")).trim();
                  Optional<Operator> operatorOpt =
                      tryFindOperator(provider, company.id(), operatorArg);
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found", Map.of("operator", operatorArg)));
                    return;
                  }
                  Operator operator = operatorOpt.get();

                  var flags = ctx.flags();

                  boolean any =
                      flags.hasFlag(nameFlag)
                          || flags.hasFlag(secondaryFlag)
                          || flags.hasFlag(colorFlag)
                          || flags.hasFlag(priorityFlag)
                          || flags.hasFlag(descFlag);
                  if (!any) {
                    sender.sendMessage(locale.component("command.operator.set.noop"));
                    return;
                  }

                  String name = flags.getValue(nameFlag, operator.name());
                  if (name != null) {
                    name = name.trim();
                  }
                  if (name == null || name.isBlank()) {
                    name = operator.name();
                  }
                  String secondary =
                      flags.getValue(secondaryFlag, operator.secondaryName().orElse(null));
                  if (secondary != null) {
                    secondary = secondary.trim();
                  }
                  String color = flags.getValue(colorFlag, operator.colorTheme().orElse(null));
                  if (color != null) {
                    color = color.trim();
                  }
                  Integer priorityRaw = flags.getValue(priorityFlag, operator.priority());
                  int priority = priorityRaw == null ? operator.priority() : priorityRaw;
                  String desc = flags.getValue(descFlag, operator.description().orElse(null));
                  if (desc != null) {
                    desc = desc.trim();
                  }

                  Operator updated =
                      new Operator(
                          operator.id(),
                          operator.code(),
                          operator.companyId(),
                          name,
                          Optional.ofNullable(secondary),
                          Optional.ofNullable(color),
                          priority,
                          Optional.ofNullable(desc),
                          operator.metadata(),
                          operator.createdAt(),
                          Instant.now());
                  provider.operators().save(updated);
                  sender.sendMessage(
                      locale.component(
                          "command.operator.set.success", Map.of("code", operator.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("operator")
            .literal("delete")
            .senderType(Player.class)
            .required("company", StringParser.stringParser())
            .required("operator", StringParser.stringParser())
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

                  String operatorArg = ((String) ctx.get("operator")).trim();
                  Optional<Operator> operatorOpt =
                      tryFindOperator(provider, company.id(), operatorArg);
                  if (operatorOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.operator.not-found", Map.of("operator", operatorArg)));
                    return;
                  }
                  Operator operator = operatorOpt.get();
                  provider.operators().delete(operator.id());
                  sender.sendMessage(
                      locale.component(
                          "command.operator.delete.success", Map.of("code", operator.code())));
                }));
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      sender.sendMessage(locale.component("error.storage-unavailable"));
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }

  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    PlayerIdentity identity =
        new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    return provider.companyMembers().findMembership(companyId, identity.id()).isPresent();
  }

  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    PlayerIdentity identity =
        new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    Optional<CompanyMember> membership =
        provider.companyMembers().findMembership(companyId, identity.id());
    if (membership.isEmpty()) {
      return false;
    }
    Set<MemberRole> roles = membership.get().roles();
    return roles.contains(MemberRole.OWNER) || roles.contains(MemberRole.MANAGER);
  }

  private Optional<Operator> tryFindOperator(
      StorageProvider provider, UUID companyId, String codeOrId) {
    return new CompanyQueryService(provider).findOperator(companyId, codeOrId);
  }
}
