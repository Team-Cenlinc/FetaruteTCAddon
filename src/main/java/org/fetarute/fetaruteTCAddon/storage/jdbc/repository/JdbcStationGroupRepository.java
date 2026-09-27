package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.company.repository.StationGroupRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的车站组仓库。
 *
 * <p>成员表的 {@code station_id} 带唯一约束：同一车站加入第二个组会在写库时失败，而不是悄悄移组。
 */
public final class JdbcStationGroupRepository extends JdbcRepositorySupport
    implements StationGroupRepository {

  private static final String GROUP_COLUMNS =
      "id, company_id, code, name, secondary_name, metadata, created_at, updated_at";
  private static final String MEMBER_COLUMNS =
      "group_id, station_id, transfer_type, walk_secs, sort_order";

  public JdbcStationGroupRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<StationGroup> findById(UUID id) {
    String sql = "SELECT " + GROUP_COLUMNS + " FROM " + table("station_groups") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(mapGroup(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询车站组失败", ex);
    }
  }

  @Override
  public Optional<StationGroup> findByCompanyAndCode(UUID companyId, String code) {
    String sql =
        "SELECT "
            + GROUP_COLUMNS
            + " FROM "
            + table("station_groups")
            + " WHERE company_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(mapGroup(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 company+code 查询车站组失败", ex);
    }
  }

  @Override
  public List<StationGroup> listByCompany(UUID companyId) {
    String sql =
        "SELECT "
            + GROUP_COLUMNS
            + " FROM "
            + table("station_groups")
            + " WHERE company_id = ? ORDER BY code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, companyId);
      try (var rs = statement.executeQuery()) {
        List<StationGroup> results = new ArrayList<>();
        while (rs.next()) {
          results.add(mapGroup(rs));
        }
        return results;
      }
    } catch (SQLException ex) {
      throw new StorageException("列出车站组失败", ex);
    }
  }

  @Override
  public List<StationGroup> listAll() {
    String sql =
        "SELECT " + GROUP_COLUMNS + " FROM " + table("station_groups") + " ORDER BY code ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<StationGroup> results = new ArrayList<>();
      while (rs.next()) {
        results.add(mapGroup(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出车站组失败", ex);
    }
  }

  /**
   * 按 ID 新增或更新。
   *
   * <p>不用方言 upsert：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 对任何唯一键冲突都会触发， 同公司同代码的另一个组会被悄悄改写成这一条。先按
   * ID 更新，没有这一行再插入，唯一约束冲突在两种后端上都会报错。
   */
  @Override
  public StationGroup save(StationGroup group) {
    Objects.requireNonNull(group, "group");
    String update =
        "UPDATE "
            + table("station_groups")
            + " SET company_id = ?, code = ?, name = ?, secondary_name = ?, metadata = ?,"
            + " updated_at = ? WHERE id = ?";
    String insert =
        "INSERT INTO "
            + table("station_groups")
            + " ("
            + GROUP_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              setUuid(statement, 1, group.companyId());
              statement.setString(2, group.code());
              statement.setString(3, group.name());
              statement.setString(4, group.secondaryName().orElse(null));
              statement.setString(5, toJson(group.metadata()));
              setInstant(statement, 6, group.updatedAt());
              setUuid(statement, 7, group.id());
              if (statement.executeUpdate() > 0) {
                return group;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, group.id());
              setUuid(statement, 2, group.companyId());
              statement.setString(3, group.code());
              statement.setString(4, group.name());
              statement.setString(5, group.secondaryName().orElse(null));
              statement.setString(6, toJson(group.metadata()));
              setInstant(statement, 7, group.createdAt());
              setInstant(statement, 8, group.updatedAt());
              statement.executeUpdate();
            }
            return group;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存车站组失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    // 与时刻表同理：不依赖 PRAGMA foreign_keys 的级联，显式把成员删干净。
    try {
      inTransaction(
          connection -> {
            try (var statement =
                connection.prepareStatement(
                    "DELETE FROM " + table("station_group_members") + " WHERE group_id = ?")) {
              setUuid(statement, 1, id);
              statement.executeUpdate();
            }
            try (var statement =
                connection.prepareStatement(
                    "DELETE FROM " + table("station_groups") + " WHERE id = ?")) {
              setUuid(statement, 1, id);
              statement.executeUpdate();
            }
            return null;
          });
    } catch (SQLException ex) {
      throw new StorageException("删除车站组失败", ex);
    }
  }

  @Override
  public List<StationGroupMember> listMembers(UUID groupId) {
    String sql =
        "SELECT "
            + MEMBER_COLUMNS
            + " FROM "
            + table("station_group_members")
            + " WHERE group_id = ? ORDER BY sort_order ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, groupId);
      try (var rs = statement.executeQuery()) {
        List<StationGroupMember> results = new ArrayList<>();
        while (rs.next()) {
          results.add(mapMember(rs));
        }
        return results;
      }
    } catch (SQLException ex) {
      throw new StorageException("列出车站组成员失败", ex);
    }
  }

  @Override
  public List<StationGroupMember> listAllMembers() {
    String sql =
        "SELECT "
            + MEMBER_COLUMNS
            + " FROM "
            + table("station_group_members")
            + " ORDER BY sort_order ASC";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<StationGroupMember> results = new ArrayList<>();
      while (rs.next()) {
        results.add(mapMember(rs));
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出车站组成员失败", ex);
    }
  }

  @Override
  public Optional<StationGroupMember> findMemberByStation(UUID stationId) {
    String sql =
        "SELECT "
            + MEMBER_COLUMNS
            + " FROM "
            + table("station_group_members")
            + " WHERE station_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, stationId);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(mapMember(rs)) : Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按车站查询车站组成员失败", ex);
    }
  }

  /**
   * 新增或更新成员（按 {@code groupId + stationId}）。
   *
   * <p>同 {@link #save}：不用方言 upsert，否则 MySQL 上“车站加入第二个组”会被当成更新原组里的那条成员。
   */
  @Override
  public StationGroupMember saveMember(StationGroupMember member) {
    Objects.requireNonNull(member, "member");
    String update =
        "UPDATE "
            + table("station_group_members")
            + " SET transfer_type = ?, walk_secs = ?, sort_order = ?"
            + " WHERE group_id = ? AND station_id = ?";
    String insert =
        "INSERT INTO "
            + table("station_group_members")
            + " ("
            + MEMBER_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?)";
    try {
      return inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(update)) {
              statement.setString(1, member.transferType().name());
              setNullableInt(statement, 2, member.walkSeconds());
              statement.setInt(3, member.sortOrder());
              setUuid(statement, 4, member.groupId());
              setUuid(statement, 5, member.stationId());
              if (statement.executeUpdate() > 0) {
                return member;
              }
            }
            try (var statement = connection.prepareStatement(insert)) {
              setUuid(statement, 1, member.groupId());
              setUuid(statement, 2, member.stationId());
              statement.setString(3, member.transferType().name());
              setNullableInt(statement, 4, member.walkSeconds());
              statement.setInt(5, member.sortOrder());
              statement.executeUpdate();
            }
            return member;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存车站组成员失败", ex);
    }
  }

  @Override
  public void removeMember(UUID groupId, UUID stationId) {
    String sql =
        "DELETE FROM " + table("station_group_members") + " WHERE group_id = ? AND station_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, groupId);
      setUuid(statement, 2, stationId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("移除车站组成员失败", ex);
    }
  }

  private static void setNullableInt(
      PreparedStatement statement, int index, Optional<Integer> value) throws SQLException {
    if (value.isPresent()) {
      statement.setInt(index, value.get());
    } else {
      statement.setObject(index, null);
    }
  }

  private StationGroup mapGroup(ResultSet rs) throws SQLException {
    return new StationGroup(
        requireUuid(rs, "id"),
        requireUuid(rs, "company_id"),
        rs.getString("code"),
        rs.getString("name"),
        Optional.ofNullable(rs.getString("secondary_name")),
        fromJson(rs.getString("metadata")),
        readInstant(rs, "created_at"),
        readInstant(rs, "updated_at"));
  }

  private StationGroupMember mapMember(ResultSet rs) throws SQLException {
    String rawType = rs.getString("transfer_type");
    StationTransferType type =
        StationTransferType.fromToken(rawType).orElse(StationTransferType.IN_STATION);
    Integer sortOrder = readNullableInteger(rs, "sort_order");
    return new StationGroupMember(
        requireUuid(rs, "group_id"),
        requireUuid(rs, "station_id"),
        type,
        Optional.ofNullable(readNullableInteger(rs, "walk_secs")),
        sortOrder == null ? 0 : sortOrder);
  }
}
