package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 站台屏管理权限。
 *
 * <ul>
 *   <li>有 {@value #MANAGE_PERMISSION} 的管理全部屏幕。
 *   <li>否则须是车站所属公司里有管理类角色（所有者、经理、职员）的成员；只读与司机角色不算。未绑定车站的屏幕只有前者能管。
 *   <li>车站按运营商代码找公司；同一代码属于多家公司时一律拒绝并告警，避免另开公司抢同名代码就能改别人的屏幕。
 *   <li>只查不建身份：没建过身份的玩家不可能是成员，判断时不写库。
 * </ul>
 */
public final class PidsAccess {

  /** 管理全部屏幕的权限。 */
  public static final String MANAGE_PERMISSION = "fetarute.pids.manage";

  /** 能管理本公司车站屏幕的成员角色。 */
  static final Set<MemberRole> MANAGING_ROLES =
      EnumSet.of(MemberRole.OWNER, MemberRole.MANAGER, MemberRole.STAFF);

  private final Optional<StorageProvider> storage;
  private final Supplier<Collection<OperatorApi.OperatorInfo>> operators;
  private final Consumer<String> warn;
  private final Set<String> warnedCodes = ConcurrentHashMap.newKeySet();

  /**
   * @param storage 存储；不可用时只认权限节点
   * @param operators 全部运营商（公开 API）
   * @param warn 告警出口（运营商代码重复）
   */
  public PidsAccess(
      Optional<StorageProvider> storage,
      Supplier<Collection<OperatorApi.OperatorInfo>> operators,
      Consumer<String> warn) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.operators = Objects.requireNonNull(operators, "operators");
    this.warn = Objects.requireNonNull(warn, "warn");
  }

  /** 能否管理绑定某车站的屏幕（安装、配置、拆除、查看信息）。 */
  public boolean canManage(CommandSender sender, Optional<PidsStationKey> station) {
    return canManageOperator(sender, station.map(PidsStationKey::operatorCode));
  }

  /** 能否管理属于某运营商的屏幕：有管理权限，或是运营商所属公司里有管理类角色的成员。 */
  public boolean canManageOperator(CommandSender sender, Optional<String> operatorCode) {
    if (sender.hasPermission(MANAGE_PERMISSION)) {
      return true;
    }
    if (operatorCode.isEmpty() || !(sender instanceof Player player)) {
      return false;
    }
    Optional<UUID> company = companyOf(operatorCode.get());
    return company.isPresent()
        && memberships(player).stream()
            .anyMatch(member -> member.companyId().equals(company.get()) && managing(member));
  }

  /** 能否发布、修改、撤下某公司的站台屏公告：有管理权限，或是这家公司里有管理类角色的成员。 */
  public boolean canManageCompany(CommandSender sender, UUID companyId) {
    return manageableCompanies(sender).test(companyId);
  }

  /**
   * 能管理哪些公司的站台屏公告：成员身份只查一次，列表与补全逐条判断时不再读库。
   *
   * @return 有管理权限时对任何公司都为真
   */
  public Predicate<UUID> manageableCompanies(CommandSender sender) {
    if (sender.hasPermission(MANAGE_PERMISSION)) {
      return company -> true;
    }
    if (!(sender instanceof Player player)) {
      return company -> false;
    }
    Set<UUID> companies =
        memberships(player).stream()
            .filter(PidsAccess::managing)
            .map(CompanyMember::companyId)
            .collect(Collectors.toSet());
    return companies::contains;
  }

  /** 能否领取安装纸与配置棍：有管理权限，或在至少一家公司里有管理类角色。 */
  public boolean canUseTools(CommandSender sender) {
    if (sender.hasPermission(MANAGE_PERMISSION)) {
      return true;
    }
    return sender instanceof Player player
        && memberships(player).stream().anyMatch(PidsAccess::managing);
  }

  /** 运营商代码唯一对应的公司；代码不存在或属于多家公司时为空。 */
  Optional<UUID> companyOf(String operatorCode) {
    Set<UUID> companies =
        operators.get().stream()
            .filter(operator -> operator.code().equalsIgnoreCase(operatorCode))
            .map(OperatorApi.OperatorInfo::companyId)
            .collect(Collectors.toSet());
    if (companies.size() > 1 && warnedCodes.add(operatorCode.toUpperCase(Locale.ROOT))) {
      warn.accept("运营商代码 " + operatorCode + " 属于 " + companies.size() + " 家公司，站台屏不按公司成员授权；请改成唯一代码");
    }
    return companies.size() == 1 ? companies.stream().findFirst() : Optional.empty();
  }

  private Collection<CompanyMember> memberships(Player player) {
    if (storage.isEmpty()) {
      return Set.of();
    }
    StorageProvider provider = storage.get();
    try {
      return provider
          .playerIdentities()
          .findByPlayerUuid(player.getUniqueId())
          .map(PlayerIdentity::id)
          .map(
              identity ->
                  (Collection<CompanyMember>) provider.companyMembers().listMemberships(identity))
          .orElse(Set.of());
    } catch (StorageException ex) {
      return Set.of();
    }
  }

  private static boolean managing(CompanyMember member) {
    return member.roles().stream().anyMatch(MANAGING_ROLES::contains);
  }
}
