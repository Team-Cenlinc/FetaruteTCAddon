package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 站台屏权限：权限节点管全部；公司里有管理类角色的成员管本公司车站；同名运营商代码一律拒绝。 */
class PidsAccessTest {

  private static final UUID SURC_COMPANY = UUID.randomUUID();
  private static final UUID OTHER_COMPANY = UUID.randomUUID();
  private static final PidsStationKey HHU = new PidsStationKey("SURC", "HHU");

  private final StorageProvider storage = mock(StorageProvider.class);
  private final PlayerIdentityRepository identities = mock(PlayerIdentityRepository.class);
  private final CompanyMemberRepository members = mock(CompanyMemberRepository.class);
  private final List<String> warnings = new ArrayList<>();
  private List<OperatorApi.OperatorInfo> operators;
  private PidsAccess access;
  private Player player;
  private UUID identity;

  @BeforeEach
  void setUp() {
    when(storage.playerIdentities()).thenReturn(identities);
    when(storage.companyMembers()).thenReturn(members);
    operators = new ArrayList<>(List.of(operator("SURC", SURC_COMPANY)));
    access = new PidsAccess(Optional.of(storage), () -> operators, warnings::add);
    player = mock(Player.class);
    UUID playerUuid = UUID.randomUUID();
    identity = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(playerUuid);
    when(identities.findByPlayerUuid(playerUuid))
        .thenReturn(
            Optional.of(
                new PlayerIdentity(
                    identity,
                    playerUuid,
                    "Sinapole",
                    IdentityAuthType.ONLINE,
                    Optional.empty(),
                    Map.of(),
                    Instant.EPOCH,
                    Instant.EPOCH)));
  }

  @Test
  void permissionNodeManagesEverything() {
    when(player.hasPermission(PidsAccess.MANAGE_PERMISSION)).thenReturn(true);

    assertTrue(access.canManage(player, Optional.empty()), "未绑定车站的屏幕也能管");
    assertTrue(access.canUseTools(player));
  }

  @Test
  void staffOfTheStationsCompanyMayManage() {
    memberOf(SURC_COMPANY, MemberRole.STAFF);

    assertTrue(access.canManage(player, Optional.of(HHU)));
    assertTrue(access.canUseTools(player));
    assertFalse(access.canManage(player, Optional.empty()), "未绑定车站的屏幕只有权限节点能管");
  }

  @Test
  void readOnlyRolesAndOtherCompaniesMayNot() {
    memberOf(SURC_COMPANY, MemberRole.VIEWER, MemberRole.DRIVER);
    assertFalse(access.canManage(player, Optional.of(HHU)));
    assertFalse(access.canUseTools(player));

    memberOf(OTHER_COMPANY, MemberRole.OWNER);
    assertFalse(access.canManage(player, Optional.of(HHU)), "别家公司的所有者管不了本站");
    assertTrue(access.canUseTools(player));
  }

  @Test
  void sharedOperatorCodesAreDeniedAndWarnedOnce() {
    memberOf(SURC_COMPANY, MemberRole.OWNER);
    operators.add(operator("surc", OTHER_COMPANY));

    assertFalse(access.canManage(player, Optional.of(HHU)));
    assertFalse(access.canManage(player, Optional.of(HHU)));
    assertEquals(1, warnings.size(), () -> warnings.toString());
  }

  @Test
  void consoleAndStorageFailuresAreDenied() {
    CommandSender console = mock(CommandSender.class);
    assertFalse(access.canManage(console, Optional.of(HHU)));
    assertFalse(access.canUseTools(console));

    when(members.listMemberships(identity)).thenThrow(new StorageException("连接断开"));
    assertFalse(access.canManage(player, Optional.of(HHU)));
    assertFalse(
        new PidsAccess(Optional.empty(), () -> operators, warnings::add).canUseTools(player));
  }

  /** 公告：按公司授权（不经运营商代码）；管理类角色才算，成员身份一条命令只查一次。 */
  @Test
  void bulletinsAreManagedPerCompany() {
    memberOf(SURC_COMPANY, MemberRole.STAFF);

    Predicate<UUID> manageable = access.manageableCompanies(player);
    assertTrue(manageable.test(SURC_COMPANY));
    assertFalse(manageable.test(OTHER_COMPANY));
    assertTrue(manageable.test(SURC_COMPANY));
    verify(members, times(1)).listMemberships(identity);

    memberOf(SURC_COMPANY, MemberRole.VIEWER);
    assertFalse(access.canManageCompany(player, SURC_COMPANY), "只读角色不能发公告");
    when(player.hasPermission(PidsAccess.MANAGE_PERMISSION)).thenReturn(true);
    assertTrue(access.canManageCompany(player, OTHER_COMPANY));
    assertFalse(access.canManageCompany(mock(CommandSender.class), SURC_COMPANY));
  }

  private void memberOf(UUID company, MemberRole... roles) {
    when(members.listMemberships(identity))
        .thenReturn(
            List.of(
                new CompanyMember(
                    company, identity, Set.of(roles), Instant.EPOCH, Optional.empty())));
  }

  private static OperatorApi.OperatorInfo operator(String code, UUID company) {
    return new OperatorApi.OperatorInfo(
        UUID.randomUUID(),
        code,
        company,
        code,
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.empty());
  }
}
