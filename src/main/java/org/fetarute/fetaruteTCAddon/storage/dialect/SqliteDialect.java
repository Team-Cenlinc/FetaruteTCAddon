package org.fetarute.fetaruteTCAddon.storage.dialect;

import java.util.List;
import java.util.stream.Collectors;

/** SQLite 方言实现，负责提供 upsert/分页语法与通用类型映射。 */
public final class SqliteDialect implements SqlDialect {

  @Override
  public String name() {
    return "sqlite";
  }

  @Override
  public String applyUpsert(String insertSql, List<String> keyColumns, List<String> updateColumns) {
    if (keyColumns.isEmpty() || updateColumns.isEmpty()) {
      return insertSql;
    }
    String conflict = String.join(", ", keyColumns);
    String updates =
        updateColumns.stream()
            .map(column -> column + " = excluded." + column)
            .collect(Collectors.joining(", "));
    return insertSql + " ON CONFLICT(" + conflict + ") DO UPDATE SET " + updates;
  }

  @Override
  public String applyLimitOffset(String baseSql, int limit, int offset) {
    return baseSql + " LIMIT " + limit + " OFFSET " + offset;
  }

  @Override
  public String uuidType() {
    return "TEXT";
  }

  @Override
  public String stringType() {
    return "TEXT";
  }

  @Override
  public String jsonType() {
    return "TEXT";
  }

  @Override
  public String timestampType() {
    return "INTEGER";
  }

  @Override
  public String intType() {
    return "INTEGER";
  }

  @Override
  public String bigIntType() {
    return "INTEGER";
  }

  @Override
  public String doubleType() {
    return "REAL";
  }
}
