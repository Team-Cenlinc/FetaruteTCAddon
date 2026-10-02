package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotCodec;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailInterlockingSnapshotRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的世界级稀疏物理联锁快照仓库。 */
public final class JdbcRailInterlockingSnapshotRepository extends JdbcRepositorySupport
    implements RailInterlockingSnapshotRepository {

  public JdbcRailInterlockingSnapshotRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<RailInterlockingSnapshotRecord> findByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT snapshot_json FROM " + table("rail_interlocking_snapshots") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      try (var rs = statement.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String json = rs.getString("snapshot_json");
        Optional<RailInterlockingSnapshotRecord> decoded =
            RailInterlockingSnapshotCodec.decode(worldId, json);
        if (decoded.isEmpty()) {
          debug("警告: 稀疏物理联锁快照损坏或版本不受支持，已按 fail-closed 忽略: world=" + worldId);
        }
        return decoded;
      }
    } catch (SQLException ex) {
      throw new StorageException("读取 rail_interlocking_snapshots 失败", ex);
    }
  }

  @Override
  public RailInterlockingSnapshotRecord save(RailInterlockingSnapshotRecord snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    String insert =
        "INSERT INTO "
            + table("rail_interlocking_snapshots")
            + " (world_id, snapshot_json) VALUES (?, ?)";
    String sql = dialect.applyUpsert(insert, List.of("world_id"), List.of("snapshot_json"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, snapshot.worldId());
      statement.setString(2, RailInterlockingSnapshotCodec.encode(snapshot));
      statement.executeUpdate();
      connection.commitIfNecessary();
      return snapshot;
    } catch (SQLException ex) {
      throw new StorageException("保存 rail_interlocking_snapshots 失败", ex);
    }
  }

  @Override
  public void delete(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql = "DELETE FROM " + table("rail_interlocking_snapshots") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_interlocking_snapshots 失败", ex);
    }
  }
}
