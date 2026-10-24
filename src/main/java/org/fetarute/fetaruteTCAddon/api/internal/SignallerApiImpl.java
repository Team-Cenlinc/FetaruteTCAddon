package org.fetarute.fetaruteTCAddon.api.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.signaller.SignallerApi;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.signaller.SignallerService;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/** 信号员 API 的实现：读信号员服务发布的概况快照（任意线程）；服务未建立时按不可用处理。 */
public final class SignallerApiImpl implements SignallerApi {

  private final FetaruteTCAddon plugin;

  public SignallerApiImpl(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  private Optional<SignallerService> service() {
    return plugin.getSignallerService();
  }

  @Override
  public boolean enabled() {
    return service().map(SignallerService::enabled).orElse(false);
  }

  @Override
  public List<SignalBoxView> boxes() {
    return service()
        .map(found -> found.published().stream().map(SignallerApiImpl::view).toList())
        .orElse(List.of());
  }

  @Override
  public Optional<SignalBoxView> box(String operatorCode, String zoneCode) {
    if (operatorCode == null || zoneCode == null) {
      return Optional.empty();
    }
    return boxes().stream()
        .filter(box -> box.operatorCode().equalsIgnoreCase(operatorCode.trim()))
        .filter(box -> box.zoneCode().equalsIgnoreCase(zoneCode.trim()))
        .findFirst();
  }

  @Override
  public Optional<SignalBoxView> dutyOf(UUID playerId) {
    if (playerId == null) {
      return Optional.empty();
    }
    return boxes().stream()
        .filter(box -> box.signallerId().filter(playerId::equals).isPresent())
        .findFirst();
  }

  @Override
  public boolean waitingForSignaller(String trainName) {
    return waitingIn(boxes(), trainName);
  }

  /** 这列车在哪座人工模式的楼里等许可（列车名按规范化的键比较）。 */
  static boolean waitingIn(List<SignalBoxView> boxes, String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return false;
    }
    String key = TrainNameNormalizer.normalizeKey(trainName);
    return boxes.stream()
        .filter(box -> box.mode() == Mode.MANUAL)
        .flatMap(box -> box.waitingTrains().stream())
        .anyMatch(name -> TrainNameNormalizer.normalizeKey(name).equals(key));
  }

  /** 信号员服务的概况换成对外的写法。 */
  static SignalBoxView view(SignallerService.BoxStatus status) {
    return new SignalBoxView(
        status.operator(),
        status.zone(),
        status.name(),
        status.mode() == SignalboxState.Mode.MANUAL ? Mode.MANUAL : Mode.AUTO,
        status.signallerId(),
        status.signaller(),
        status.onDutySince(),
        status.waitingTrains(),
        status.permits(),
        status.heldTrains());
  }
}
