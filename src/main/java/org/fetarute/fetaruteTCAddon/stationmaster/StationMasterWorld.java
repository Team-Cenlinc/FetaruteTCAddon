package org.fetarute.fetaruteTCAddon.stationmaster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;

/** 站长模块看到的外部世界：在线玩家、车站与股道、时刻表、列车位置与乘务员。测试用假实现替换。只在主线程调用。 */
interface StationMasterWorld {

  /**
   * 一个车站。
   *
   * @param operator 运营商代码
   * @param code 站码
   * @param name 站名
   */
  record Station(String operator, String code, String name) {

    /** 同一个车站（运营商与站码不分大小写）。 */
    boolean same(Station other) {
      return other != null
          && operator.equalsIgnoreCase(other.operator)
          && code.equalsIgnoreCase(other.code);
    }
  }

  /**
   * 车站的一条站台股道（调度图上的车站节点）。
   *
   * @param node 节点
   * @param number 股道号
   * @param world 所在世界
   * @param x 车站牌子 X
   * @param y 车站牌子 Y
   * @param z 车站牌子 Z
   */
  record Track(NodeId node, int number, UUID world, double x, double y, double z) {

    /** 车站牌子的位置。 */
    Spot sign() {
      return new Spot(world, x, y, z);
    }
  }

  /**
   * 世界中的一个位置。
   *
   * @param world 所在世界
   * @param x X
   * @param y Y
   * @param z Z
   */
  record Spot(UUID world, double x, double y, double z) {

    /** 同一世界里水平距离不超过 {@code range}、高差不超过 {@link #HEIGHT_RANGE}。 */
    boolean near(Spot other, double range) {
      if (other == null || !world.equals(other.world) || Math.abs(y - other.y) > HEIGHT_RANGE) {
        return false;
      }
      double dx = x - other.x;
      double dz = z - other.z;
      return dx * dx + dz * dz <= range * range;
    }

    /** 站在旁边的高差上限：上下层站台不算在一起。 */
    static final double HEIGHT_RANGE = 6.0;
  }

  /**
   * 在线玩家。
   *
   * @param name 名字
   * @param spot 位置
   * @param fit 还能值守（没死）
   * @param lookX 视线的水平方向 X 分量（与 {@code lookZ} 合成单位向量；正对上下时都为 0）
   * @param lookZ 视线的水平方向 Z 分量
   */
  record PlayerSpot(String name, Spot spot, boolean fit, double lookX, double lookZ) {

    /** 视线（水平方向）与看向 {@code target} 的方向夹角不超过 {@code maxDegrees}。 */
    boolean looksAt(Spot target, double maxDegrees) {
      double dx = target.x() - spot.x();
      double dz = target.z() - spot.z();
      double length = Math.sqrt(dx * dx + dz * dz);
      double look = Math.sqrt(lookX * lookX + lookZ * lookZ);
      if (length < 1e-6) {
        return true;
      }
      if (look < 1e-6) {
        return false;
      }
      double cos = (dx * lookX + dz * lookZ) / (length * look);
      return cos >= Math.cos(Math.toRadians(maxDegrees));
    }
  }

  /**
   * 附近的一列调度列车。
   *
   * @param trainName 列车名
   * @param cars 各节车厢的位置
   * @param moving 还在动
   */
  record TrainNear(String trainName, List<Spot> cars, boolean moving) {
    public TrainNear {
      cars = List.copyOf(cars);
    }
  }

  /**
   * 站长该站的地方：这列车停稳后车头在哪、车身沿哪个方向摆。
   *
   * @param head 停稳后车头最前端的位置
   * @param forwardX 列车前进方向的水平 X 分量（与 {@code forwardZ} 合成单位向量）
   * @param forwardZ 列车前进方向的水平 Z 分量
   * @param bodyBlocks 车身长度（格）
   */
  record StandPoint(Spot head, double forwardX, double forwardZ, double bodyBlocks) {

