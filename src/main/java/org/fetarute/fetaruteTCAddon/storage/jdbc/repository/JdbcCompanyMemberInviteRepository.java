package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMemberInvite;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberInviteRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的公司成员邀请仓库。 */
public final class JdbcCompanyMemberInviteRepository extends JdbcRepositorySupport
    implements CompanyMemberInviteRepository {

  private static final Type ROLE_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

  public JdbcCompanyMemberInviteRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<CompanyMemberInvite> findInvite(UUID companyId, UUID playerIdentityId) {
    String sql =
        "SELECT company_id, player_identity_id, roles, invited_by_identity_id, invited_at FROM "
            + table("company_member_invites")
            + " WHERE company_id = ? AND player_identity_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      setUuid(statement, 2, playerIdentityId);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询公司邀请失败", ex);
    }
  }

  @Override
  public List<CompanyMemberInvite> listInvites(UUID playerIdentityId) {
    String sql =
        "SELECT company_id, player_identity_id, roles, invited_by_identity_id, invited_at FROM "
            + table("company_member_invites")
            + " WHERE player_identity_id = ?";
    List<CompanyMemberInvite> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, playerIdentityId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出公司邀请失败", ex);
    }
  }

  @Override
  public CompanyMemberInvite save(CompanyMemberInvite invite) {
    Objects.requireNonNull(invite, "invite");
    String insert =
        "INSERT INTO "
            + table("company_member_invites")
            + " (company_id, player_identity_id, roles, invited_by_identity_id, invited_at)"
            + " VALUES (?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("company_id", "player_identity_id"),
            List.of("roles", "invited_by_identity_id", "invited_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeInvite(statement, invite);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return invite;
    } catch (SQLException ex) {
      throw new StorageException("保存公司邀请失败", ex);
    }
  }

  @Override
  public void delete(UUID companyId, UUID playerIdentityId) {
    String sql =
        "DELETE FROM "
            + table("company_member_invites")
            + " WHERE company_id = ? AND player_identity_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      setUuid(statement, 2, playerIdentityId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除公司邀请失败", ex);
    }
  }

  private void writeInvite(java.sql.PreparedStatement statement, CompanyMemberInvite invite)
      throws SQLException {
    setUuid(statement, 1, invite.companyId());
    setUuid(statement, 2, invite.playerIdentityId());
    statement.setString(3, rolesToJson(invite.roles()));
    setUuid(statement, 4, invite.invitedByIdentityId());
    setInstant(statement, 5, invite.invitedAt());
  }

  private CompanyMemberInvite mapRow(ResultSet rs) throws SQLException {
    UUID companyId = requireUuid(rs, "company_id");
    UUID playerIdentityId = requireUuid(rs, "player_identity_id");
    Set<MemberRole> roles = rolesFromJson(rs.getString("roles"));
    UUID invitedById = requireUuid(rs, "invited_by_identity_id");
    Instant invitedAt = readInstant(rs, "invited_at");
    return new CompanyMemberInvite(companyId, playerIdentityId, roles, invitedById, invitedAt);
  }

  /** 角色集合序列化为 JSON 字符串，便于存储。 */
  private String rolesToJson(Set<MemberRole> roles) {
    if (roles == null || roles.isEmpty()) {
      return "[]";
    }
    List<String> names = roles.stream().map(Enum::name).toList();
    return gson.toJson(names, ROLE_LIST_TYPE);
  }

  /** 从 JSON 字符串反序列化角色集合，并过滤非法值。 */
  private Set<MemberRole> rolesFromJson(String json) {
    if (json == null || json.isBlank()) {
      return Set.of();
    }
    List<String> names = gson.fromJson(json, ROLE_LIST_TYPE);
    if (names == null || names.isEmpty()) {
      return Set.of();
    }
    EnumSet<MemberRole> roles = EnumSet.noneOf(MemberRole.class);
    for (String name : names) {
      if (name == null || name.isBlank()) {
        continue;
      }
      try {
        roles.add(MemberRole.valueOf(name));
      } catch (IllegalArgumentException ex) {
        throw new StorageException("无效的成员角色: " + name, ex);
      }
    }
    return Set.copyOf(roles);
  }
}
