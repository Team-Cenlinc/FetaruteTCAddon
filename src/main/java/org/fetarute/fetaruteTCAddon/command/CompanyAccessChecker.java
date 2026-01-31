package org.fetarute.fetaruteTCAddon.command;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.company.api.PlayerIdentityService;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 命令层“公司权限”判定工具：统一成员可见性与管理权限规则。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>管理员（{@code fetarute.admin}）可见/可管所有公司。
 *   <li>普通玩家必须是公司成员才可读取；具备 {@code OWNER/MANAGER} 才可管理。
 *   <li>Tab 补全阶段禁止“创建身份”（避免补全触发写操作）。
 * </ul>
 */
public final class CompanyAccessChecker {

  private CompanyAccessChecker() {}

  /** 判断 sender 是否具备读取指定公司的权限（公司成员或管理员）。 */
  public static boolean canReadCompany(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender == null || provider == null || companyId == null) {
      return false;
    }
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

  /** 判断 sender 是否具备管理指定公司的权限（Owner/Manager 或管理员）。 */
  public static boolean canManageCompany(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender == null || provider == null || companyId == null) {
      return false;
    }
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    PlayerIdentity identity =
        new PlayerIdentityService(provider.playerIdentities()).requireIdentity(player);
    Optional<CompanyMember> membershipOpt =
        provider.companyMembers().findMembership(companyId, identity.id());
    if (membershipOpt.isEmpty()) {
      return false;
    }
    Set<MemberRole> roles = membershipOpt.get().roles();
    return roles.contains(MemberRole.OWNER) || roles.contains(MemberRole.MANAGER);
  }

  /**
   * 判断 sender 是否可读取公司，但不创建身份。
   *
   * <p>用于 Tab 补全阶段：若身份尚未建立则返回 false，避免补全触发写操作。
   */
  public static boolean canReadCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender == null || provider == null || companyId == null) {
      return false;
    }
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    Optional<PlayerIdentity> identityOpt =
        provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
    if (identityOpt.isEmpty()) {
      return false;
    }
    return provider.companyMembers().findMembership(companyId, identityOpt.get().id()).isPresent();
  }

  /**
   * 判断 sender 是否可管理公司，但不创建身份。
   *
   * <p>用于 Tab 补全或“只读查询”阶段：若身份尚未建立则返回 false，避免命令触发写操作。
   */
  public static boolean canManageCompanyNoCreateIdentity(
      CommandSender sender, StorageProvider provider, UUID companyId) {
    if (sender == null || provider == null || companyId == null) {
      return false;
    }
    if (sender.hasPermission("fetarute.admin")) {
      return true;
    }
    if (!(sender instanceof Player player)) {
      return false;
    }
    Optional<PlayerIdentity> identityOpt =
        provider.playerIdentities().findByPlayerUuid(player.getUniqueId());
    if (identityOpt.isEmpty()) {
      return false;
    }
    Optional<CompanyMember> membershipOpt =
        provider.companyMembers().findMembership(companyId, identityOpt.get().id());
    if (membershipOpt.isEmpty()) {
      return false;
    }
    Set<MemberRole> roles = membershipOpt.get().roles();
    return roles.contains(MemberRole.OWNER) || roles.contains(MemberRole.MANAGER);
  }
}
