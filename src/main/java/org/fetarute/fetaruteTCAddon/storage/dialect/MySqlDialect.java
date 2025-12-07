package org.fetarute.fetaruteTCAddon.storage.dialect;

import java.util.List;
import java.util.stream.Collectors;

/** MySQL 方言实现，便于在保留通用 JDBC 代码的前提下插入方言特性（如 ON DUPLICATE KEY）。 */
public final class MySqlDialect implements SqlDialect {

  @Override
  public String name() {
    return "mysql";
  }

  @Override
  public String applyUpsert(String insertSql, List<String> keyColumns, List<String> updateColumns) {
    if (updateColumns.isEmpty()) {
      return insertSql;
    }
    String updates =
        updateColumns.stream()
            .map(column -> column + " = VALUES(" + column + ")")
            .collect(Collectors.joining(", "));
    return insertSql + " ON DUPLICATE KEY UPDATE " + updates;
  }

  @Override
  public String applyLimitOffset(String baseSql, int limit, int offset) {
    return baseSql + " LIMIT " + limit + " OFFSET " + offset;
  }

  @Override
  public String uuidType() {
    return "BINARY(16)";
  }

  @Override
  public String stringType() {
    return "VARCHAR(255)";
  }

  @Override
  public String jsonType() {
    return "JSON";
  }

  @Override
  public String timestampType() {
    return "TIMESTAMP";
  }

  @Override
  public String intType() {
    return "INT";
  }

  @Override
  public String bigIntType() {
    return "BIGINT";
  }

  @Override
  public String doubleType() {
    return "DOUBLE";
  }
}