    /** 沿车身方向站在车头到车尾之间（两端各放宽 {@link #END_SLACK} 格）、离轨道中心不超过 {@code sideBlocks} 格。 */
    boolean beside(Spot at, double sideBlocks) {
      if (at == null
          || !head.world().equals(at.world())
          || Math.abs(head.y() - at.y()) > Spot.HEIGHT_RANGE) {
        return false;
      }
      double dx = at.x() - head.x();
      double dz = at.z() - head.z();
      double along = dx * forwardX + dz * forwardZ;
      double side = Math.abs(dx * forwardZ - dz * forwardX);
      return along <= END_SLACK && along >= -bodyBlocks - END_SLACK && side <= sideBlocks;
    }

    /** 车头、车尾两端放宽多少格：站在车头标线前一点也算到位。 */
    static final double END_SLACK = 4.0;
  }

  /** 列车上谁在值乘。 */
  enum CrewOnTrain {
    GUARD,
    DRIVER,
    ATO,
    AUTO
  }

  /**
   * 站牌预计要停到本站的一列运行中列车。
   *
   * @param trainName 列车名
   * @param track 停哪一道
   * @param eta 预计到站时刻
   */
  record Arrival(String trainName, int track, Instant eta) {}

  /** 站台紧急停车的结果。 */
  enum Emergency {
    /** 自动运行（含 ATO）的车：按紧急制动减速度刹停，停住后由站长扣着。 */
    BRAKING,
    /** 人工驾驶的车：驾驶员的手柄拨到 EB，由驾驶员停稳后缓解。 */
    DRIVER,
    /** 车已停着。 */
    NOT_MOVING,
    /** 列车不在了或驾驶模块没接上。 */
    UNAVAILABLE
  }

  /** 在线玩家；不在线时为空。 */
  Optional<PlayerSpot> player(UUID player);

  /** 全部车站。 */
  List<Station> stations();

  /** 玩家附近最近的车站；不在车站附近时为空。 */
  Optional<Station> nearestStation(UUID player);

  /** 车站的站台股道，按股道号排序；调度图里没有时为空。 */
  List<Track> tracks(Station station);

  /** 接下来 {@code horizon} 内要停在这个车站、站台已定的运行中列车；站牌未就绪时为空。 */
  List<Arrival> arrivals(Station station, Duration horizon);

  /** 已发布的时刻表。 */
  List<Timetable> timetables();

  /** 列车各节车厢的位置；列车不在了时为空。 */
  List<Spot> cars(String trainName);

  /** 列车还在；比取各节车厢的位置便宜。 */
  default boolean trainExists(String trainName) {
    return !cars(trainName).isEmpty();
  }

  /** 一列调度列车此刻的位置；列车不在了时为空。 */
  Optional<TrainNear> train(String trainName);

  /** 这列车停到这条股道时站长该站的地方；列车不在了、车站轨道所在区块未加载时为空。 */
  Optional<StandPoint> standPoint(String trainName, NodeId node);

  /** 列车给人看的称呼（车次号，没有时为车名）。 */
  String trainLabel(String trainName);

  /** 列车上值乘的驾驶员与车掌。 */
  List<UUID> crew(String trainName);

  /** 给站长看的值乘情况：有车掌写车掌，否则写驾驶员、ATO 或自动运行。 */
  CrewOnTrain crewOn(String trainName);

  /** 让调度下一拍重评这列车（放行扣车后立即生效）。 */
  void reevaluate(String trainName);

  /** 任一节车厢离 {@code center} 水平 {@code range} 格以内的调度列车。 */
  List<TrainNear> trainsNear(Spot center, double range);

  /** 站台紧急停车。 */
  Emergency emergencyStop(String trainName);

  /** 解除站台紧急停车后立即重算一次信号。 */
  void refreshSignal(String trainName);

  /** 列车在这一站之后不再载客（本站是最后一个载客站，之后回库、接空驶段或销毁）；终点折返接着开下一趟的不算。 */
  boolean lastPassengerStop(String trainName, NodeId node);

  /** 车上的乘客（不含值乘的驾驶员与车掌）。 */
  List<UUID> riders(String trainName);

  /** 车内广播：请全体乘客下车（聊天、副标题与提示音）。 */
  void announceClearing(String trainName);

  /** 把乘客从车上送到这一站的站台。 */
  void sendToPlatform(UUID rider, NodeId node);
}
