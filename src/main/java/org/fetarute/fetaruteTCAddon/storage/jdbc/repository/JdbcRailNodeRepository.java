package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 RailNode 仓库。 */
public final class JdbcRailNodeRepository extends JdbcRepositorySupport
    implements RailNodeRepository {

  public JdbcRailNodeRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<RailNodeRecord> listByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT world_id, node_id, node_type, x, y, z, tc_destination, waypoint_operator, waypoint_origin, waypoint_destination, waypoint_track, waypoint_sequence, waypoint_kind FROM "
            + table("rail_nodes")
            + " WHERE world_id = ?"
            + " ORDER BY node_id ASC";
    List<RailNodeRecord> results = new ArrayList<>();
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
      throw new StorageException("读取 rail_nodes 失败", ex);
    }
  }

  @Override
  public void upsert(RailNodeRecord node) {
    Objects.requireNonNull(node, "node");
    String insert =
        "INSERT INTO "
            + table("rail_nodes")
            + " (world_id, node_id, node_type, x, y, z, tc_destination, waypoint_operator, waypoint_origin, waypoint_destination, waypoint_track, waypoint_sequence, waypoint_kind)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("world_id", "node_id"),
            List.of(
                "node_type",
                "x",
                "y",
                "z",
                "tc_destination",
                "waypoint_operator",
                "waypoint_origin",
                "waypoint_destination",
                "waypoint_track",
                "waypoint_sequence",
                "waypoint_kind"));

    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeRow(statement, node);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("写入 rail_nodes 失败", ex);
    }
  }

  @Override
  public void delete(UUID worldId, NodeId nodeId) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeId, "nodeId");
    String sql = "DELETE FROM " + table("rail_nodes") + " WHERE world_id = ? AND node_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setString(2, nodeId.value());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_nodes 失败", ex);
    }
  }

  @Override
  public void deleteByPosition(UUID worldId, int x, int y, int z) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "DELETE FROM " + table("rail_nodes") + " WHERE world_id = ? AND x = ? AND y = ? AND z = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.setInt(2, x);
      statement.setInt(3, y);
      statement.setInt(4, z);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_nodes 失败", ex);
    }
  }

  @Override
  public void replaceWorld(UUID worldId, Collection<RailNodeRecord> nodes) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodes, "nodes");
    deleteWorld(worldId);
    if (nodes.isEmpty()) {
      return;
    }

    String insert =
        "INSERT INTO "
            + table("rail_nodes")
            + " (world_id, node_id, node_type, x, y, z, tc_destination, waypoint_operator, waypoint_origin, waypoint_destination, waypoint_track, waypoint_sequence, waypoint_kind)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    try (var connection = openConnection();
        var statement = connection.prepareStatement(insert)) {
      for (RailNodeRecord node : nodes) {
        writeRow(statement, node);
        statement.addBatch();
      }
      statement.executeBatch();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("写入 rail_nodes 失败", ex);
    }
  }

  @Override
  public void deleteWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql = "DELETE FROM " + table("rail_nodes") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("清空 rail_nodes 失败", ex);
    }
  }

  private void writeRow(PreparedStatement statement, RailNodeRecord node) throws SQLException {
    setUuid(statement, 1, node.worldId());
    statement.setString(2, node.nodeId().value());
    statement.setString(3, node.nodeType().name());
    statement.setInt(4, node.x());
    statement.setInt(5, node.y());
    statement.setInt(6, node.z());
    statement.setString(7, node.trainCartsDestination().orElse(null));

    Optional<WaypointMetadata> metadataOpt = node.waypointMetadata();
    if (metadataOpt.isPresent()) {
      WaypointMetadata metadata = metadataOpt.get();
      statement.setString(8, metadata.operator());
      statement.setString(9, metadata.originStation());
      statement.setString(10, metadata.destinationStation().orElse(null));
      statement.setInt(11, metadata.trackNumber());
      statement.setString(12, metadata.sequence().orElse(null));
      statement.setString(13, metadata.kind().name());
    } else {
      statement.setObject(8, null);
      statement.setObject(9, null);
      statement.setObject(10, null);
      statement.setObject(11, null);
      statement.setObject(12, null);
      statement.setObject(13, null);
    }
  }

  private RailNodeRecord mapRow(ResultSet rs) throws SQLException {
    UUID worldId = requireUuid(rs, "world_id");
    String nodeIdRaw = rs.getString("node_id");
    String nodeTypeRaw = rs.getString("node_type");
    Integer x = readNullableInteger(rs, "x");
    Integer y = readNullableInteger(rs, "y");
    Integer z = readNullableInteger(rs, "z");
    if (nodeIdRaw == null || nodeTypeRaw == null || x == null || y == null || z == null) {
      throw new StorageException("rail_nodes 行缺少必要字段");
    }
    NodeType nodeType;
    try {
      nodeType = NodeType.valueOf(nodeTypeRaw.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new StorageException("rail_nodes.node_type 无法解析: " + nodeTypeRaw, ex);
    }

    Optional<String> tcDestination = Optional.ofNullable(rs.getString("tc_destination"));

    Optional<WaypointMetadata> waypointMetadata =
        readWaypointMetadata(
            rs.getString("waypoint_operator"),
            rs.getString("waypoint_origin"),
            rs.getString("waypoint_destination"),
            readNullableInteger(rs, "waypoint_track"),
            rs.getString("waypoint_sequence"),
            rs.getString("waypoint_kind"));

    return new RailNodeRecord(
        worldId, NodeId.of(nodeIdRaw), nodeType, x, y, z, tcDestination, waypointMetadata);
  }

  private Optional<WaypointMetadata> readWaypointMetadata(
      String operator,
      String origin,
      String destination,
      Integer track,
      String sequence,
      String kindRaw) {
    if (kindRaw == null || kindRaw.isBlank()) {
      return Optional.empty();
    }
    WaypointKind kind;
    try {
      kind = WaypointKind.valueOf(kindRaw.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      debug("rail_nodes.waypoint_kind 无法解析: " + kindRaw);
      return Optional.empty();
    }
    if (operator == null || origin == null || track == null) {
      debug("rail_nodes waypoint 字段不完整: kind=" + kindRaw);
      return Optional.empty();
    }
    try {
      return Optional.of(
          new WaypointMetadata(
              operator,
              origin,
              Optional.ofNullable(destination),
              track,
              Optional.ofNullable(sequence),
              kind));
    } catch (RuntimeException ex) {
      debug("rail_nodes waypoint 元数据无效: kind=" + kindRaw + " msg=" + ex.getMessage());
      return Optional.empty();
    }
  }
}
