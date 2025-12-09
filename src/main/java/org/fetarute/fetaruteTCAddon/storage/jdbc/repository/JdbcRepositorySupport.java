package org.fetarute.fetaruteTCAddon.storage.jdbc.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/** JDBC 仓库通用的工具方法，封装 UUID/时间/JSON 转换与表名前缀处理。 */
abstract class JdbcRepositorySupport {

  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

  protected final DataSource dataSource;
  protected final SqlDialect dialect;
  protected final String tablePrefix;
  // MySQL 以 BINARY(16) 存储 UUID，SQLite 用字符串
  protected final boolean binaryUuid;
  protected final Gson gson = new Gson();
  private final Consumer<String> debugLogger;

  protected JdbcRepositorySupport(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, Consumer<String> debugLogger) {
    this.dataSource = dataSource;
    this.dialect = dialect;
    this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
    this.binaryUuid = dialect instanceof MySqlDialect;
    this.debugLogger = debugLogger == null ? msg -> {} : debugLogger;
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

  protected Timestamp toTimestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  protected Instant fromTimestamp(Timestamp timestamp) {
    if (timestamp == null) {
      throw new StorageException("时间戳列缺失");
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

  protected void commitIfNecessary(java.sql.Connection connection) throws SQLException {
    // Hikari 默认自动提交，MySQL/未来支持可切换成显式事务时也能复用
    if (!connection.getAutoCommit()) {
      connection.commit();
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
