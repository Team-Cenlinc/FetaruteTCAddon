package org.fetarute.fetaruteTCAddon.coupler;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 解编时车上值乘的人（驾驶员、车掌），由驾驶模块接入；没有接入时当作没人值乘。
 *
 * <p>有人值乘时，解编等这一站开过门、这一趟像终点一样结算之后才拆；拆开后请值乘的人选择继续哪一段（或结束），两段车都扣着直到选完， 最多等一分钟，超时结束值乘。
 */
public interface DecoupleCrew {

  /** 没有接入驾驶模块。 */
  DecoupleCrew NONE =
      new DecoupleCrew() {
        @Override
        public boolean crewed(String trainName) {
          return false;
        }

        @Override
        public boolean readyToSplit(String trainName) {
          return true;
        }

        @Override
        public void split(Split split) {}

        @Override
        public Optional<Instant> holding(String trainName) {
          return Optional.empty();
        }
      };

  /** 合起几方值乘的人（驾驶员、车掌）：每次调用时再取各方此刻的实现（驾驶模块会重载）；有一方在值乘就算有人值乘，各方都结算过才拆， 拆开、没拆都告诉每一方，扣留取最晚的期限。 */
  static DecoupleCrew all(List<Supplier<DecoupleCrew>> parts) {
    List<Supplier<DecoupleCrew>> copy = List.copyOf(parts);
    return new DecoupleCrew() {
      @Override
      public boolean crewed(String trainName) {
        return copy.stream().anyMatch(part -> part.get().crewed(trainName));
      }

      @Override
      public boolean readyToSplit(String trainName) {
        return copy.stream().allMatch(part -> part.get().readyToSplit(trainName));
      }

      @Override
      public void split(Split split) {
        copy.forEach(part -> part.get().split(split));
      }

      @Override
      public void notSplit(String trainName) {
        copy.forEach(part -> part.get().notSplit(trainName));
      }

      @Override
      public Optional<Instant> holding(String trainName) {
        return copy.stream()
            .map(part -> part.get().holding(trainName))
            .flatMap(Optional::stream)
            .max(Instant::compareTo);
      }
    };
  }

  /** 车上有人值乘（驾驶员或车掌）。 */
  boolean crewed(String trainName);

  /** 值乘的人在这一站已经开过门、这一趟已经结算，可以拆了；没人值乘时为 {@code true}。 */
  boolean readyToSplit(String trainName);

  /** 拆开了：请值乘的人选择继续哪一段。 */
  void split(Split split);

  /** 值乘的人已为解编结算过，这一站却没有拆（编组对不上、拆分失败等）：让他接着值乘本趟。 */
  default void notSplit(String trainName) {}

  /** 这列车（拆开的任一段）正等值乘的人选择或换段时扣到几点（选择期限）：期间不发车、不接票；不扣时为空。 */
  Optional<Instant> holding(String trainName);

  /**
   * 一次拆分。
   *
   * @param keptName 留下继续本交路的一段（保留原身份）
   * @param detachedName 解下的一段
   * @param keptLabel 留下那一段的说明（“前 4 节，开往南渡”）
   * @param detachedLabel 解下那一段的说明（“后 4 节，开往大港城”）
   */
  record Split(String keptName, String detachedName, String keptLabel, String detachedLabel) {

    public Split {
      Objects.requireNonNull(keptName, "keptName");
      Objects.requireNonNull(detachedName, "detachedName");
      keptLabel = keptLabel == null ? "" : keptLabel;
      detachedLabel = detachedLabel == null ? "" : detachedLabel;
    }
  }
}
