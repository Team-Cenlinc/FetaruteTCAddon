package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的公司成员仓库。 */
public final class JdbcCompanyMemberRepository extends JdbcRepositorySupport
    implements CompanyMemberRepository {

  private static final Type ROLE_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

  public JdbcCompanyMemberRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<CompanyMember> findMembership(UUID companyId, UUID playerIdentityId) {
    String sql =
        "SELECT company_id, player_identity_id, roles, joined_at, permissions FROM "
            + table("company_members")
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
      throw new StorageException("查询公司成员关系失败", ex);
    }
  }

  @Override
  public List<CompanyMember> listMembers(UUID companyId) {
    String sql =
        "SELECT company_id, player_identity_id, roles, joined_at, permissions FROM "
            + table("company_members")
            + " WHERE company_id = ?";
    List<CompanyMember> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出公司成员失败", ex);
    }
  }

  @Override
  public List<CompanyMember> listMemberships(UUID playerIdentityId) {
    String sql =
        "SELECT company_id, player_identity_id, roles, joined_at, permissions FROM "
            + table("company_members")
            + " WHERE player_identity_id = ?";
    List<CompanyMember> results = new ArrayList<>();
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
      throw new StorageException("列出成员加入的公司失败", ex);
    }
  }

  @Override
  public CompanyMember save(CompanyMember member) {
    Objects.requireNonNull(member, "member");
    String insert =
        "INSERT INTO "
            + table("company_members")
            + " (company_id, player_identity_id, roles, joined_at, permissions)"
            + " VALUES (?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert, List.of("company_id", "player_identity_id"), List.of("roles", "permissions"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeMember(statement, member);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return member;
    } catch (SQLException ex) {
      throw new StorageException("保存公司成员失败", ex);
    }
  }

  @Override
  public void delete(UUID companyId, UUID playerIdentityId) {
    String sql =
        "DELETE FROM "
            + table("company_members")
            + " WHERE company_id = ? AND player_identity_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      setUuid(statement, 2, playerIdentityId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除公司成员失败", ex);
    }
  }

  private void writeMember(java.sql.PreparedStatement statement, CompanyMember member)
      throws SQLException {
    setUuid(statement, 1, member.companyId());
    setUuid(statement, 2, member.playerIdentityId());
    statement.setString(3, rolesToJson(member.roles()));
    setInstant(statement, 4, member.joinedAt());
    statement.setString(5, member.permissions().map(this::toJson).orElse(null));
  }

  private CompanyMember mapRow(ResultSet rs) throws SQLException {
    UUID companyId = requireUuid(rs, "company_id");
    UUID playerIdentityId = requireUuid(rs, "player_identity_id");
    Set<MemberRole> roles = rolesFromJson(rs.getString("roles"));
    Instant joinedAt = readInstant(rs, "joined_at");
    String permissionsJson = rs.getString("permissions");
    Optional<Map<String, Object>> permissions =
        permissionsJson == null || permissionsJson.isBlank()
            ? Optional.empty()
            : Optional.of(fromJson(permissionsJson));
    return new CompanyMember(companyId, playerIdentityId, roles, joinedAt, permissions);
  }

  private String rolesToJson(Set<MemberRole> roles) {
    if (roles == null || roles.isEmpty()) {
      return "[]";
    }
    List<String> names = roles.stream().map(Enum::name).toList();
    return gson.toJson(names, ROLE_LIST_TYPE);
  }

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
