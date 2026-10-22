package org.fetarute.fetaruteTCAddon.web;

import java.util.Map;
import java.util.Objects;

/**
 * 一次 HTTP 请求里路由用得到的部分。
 *
 * @param method 方法（大写）
 * @param path 路径（不含查询串）
 * @param query 查询参数（同名取第一个）
 */
record WebRequest(String method, String path, Map<String, String> query) {

  WebRequest {
    Objects.requireNonNull(method, "method");
    path = path == null || path.isEmpty() ? "/" : path;
    query = query == null ? Map.of() : Map.copyOf(query);
  }

  /** HEAD 请求：照 GET 处理，只是不回正文。 */
  boolean head() {
    return "HEAD".equals(method);
  }
}
