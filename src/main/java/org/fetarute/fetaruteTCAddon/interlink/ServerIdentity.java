package org.fetarute.fetaruteTCAddon.interlink;

import java.util.Optional;

/**
 * 本服的服务器 ID（{@code config.yml} 的 {@code server-id}）。跨服时节点 ID 只在本服唯一，跨边界的引用写成“服务器 ID + 节点 ID”。
 *
 * <p>单服可以留空。配置加载时设置，可在任意线程读。
 */
public final class ServerIdentity {

  private static volatile String id = "";

  private ServerIdentity() {}

  /** 配置加载时设置；空白视为未配置。 */
  public static void configure(String serverId) {
    id = serverId == null ? "" : serverId.trim();
  }

  /** 本服 ID；未配置时为空。 */
  public static Optional<String> id() {
    String value = id;
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }
}
