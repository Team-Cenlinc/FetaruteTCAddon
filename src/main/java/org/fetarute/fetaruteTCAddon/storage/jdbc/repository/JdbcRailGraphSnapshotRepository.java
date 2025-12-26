package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 RailGraph 快照仓库。 */
public final class JdbcRailGraphSnapshotRepository extends JdbcRepositorySupport
    implements RailGraphSnapshotRepository {

  public JdbcRailGraphSnapshotRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<RailGraphSnapshotRecord> findByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT world_id, built_at, node_count, edge_count, node_signature FROM "
            + table("rail_graph_snapshots")
            + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
      }
      return Optional.empty();
    } catch (SQLException ex) {
      throw new StorageException("查询 rail_graph_snapshots 失败", ex);
    }
  }

  @Override
  public RailGraphSnapshotRecord save(RailGraphSnapshotRecord snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    String insert =
        "INSERT INTO "
            + table("rail_graph_snapshots")
            + " (world_id, built_at, node_count, edge_count, node_signature)"
            + " VALUES (?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("world_id"),
            List.of("built_at", "node_count", "edge_count", "node_signature"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, snapshot.worldId());
      setInstant(statement, 2, snapshot.builtAt());
      statement.setInt(3, snapshot.nodeCount());
      statement.setInt(4, snapshot.edgeCount());
      statement.setString(5, snapshot.nodeSignature());
      statement.executeUpdate();
      connection.commitIfNecessary();
      return snapshot;
    } catch (SQLException ex) {
      throw new StorageException("保存 rail_graph_snapshots 失败", ex);
    }
  }

  @Override
  public void delete(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql = "DELETE FROM " + table("rail_graph_snapshots") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_graph_snapshots 失败", ex);
    }
  }

  private RailGraphSnapshotRecord mapRow(ResultSet rs) throws SQLException {
    UUID worldId = requireUuid(rs, "world_id");
    Instant builtAt = readInstant(rs, "built_at");
    Integer nodeCount = readNullableInteger(rs, "node_count");
    Integer edgeCount = readNullableInteger(rs, "edge_count");
    String signature = rs.getString("node_signature");
    if (nodeCount == null || edgeCount == null) {
      throw new StorageException("rail_graph_snapshots 行缺少必要字段");
    }
    return new RailGraphSnapshotRecord(worldId, builtAt, nodeCount, edgeCount, signature);
  }
}
