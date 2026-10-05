package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 按命令参数找任务板的车站：参数写站码（如 {@code HHU}），站码在几家运营商下重名时写 {@code 运营商:站码}； 也认节点写法（{@code
 * 运营商:S:站码}，可带股道号）与站名。本类不依赖服务器对象，便于单测。
 *
 * <p>站码、运营商代码与站名都不区分大小写。
 */
public final class TaskBoardStations {

  /** 查找结果的种类。 */
  public enum Outcome {
    FOUND,
    NOT_FOUND,
    /** 站码在几家运营商下重名，须写明运营商。 */
    AMBIGUOUS
  }

  /**
   * 查找结果。
   *
   * @param outcome 种类
   * @param station 找到的车站；其余种类为空
   * @param candidates 重名时的候选写法（{@code 运营商:站码}）
   */
  public record Lookup(
      Outcome outcome, Optional<TaskBoardSource.Station> station, List<String> candidates) {
    public Lookup {
      Objects.requireNonNull(outcome, "outcome");
      station = station == null ? Optional.empty() : station;
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
  }

  private TaskBoardStations() {}

  /**
   * 按参数找车站。
   *
   * @param stations 全部车站
   * @param input 命令参数：站码、{@code 运营商:站码}、节点写法 {@code 运营商:S:站码[:股道]}，或站名
   */
  public static Lookup find(Collection<TaskBoardSource.Station> stations, String input) {
    Objects.requireNonNull(stations, "stations");
    String raw = input == null ? "" : input.trim();
    if (raw.isEmpty()) {
      return new Lookup(Outcome.NOT_FOUND, Optional.empty(), List.of());
    }
    String[] parts = raw.split(":", -1);
    String operator = null;
    String code = raw;
    if (parts.length >= 3 && parts[1].equalsIgnoreCase("S")) {
      // 节点写法：运营商:S:站码[:股道]
      operator = parts[0];
      code = parts[2];
    } else if (parts.length == 2) {
      operator = parts[0].isEmpty() ? null : parts[0];
      code = parts[1];
    }
    Lookup byCode = byCode(stations, operator, code);
    if (byCode.outcome() != Outcome.NOT_FOUND) {
      return byCode;
    }
    return byName(stations, raw);
  }

  private static Lookup byName(Collection<TaskBoardSource.Station> stations, String name) {
    List<TaskBoardSource.Station> matches = new ArrayList<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.name() != null && station.name().trim().equalsIgnoreCase(name)) {
        matches.add(station);
      }
    }
    return result(matches);
  }

  private static Lookup byCode(
      Collection<TaskBoardSource.Station> stations, String operator, String code) {
    List<TaskBoardSource.Station> matches = new ArrayList<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.stationCode() == null || !station.stationCode().equalsIgnoreCase(code)) {
        continue;
      }
      if (operator != null
          && (station.operatorCode() == null
              || !station.operatorCode().equalsIgnoreCase(operator))) {
        continue;
      }
      matches.add(station);
    }
    return result(matches);
  }

  private static Lookup result(List<TaskBoardSource.Station> matches) {
    if (matches.isEmpty()) {
      return new Lookup(Outcome.NOT_FOUND, Optional.empty(), List.of());
    }
    if (matches.size() > 1) {
      List<String> candidates = new ArrayList<>();
      for (TaskBoardSource.Station station : matches) {
        candidates.add(qualified(station));
      }
      candidates.sort(Comparator.naturalOrder());
      return new Lookup(Outcome.AMBIGUOUS, Optional.empty(), candidates);
    }
    return new Lookup(Outcome.FOUND, Optional.of(matches.get(0)), List.of());
  }

  /**
   * 列给玩家点选的一个车站。
   *
   * @param argument 命令参数写法：站码唯一时只写站码，重名时写 {@code 运营商:站码}
   * @param name 站名
   */
  public record Choice(String argument, String name) {}

  /** 列给玩家点选的车站，按命令参数写法排序、去重。 */
  public static List<Choice> choices(Collection<TaskBoardSource.Station> stations) {
    Map<String, Integer> counts = new HashMap<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.stationCode() != null && !station.stationCode().isBlank()) {
        counts.merge(station.stationCode().toUpperCase(Locale.ROOT), 1, Integer::sum);
      }
    }
    Map<String, Choice> result = new TreeMap<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.stationCode() == null || station.stationCode().isBlank()) {
        continue;
      }
      String code = station.stationCode().toUpperCase(Locale.ROOT);
      String argument = counts.getOrDefault(code, 0) > 1 ? qualified(station) : code;
      String name = station.name() == null || station.name().isBlank() ? code : station.name();
      result.putIfAbsent(argument, new Choice(argument, name));
    }
    return List.copyOf(result.values());
  }

  /** 补全用的写法：站码唯一时只写站码，重名时写 {@code 运营商:站码}；按字母排序、去重。 */
  public static List<String> suggestions(Collection<TaskBoardSource.Station> stations) {
    List<String> result = new ArrayList<>();
    for (Choice choice : choices(stations)) {
      result.add(choice.argument());
    }
    return List.copyOf(result);
  }

  private static String qualified(TaskBoardSource.Station station) {
    String operator = station.operatorCode() == null ? "" : station.operatorCode();
    return operator.toUpperCase(Locale.ROOT) + ":" + station.stationCode().toUpperCase(Locale.ROOT);
  }
}
