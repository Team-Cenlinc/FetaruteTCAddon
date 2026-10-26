package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 最小 CSV 写出器（RFC 4180 转义、{@code \n} 行尾）。
 *
 * <p>只写 GTFS 需要的东西：逗号分隔、字段含逗号/引号/换行时加引号并把内部引号翻倍。行尾固定 {@code \n}、不写 BOM，保证同一个 feed 重复生成时逐字节一致（zip 的
 * ETag 与 {@code feed_version} 都依赖这一点）。
 */
public final class GtfsCsv {

  private GtfsCsv() {}

  /**
   * 写一张 CSV 表。
   *
   * @param columns 表头
   * @param rows 数据行（每行长度应与表头一致）
   * @return UTF-8 字节
   */
  public static byte[] write(List<String> columns, List<List<String>> rows) {
    StringBuilder out = new StringBuilder();
    appendRow(out, columns);
    for (List<String> row : rows) {
      appendRow(out, row);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 一个字段的 CSV 写法：必要时加引号。
   *
   * <p>GTFS 明确禁止字段值里出现制表符、回车与换行（RFC 4180 的引号包不住这条规定）：这类字符一律换成空格，宁可名字走样，也不能让整张表 因一行坏数据而解析错位。
   */
  static String field(String value) {
    String text = sanitize(value);
    if (text.indexOf(',') < 0 && text.indexOf('"') < 0) {
      return text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      out.append(c == '\t' || c == '\r' || c == '\n' ? ' ' : c);
    }
    return out.toString();
  }

  private static void appendRow(StringBuilder out, List<String> row) {
    for (int i = 0; i < row.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(field(row.get(i)));
    }
    out.append('\n');
  }
}
