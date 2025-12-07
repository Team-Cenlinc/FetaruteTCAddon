package org.fetarute.fetaruteTCAddon.storage.dialect;

import java.util.List;
import java.util.stream.Collectors;

/** Postgres 方言实现，预留给未来的 JDBC 仓库以最小改动接入。 */
public final class PostgresDialect implements SqlDialect {

  @Override
  public String name() {
    return "postgres";
  }

  @Override
  public String applyUpsert(String insertSql, List<String> keyColumns, List<String> updateColumns) {
    if (keyColumns.isEmpty() || updateColumns.isEmpty()) {
      return insertSql;
    }
    String conflict = String.join(", ", keyColumns);
    String updates =
        updateColumns.stream()
            .map(column -> column + " = EXCLUDED." + column)
            .collect(Collectors.joining(", "));
    return insertSql + " ON CONFLICT(" + conflict + ") DO UPDATE SET " + updates;
  }

  @Override
  public String applyLimitOffset(String baseSql, int limit, int offset) {
    return baseSql + " LIMIT " + limit + " OFFSET " + offset;
  }

  @Override
  public String uuidType() {
    return "UUID";
  }

  @Override
  public String stringType() {
    return "TEXT";
  }

  @Override
  public String jsonType() {
    return "JSONB";
  }

  @Override
  public String timestampType() {
    return "TIMESTAMPTZ";
  }

  @Override
  public String intType() {
    return "INTEGER";
  }

  @Override
  public String bigIntType() {
    return "BIGINT";
  }

  @Override
  public String doubleType() {
    return "DOUBLE PRECISION";
  }
}
