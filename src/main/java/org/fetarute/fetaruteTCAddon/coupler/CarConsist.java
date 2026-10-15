package org.fetarute.fetaruteTCAddon.coupler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 车厢的来源编组：每节车第一次被扳手拆挂时打上 {@code FTA_CAR_CONSIST=<编组键>|<来源编组 ID>|<来源编组节数>}（车厢级标签，随车厢走），
 * 拆挂后据此推出新编组的编组键。
 *
 * <p>车头到车尾逐节看：同一个来源编组连续、且节数等于它整列的，是一个完整的来源编组。全是一个完整来源编组 → 原编组键；几个完整来源编组拼成 → {@code
 * A+B}（车头到车尾）；有不完整的 → 也写成拼接形式，另标为不完整编组（不完整编组只接回库票）。纯计算。
 */
public final class CarConsist {

  /** 车厢级标签：这节车来自哪个编组。 */
  public static final String TAG_CAR_CONSIST = "FTA_CAR_CONSIST";

  /** 列车级标签：拆出来的不是完整编组。 */
  public static final String TAG_CONSIST_PARTIAL = "FTA_CONSIST_PARTIAL";

  private CarConsist() {}

  /**
   * 一节车的来源。
   *
   * @param pattern 来源编组的编组键
   * @param block 来源编组 ID
   * @param size 来源编组整列的节数
   */
  public record Origin(String pattern, String block, int size) {

    public Origin {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(block, "block");
      size = Math.max(1, size);
    }

    /** 写成车厢标签的值。 */
    public String format() {
      return pattern.replace("|", "/") + "|" + block + "|" + size;
    }

    /** 解析车厢标签的值；写法不对时为空。 */
    public static Optional<Origin> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      String[] parts = raw.split("\\|");
      if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            new Origin(parts[0].trim(), parts[1].trim(), Integer.parseInt(parts[2].trim())));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
    }
  }

  /**
   * 推出的编组。
   *
   * @param pattern 编组键（写进 {@code FTA_SPAWN_PATTERN}）
   * @param partial 含不完整的来源编组
   */
  public record Derived(String pattern, boolean partial) {}

  /**
   * 由车头到车尾各节车的来源推出编组；有车没有来源时为空（不改原编组键）。
   *
   * @param cars 车头到车尾各节车的来源
   */
  public static Optional<Derived> derive(List<Optional<Origin>> cars) {
    if (cars == null || cars.isEmpty() || cars.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }
    List<String> labels = new ArrayList<>();
    boolean partial = false;
    int i = 0;
    while (i < cars.size()) {
      Origin origin = cars.get(i).orElseThrow();
      int run = 1;
      while (i + run < cars.size()
          && cars.get(i + run).orElseThrow().block().equals(origin.block())) {
        run++;
      }
      if (run != origin.size()) {
        partial = true;
        labels.add(origin.pattern() + "(" + run + "/" + origin.size() + ")");
      } else {
        labels.add(origin.pattern());
      }
      i += run;
    }
    return Optional.of(new Derived(String.join("+", labels), partial));
  }
}
