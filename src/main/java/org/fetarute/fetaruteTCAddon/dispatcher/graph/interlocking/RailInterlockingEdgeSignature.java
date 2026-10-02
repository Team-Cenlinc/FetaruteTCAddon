package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;

/** 为稀疏联锁快照计算规范化 Edge universe 的稳定 SHA-256 摘要。 */
public final class RailInterlockingEdgeSignature {

  private RailInterlockingEdgeSignature() {}

  /**
   * 计算忽略 Edge 朝向与输入迭代顺序的稳定签名。
   *
   * @param edges 当前图快照的全部区间
   * @return 64 位小写十六进制摘要
   */
  public static String of(Collection<EdgeId> edges) {
    Objects.requireNonNull(edges, "edges");
    TreeSet<EdgeId> sorted = new TreeSet<>(InterlockingZoneInfo::compareEdges);
    edges.stream()
        .filter(Objects::nonNull)
        .map(InterlockingZoneInfo::canonical)
        .forEach(sorted::add);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (EdgeId edge : sorted) {
        updateString(digest, edge.a().value());
        updateString(digest, edge.b().value());
      }
      return toHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
    }
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      result.append(Character.forDigit(value & 0x0f, 16));
    }
    return result.toString();
  }
}
