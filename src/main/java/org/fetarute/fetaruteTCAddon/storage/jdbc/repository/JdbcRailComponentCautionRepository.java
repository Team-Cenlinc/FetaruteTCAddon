package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailComponentCautionRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 RailComponentCaution 仓库。 */
public final class JdbcRailComponentCautionRepository extends JdbcRepositorySupport
    implements RailComponentCautionRepository {

  public JdbcRailComponentCautionRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<RailComponentCautionRecord> find(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    String sql =
        "SELECT world_id, component_key, caution_speed_bps, updated_at"
            + " FROM "
            + table("rail_component_cautions")
            + " WHERE world_id = ? AND component_key = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setString(2, componentKey);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
      }
      return Optional.empty();
    } catch (SQLException ex) {
      throw new StorageException("查询 rail_component_cautions 失败", ex);
    }
  }

  @Override
  public List<RailComponentCautionRecord> listByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT world_id, component_key, caution_speed_bps, updated_at"
            + " FROM "
            + table("rail_component_cautions")
            + " WHERE world_id = ?"
            + " ORDER BY component_key ASC";
    List<RailComponentCautionRecord> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("读取 rail_component_cautions 失败", ex);
    }
  }

  @Override
  public void upsert(RailComponentCautionRecord record) {
    Objects.requireNonNull(record, "record");
    String insert =
        "INSERT INTO "
            + table("rail_component_cautions")
            + " (world_id, component_key, caution_speed_bps, updated_at)"
            + " VALUES (?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("world_id", "component_key"),
            List.of("caution_speed_bps", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeRow(statement, record);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("写入 rail_component_cautions 失败", ex);
    }
  }

  @Override
  public void delete(UUID worldId, String componentKey) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(componentKey, "componentKey");
    String sql =
        "DELETE FROM "
            + table("rail_component_cautions")
            + " WHERE world_id = ? AND component_key = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setString(2, componentKey);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_component_cautions 失败", ex);
    }
  }

  private void writeRow(java.sql.PreparedStatement statement, RailComponentCautionRecord record)
      throws SQLException {
    setUuid(statement, 1, record.worldId());
    statement.setString(2, record.componentKey());
    statement.setDouble(3, record.cautionSpeedBlocksPerSecond());
    setInstant(statement, 4, record.updatedAt());
  }

  private RailComponentCautionRecord mapRow(ResultSet rs) throws SQLException {
    UUID worldId = requireUuid(rs, "world_id");
    String componentKey = rs.getString("component_key");
    Double cautionBps = readNullableDouble(rs, "caution_speed_bps");
    Instant updatedAt = readInstant(rs, "updated_at");
    if (componentKey == null || cautionBps == null) {
      throw new StorageException("rail_component_cautions 行缺少必要字段");
    }
    return new RailComponentCautionRecord(worldId, componentKey, cautionBps, updatedAt);
  }
}
