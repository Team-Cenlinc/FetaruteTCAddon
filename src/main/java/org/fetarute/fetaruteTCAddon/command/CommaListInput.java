package org.fetarute.fetaruteTCAddon.command;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 补全时正在输入的一段逗号分隔列表（例如联编的几条线 {@code "MT,WS"}）。
 *
 * <p>客户端按 Brigadier 规则解析参数：不加引号的参数只认字母、数字与 {@code _ - . +}，逗号会让整条命令标红、发不出去。 所以多个值时候选一律带双引号；
 * 只写一个值、也没起引号时照常不带。
 *
 * @param head 已写完的几个值连同末尾的逗号（{@code "MT,"}）；只有一个值时为空
 * @param prefix 正在写的最后一个值（小写，去掉首尾空白）
 * @param quoted 输入已经起了双引号
 */
record CommaListInput(String head, String prefix, boolean quoted) {

  /**
   * 解析当前参数的输入。
   *
   * @param token 当前参数已输入的文字（{@code CommandInput#lastRemainingToken()}）
   */
  static CommaListInput of(String token) {
    String body = token == null ? "" : token.trim();
    boolean quoted = body.startsWith("\"");
    if (quoted) {
      body = body.substring(1);
    }
    if (body.endsWith("\"")) {
      body = body.substring(0, body.length() - 1);
    }
    int comma = body.lastIndexOf(',');
    String head = comma < 0 ? "" : body.substring(0, comma + 1);
    String prefix = (comma < 0 ? body : body.substring(comma + 1)).trim();
    return new CommaListInput(head, prefix.toLowerCase(Locale.ROOT), quoted);
  }

  /** 什么都还没写：给占位符。 */
  boolean blank() {
    return head.isEmpty() && prefix.isEmpty() && !quoted;
  }

  /** 已写过的值（小写），候选不再重复给出。 */
  Set<String> chosen() {
    Set<String> out = new HashSet<>();
    for (String part : head.split(",")) {
      if (!part.isBlank()) {
        out.add(part.trim().toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /**
   * 补上这个值之后的候选：只有一个值、也没起引号时原样给出；否则带双引号，给收好引号的（{@code "MT,WS"}） 与接着写下一个的（{@code "MT,WS,}）两种。
   *
   * @param value 补上的值
   */
  List<String> complete(String value) {
    if (head.isEmpty() && !quoted) {
      return List.of(value);
    }
    String list = head + value;
    return List.of("\"" + list + "\"", "\"" + list + ",");
  }
}
