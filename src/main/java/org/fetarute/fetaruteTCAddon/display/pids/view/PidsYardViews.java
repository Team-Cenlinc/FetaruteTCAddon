package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;

/** 构建站场图屏的一帧：站名、时间、图例与说明文案配上站场图。 */
public final class PidsYardViews {

  private final PidsDirectory directory;
  private final PidsVocabulary vocabulary;

  public PidsYardViews(PidsDirectory directory, PidsVocabulary vocabulary) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
  }

  /**
   * 车站的站场图，标题是站名。
   *
   * @param station 车站
   * @param theme 配色
   * @param now 当前时刻
   * @param zone 时钟时区
   * @param picture 站场图
   */
  public PidsYardView build(
      PidsStationKey station, PidsTheme theme, Instant now, ZoneId zone, YardPicture picture) {
    return build(
        directory.stationName(station.toString()).orElse(new Names(station.stationCode(), "")),
        theme,
        now,
        zone,
        picture);
  }

  /**
   * @param name 标题：站名或控制区名
   * @param theme 配色
   * @param now 当前时刻
   * @param zone 时钟时区
   * @param picture 站场图
   */
  public PidsYardView build(
      Names name, PidsTheme theme, Instant now, ZoneId zone, YardPicture picture) {
    boolean partial = picture.geometry().map(geometry -> geometry.partial()).orElse(false);
    return new PidsYardView(
        theme,
        PidsViewBuilder.CLOCK.format(now.atZone(zone)),
        new Names(PidsText.compactSeparators(name.primary()), name.secondary()),
        vocabulary.yardLegend(),
        vocabulary.yardTrack(),
        picture,
        picture.problem().map(vocabulary::yardProblem),
        partial ? Optional.of(vocabulary.yardPartial()) : Optional.empty());
  }
}
