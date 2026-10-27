package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.List;

/**
 * 一张 GTFS 表：表头、数据行与写出成 CSV 的入口。
 *
 * @param name 文件名去掉 {@code .txt} 的名字（如 {@code stops}）
 * @param columns 表头
 * @param rows 数据行
 */
record GtfsTable(String name, List<String> columns, List<List<String>> rows) {

  GtfsTable {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  /** 包内文件名。 */
  String fileName() {
    return name + ".txt";
  }

  /** 写成 CSV。 */
  byte[] bytes() {
    return GtfsCsv.write(columns, rows);
  }
}
