package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 RailEdgeOverride 仓库。 */
public final class JdbcRailEdgeOverrideRepository extends JdbcRepositorySupport
    implements RailEdgeOverrideRepository {

  public JdbcRailEdgeOverrideRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<RailEdgeOverrideRecord> findByEdge(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    String sql =
        "SELECT world_id, node_a, node_b, speed_limit_bps, temp_speed_limit_bps, temp_speed_limit_until,"
            + " blocked_manual, blocked_until, updated_at"
            + " FROM "
            + table("rail_edge_overrides")
            + " WHERE world_id = ? AND node_a = ? AND node_b = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setString(2, normalized.a().value());
      statement.setString(3, normalized.b().value());
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
      }
      return Optional.empty();
    } catch (SQLException ex) {
      throw new StorageException("查询 rail_edge_overrides 失败", ex);
    }
  }

  @Override
  public List<RailEdgeOverrideRecord> listByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT world_id, node_a, node_b, speed_limit_bps, temp_speed_limit_bps, temp_speed_limit_until,"
            + " blocked_manual, blocked_until, updated_at"
            + " FROM "
            + table("rail_edge_overrides")
            + " WHERE world_id = ?"
            + " ORDER BY node_a ASC, node_b ASC";
    List<RailEdgeOverrideRecord> results = new ArrayList<>();
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
      throw new StorageException("读取 rail_edge_overrides 失败", ex);
    }
  }

  @Override
  public void upsert(RailEdgeOverrideRecord override) {
    Objects.requireNonNull(override, "override");
    String insert =
        "INSERT INTO "
            + table("rail_edge_overrides")
            + " (world_id, node_a, node_b, speed_limit_bps, temp_speed_limit_bps, temp_speed_limit_until,"
            + " blocked_manual, blocked_until, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("world_id", "node_a", "node_b"),
            List.of(
                "speed_limit_bps",
                "temp_speed_limit_bps",
                "temp_speed_limit_until",
                "blocked_manual",
                "blocked_until",
                "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeRow(statement, override);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("写入 rail_edge_overrides 失败", ex);
    }
  }

  @Override
  public void delete(UUID worldId, EdgeId edgeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edgeId, "edgeId");
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    String sql =
        "DELETE FROM "
            + table("rail_edge_overrides")
            + " WHERE world_id = ? AND node_a = ? AND node_b = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setString(2, normalized.a().value());
      statement.setString(3, normalized.b().value());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_edge_overrides 失败", ex);
    }
  }

  @Override
  public void deleteWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql = "DELETE FROM " + table("rail_edge_overrides") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("清空 rail_edge_overrides 失败", ex);
    }
  }

  private void writeRow(java.sql.PreparedStatement statement, RailEdgeOverrideRecord override)
      throws SQLException {
    EdgeId id = EdgeId.undirected(override.edgeId().a(), override.edgeId().b());
    setUuid(statement, 1, override.worldId());
    statement.setString(2, id.a().value());
    statement.setString(3, id.b().value());
    statement.setObject(
        4,
        override.speedLimitBlocksPerSecond().isPresent()
            ? override.speedLimitBlocksPerSecond().getAsDouble()
            : null);
    statement.setObject(
        5,
        override.tempSpeedLimitBlocksPerSecond().isPresent()
            ? override.tempSpeedLimitBlocksPerSecond().getAsDouble()
            : null);
    setInstant(statement, 6, override.tempSpeedLimitUntil().orElse(null));
    statement.setInt(7, override.blockedManual() ? 1 : 0);
    setInstant(statement, 8, override.blockedUntil().orElse(null));
    setInstant(statement, 9, override.updatedAt());
  }

  private RailEdgeOverrideRecord mapRow(ResultSet rs) throws SQLException {
    UUID worldId = requireUuid(rs, "world_id");
    String aRaw = rs.getString("node_a");
    String bRaw = rs.getString("node_b");
    Double speedLimit = readNullableDouble(rs, "speed_limit_bps");
    Double tempSpeedLimit = readNullableDouble(rs, "temp_speed_limit_bps");
    Instant tempUntil = readNullableInstant(rs, "temp_speed_limit_until");
    Integer blockedManual = readNullableInteger(rs, "blocked_manual");
    Instant blockedUntil = readNullableInstant(rs, "blocked_until");
    Instant updatedAt = readInstant(rs, "updated_at");
    if (aRaw == null || bRaw == null) {
      throw new StorageException("rail_edge_overrides 行缺少必要字段");
    }
    EdgeId edgeId = EdgeId.undirected(NodeId.of(aRaw), NodeId.of(bRaw));
    OptionalDouble speedOpt =
        speedLimit == null ? OptionalDouble.empty() : OptionalDouble.of(speedLimit);
    OptionalDouble tempSpeedOpt =
        tempSpeedLimit == null ? OptionalDouble.empty() : OptionalDouble.of(tempSpeedLimit);
    return new RailEdgeOverrideRecord(
        worldId,
        edgeId,
        speedOpt,
        tempSpeedOpt,
        Optional.ofNullable(tempUntil),
        blockedManual != null && blockedManual != 0,
        Optional.ofNullable(blockedUntil),
        updatedAt);
  }
}
