package org.fetarute.fetaruteTCAddon.coupler;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteCouplingDirectives;

/**
 * 解编 {@code DECP} 在哪里切：按运行方向（车头＝前端）从指定那头数节数，切点须落在来源编组分界上、切下的部分全是完整的来源编组。
 * 指定那头切不开时改拆另一头（节数相同、同样的条件）；两头都不行就不拆。与编表推车型的 {@code CouplingPlanner.cut} 同一条规则。纯计算。
 */
final class DecoupleCut {

  private DecoupleCut() {}

  /**
   * 切法。
   *
   * @param at 后一半第一节在编组里的下标（车头为 0）
   * @param end 切下的是哪一头
   */
  record Cut(int at, RouteCouplingDirectives.End end) {}

  /**
   * @param cars 车头到车尾各节车的来源
   * @param end 指令指定切哪一头
   * @param count 切下的节数
   * @return 切法；切不开（节数不对、切点不在分界上、切下的不是完整编组、有车没有来源）时为空
   */
  static Optional<Cut> resolve(
      List<Optional<CarConsist.Origin>> cars, RouteCouplingDirectives.End end, int count) {
    if (cars == null
        || count <= 0
        || count >= cars.size()
        || cars.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }
    Optional<Cut> direct = at(cars, end, count);
    if (direct.isPresent()) {
      return direct;
    }
    return at(
        cars,
        end == RouteCouplingDirectives.End.FRONT
            ? RouteCouplingDirectives.End.REAR
            : RouteCouplingDirectives.End.FRONT,
        count);
  }

  private static Optional<Cut> at(
      List<Optional<CarConsist.Origin>> cars, RouteCouplingDirectives.End end, int count) {
    int at = end == RouteCouplingDirectives.End.FRONT ? count : cars.size() - count;
    if (block(cars, at - 1).equals(block(cars, at))) {
      return Optional.empty();
    }
    int from = end == RouteCouplingDirectives.End.FRONT ? 0 : at;
    int to = end == RouteCouplingDirectives.End.FRONT ? at : cars.size();
    return wholeBlocks(cars, from, to) ? Optional.of(new Cut(at, end)) : Optional.empty();
  }

  /** {@code [from, to)} 里每一段连续的同一来源编组都是整列。 */
  private static boolean wholeBlocks(List<Optional<CarConsist.Origin>> cars, int from, int to) {
    int i = from;
    while (i < to) {
      CarConsist.Origin origin = cars.get(i).orElseThrow();
      int run = 1;
      while (i + run < to && block(cars, i + run).equals(origin.block())) {
        run++;
      }
      if (run != origin.size()) {
        return false;
      }
      i += run;
    }
    return true;
  }

  private static String block(List<Optional<CarConsist.Origin>> cars, int index) {
    return cars.get(index).orElseThrow().block();
  }
}
