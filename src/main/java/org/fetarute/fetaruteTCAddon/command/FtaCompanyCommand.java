package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.CompanyQueryService;
import org.fetarute.fetaruteTCAddon.company.api.PlayerIdentityService;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 公司管理命令入口：/fta company ...
 *
 * <p>覆盖 create/list/info/rename/transfer/delete 以及 member/admin 子命令，用于日常公司与成员管理。
 */
public final class FtaCompanyCommand {

  private final FetaruteTCAddon plugin;
  private static final int SUGGESTION_LIMIT = 20;

  public FtaCompanyCommand(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  public void register(CommandManager<CommandSender> manager) {
    CommandFlag<Void> confirmFlag = CommandFlag.builder("confirm").build();
    SuggestionProvider<CommandSender> companySuggestions = companySuggestions();
    SuggestionProvider<CommandSender> codeSuggestions = placeholderSuggestion("<code>");
    SuggestionProvider<CommandSender> nameSuggestions = placeholderSuggestion("\"<name>\"");
    SuggestionProvider<CommandSender> secondarySuggestions =
        placeholderSuggestion("\"<secondaryName>\"");
    SuggestionProvider<CommandSender> playerSuggestions = placeholderSuggestion("<player>");
    SuggestionProvider<CommandSender> roleSuggestions = roleSuggestions();

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("create")
            .permission("fetarute.company.create")
            .senderType(Player.class)
            .required("code", StringParser.stringParser(), codeSuggestions)
            .required("name", StringParser.quotedStringParser(), nameSuggestions)
            .optional("secondaryName", StringParser.quotedStringParser(), secondarySuggestions)
            .handler(
                ctx -> {
                  Player sender = (Player) ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());

                  String code = ((String) ctx.get("code")).trim();
                  String name = ((String) ctx.get("name")).trim();
                  String secondaryName =
                      ctx.optional("secondaryName").map(String.class::cast).orElse(null);

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            // 事务内校验 code 唯一并创建公司与默认 Owner 成员记录。
                            if (provider.companies().findByCode(code).isPresent()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.create.exists", Map.of("code", code)));
                              return null;
                            }

                            PlayerIdentity identity = identityService.requireIdentity(sender);
                            Instant now = Instant.now();
                            Company company =
                                new Company(
                                    UUID.randomUUID(),
                                    code,
                                    name,
                                    Optional.ofNullable(secondaryName),
                                    identity.id(),
                                    CompanyStatus.ACTIVE,
                                    0L,
                                    Map.of(),
                                    now,
                                    now);
                            provider.companies().save(company);
                            provider
                                .companyMembers()
                                .save(
                                    new CompanyMember(
                                        company.id(),
                                        identity.id(),
                                        EnumSet.of(MemberRole.OWNER),
                                        now,
                                        Optional.empty()));
                            sender.sendMessage(
                                locale.component(
                                    "command.company.create.success", Map.of("code", code)));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("list")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
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
                          "command.company.member.list.header", Map.of("code", company.code())));
                  // 读取成员列表并回填玩家显示名用于展示。
                  List<CompanyMember> members = provider.companyMembers().listMembers(company.id());
                  if (members.isEmpty()) {
                    sender.sendMessage(locale.component("command.company.member.list.empty"));
                    return;
                  }
                  for (CompanyMember member : members) {
                    String roles =
                        String.join(",", member.roles().stream().map(Enum::name).toList());
                    String name =
                        provider
                            .playerIdentities()
                            .findById(member.playerIdentityId())
                            .map(PlayerIdentity::name)
                            .orElse(member.playerIdentityId().toString());
                    sender.sendMessage(
                        locale
                            .component(
                                "command.company.member.list.entry",
                                Map.of("player", name, "roles", roles))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locale.component(
                                        "command.company.member.list.hover-entry",
                                        Map.of("player", name, "roles", roles)))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("invite")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("player", StringParser.stringParser(), playerSuggestions)
            .optional("roles", StringParser.greedyStringParser(), roleSuggestions)
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

                  String playerName = ((String) ctx.get("player")).trim();
                  OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                  String rolesRaw = ctx.optional("roles").map(String.class::cast).orElse("");
                  Optional<EnumSet<MemberRole>> rolesOpt =
                      parseRoles(locale, sender, rolesRaw, EnumSet.of(MemberRole.STAFF));
                  if (rolesOpt.isEmpty()) {
                    return;
                  }
                  EnumSet<MemberRole> roles = rolesOpt.get();

                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());
                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            // 事务内创建/获取身份并合并既有角色，保证成员记录一致性。
                            PlayerIdentity identity = identityService.getOrCreate(target);
                            Optional<CompanyMember> existing =
                                provider
                                    .companyMembers()
                                    .findMembership(company.id(), identity.id());
                            Instant now = Instant.now();
                            if (existing.isPresent()) {
                              CompanyMember member = existing.get();
                              EnumSet<MemberRole> merged = EnumSet.copyOf(member.roles());
                              merged.addAll(roles);
                              provider
                                  .companyMembers()
                                  .save(
                                      new CompanyMember(
                                          company.id(),
                                          identity.id(),
                                          merged,
                                          member.joinedAt(),
                                          member.permissions()));
                            } else {
                              provider
                                  .companyMembers()
                                  .save(
                                      new CompanyMember(
                                          company.id(),
                                          identity.id(),
                                          roles,
                                          now,
                                          Optional.empty()));
                            }
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.invite.success",
                                    Map.of(
                                        "code", company.code(),
                                        "player", identity.name(),
                                        "roles",
                                            String.join(
                                                ",", roles.stream().map(Enum::name).toList()))));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("setroles")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("player", StringParser.stringParser(), playerSuggestions)
            .required("roles", StringParser.greedyStringParser(), roleSuggestions)
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

                  String playerName = ((String) ctx.get("player")).trim();
                  OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                  String rolesRaw = ((String) ctx.get("roles")).trim();
                  Optional<EnumSet<MemberRole>> rolesOpt =
                      parseRoles(locale, sender, rolesRaw, EnumSet.noneOf(MemberRole.class));
                  if (rolesOpt.isEmpty()) {
                    return;
                  }
                  EnumSet<MemberRole> roles = rolesOpt.get();
                  if (roles.isEmpty()) {
                    sender.sendMessage(locale.component("command.company.member.roles-empty"));
                    return;
                  }

                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());
                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            // 事务内覆盖角色列表，但保留加入时间与权限字段。
                            PlayerIdentity identity = identityService.getOrCreate(target);
                            Optional<CompanyMember> existing =
                                provider
                                    .companyMembers()
                                    .findMembership(company.id(), identity.id());
                            if (existing.isEmpty()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.not-found",
                                      Map.of("player", identity.name())));
                              return null;
                            }
                            CompanyMember member = existing.get();
                            provider
                                .companyMembers()
                                .save(
                                    new CompanyMember(
                                        company.id(),
                                        identity.id(),
                                        roles,
                                        member.joinedAt(),
                                        member.permissions()));
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.setroles.success",
                                    Map.of(
                                        "code",
                                        company.code(),
                                        "player",
                                        identity.name(),
                                        "roles",
                                        String.join(
                                            ",", roles.stream().map(Enum::name).toList()))));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("remove")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("player", StringParser.stringParser(), playerSuggestions)
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

                  String playerName = ((String) ctx.get("player")).trim();
                  OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            PlayerIdentity identity = identityService.getOrCreate(target);
                            // 非本人移除需具备管理权限；本人可自行退出。
                            boolean self = identity.playerUuid().equals(sender.getUniqueId());
                            if (!self && !canManageCompany(sender, provider, company.id())) {
                              sender.sendMessage(locale.component("error.no-permission"));
                              return null;
                            }

                            Optional<CompanyMember> existing =
                                provider
                                    .companyMembers()
                                    .findMembership(company.id(), identity.id());
                            if (existing.isEmpty()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.not-found",
                                      Map.of("player", identity.name())));
                              return null;
                            }
                            if (existing.get().roles().contains(MemberRole.OWNER)
                                && !sender.hasPermission("fetarute.admin")) {
                              // 非管理员禁止移除 Owner，避免公司失去所有者。
                              sender.sendMessage(
                                  locale.component("command.company.member.remove.owner"));
                              return null;
                            }
                            provider.companyMembers().delete(company.id(), identity.id());
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.remove.success",
                                    Map.of("code", company.code(), "player", identity.name())));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("list")
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
                  PlayerIdentity identity =
                      new PlayerIdentityService(provider.playerIdentities())
                          .requireIdentity(sender);

                  List<CompanyMember> memberships =
                      provider.companyMembers().listMemberships(identity.id());
                  sender.sendMessage(locale.component("command.company.list.header"));
                  // 基于玩家成员关系汇总公司列表。
                  if (memberships.isEmpty()) {
                    sender.sendMessage(locale.component("command.company.list.empty"));
                    return;
                  }
                  for (CompanyMember membership : memberships) {
                    Optional<Company> companyOpt =
                        provider.companies().findById(membership.companyId());
                    if (companyOpt.isEmpty()) {
                      continue;
                    }
                    Company company = companyOpt.get();
                    String roles =
                        String.join(",", membership.roles().stream().map(Enum::name).toList());
                    String status = company.status().name();
                    sender.sendMessage(
                        locale
                            .component(
                                "command.company.list.entry",
                                Map.of(
                                    "code", company.code(), "name", company.name(), "roles", roles))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locale.component(
                                        "command.company.list.hover-entry",
                                        Map.of(
                                            "code",
                                            company.code(),
                                            "name",
                                            company.name(),
                                            "roles",
                                            roles,
                                            "status",
                                            status)))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("info")
            .required("company", StringParser.stringParser(), companySuggestions)
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

                  Optional<PlayerIdentity> owner =
                      provider.playerIdentities().findById(company.ownerIdentityId());
                  // 统计成员与运营商数量，便于一次性展示公司信息。
                  int memberCount = provider.companyMembers().listMembers(company.id()).size();
                  int operatorCount = provider.operators().listByCompany(company.id()).size();

                  sender.sendMessage(
                      locale.component(
                          "command.company.info.header",
                          Map.of("code", company.code(), "name", company.name())));
                  sender.sendMessage(
                      locale.component(
                          "command.company.info.status",
                          Map.of("status", company.status().name())));
                  sender.sendMessage(
                      locale.component(
                          "command.company.info.owner",
                          Map.of("owner", owner.map(PlayerIdentity::name).orElse("unknown"))));
                  sender.sendMessage(
                      locale.component(
                          "command.company.info.members",
                          Map.of("count", String.valueOf(memberCount))));
                  sender.sendMessage(
                      locale.component(
                          "command.company.info.operators",
                          Map.of("count", String.valueOf(operatorCount))));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("rename")
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("name", StringParser.quotedStringParser(), nameSuggestions)
            .optional("secondaryName", StringParser.quotedStringParser(), secondarySuggestions)
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
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  String name = ((String) ctx.get("name")).trim();
                  String secondaryName =
                      ctx.optional("secondaryName").map(String.class::cast).orElse(null);
                  // 仅更新名称与别名字段，其他字段保持不变。
                  Company updated =
                      new Company(
                          company.id(),
                          company.code(),
                          name,
                          Optional.ofNullable(secondaryName),
                          company.ownerIdentityId(),
                          company.status(),
                          company.balanceMinor(),
                          company.metadata(),
                          company.createdAt(),
                          Instant.now());
                  provider.companies().save(updated);
                  sender.sendMessage(
                      locale.component(
                          "command.company.rename.success", Map.of("code", company.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("transfer")
            .required("company", StringParser.stringParser(), companySuggestions)
            .required("player", StringParser.stringParser(), playerSuggestions)
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
                  if (!canManageCompany(sender, provider, company.id())) {
                    sender.sendMessage(locale.component("error.no-permission"));
                    return;
                  }

                  String playerName = ((String) ctx.get("player")).trim();
                  OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            PlayerIdentity newOwner = identityService.getOrCreate(target);
                            // 事务内更新公司 Owner，并同步成员角色（新 Owner 提升、旧 Owner 降级）。
                            if (Objects.equals(company.ownerIdentityId(), newOwner.id())) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.transfer.noop",
                                      Map.of("code", company.code(), "player", newOwner.name())));
                              return null;
                            }

                            Company updated =
                                new Company(
                                    company.id(),
                                    company.code(),
                                    company.name(),
                                    company.secondaryName(),
                                    newOwner.id(),
                                    company.status(),
                                    company.balanceMinor(),
                                    company.metadata(),
                                    company.createdAt(),
                                    Instant.now());
                            provider.companies().save(updated);

                            CompanyMemberRepositoryFacade members =
                                new CompanyMemberRepositoryFacade(provider);
                            members.ensureOwner(company.id(), newOwner.id());
                            members.demoteOwnerToManager(company.id(), company.ownerIdentityId());

                            sender.sendMessage(
                                locale.component(
                                    "command.company.transfer.success",
                                    Map.of("code", company.code(), "player", newOwner.name())));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("delete")
            .flag(confirmFlag)
            .required("company", StringParser.stringParser(), companySuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
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

                  Company updated =
                      new Company(
                          company.id(),
                          company.code(),
                          company.name(),
                          company.secondaryName(),
                          company.ownerIdentityId(),
                          CompanyStatus.DELETED,
                          company.balanceMinor(),
                          company.metadata(),
                          company.createdAt(),
                          Instant.now());
                  // 标记为 DELETED 以支持后续恢复。
                  provider.companies().save(updated);
                  sender.sendMessage(
                      locale.component(
                          "command.company.delete.success", Map.of("code", company.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("admin")
            .literal("list")
            .permission("fetarute.admin")
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
                  Optional<StorageProvider> providerOpt = readyProvider(sender);
                  if (providerOpt.isEmpty()) {
                    return;
                  }
                  StorageProvider provider = providerOpt.get();
                  LocaleManager locale = plugin.getLocaleManager();
                  sender.sendMessage(locale.component("command.company.admin.list.header"));
                  // 管理员查看所有公司与状态概览。
                  for (Company company : provider.companies().listAll()) {
                    String status = company.status().name();
                    sender.sendMessage(
                        locale
                            .component(
                                "command.company.admin.list.entry",
                                Map.of(
                                    "code",
                                    company.code(),
                                    "name",
                                    company.name(),
                                    "status",
                                    status))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locale.component(
                                        "command.company.admin.list.hover-entry",
                                        Map.of(
                                            "code",
                                            company.code(),
                                            "name",
                                            company.name(),
                                            "status",
                                            status)))));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("admin")
            .literal("restore")
            .permission("fetarute.admin")
            .required("company", StringParser.stringParser(), companySuggestions)
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
                  if (company.status() != CompanyStatus.DELETED) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.admin.restore.not-deleted",
                            Map.of("code", company.code(), "status", company.status().name())));
                    return;
                  }

                  // 从 DELETED 状态恢复为 ACTIVE。
                  Company updated =
                      new Company(
                          company.id(),
                          company.code(),
                          company.name(),
                          company.secondaryName(),
                          company.ownerIdentityId(),
                          CompanyStatus.ACTIVE,
                          company.balanceMinor(),
                          company.metadata(),
                          company.createdAt(),
                          Instant.now());
                  provider.companies().save(updated);
                  sender.sendMessage(
                      locale.component(
                          "command.company.admin.restore.success", Map.of("code", company.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("admin")
            .literal("purge")
            .permission("fetarute.admin")
            .flag(confirmFlag)
            .required("company", StringParser.stringParser(), companySuggestions)
            .handler(
                ctx -> {
                  CommandSender sender = ctx.sender();
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
                  // 永久删除公司记录，不可恢复。
                  provider.companies().delete(company.id());
                  sender.sendMessage(
                      locale.component(
                          "command.company.admin.purge.success", Map.of("code", company.code())));
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("admin")
            .literal("takeover")
            .permission("fetarute.admin")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), companySuggestions)
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
                  PlayerIdentityService identityService =
                      new PlayerIdentityService(provider.playerIdentities());

                  String companyArg = ((String) ctx.get("company")).trim();
                  Optional<Company> companyOpt = query.findCompany(companyArg);
                  if (companyOpt.isEmpty()) {
                    sender.sendMessage(
                        locale.component(
                            "command.company.info.not-found", Map.of("company", companyArg)));
                    return;
                  }
                  Company company = companyOpt.get();

                  provider
                      .transactionManager()
                      .execute(
                          () -> {
                            PlayerIdentity identity = identityService.requireIdentity(sender);
                            // 管理员接管 Owner，并确保成员表中存在 Owner 角色。
                            Company updated =
                                new Company(
                                    company.id(),
                                    company.code(),
                                    company.name(),
                                    company.secondaryName(),
                                    identity.id(),
                                    company.status(),
                                    company.balanceMinor(),
                                    company.metadata(),
                                    company.createdAt(),
                                    Instant.now());
                            provider.companies().save(updated);
                            new CompanyMemberRepositoryFacade(provider)
                                .ensureOwner(company.id(), identity.id());
                            sender.sendMessage(
                                locale.component(
                                    "command.company.admin.takeover.success",
                                    Map.of("code", company.code())));
                            return null;
                          });
                }));
  }

  private Optional<StorageProvider> readyProvider(CommandSender sender) {
    Optional<StorageProvider> providerOpt = providerIfReady();
    if (providerOpt.isEmpty()) {
      sender.sendMessage(plugin.getLocaleManager().component("error.storage-unavailable"));
    }
    return providerOpt;
  }

  private Optional<StorageProvider> providerIfReady() {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
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

  private Optional<EnumSet<MemberRole>> parseRoles(
      LocaleManager locale, CommandSender sender, String raw, EnumSet<MemberRole> defaultRoles) {
    if (raw == null || raw.isBlank()) {
      return Optional.of(defaultRoles);
    }
    String[] parts = raw.replace(',', ' ').trim().split("\\s+");
    EnumSet<MemberRole> roles = EnumSet.noneOf(MemberRole.class);
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        continue;
      }
      try {
        roles.add(MemberRole.valueOf(part.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        sender.sendMessage(
            locale.component("command.company.member.role-invalid", Map.of("role", part)));
        return Optional.empty();
      }
    }
    return Optional.of(roles);
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

  private static SuggestionProvider<CommandSender> roleSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input).toUpperCase(Locale.ROOT);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<roles...>");
          }
          for (MemberRole role : MemberRole.values()) {
            String name = role.name();
            if (prefix.isBlank() || name.startsWith(prefix)) {
              suggestions.add(name);
            }
          }
          return suggestions;
        });
  }

  private static String normalizePrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toLowerCase(Locale.ROOT);
  }

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

  private static final class CompanyMemberRepositoryFacade {

    private final StorageProvider provider;

    private CompanyMemberRepositoryFacade(StorageProvider provider) {
      this.provider = provider;
    }

    private void ensureOwner(UUID companyId, UUID identityId) {
      Instant now = Instant.now();
      Optional<CompanyMember> existing =
          provider.companyMembers().findMembership(companyId, identityId);
      if (existing.isPresent()) {
        CompanyMember member = existing.get();
        if (member.roles().contains(MemberRole.OWNER)) {
          return;
        }
        EnumSet<MemberRole> roles = EnumSet.copyOf(member.roles());
        roles.add(MemberRole.OWNER);
        provider
            .companyMembers()
            .save(
                new CompanyMember(
                    companyId, identityId, roles, member.joinedAt(), member.permissions()));
        return;
      }
      provider
          .companyMembers()
          .save(
              new CompanyMember(
                  companyId, identityId, EnumSet.of(MemberRole.OWNER), now, Optional.empty()));
    }

    private void demoteOwnerToManager(UUID companyId, UUID identityId) {
      Optional<CompanyMember> existing =
          provider.companyMembers().findMembership(companyId, identityId);
      if (existing.isEmpty()) {
        return;
      }
      CompanyMember member = existing.get();
      if (!member.roles().contains(MemberRole.OWNER)) {
        return;
      }
      EnumSet<MemberRole> roles = EnumSet.copyOf(member.roles());
      roles.remove(MemberRole.OWNER);
      roles.add(MemberRole.MANAGER);
      provider
          .companyMembers()
          .save(
              new CompanyMember(
                  companyId, identityId, roles, member.joinedAt(), member.permissions()));
    }
  }
}
