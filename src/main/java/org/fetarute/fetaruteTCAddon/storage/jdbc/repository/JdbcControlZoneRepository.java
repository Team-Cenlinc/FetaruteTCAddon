package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.repository.ControlZoneRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的控制区仓库。
 *
 * <p>与车站组同样不用方言 upsert：先按主键更新，没有这一行再插入，唯一约束冲突在 SQLite 与 MySQL 上都会报错，而不是悄悄改写另一行。
 */
public final class JdbcControlZoneRepository extends JdbcRepositorySupport
    implements ControlZoneRepository {

  private static final String ZONE_COLUMNS =
      "id, operator_id, code, name, secondary_name, metadata, created_at, updated_at";
  private static final String MEMBER_COLUMNS = "zone_id, member_kind, member_code, sort_order";

  public JdbcControlZoneRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<ControlZone> findById(UUID id) {
    String sql = "SELECT " + ZONE_COLUMNS + " FROM " + table("control_zones") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(mapZone(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询控制区失败", ex);
    }
  }

  @Override
  public Optional<ControlZone> findByOperatorAndCode(UUID operatorId, String code) {
    String sql =
        "SELECT "
            + ZONE_COLUMNS
            + " FROM "
            + table("control_zones")
            + " WHERE operator_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(mapZone(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 operator+code 查询控制区失败", ex);
    }
  }

  @Override
  public List<ControlZone> listByOperator(UUID operatorId) {
    String sql =
        "SELECT "
            + ZONE_COLUMNS
            + " FROM "
            + table("control_zones")
            + " WHERE operator_id = ? ORDER BY code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      try (var rs = statement.executeQuery()) {
        List<ControlZone> results = new ArrayList<>();
        while (rs.next()) {
          results.add(mapZone(rs));
        }
        return results;
      }
    } catch (SQLException ex) {
      throw new StorageException("列出控制区失败", ex);
    }
  }

  @Override
  public List<ControlZone> listAll() {
    String sql =
        "SELECT " + ZONE_COLUMNS + " FROM " + table("control_zones") + " ORDER BY code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<ControlZone> results = new ArrayList<>();
      while (rs.next()) {
        results.add(mapZone(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出控制区失败", ex);
    }
  }

  @Override
  public ControlZone save(ControlZone zone) {
    Objects.requireNonNull(zone, "zone");
    String update =
        "UPDATE "
            + table("control_zones")
            + " SET operator_id = ?, code = ?, name = ?, secondary_name = ?, metadata = ?,"
            + " updated_at = ? WHERE id = ?";
    String insert =
        "INSERT INTO "
            + table("control_zones")
            + " ("
            + ZONE_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              setUuid(statement, 1, zone.operatorId());
              statement.setString(2, zone.code());
              statement.setString(3, zone.name());
              statement.setString(4, zone.secondaryName().orElse(null));
              statement.setString(5, toJson(zone.metadata()));
              setInstant(statement, 6, zone.updatedAt());
              setUuid(statement, 7, zone.id());
              if (statement.executeUpdate() > 0) {
                return zone;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, zone.id());
              setUuid(statement, 2, zone.operatorId());
              statement.setString(3, zone.code());
              statement.setString(4, zone.name());
              statement.setString(5, zone.secondaryName().orElse(null));
              statement.setString(6, toJson(zone.metadata()));
              setInstant(statement, 7, zone.createdAt());
              setInstant(statement, 8, zone.updatedAt());
              statement.executeUpdate();
            }
            return zone;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存控制区失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    // 不依赖 PRAGMA foreign_keys 的级联，显式把成员删干净
    try {
      inTransaction(
          connection -> {
            try (var statement =
                connection.prepareStatement(
                    "DELETE FROM " + table("control_zone_members") + " WHERE zone_id = ?")) {
              setUuid(statement, 1, id);
              statement.executeUpdate();
            }
            try (var statement =
                connection.prepareStatement(
                    "DELETE FROM " + table("control_zones") + " WHERE id = ?")) {
              setUuid(statement, 1, id);
              statement.executeUpdate();
            }
            return null;
          });
    } catch (SQLException ex) {
      throw new StorageException("删除控制区失败", ex);
    }
  }

  @Override
  public List<ControlZoneMember> listMembers(UUID zoneId) {
    String sql =
        "SELECT "
            + MEMBER_COLUMNS
            + " FROM "
            + table("control_zone_members")
            + " WHERE zone_id = ? ORDER BY sort_order ASC, member_kind ASC, member_code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, zoneId);
      try (var rs = statement.executeQuery()) {
        List<ControlZoneMember> results = new ArrayList<>();
        while (rs.next()) {
          mapMember(rs).ifPresent(results::add);
        }
        return results;
      }
    } catch (SQLException ex) {
      throw new StorageException("列出控制区成员失败", ex);
    }
  }

  @Override
  public List<ControlZoneMember> listAllMembers() {
    String sql =
        "SELECT "
            + MEMBER_COLUMNS
            + " FROM "
            + table("control_zone_members")
            + " ORDER BY sort_order ASC, member_kind ASC, member_code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<ControlZoneMember> results = new ArrayList<>();
      while (rs.next()) {
        mapMember(rs).ifPresent(results::add);
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出控制区成员失败", ex);
    }
  }

  @Override
  public ControlZoneMember saveMember(ControlZoneMember member) {
    Objects.requireNonNull(member, "member");
    String update =
        "UPDATE "
            + table("control_zone_members")
            + " SET sort_order = ? WHERE zone_id = ? AND member_kind = ? AND member_code = ?";
    String insert =
        "INSERT INTO "
            + table("control_zone_members")
            + " ("
            + MEMBER_COLUMNS
            + ") VALUES (?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              statement.setInt(1, member.sortOrder());
              setUuid(statement, 2, member.zoneId());
              statement.setString(3, member.kind().name());
              statement.setString(4, member.code());
              if (statement.executeUpdate() > 0) {
                return member;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, member.zoneId());
              statement.setString(2, member.kind().name());
              statement.setString(3, member.code());
              statement.setInt(4, member.sortOrder());
              statement.executeUpdate();
            }
            return member;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存控制区成员失败", ex);
    }
  }

  @Override
  public void removeMember(UUID zoneId, ControlZoneMember.Kind kind, String code) {
    String sql =
        "DELETE FROM "
            + table("control_zone_members")
            + " WHERE zone_id = ? AND member_kind = ? AND member_code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, zoneId);
      statement.setString(2, kind.name());
      statement.setString(3, code);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("移除控制区成员失败", ex);
    }
  }

  private ControlZone mapZone(ResultSet rs) throws SQLException {
    return new ControlZone(
        requireUuid(rs, "id"),
        requireUuid(rs, "operator_id"),
        rs.getString("code"),
        rs.getString("name"),
        Optional.ofNullable(rs.getString("secondary_name")),
        fromJson(rs.getString("metadata")),
        readInstant(rs, "created_at"),
        readInstant(rs, "updated_at"));
  }

  /** 认不出的种类（库里被手改过）略过，不让整张表读不出来。 */
  private Optional<ControlZoneMember> mapMember(ResultSet rs) throws SQLException {
    Optional<ControlZoneMember.Kind> kind =
        ControlZoneMember.Kind.fromToken(rs.getString("member_kind"));
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    Integer sortOrder = readNullableInteger(rs, "sort_order");
    return Optional.of(
        new ControlZoneMember(
            requireUuid(rs, "zone_id"),
            kind.get(),
            rs.getString("member_code"),
            sortOrder == null ? 0 : sortOrder));
  }
}
