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
import java.util.TreeSet;

/**
 * 按命令参数找任务板的车站：参数写站码（如 {@code HHU}），站码在几家运营商下重名时写 {@code 运营商:站码}。本类不依赖服务器对象，便于单测。
 *
 * <p>站码与运营商代码都不区分大小写。
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
   * @param input 命令参数：站码，或 {@code 运营商:站码}
   */
  public static Lookup find(Collection<TaskBoardSource.Station> stations, String input) {
    Objects.requireNonNull(stations, "stations");
    String raw = input == null ? "" : input.trim();
    if (raw.isEmpty()) {
      return new Lookup(Outcome.NOT_FOUND, Optional.empty(), List.of());
    }
    int colon = raw.indexOf(':');
    String operator = colon > 0 ? raw.substring(0, colon) : null;
    String code = colon >= 0 ? raw.substring(colon + 1) : raw;
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

  /** 补全用的写法：站码唯一时只写站码，重名时写 {@code 运营商:站码}；按字母排序、去重。 */
  public static List<String> suggestions(Collection<TaskBoardSource.Station> stations) {
    Map<String, Integer> counts = new HashMap<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.stationCode() != null && !station.stationCode().isBlank()) {
        counts.merge(station.stationCode().toUpperCase(Locale.ROOT), 1, Integer::sum);
      }
    }
    TreeSet<String> result = new TreeSet<>();
    for (TaskBoardSource.Station station : stations) {
      if (station.stationCode() == null || station.stationCode().isBlank()) {
        continue;
      }
      String code = station.stationCode().toUpperCase(Locale.ROOT);
      result.add(counts.getOrDefault(code, 0) > 1 ? qualified(station) : code);
    }
    return List.copyOf(result);
  }

  private static String qualified(TaskBoardSource.Station station) {
    String operator = station.operatorCode() == null ? "" : station.operatorCode();
    return operator.toUpperCase(Locale.ROOT) + ":" + station.stationCode().toUpperCase(Locale.ROOT);
  }
}
