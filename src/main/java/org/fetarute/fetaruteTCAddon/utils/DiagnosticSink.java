package org.fetarute.fetaruteTCAddon.utils;

import java.util.function.Consumer;

/**
 * 诊断输出端：除了接收一行文字，还能回答此刻是否真的会输出。
 *
 * <p>诊断行在拼装、去重签名上的花费往往比写日志本身还大；输出关着时调用方据此整段跳过。
 */
public interface DiagnosticSink extends Consumer<String> {

  /** 此刻写进来的诊断是否会被输出。 */
  boolean enabled();

  /**
   * {@code logger} 此刻是否会输出；不是 {@link DiagnosticSink} 的一律视为会输出。
   *
   * @param logger 诊断输出端，可为空
   */
  static boolean enabled(Consumer<String> logger) {
    return !(logger instanceof DiagnosticSink sink) || sink.enabled();
  }
}
