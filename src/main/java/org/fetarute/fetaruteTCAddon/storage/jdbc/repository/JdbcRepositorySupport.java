package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;
import org.fetarute.fetaruteTCAddon.storage.jdbc.JdbcConnectionContext;

/** JDBC 仓库通用的工具方法，封装 UUID/时间/JSON 转换与表名前缀处理。 */
abstract class JdbcRepositorySupport {

  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

  protected final DataSource dataSource;
  protected final SqlDialect dialect;
  protected final String tablePrefix;
  // MySQL 以 BINARY(16) 存储 UUID，SQLite 用字符串
  protected final boolean binaryUuid;
  // SQLite 使用 epoch millis 存储时间戳，避免 driver/类型推断差异
  protected final boolean epochMillisTimestamp;
  protected final Gson gson = new Gson();
  private final Consumer<String> debugLogger;

  protected JdbcRepositorySupport(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    this.dataSource = dataSource;
    this.dialect = dialect;
    this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
    this.binaryUuid = dialect instanceof MySqlDialect;
    this.epochMillisTimestamp = dialect instanceof SqliteDialect;
    this.debugLogger = debugLogger == null ? msg -> {} : debugLogger;
  }

  protected final ConnectionResource openConnection() throws SQLException {
    Connection txConnection = JdbcConnectionContext.current();
    if (txConnection != null) {
      return new ConnectionResource(txConnection, false);
    }
    return new ConnectionResource(dataSource.getConnection(), true);
  }

  protected void setUuid(PreparedStatement statement, int index, UUID uuid) throws SQLException {
    if (uuid == null) {
      statement.setObject(index, null);
      return;
    }
    if (binaryUuid) {
      statement.setBytes(index, uuidToBytes(uuid));
    } else {
      statement.setString(index, uuid.toString());
    }
  }

  protected UUID readUuid(ResultSet rs, String column) throws SQLException {
    if (binaryUuid) {
      byte[] bytes = rs.getBytes(column);
      if (bytes == null) {
        return null;
      }
      return bytesToUuid(bytes);
    }
    String value = rs.getString(column);
    return value == null ? null : UUID.fromString(value);
  }

  protected UUID requireUuid(ResultSet rs, String column) throws SQLException {
    UUID uuid = readUuid(rs, column);
    if (uuid == null) {
      throw new StorageException("UUID 列缺失: " + column);
    }
    return uuid;
  }

  protected void setInstant(PreparedStatement statement, int index, Instant instant)
      throws SQLException {
    if (instant == null) {
      statement.setObject(index, null);
      return;
    }
    if (epochMillisTimestamp) {
      statement.setLong(index, instant.toEpochMilli());
    } else {
      statement.setTimestamp(index, Timestamp.from(instant));
    }
  }

  protected Instant readInstant(ResultSet rs, String column) throws SQLException {
    if (epochMillisTimestamp) {
      long value = rs.getLong(column);
      if (rs.wasNull()) {
        throw new StorageException("时间戳列缺失: " + column);
      }
      return Instant.ofEpochMilli(value);
    }
    Timestamp timestamp = rs.getTimestamp(column);
    if (timestamp == null) {
      throw new StorageException("时间戳列缺失: " + column);
    }
    return timestamp.toInstant();
  }

  protected String toJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    return gson.toJson(map);
  }

  protected Map<String, Object> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    Map<String, Object> parsed = gson.fromJson(json, MAP_TYPE);
    return parsed == null ? Map.of() : Map.copyOf(parsed);
  }

  protected String table(String raw) {
    return tablePrefix + raw;
  }

  protected void debug(String message) {
    debugLogger.accept(message);
  }

  protected void commitIfNecessary(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    if (JdbcConnectionContext.isCurrent(connection)) {
      return;
    }
    // Hikari 默认自动提交，MySQL/未来支持可切换成显式事务时也能复用
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }

  protected static final class ConnectionResource implements AutoCloseable {

    private final Connection connection;
    private final boolean owned;

    private ConnectionResource(Connection connection, boolean owned) {
      this.connection = Objects.requireNonNull(connection, "connection");
      this.owned = owned;
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
      return connection.prepareStatement(sql);
    }

    public Statement createStatement() throws SQLException {
      return connection.createStatement();
    }

    public void commitIfNecessary() throws SQLException {
      if (JdbcConnectionContext.isCurrent(connection)) {
        return;
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
    }

    @Override
    public void close() throws SQLException {
      if (owned) {
        connection.close();
      }
    }
  }

  private byte[] uuidToBytes(UUID uuid) {
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putLong(uuid.getMostSignificantBits());
    buffer.putLong(uuid.getLeastSignificantBits());
    return buffer.array();
  }

  private UUID bytesToUuid(byte[] bytes) {
    if (bytes.length != 16) {
      throw new StorageException("无效的 UUID 字节长度: " + bytes.length);
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }
}
