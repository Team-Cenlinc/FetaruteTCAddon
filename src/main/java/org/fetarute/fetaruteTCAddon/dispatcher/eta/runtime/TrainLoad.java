package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.util.List;

/**
 * 列车载客：每节车的座位数与在座乘客数，从车头到车尾。
 *
 * <p>座位数取车辆模型（TrainCarts 附件）里的座位个数，在座取坐在这节车上的玩家。采样在主线程（{@link EtaRuntimeSampler}）， 站台屏与公开 API
 * 在任意线程读快照。
 *
 * @param cars 各节车，车头在前
 */
public record TrainLoad(List<Car> cars) {

  public TrainLoad {
    cars = cars == null ? List.of() : List.copyOf(cars);
  }

  /** 全车座位数。 */
  public int seats() {
    return cars.stream().mapToInt(Car::seats).sum();
  }

  /** 全车空位数。 */
  public int vacant() {
    return cars.stream().mapToInt(Car::vacant).sum();
  }

  /** 乘客全部下车后的载客：座位不变，在座清零（终点到站全员下车）。 */
  public TrainLoad emptied() {
    return new TrainLoad(cars.stream().map(car -> new Car(car.seats(), 0)).toList());
  }

  /** 车头换到另一端后的载客：车厢顺序倒过来（原地折返反向开出）。 */
  public TrainLoad reversed() {
    return new TrainLoad(cars.reversed());
  }

  /**
   * 一节车。
   *
   * @param seats 座位数
   * @param occupied 在座乘客数；多于座位数时按座位数计（站着的不算空位）
   */
  public record Car(int seats, int occupied) {

    public Car {
      seats = Math.max(0, seats);
      occupied = Math.max(0, Math.min(occupied, seats));
    }

    /** 空位数。 */
    public int vacant() {
      return seats - occupied;
    }
  }
}
