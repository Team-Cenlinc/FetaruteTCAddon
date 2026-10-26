package org.fetarute.fetaruteTCAddon.signaller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/**
 * 临时小区登记（中心工位）：大区有人值守时，后来的人在工位上岗分到的那一片。代码为“大区代码~序号”，名字按语言文件写成“大区名 · 工位 序号”。
 *
 * <p>只在内存里，重启、重载时与值守一起清空；不写进控制区目录，{@code /fta signal zone list} 里不列。成员是大区成员的子集，嵌套与让出照现有的嵌套小区算。
 * 只在主线程调用。
 */
final class TransientZones {

  /** 代码里分隔大区代码与序号的字符。 */
  static final char SEPARATOR = '~';

  /** 第一个工位的序号（大区那位算第 1 个）。 */
  static final int FIRST_SEQ = 2;

  /**
   * 一片临时小区。
   *
   * @param zone 小区（运营商、代码、名字、成员）
   * @param parent 所属大区的代码
   * @param seq 序号
   */
  record Entry(SignallerWorld.Zone zone, String parent, int seq) {}

  private final Map<String, Entry> entries = new LinkedHashMap<>();

  /** 按运营商与代码找；不是临时小区时为空。 */
  Optional<SignallerWorld.Zone> find(String operator, String code) {
    return entry(operator, code).map(Entry::zone);
  }

  /** 按运营商与代码找登记（带序号与大区）；不是临时小区时为空。 */
  Optional<Entry> entry(String operator, String code) {
    return Optional.ofNullable(entries.get(SignallerService.boxKey(operator, code)));
  }

  /** 是不是一片临时小区（按代码里的分隔符认，不查登记）。 */
  static boolean isTransient(String code) {
    return code != null && code.indexOf(SEPARATOR) >= 0;
  }

  /**
   * 在大区下登记一片，序号取最小的空号。
   *
   * @param parent 大区
   * @param members 分出去的成员
   * @param name 按序号给出名字（语言文件里的写法）
   */
  Entry add(
      SignallerWorld.Zone parent,
      List<YardArea.Member> members,
      java.util.function.IntFunction<String> name) {
    int seq = FIRST_SEQ;
    while (entries.containsKey(
        SignallerService.boxKey(parent.operator(), code(parent.code(), seq)))) {
      seq++;
    }
    SignallerWorld.Zone zone =
        new SignallerWorld.Zone(
            parent.operator(), code(parent.code(), seq), name.apply(seq), members);
    Entry entry = new Entry(zone, parent.code().toUpperCase(Locale.ROOT), seq);
    entries.put(SignallerService.boxKey(zone.operator(), zone.code()), entry);
    return entry;
  }

  /** 撤销一片。 */
  void remove(String operator, String code) {
    entries.remove(SignallerService.boxKey(operator, code));
  }

  /** 一个大区下登记着的各片。 */
  List<Entry> under(String operator, String parent) {
    List<Entry> out = new ArrayList<>();
    for (Entry entry : entries.values()) {
      if (entry.zone().operator().equalsIgnoreCase(operator)
          && entry.parent().equalsIgnoreCase(parent)) {
        out.add(entry);
      }
    }
    return out;
  }

  /** 全部登记。 */
  List<Entry> all() {
    return List.copyOf(entries.values());
  }

  void clear() {
    entries.clear();
  }

  private static String code(String parent, int seq) {
    return parent.toUpperCase(Locale.ROOT) + SEPARATOR + seq;
  }
}
