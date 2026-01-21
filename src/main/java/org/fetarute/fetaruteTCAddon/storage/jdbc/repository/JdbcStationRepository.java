package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationLocation;
import org.fetarute.fetaruteTCAddon.company.model.StationSidingPool;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的车站仓库。
 *
 * <p>Station 同时包含“业务主数据”（code/name）与“可选的位置/图节点信息”（world/location/graphNodeId），因此写入时允许大量列为
 * null；读取时对位置字段采用“全量一致性”策略：只有 x/y/z/yaw/pitch 均存在时才认为 location 有效。
 */
public final class JdbcStationRepository extends JdbcRepositorySupport
    implements StationRepository {

  private static final Type SIDING_POOL_LIST_TYPE =
      new TypeToken<List<StationSidingPool>>() {}.getType();

  public JdbcStationRepository(
      DataSource dataSource,
      SqlDialect dialect,
      String tablePrefix,
      java.util.function.Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public Optional<Station> findById(UUID id) {
    String sql =
        "SELECT id, code, operator_id, primary_line_id, name, secondary_name, world, x, y, z, yaw, pitch, graph_node_id, amenities, metadata, created_at, updated_at FROM "
            + table("stations")
            + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("查询站点失败", ex);
    }
  }

  @Override
  public Optional<Station> findByOperatorAndCode(UUID operatorId, String code) {
    String sql =
        "SELECT id, code, operator_id, primary_line_id, name, secondary_name, world, x, y, z, yaw, pitch, graph_node_id, amenities, metadata, created_at, updated_at FROM "
            + table("stations")
            + " WHERE operator_id = ? AND code = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      statement.setString(2, code);
      try (var rs = statement.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapRow(rs));
        }
        return Optional.empty();
      }
    } catch (SQLException ex) {
      throw new StorageException("按 operator+code 查询站点失败", ex);
    }
  }

  @Override
  public List<Station> listByOperator(UUID operatorId) {
    String sql =
        "SELECT id, code, operator_id, primary_line_id, name, secondary_name, world, x, y, z, yaw, pitch, graph_node_id, amenities, metadata, created_at, updated_at FROM "
            + table("stations")
            + " WHERE operator_id = ?"
            + " ORDER BY code ASC";
    List<Station> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, operatorId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("列出站点失败", ex);
    }
  }

  @Override
  public List<Station> listByLine(UUID lineId) {
    String sql =
        "SELECT id, code, operator_id, primary_line_id, name, secondary_name, world, x, y, z, yaw, pitch, graph_node_id, amenities, metadata, created_at, updated_at FROM "
            + table("stations")
            + " WHERE primary_line_id = ?"
            + " ORDER BY code ASC";
    List<Station> results = new ArrayList<>();
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, lineId);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          results.add(mapRow(rs));
        }
      }
      return results;
    } catch (SQLException ex) {
      throw new StorageException("按线路列出站点失败", ex);
    }
  }

  @Override
  public Station save(Station station) {
    Objects.requireNonNull(station, "station");
    String insert =
        "INSERT INTO "
            + table("stations")
            + " (id, code, operator_id, primary_line_id, name, secondary_name, world, x, y, z, yaw, pitch, graph_node_id, amenities, metadata, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    String sql =
        dialect.applyUpsert(
            insert,
            List.of("id"),
            List.of(
                "code",
                "operator_id",
                "primary_line_id",
                "name",
                "secondary_name",
                "world",
                "x",
                "y",
                "z",
                "yaw",
                "pitch",
                "graph_node_id",
                "amenities",
                "metadata",
                "updated_at"));
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      writeStation(statement, station);
      statement.executeUpdate();
      connection.commitIfNecessary();
      return station;
    } catch (SQLException ex) {
      throw new StorageException("保存站点失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    String sql = "DELETE FROM " + table("stations") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除站点失败", ex);
    }
  }

  private void writeStation(java.sql.PreparedStatement statement, Station station)
      throws SQLException {
    setUuid(statement, 1, station.id());
    statement.setString(2, station.code());
    setUuid(statement, 3, station.operatorId());
    setUuid(statement, 4, station.primaryLineId().orElse(null));
    statement.setString(5, station.name());
    statement.setString(6, station.secondaryName().orElse(null));
    statement.setString(7, station.world().orElse(null));

    Optional<StationLocation> location = station.location();
    if (location.isPresent()) {
      statement.setDouble(8, location.get().x());
      statement.setDouble(9, location.get().y());
      statement.setDouble(10, location.get().z());
      statement.setDouble(11, location.get().yaw());
      statement.setDouble(12, location.get().pitch());
    } else {
      statement.setObject(8, null);
      statement.setObject(9, null);
      statement.setObject(10, null);
      statement.setObject(11, null);
      statement.setObject(12, null);
    }

    statement.setString(13, station.graphNodeId().orElse(null));
    statement.setString(14, station.amenities().map(this::toJson).orElse(null));

    // 将 siding pools 注入 metadata，便于持久化（表结构不变）
    Map<String, Object> metadata = new java.util.HashMap<>(station.metadata());
    if (!station.sidingPools().isEmpty()) {
      metadata.put("_siding_pools", station.sidingPools());
    }
    statement.setString(15, toJson(metadata));

    setInstant(statement, 16, station.createdAt());
    setInstant(statement, 17, station.updatedAt());
  }

  private Station mapRow(ResultSet rs) throws SQLException {
    UUID id = requireUuid(rs, "id");
    String code = rs.getString("code");
    UUID operatorId = requireUuid(rs, "operator_id");
    UUID primaryLineId = readUuid(rs, "primary_line_id");
    String name = rs.getString("name");
    String secondaryName = rs.getString("secondary_name");
    String world = rs.getString("world");
    Double x = rs.getObject("x", Double.class);
    Double y = rs.getObject("y", Double.class);
    Double z = rs.getObject("z", Double.class);
    Double yaw = rs.getObject("yaw", Double.class);
    Double pitch = rs.getObject("pitch", Double.class);
    String graphNodeId = rs.getString("graph_node_id");
    String amenitiesJson = rs.getString("amenities");
    Map<String, Object> metadata = fromJson(rs.getString("metadata"));
    Instant createdAt = readInstant(rs, "created_at");
    Instant updatedAt = readInstant(rs, "updated_at");

    Optional<StationLocation> location = Optional.empty();
    if (x != null && y != null && z != null && yaw != null && pitch != null) {
      location = Optional.of(new StationLocation(x, y, z, yaw.floatValue(), pitch.floatValue()));
    }

    Optional<Map<String, Object>> amenities =
        amenitiesJson == null ? Optional.empty() : Optional.of(fromJson(amenitiesJson));

    List<StationSidingPool> sidingPools = List.of();
    if (metadata.containsKey("_siding_pools")) {
      try {
        Object raw = metadata.get("_siding_pools");
        String json = gson.toJson(raw);
        sidingPools = gson.fromJson(json, SIDING_POOL_LIST_TYPE);
        // 从 metadata 视图移除该字段，避免上层误以为它是普通 metadata（可选）
        metadata = new java.util.HashMap<>(metadata);
        metadata.remove("_siding_pools");
      } catch (Exception ex) {
        debug("解析 Station Siding Pools 失败: " + ex.getMessage());
      }
    }

    return new Station(
        id,
        code,
        operatorId,
        Optional.ofNullable(primaryLineId),
        name,
        Optional.ofNullable(secondaryName),
        Optional.ofNullable(world),
        location,
        Optional.ofNullable(graphNodeId),
        amenities,
        sidingPools,
        metadata,
        createdAt,
        updatedAt);
  }
}
