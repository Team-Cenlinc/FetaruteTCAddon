package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class CompanyAccessCheckerTest {

  private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID IDENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void adminBypassesAllChecks() {
    CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission("fetarute.admin")).thenReturn(true);

    StorageProvider provider = mock(StorageProvider.class);

    assertTrue(CompanyAccessChecker.canReadCompany(sender, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canManageCompany(sender, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canManageCompanyNoCreateIdentity(sender, provider, COMPANY_ID));
    verifyNoInteractions(provider);
  }

  @Test
  void nonPlayerCannotReadOrManageWhenNotAdmin() {
    CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission("fetarute.admin")).thenReturn(false);
    StorageProvider provider = mock(StorageProvider.class);

    assertFalse(CompanyAccessChecker.canReadCompany(sender, provider, COMPANY_ID));
    assertFalse(CompanyAccessChecker.canManageCompany(sender, provider, COMPANY_ID));
    assertFalse(CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, COMPANY_ID));
    assertFalse(
        CompanyAccessChecker.canManageCompanyNoCreateIdentity(sender, provider, COMPANY_ID));
  }

  @Test
  void memberCanReadCompany() {
    Player player = mock(Player.class);
    when(player.hasPermission("fetarute.admin")).thenReturn(false);
    when(player.getUniqueId()).thenReturn(PLAYER_ID);
    when(player.getName()).thenReturn("alice");

    PlayerIdentityRepository identities = mock(PlayerIdentityRepository.class);
    CompanyMemberRepository members = mock(CompanyMemberRepository.class);
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.playerIdentities()).thenReturn(identities);
    when(provider.companyMembers()).thenReturn(members);

    PlayerIdentity identity = identity("alice", IdentityAuthType.ONLINE);
    when(identities.findByPlayerUuid(PLAYER_ID)).thenReturn(Optional.of(identity));
    when(members.findMembership(COMPANY_ID, IDENTITY_ID))
        .thenReturn(Optional.of(member(Set.of(MemberRole.VIEWER))));

    assertTrue(CompanyAccessChecker.canReadCompany(player, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canReadCompanyNoCreateIdentity(player, provider, COMPANY_ID));
  }

  @Test
  void manageRequiresOwnerOrManager() {
    Player player = mock(Player.class);
    when(player.hasPermission("fetarute.admin")).thenReturn(false);
    when(player.getUniqueId()).thenReturn(PLAYER_ID);
    when(player.getName()).thenReturn("alice");

    PlayerIdentityRepository identities = mock(PlayerIdentityRepository.class);
    CompanyMemberRepository members = mock(CompanyMemberRepository.class);
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.playerIdentities()).thenReturn(identities);
    when(provider.companyMembers()).thenReturn(members);

    PlayerIdentity identity = identity("alice", IdentityAuthType.ONLINE);
    when(identities.findByPlayerUuid(PLAYER_ID)).thenReturn(Optional.of(identity));

    when(members.findMembership(COMPANY_ID, IDENTITY_ID))
        .thenReturn(Optional.of(member(EnumSet.of(MemberRole.STAFF))));
    assertFalse(CompanyAccessChecker.canManageCompany(player, provider, COMPANY_ID));
    assertFalse(
        CompanyAccessChecker.canManageCompanyNoCreateIdentity(player, provider, COMPANY_ID));

    when(members.findMembership(COMPANY_ID, IDENTITY_ID))
        .thenReturn(Optional.of(member(EnumSet.of(MemberRole.OWNER))));
    assertTrue(CompanyAccessChecker.canManageCompany(player, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canManageCompanyNoCreateIdentity(player, provider, COMPANY_ID));

    when(members.findMembership(COMPANY_ID, IDENTITY_ID))
        .thenReturn(Optional.of(member(EnumSet.of(MemberRole.MANAGER))));
    assertTrue(CompanyAccessChecker.canManageCompany(player, provider, COMPANY_ID));
    assertTrue(CompanyAccessChecker.canManageCompanyNoCreateIdentity(player, provider, COMPANY_ID));
  }

  @Test
  void noCreateIdentityReturnsFalseWhenMissingIdentity() {
    Player player = mock(Player.class);
    when(player.hasPermission("fetarute.admin")).thenReturn(false);
    when(player.getUniqueId()).thenReturn(PLAYER_ID);

    PlayerIdentityRepository identities = mock(PlayerIdentityRepository.class);
    CompanyMemberRepository members = mock(CompanyMemberRepository.class);
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.playerIdentities()).thenReturn(identities);
    when(provider.companyMembers()).thenReturn(members);

    when(identities.findByPlayerUuid(PLAYER_ID)).thenReturn(Optional.empty());

    assertFalse(CompanyAccessChecker.canReadCompanyNoCreateIdentity(player, provider, COMPANY_ID));
    assertFalse(
        CompanyAccessChecker.canManageCompanyNoCreateIdentity(player, provider, COMPANY_ID));
  }

  private static PlayerIdentity identity(String name, IdentityAuthType authType) {
    Instant now = Instant.EPOCH;
    return new PlayerIdentity(
        IDENTITY_ID, PLAYER_ID, name, authType, Optional.empty(), Map.of(), now, now);
  }

  private static CompanyMember member(Set<MemberRole> roles) {
    return new CompanyMember(COMPANY_ID, IDENTITY_ID, roles, Instant.EPOCH, Optional.empty());
  }
}
