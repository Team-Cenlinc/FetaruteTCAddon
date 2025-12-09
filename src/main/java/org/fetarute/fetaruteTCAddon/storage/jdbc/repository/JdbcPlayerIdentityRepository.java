package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的玩家身份仓库。 */
public final class JdbcPlayerIdentityRepository extends JdbcRepositorySupport
    implements PlayerIdentityRepository {

  public JdbcPlayerIdentityRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<PlayerIdentity> findById(UUID id) {
    String sql =
        "SELECT id, player_uuid, name, auth_type, external_ref, metadata, created_at, updated_at FROM "
            + table("player_identities")
            + " WHERE id = ?";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询玩家身份失败", ex);
    }
  }

  @Override
  public Optional<PlayerIdentity> findByPlayerUuid(UUID playerUuid) {
    String sql =
        "SELECT id, player_uuid, name, auth_type, external_ref, metadata, created_at, updated_at FROM "
            + table("player_identities")
            + " WHERE player_uuid = ?";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, playerUuid);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按玩家 UUID 查询身份失败", ex);
    }
  }

  @Override
  public List<PlayerIdentity> listAll() {
    String sql =
        "SELECT id, player_uuid, name, auth_type, external_ref, metadata, created_at, updated_at FROM "
            + table("player_identities");
    List<PlayerIdentity> results = new ArrayList<>();
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      while (rs.next()) {
        results.add(mapRow(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出玩家身份失败", ex);
    }
  }

  @Override
  public PlayerIdentity save(PlayerIdentity identity) {
    String insert =
        "INSERT INTO "
            + table("player_identities")
            + " (id, player_uuid, name, auth_type, external_ref, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    // 按主键 upsert，冲突时覆盖玩家 uuid/name/auth_type 等字段
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of("player_uuid", "name", "auth_type", "external_ref", "metadata", "updated_at"));
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      writeIdentity(statement, identity);
      statement.executeUpdate();
      commitIfNecessary(connection);
      return identity;
    } catch (SQLException ex) {
      throw new StorageException("保存玩家身份失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("player_identities") + " WHERE id = ?";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      commitIfNecessary(connection);
    } catch (SQLException ex) {
      throw new StorageException("删除玩家身份失败", ex);
    }
  }

  private void writeIdentity(java.sql.PreparedStatement statement, PlayerIdentity identity)
      throws SQLException {
    setUuid(statement, 1, identity.id());
    setUuid(statement, 2, identity.playerUuid());
    statement.setString(3, identity.name());
    statement.setString(4, identity.authType().name());
    statement.setString(5, identity.externalRef().orElse(null));
    statement.setString(6, toJson(identity.metadata()));
    statement.setTimestamp(7, toTimestamp(identity.createdAt()));
    statement.setTimestamp(8, toTimestamp(identity.updatedAt()));
  }

  private PlayerIdentity mapRow(ResultSet rs) throws SQLException {
    UUID id = readUuid(rs, "id");
    UUID playerUuid = readUuid(rs, "player_uuid");
    String name = rs.getString("name");
    IdentityAuthType authType = IdentityAuthType.valueOf(rs.getString("auth_type"));
    String externalRef = rs.getString("external_ref");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = fromTimestamp(rs.getTimestamp("created_at"));
    Instant updatedAt = fromTimestamp(rs.getTimestamp("updated_at"));
    return new PlayerIdentity(
        id,
        playerUuid,
        name,
        authType,
        Optional.ofNullable(externalRef),
        metadata,
        createdAt,
        updatedAt);
  }
}
