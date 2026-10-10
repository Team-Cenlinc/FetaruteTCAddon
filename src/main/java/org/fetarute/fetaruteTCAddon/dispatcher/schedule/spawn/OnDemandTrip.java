package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 叫车按需票的车次标识（{@link SpawnTicket#serviceTripId()}）：{@code CALL-<叫车标签>[~T][#<入路下标>[/<生成点>]]}。
 *
 * <p>叫车标签由叫车服务写、也由它读；发车侧只看入路：带着它的票在交路中途的区间点生成列车，从那个下标起跑， 不从车库出车、也不接首站的待命车。生成点写了时它是交路节点 {@code 入路下标}
 * 与下一个节点之间图路径上的区间点（交路节点表里没有它）；没写时生成点就是交路节点 {@code 入路下标}。重载时票据原样迁移，标识跟着走。
 *
 * <p>{@code ~T} 是折返车票：叫车的交路从终点出发、本站上游又生成不了车时，先在开往这个终点的交路上生成一列车， 它开进终点后由同一单叫车的票接走（见叫车服务）。
 */
public final class OnDemandTrip {

  public static final String PREFIX = "CALL-";
  private static final char ENTRY_SEPARATOR = '#';
  private static final char NODE_SEPARATOR = '/';
  private static final String TURNBACK_MARK = "~T";

  private OnDemandTrip() {}

  /**
   * 区间生成的入路。
   *
   * @param index 入路下标：生成点就是交路节点 {@code index}，或在它与下一个节点之间
   * @param node 生成点；为空时就是交路节点 {@code index}
   */
  public record Entry(int index, Optional<NodeId> node) {
    public Entry {
      Objects.requireNonNull(node, "node");
    }
  }

  /**
   * 写车次标识。
   *
   * @param callTag 叫车标签（不含 {@code #}）
   * @param entry 区间生成的入路；不在区间生成时为空
   */
  public static String format(String callTag, Optional<Entry> entry) {
    return format(callTag, entry, false);
  }

  /**
   * 写车次标识。
   *
   * @param callTag 叫车标签（不含 {@code #}、{@code ~}）
   * @param entry 区间生成的入路；不在区间生成时为空
   * @param turnback 折返车票
   */
  public static String format(String callTag, Optional<Entry> entry, boolean turnback) {
    String base =
        PREFIX
            + (callTag == null ? "" : callTag.replace(ENTRY_SEPARATOR, '_').replace('~', '_'))
            + (turnback ? TURNBACK_MARK : "");
    if (entry == null || entry.isEmpty() || entry.get().index() <= 0) {
      return base;
    }
    String out = base + ENTRY_SEPARATOR + entry.get().index();
    return entry
        .get()
        .node()
        .map(NodeId::value)
        .filter(value -> !value.isBlank())
        .map(value -> out + NODE_SEPARATOR + value)
        .orElse(out);
  }

  /** 叫车标签；不是叫车票时为空。 */
  public static Optional<String> callTagOf(Optional<String> serviceTripId) {
    return serviceTripId
        .filter(id -> id.startsWith(PREFIX))
        .map(id -> id.substring(PREFIX.length()))
        .map(
            rest -> {
              int at = rest.indexOf(ENTRY_SEPARATOR);
              String head = at < 0 ? rest : rest.substring(0, at);
              return head.endsWith(TURNBACK_MARK)
                  ? head.substring(0, head.length() - TURNBACK_MARK.length())
                  : head;
            })
        .filter(tag -> !tag.isBlank());
  }

  /** 是不是折返车票（见类说明）。 */
  public static boolean isTurnback(Optional<String> serviceTripId) {
    return serviceTripId != null
        && serviceTripId
            .filter(id -> id.startsWith(PREFIX))
            .map(
                id -> {
                  int at = id.indexOf(ENTRY_SEPARATOR);
                  return (at < 0 ? id : id.substring(0, at)).endsWith(TURNBACK_MARK);
                })
            .orElse(false);
  }

  /** 叫车标签里的叫车编号部分（{@code @} 之前）；不是叫车票时为空。 */
  public static Optional<String> callIdOf(Optional<String> serviceTripId) {
    return callTagOf(serviceTripId)
        .map(tag -> tag.indexOf('@') < 0 ? tag : tag.substring(0, tag.indexOf('@')))
        .filter(id -> !id.isBlank());
  }

  /** 区间生成的入路下标；不是叫车票、或不在区间生成时为空。 */
  public static OptionalInt entryIndexOf(Optional<String> serviceTripId) {
    Optional<Entry> entry = entryOf(serviceTripId);
    return entry.isPresent() ? OptionalInt.of(entry.get().index()) : OptionalInt.empty();
  }

  /** 区间生成的入路；不是叫车票、或不在区间生成时为空。 */
  public static Optional<Entry> entryOf(Optional<String> serviceTripId) {
    if (serviceTripId == null || serviceTripId.isEmpty()) {
      return Optional.empty();
    }
    String id = serviceTripId.get();
    if (!id.startsWith(PREFIX)) {
      return Optional.empty();
    }
    int at = id.lastIndexOf(ENTRY_SEPARATOR);
    if (at < 0 || at == id.length() - 1) {
      return Optional.empty();
    }
    String rest = id.substring(at + 1);
    int slash = rest.indexOf(NODE_SEPARATOR);
    String indexText = slash < 0 ? rest : rest.substring(0, slash);
    Optional<NodeId> node =
        slash < 0 || slash == rest.length() - 1
            ? Optional.empty()
            : Optional.of(NodeId.of(rest.substring(slash + 1)));
    try {
      int index = Integer.parseInt(indexText);
      return index > 0 ? Optional.of(new Entry(index, node)) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** 去掉入路下标：区间生成一直不成时改走交路本来的车源。 */
  public static Optional<String> withoutEntry(Optional<String> serviceTripId) {
    return serviceTripId.map(
        id -> {
          if (!id.startsWith(PREFIX)) {
            return id;
          }
          int at = id.lastIndexOf(ENTRY_SEPARATOR);
          return at < 0 ? id : id.substring(0, at);
        });
  }
}
