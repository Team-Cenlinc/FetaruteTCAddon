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
import org.fetarute.fetaruteTCAddon.company.model.CompanyMemberInvite;
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
    SuggestionProvider<CommandSender> playerSuggestions = onlinePlayerSuggestions();
    SuggestionProvider<CommandSender> inviteCompanySuggestions = inviteCompanySuggestions();
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
                            PlayerIdentity inviterIdentity =
                                identityService.requireIdentity(sender);
                            PlayerIdentity targetIdentity = identityService.getOrCreate(target);
                            Optional<CompanyMember> existing =
                                provider
                                    .companyMembers()
                                    .findMembership(company.id(), targetIdentity.id());
                            Instant now = Instant.now();
                            if (existing.isPresent()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.invite.already-member",
                                      Map.of(
                                          "code",
                                          company.code(),
                                          "player",
                                          targetIdentity.name())));
                              return null;
                            }
                            provider
                                .companyMemberInvites()
                                .save(
                                    new CompanyMemberInvite(
                                        company.id(),
                                        targetIdentity.id(),
                                        roles,
                                        inviterIdentity.id(),
                                        now));
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.invite.sent",
                                    Map.of(
                                        "code", company.code(),
                                        "player", targetIdentity.name(),
                                        "roles",
                                            String.join(
                                                ",", roles.stream().map(Enum::name).toList()))));
                            Player targetOnline = Bukkit.getPlayer(targetIdentity.playerUuid());
                            if (targetOnline != null && targetOnline.isOnline()) {
                              sendInviteNotification(
                                  locale,
                                  targetOnline,
                                  company.code(),
                                  inviterIdentity.name(),
                                  roles);
                            }
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("invites")
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

                  List<CompanyMemberInvite> invites =
                      provider.companyMemberInvites().listInvites(identity.id());
                  sender.sendMessage(locale.component("command.company.member.invite.list.header"));
                  if (invites.isEmpty()) {
                    sender.sendMessage(
                        locale.component("command.company.member.invite.list.empty"));
                    return;
                  }
                  for (CompanyMemberInvite invite : invites) {
                    if (invite == null) {
                      continue;
                    }
                    Optional<Company> companyOpt =
                        provider.companies().findById(invite.companyId());
                    if (companyOpt.isEmpty()) {
                      continue;
                    }
                    String inviter =
                        provider
                            .playerIdentities()
                            .findById(invite.invitedByIdentityId())
                            .map(PlayerIdentity::name)
                            .orElse(invite.invitedByIdentityId().toString());
                    String roles =
                        String.join(",", invite.roles().stream().map(Enum::name).toList());
                    sender.sendMessage(
                        locale.component(
                            "command.company.member.invite.list.entry",
                            Map.of(
                                "code",
                                companyOpt.get().code(),
                                "inviter",
                                inviter,
                                "roles",
                                roles)));
                  }
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("accept")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), inviteCompanySuggestions)
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
                            Optional<CompanyMemberInvite> inviteOpt =
                                provider
                                    .companyMemberInvites()
                                    .findInvite(company.id(), identity.id());
                            if (inviteOpt.isEmpty()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.invite.not-found",
                                      Map.of("code", company.code())));
                              return null;
                            }
                            Optional<CompanyMember> existing =
                                provider
                                    .companyMembers()
                                    .findMembership(company.id(), identity.id());
                            if (existing.isPresent()) {
                              provider.companyMemberInvites().delete(company.id(), identity.id());
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.invite.already-member",
                                      Map.of("code", company.code(), "player", identity.name())));
                              return null;
                            }

                            CompanyMemberInvite invite = inviteOpt.get();
                            EnumSet<MemberRole> roles = EnumSet.copyOf(invite.roles());
                            provider
                                .companyMembers()
                                .save(
                                    new CompanyMember(
                                        company.id(),
                                        identity.id(),
                                        roles,
                                        Instant.now(),
                                        Optional.empty()));
                            provider.companyMemberInvites().delete(company.id(), identity.id());
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.invite.accept-success",
                                    Map.of(
                                        "code",
                                        company.code(),
                                        "roles",
                                        String.join(
                                            ",", roles.stream().map(Enum::name).toList()))));
                            notifyInviteDecision(
                                provider,
                                locale,
                                invite.invitedByIdentityId(),
                                "command.company.member.invite.notify-accept",
                                Map.of("code", company.code(), "player", identity.name()));
                            return null;
                          });
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("company")
            .literal("member")
            .literal("decline")
            .senderType(Player.class)
            .required("company", StringParser.stringParser(), inviteCompanySuggestions)
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
                            Optional<CompanyMemberInvite> inviteOpt =
                                provider
                                    .companyMemberInvites()
                                    .findInvite(company.id(), identity.id());
                            if (inviteOpt.isEmpty()) {
                              sender.sendMessage(
                                  locale.component(
                                      "command.company.member.invite.not-found",
                                      Map.of("code", company.code())));
                              return null;
                            }
                            CompanyMemberInvite invite = inviteOpt.get();
                            provider.companyMemberInvites().delete(company.id(), identity.id());
                            sender.sendMessage(
                                locale.component(
                                    "command.company.member.invite.decline-success",
                                    Map.of("code", company.code())));
                            notifyInviteDecision(
                                provider,
                                locale,
                                invite.invitedByIdentityId(),
                                "command.company.member.invite.notify-decline",
                                Map.of("code", company.code(), "player", identity.name()));
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
    return CommandStorageProviders.readyProvider(sender, plugin);
  }

  private Optional<StorageProvider> providerIfReady() {
    return CommandStorageProviders.providerIfReady(plugin);
  }

  private boolean canReadCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canReadCompany(sender, provider, companyId);
  }

  private boolean canManageCompany(CommandSender sender, StorageProvider provider, UUID companyId) {
    return CompanyAccessChecker.canManageCompany(sender, provider, companyId);
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

  private SuggestionProvider<CommandSender> onlinePlayerSuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<player>");
          }
          Bukkit.getOnlinePlayers().stream()
              .map(Player::getName)
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
    return CommandSuggestionProviders.enumValuesList(MemberRole.class, "<roles...>");
  }

  /** 针对“accept/decline”子命令，仅提示当前玩家收到的邀请公司 code。 */
  private SuggestionProvider<CommandSender> inviteCompanySuggestions() {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizePrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add("<company>");
          }
          if (!(ctx.sender() instanceof Player player)) {
            return suggestions;
          }
          Optional<StorageProvider> providerOpt = providerIfReady();
          if (providerOpt.isEmpty()) {
            return suggestions;
          }
          StorageProvider provider = providerOpt.get();
          Optional<PlayerIdentity> identityOpt =
              provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
          if (identityOpt.isEmpty()) {
            return suggestions;
          }
          List<CompanyMemberInvite> invites =
              provider.companyMemberInvites().listInvites(identityOpt.get().id());
          for (CompanyMemberInvite invite : invites) {
            if (invite == null) {
              continue;
            }
            Optional<Company> companyOpt = provider.companies().findById(invite.companyId());
            if (companyOpt.isEmpty()) {
              continue;
            }
            String code = companyOpt.get().code();
            if (code == null) {
              continue;
            }
            String trimmed = code.trim();
            if (trimmed.isBlank()) {
              continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
              suggestions.add(trimmed);
            }
          }
          return suggestions.stream().distinct().limit(SUGGESTION_LIMIT).toList();
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

  /** 向被邀请人发送可点击的接受/拒绝通知。 */
  private void sendInviteNotification(
      LocaleManager locale,
      Player target,
      String companyCode,
      String inviter,
      Set<MemberRole> roles) {
    if (target == null || locale == null) {
      return;
    }
    String rolesText = String.join(",", roles.stream().map(Enum::name).toList());
    String acceptCommand = "/fta company member accept " + companyCode;
    String declineCommand = "/fta company member decline " + companyCode;

    net.kyori.adventure.text.Component accept =
        locale
            .component("command.company.member.invite.action.accept")
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(acceptCommand))
            .hoverEvent(
                net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.Component.text(acceptCommand)));
    net.kyori.adventure.text.Component decline =
        locale
            .component("command.company.member.invite.action.decline")
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(declineCommand))
            .hoverEvent(
                net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.Component.text(declineCommand)));

    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver =
        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder()
            .resolver(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                    "accept", accept))
            .resolver(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(
                    "decline", decline))
            .resolver(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                    "code", companyCode))
            .resolver(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                    "roles", rolesText))
            .resolver(
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                    "inviter", inviter))
            .build();
    target.sendMessage(locale.component("command.company.member.invite.notify", resolver));
  }

  /** 通知邀请发起人对方已接受或拒绝（仅在线时通知）。 */
  private void notifyInviteDecision(
      StorageProvider provider,
      LocaleManager locale,
      UUID invitedById,
      String messageKey,
      Map<String, String> placeholders) {
    if (provider == null || locale == null || invitedById == null || messageKey == null) {
      return;
    }
    Optional<PlayerIdentity> inviterOpt = provider.playerIdentities().findById(invitedById);
    if (inviterOpt.isEmpty()) {
      return;
    }
    PlayerIdentity inviter = inviterOpt.get();
    Player online = Bukkit.getPlayer(inviter.playerUuid());
    if (online == null || !online.isOnline()) {
      return;
    }
    online.sendMessage(locale.component(messageKey, placeholders));
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
