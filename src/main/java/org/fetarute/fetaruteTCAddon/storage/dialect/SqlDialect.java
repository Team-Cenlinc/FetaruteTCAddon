package org.fetarute.fetaruteTCAddon.storage.dialect;

import java.util.List;

/**
 * SQL 方言抽象，封装分页、upsert 与常用类型映射，屏蔽 SQLite/MySQL/Postgres 细节。
 *
 * <p>所有 JDBC 仓库应仅依赖此接口拼装 SQL，新增数据库时只需补充实现。
 */
public interface SqlDialect {

  /** 便于日志输出的方言名称。 */
  String name();

  /**
   * 为通用 INSERT 语句拼接各数据库的 upsert 语法。
   *
   * @param insertSql 不含 upsert 的 INSERT 语句
   * @param keyColumns 用于判定冲突的主键或唯一列
   * @param updateColumns 发生冲突时需要更新的列
   * @return 完整的 upsert 语句
   */
  String applyUpsert(String insertSql, List<String> keyColumns, List<String> updateColumns);

  /** 在 SQL 末尾附加分页语法。 */
  String applyLimitOffset(String baseSql, int limit, int offset);

  /** 常用数据类型映射，便于跨库迁移。 */
  String uuidType();

  String stringType();

  String jsonType();

  /** 长文本类型（用于模板、文档等大字段）。 */
  String textType();

  String timestampType();

  String intType();

  String bigIntType();

  String doubleType();
}
