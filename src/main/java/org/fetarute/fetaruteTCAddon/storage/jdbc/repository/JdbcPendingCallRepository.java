package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.call.PendingCallRecord;
import org.fetarute.fetaruteTCAddon.call.repository.PendingCallRepository;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 实现的未派出叫车仓库。屏幕站台存为一个 JSON 字符串数组。 */
public final class JdbcPendingCallRepository extends JdbcRepositorySupport
    implements PendingCallRepository {

  private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

  private static final String COLUMNS =
      "id, player_uuid, operator_code, station_code, screen_platforms, direction_key, line_id,"
          + " created_at, eta_seconds";

  public JdbcPendingCallRepository(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    super(dataSource, dialect, tablePrefix, debugLogger);
  }

  @Override
  public List<PendingCallRecord> listAll() {
    String sql = "SELECT " + COLUMNS + " FROM " + table("pending_calls") + " ORDER BY created_at";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      List<PendingCallRecord> calls = new ArrayList<>();
      while (rs.next()) {
        calls.add(map(rs));
      }
      return calls;
    } catch (SQLException ex) {
      throw new StorageException("列出未派出的叫车失败", ex);
    }
  }

  /** 先按编号删除再插入：一条叫车只写一次，不会频繁更新。 */
  @Override
  public void save(PendingCallRecord call) {
    Objects.requireNonNull(call, "call");
    String delete = "DELETE FROM " + table("pending_calls") + " WHERE id = ?";
    String insert =
        "INSERT INTO "
            + table("pending_calls")
            + " ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      inTransaction(
          connection -> {
            try (var statement = connection.prepareStatement(delete)) {
              setUuid(statement, 1, call.id());
              statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(insert)) {
              bind(statement, call);
              statement.executeUpdate();
            }
            return null;
          });
    } catch (SQLException ex) {
      throw new StorageException("保存未派出的叫车失败", ex);
    }
  }

  @Override
  public void delete(UUID id) {
    if (id == null) {
      return;
    }
    String sql = "DELETE FROM " + table("pending_calls") + " WHERE id = ?";
    try (var connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      setUuid(statement, 1, id);
      statement.executeUpdate();
      connection.commitIfNecessary();
    } catch (SQLException ex) {
      throw new StorageException("删除未派出的叫车失败", ex);
    }
  }

  private void bind(PreparedStatement statement, PendingCallRecord call) throws SQLException {
    setUuid(statement, 1, call.id());
    setUuid(statement, 2, call.playerId());
    statement.setString(3, call.station().operatorCode());
    statement.setString(4, call.station().stationCode());
    statement.setString(5, gson.toJson(List.copyOf(call.screenPlatforms())));
    statement.setString(6, call.directionKey());
    setUuid(statement, 7, call.lineId());
    setInstant(statement, 8, call.createdAt());
    if (call.etaSeconds().isPresent()) {
      statement.setInt(9, call.etaSeconds().getAsInt());
    } else {
      statement.setNull(9, Types.INTEGER);
    }
  }

  private PendingCallRecord map(ResultSet rs) throws SQLException {
    Integer eta = readNullableInteger(rs, "eta_seconds");
    return new PendingCallRecord(
        requireUuid(rs, "id"),
        requireUuid(rs, "player_uuid"),
        new PidsStationKey(rs.getString("operator_code"), rs.getString("station_code")),
        strings(rs.getString("screen_platforms")),
        rs.getString("direction_key"),
        requireUuid(rs, "line_id"),
        readInstant(rs, "created_at"),
        eta == null ? OptionalInt.empty() : OptionalInt.of(eta));
  }

  private Set<String> strings(String json) {
    if (json == null || json.isBlank()) {
      return Set.of();
    }
    List<String> values = gson.fromJson(json, STRING_LIST);
    return values == null ? Set.of() : Set.copyOf(values);
  }
}
