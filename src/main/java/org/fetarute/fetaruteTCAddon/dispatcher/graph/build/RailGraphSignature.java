package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;

/** 用于判断节点集合是否变化的签名计算器（基于节点 id 与坐标）。 */
public final class RailGraphSignature {

  private RailGraphSignature() {}

  public static String signatureForNodes(List<RailNodeRecord> nodes) {
    Objects.requireNonNull(nodes, "nodes");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      nodes.stream()
          .sorted(Comparator.comparing(n -> n.nodeId().value()))
          .forEach(
              node -> {
                String line =
                    node.nodeId().value() + "@" + node.x() + "," + node.y() + "," + node.z() + ";";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
              });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      // 理论上不会发生：SHA-256 总是可用
      return "";
    }
  }
}
