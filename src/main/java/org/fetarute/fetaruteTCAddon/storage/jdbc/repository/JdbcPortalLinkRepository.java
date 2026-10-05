package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的传送门连接仓库。 */
public final class JdbcPortalLinkRepository extends JdbcRepositorySupport
    implements PortalLinkRepository {

  private static final String TABLE = "rail_portal_links";

  public JdbcPortalLinkRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<PortalLink> listAll() {
    String sql = "SELECT * FROM " + table(TABLE);
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        ResultSet rs = statement.executeQuery()) {
      List<PortalLink> links = new ArrayList<>();
      while (rs.next()) {
        links.add(
            new PortalLink(
                requireUuid(rs, "from_world"),
                NodeId.of(rs.getString("from_node")),
                requireUuid(rs, "to_world"),
                NodeId.of(rs.getString("to_node")),
                PortalLink.Source.valueOf(rs.getString("source")),
                rs.getDouble("transit_blocks"),
                readInstant(rs, "updated_at")));
      }
      return links;
    } catch (SQLException | IllegalArgumentException ex) {
      throw new StorageException("读取 rail_portal_links 失败", ex);
    }
  }

  @Override
  public void upsert(PortalLink link) {
    Objects.requireNonNull(link, "link");
    String insert =
        "INSERT INTO "
            + table(TABLE)
            + " (from_world, from_node, to_world, to_node, source, transit_blocks, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("from_world", "from_node"),
            List.of("to_world", "to_node", "source", "transit_blocks", "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, link.fromWorld());
      statement.setString(2, link.fromNode().value());
      setUuid(statement, 3, link.toWorld());
      statement.setString(4, link.toNode().value());
      statement.setString(5, link.source().name());
      statement.setDouble(6, link.transitBlocks());
      setInstant(statement, 7, link.updatedAt());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("保存 rail_portal_links 失败", ex);
    }
  }

  @Override
  public void delete(UUID fromWorld, NodeId fromNode) {
    String sql = "DELETE FROM " + table(TABLE) + " WHERE from_world = ? AND from_node = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, fromWorld);
      statement.setString(2, fromNode.value());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_portal_links 失败", ex);
    }
  }

  @Override
  public void deleteAuto() {
    String sql = "DELETE FROM " + table(TABLE) + " WHERE source = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, PortalLink.Source.AUTO.name());
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除 rail_portal_links 失败", ex);
    }
  }
}
