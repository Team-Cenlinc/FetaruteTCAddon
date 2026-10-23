package org.fetarute.fetaruteTCAddon.web;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.internal.LineApiImpl;
import org.fetarute.fetaruteTCAddon.api.internal.OperatorApiImpl;
import org.fetarute.fetaruteTCAddon.api.internal.StationApiImpl;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;

/**
 * 只读 HTTP 接口要的数据，可在后台线程调用。
 *
 * <p>运营商、线路、车站取自车站目录的内存快照（不读库：存储只有一条连接，读库会和主线程抢）；车站组、停靠线路、在线列车与站牌取自公开 API 的内存快照；线路运行状况取自站台屏的汇总。 公开
 * API 或车站目录还没初始化（或已停用）时各项为空。
 */
interface WebApiData {

  Collection<OperatorApi.OperatorInfo> operators();

  Collection<LineApi.LineInfo> lines();

  Collection<StationApi.StationInfo> stations();

  Collection<StationApi.StationGroupInfo> stationGroups();

  List<StationApi.ServingLine> linesServing(UUID stationId);

  /**
   * 线路此刻的运行状况（与线路运行状况屏同一份汇总）。
   *
   * @param operatorCode 运营商代码
   * @param lineCode 线路代码
   * @return 站台屏没启用、线路筹建中或不在目录里时为空
   */
  Optional<PidsLineStatus> lineStatus(String operatorCode, String lineCode);

  Collection<TrainApi.TrainSnapshot> trains();

  /**
   * 车站代码的规范写法（与车站目录一致，不分大小写查找）。
   *
   * @return 车站不存在或车站目录还没加载时为空
   */
  Optional<StationCodes> station(String operatorCode, String stationCode);

  /**
   * 线路代码的规范写法（不分大小写查找）。
   *
   * @return 线路不存在或车站目录还没加载时为空
   */
  Optional<String> lineCode(String operatorCode, String lineCode);

  /**
   * 车站的规范写法。
   *
   * @param operatorCode 运营商代码
   * @param stationCode 站码
   */
  record StationCodes(String operatorCode, String stationCode) {}

  /**
   * 站牌。
   *
   * @param operator 运营商代码
   * @param stationCode 站码
   * @param lineCode 线路代码；为空时全部线路
   * @param horizon 时间窗口
   */
  EtaApi.BoardResult board(String operator, String stationCode, String lineCode, Duration horizon);

  /**
   * 每次调用取此刻的公开 API 与车站目录快照（重载后换成新的实例）。
   *
   * @param api 公开 API
   * @param directory 车站目录快照
   * @param lineStatus 线路运行状况（运营商代码、线路代码）
   */
  static WebApiData of(
      Supplier<Optional<FetaruteApi>> api,
      Supplier<Optional<StationDirectory.Snapshot>> directory,
      BiFunction<String, String, Optional<PidsLineStatus>> lineStatus) {
    return new WebApiData() {
      @Override
      public Optional<PidsLineStatus> lineStatus(String operatorCode, String lineCode) {
        return lineStatus.apply(operatorCode, lineCode);
      }

      @Override
      public Collection<OperatorApi.OperatorInfo> operators() {
        return directory.get().stream()
            .flatMap(snapshot -> snapshot.operators().stream())
            .map(OperatorApiImpl::convertOperator)
            .toList();
      }

      @Override
      public Collection<LineApi.LineInfo> lines() {
        return directory.get().stream()
            .flatMap(snapshot -> snapshot.lines().stream())
            .map(LineApiImpl::convertLine)
            .toList();
      }

      @Override
      public Collection<StationApi.StationInfo> stations() {
        return directory.get().stream()
            .flatMap(snapshot -> snapshot.stations().stream())
            .map(entry -> StationApiImpl.convertStation(entry.station()))
            .toList();
      }

      @Override
      public Optional<StationCodes> station(String operatorCode, String stationCode) {
        return directory
            .get()
            .flatMap(snapshot -> snapshot.findStation(operatorCode, stationCode))
            .map(entry -> new StationCodes(entry.operator().code(), entry.code()));
      }

      @Override
      public Optional<String> lineCode(String operatorCode, String lineCode) {
        return directory
            .get()
            .flatMap(snapshot -> snapshot.findLine(operatorCode, lineCode))
            .map(found -> found.line().code());
      }

      // 子接口可能为空（存储或到站预测没起来）：按没有数据处理
      @Override
      public Collection<StationApi.StationGroupInfo> stationGroups() {
        return api.get()
            .map(FetaruteApi::stations)
            .map(StationApi::listStationGroups)
            .orElse(List.of());
      }

      @Override
      public List<StationApi.ServingLine> linesServing(UUID stationId) {
        return api.get()
            .map(FetaruteApi::stations)
            .map(stations -> stations.linesServing(stationId))
            .orElse(List.of());
      }

      @Override
      public Collection<TrainApi.TrainSnapshot> trains() {
        return api.get()
            .map(FetaruteApi::trains)
            .map(trains -> trains.listAllActiveTrains(false))
            .orElse(List.of());
      }

      @Override
      public EtaApi.BoardResult board(
          String operator, String stationCode, String lineCode, Duration horizon) {
        return api.get()
            .map(FetaruteApi::eta)
            .map(eta -> eta.getBoard(operator, stationCode, lineCode, horizon))
            .orElse(new EtaApi.BoardResult(List.of()));
      }
    };
  }
}
