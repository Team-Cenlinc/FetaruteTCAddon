package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的 RailEdge 仓库。 */
public final class JdbcRailEdgeRepository extends JdbcRepositorySupport
    implements RailEdgeRepository {

  public JdbcRailEdgeRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<RailEdgeRecord> listByWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql =
        "SELECT world_id, node_a, node_b, length_blocks, base_speed_limit, bidirectional FROM "
            + table("rail_edges")
            + " WHERE world_id = ?"
            + " ORDER BY node_a ASC, node_b ASC";
    List<RailEdgeRecord> results = new ArrayList<>();
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
      throw new StorageException("读取 rail_edges 失败", ex);
    }
  }

  @Override
  public void replaceWorld(UUID worldId, Collection<RailEdgeRecord> edges) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(edges, "edges");
    deleteWorld(worldId);
    if (edges.isEmpty()) {
      return;
    }

    String insert =
        "INSERT INTO "
            + table("rail_edges")
            + " (world_id, node_a, node_b, length_blocks, base_speed_limit, bidirectional)"
            + " VALUES (?, ?, ?, ?, ?, ?)";

    try (var connection = openConnection();
        var statement = connection.prepareStatement(insert)) {
      for (RailEdgeRecord edge : edges) {
        writeRow(statement, edge);
        statement.addBatch();
      }
      statement.executeBatch();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("写入 rail_edges 失败", ex);
    }
  }

  @Override
  public void deleteWorld(UUID worldId) {
    Objects.requireNonNull(worldId, "worldId");
    String sql = "DELETE FROM " + table("rail_edges") + " WHERE world_id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, worldId);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("清空 rail_edges 失败", ex);
    }
  }

  private void writeRow(PreparedStatement statement, RailEdgeRecord edge) throws SQLException {
    EdgeId id = edge.edgeId();
    setUuid(statement, 1, edge.worldId());
    statement.setString(2, id.a().value());
    statement.setString(3, id.b().value());
    statement.setInt(4, edge.lengthBlocks());
    statement.setDouble(5, edge.baseSpeedLimit());
    statement.setInt(6, edge.bidirectional() ? 1 : 0);
  }

  private RailEdgeRecord mapRow(ResultSet rs) throws SQLException {
    UUID worldId = requireUuid(rs, "world_id");
    String aRaw = rs.getString("node_a");
    String bRaw = rs.getString("node_b");
    Integer lengthBlocks = readNullableInteger(rs, "length_blocks");
    Double baseSpeedLimit = rs.getObject("base_speed_limit", Double.class);
    Integer bidirectional = readNullableInteger(rs, "bidirectional");
    if (aRaw == null
        || bRaw == null
        || lengthBlocks == null
        || baseSpeedLimit == null
        || bidirectional == null) {
      throw new StorageException("rail_edges 行缺少必要字段");
    }
    EdgeId edgeId = EdgeId.undirected(NodeId.of(aRaw), NodeId.of(bRaw));
    return new RailEdgeRecord(worldId, edgeId, lengthBlocks, baseSpeedLimit, bidirectional != 0);
  }
}
