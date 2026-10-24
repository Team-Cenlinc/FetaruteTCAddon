package org.fetarute.fetaruteTCAddon.display.pids.fixtures;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.display.Lateness;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.render.PidsTheme;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsVocabulary;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsYardView;
import org.fetarute.fetaruteTCAddon.display.pids.view.SignalboxView;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardGeometry;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardPicture;
import org.fetarute.fetaruteTCAddon.signaller.console.SignalboxState;

/**
 * 信号员控制台测试夹具：{@code signalbox-3x5} 布局上的小站 ABC（{@link YardFixtures#graph()}），两列车、两架入口信号、两条可改停的股道。
 */
public final class SignalboxFixtures {

  /** 站场图上 1 道里的一列车（车次 1234）。 */
  public static final String TRAIN_A = "train-a";

  /** 站场图上咽喉里的一列车（车次 5678）。 */
  public static final String TRAIN_B = "train-b";

  /** 在西头入口等许可的车（不在站场图上）。 */
  public static final String WAITING = "train-w";

  /** 西头入口（区间点 IA 到道岔 SW1）。 */
  public static final EdgeId WEST_ENTRY = EdgeId.undirected(YardFixtures.IA, YardFixtures.SW1);

  /** 东头入口（道岔 SW2 到区间点 IB）。 */
  public static final EdgeId EAST_ENTRY = EdgeId.undirected(YardFixtures.SW2, YardFixtures.IB);

  private SignalboxFixtures() {}

  /** 内置的 {@code signalbox-3x5}。 */
  public static PidsLayout layout() {
    return PidsFixtures.builtInLayout("signalbox-3x5");
  }

  /** 按布局的站场图组件算出小站 ABC 的几何。 */
  public static YardGeometry geometry(PidsLayout layout) {
    SimpleRailGraph graph = YardFixtures.graph();
    PidsLayout.Yard yard = layout.yard().orElseThrow();
    return YardGeometry.of(
        graph,
        YardArea.of(graph, "SURC", "ABC").orElseThrow(),
        graph.interlockingState().footprintCellsByEdge(),
        new YardGeometry.Rect(
            yard.trackLeft(), yard.trackTop(), yard.trackWidth(), yard.trackHeight()),
        code -> code);
  }

  /** 两列车在图上：TRAIN_A 在 1 道（准点），TRAIN_B 在西头咽喉（晚点）。 */
  public static YardPicture picture(YardGeometry geometry) {
    return new YardPicture(
        Optional.of(geometry),
        List.of(
            new YardPicture.EdgeMark(
                EdgeId.undirected(YardFixtures.P1, YardFixtures.SW2), YardPicture.Mark.ROUTE)),
        List.of(
            new YardPicture.TrainMark(
                TRAIN_A,
                "1234",
                Optional.of(0xF28C28),
                Lateness.ON_TIME,
                List.of(List.of(at(geometry, 10), at(geometry, 8), at(geometry, 6)))),
            new YardPicture.TrainMark(
                TRAIN_B,
                "5678",
                Optional.empty(),
                Lateness.LATE,
                List.of(List.of(at(geometry, -30), at(geometry, -32))))),
        Optional.empty());
  }

  /** 1 道上东西坐标为 {@code x} 的方块在屏幕上的位置。 */
  public static YardGeometry.Point at(YardGeometry geometry, int x) {
    return geometry.pointOf(YardFixtures.cell(x, 0, false)).orElseThrow();
  }

  /** 站场图屏的一帧：深色、14:32、控制区名“示例站信号楼”。 */
  public static PidsYardView yardView(YardPicture picture) {
    return yardView(PidsTheme.DARK, picture);
  }

  public static PidsYardView yardView(PidsTheme theme, YardPicture picture) {
    PidsVocabulary vocabulary = vocabulary();
    return new PidsYardView(
        theme,
        "14:32",
        new Names("示例站信号楼", "Example Signal Box"),
        vocabulary.yardLegend(),
        vocabulary.yardTrack(),
        vocabulary.yardDepot(),
        vocabulary.yardDepotTrack(),
        picture,
        Optional.empty(),
        Optional.empty());
  }

  /** 人工模式、Sinapole 值守、选中 TRAIN_A、四行列车（一行在等、带进度条；一行自动通过）、呼叫牌、计划进路与排列中的进路、三种入口信号显示、两条可改停的股道。 */
  public static SignalboxState state() {
    return new SignalboxState(
        SignalboxState.Mode.MANUAL,
        Optional.of("Sinapole"),
        Optional.of(TRAIN_A),
        List.of(
            new SignalboxState.TrainRow(
                WAITING,
                "3310",
                Optional.of(0x3366CC),
                Optional.of("DEF"),
                SignalboxState.RowStatus.WAITING,
                18,
                OptionalLong.of(80),
                0.6,
                false,
                false),
            new SignalboxState.TrainRow(
                TRAIN_A,
                "1234",
                Optional.of(0xF28C28),
                Optional.of("XYZ"),
                SignalboxState.RowStatus.DWELLING,
                0,
                OptionalLong.of(-30),
                0.0,
                false,
                false),
            new SignalboxState.TrainRow(
                TRAIN_B,
                "5678",
                Optional.empty(),
                Optional.of("DEF"),
                SignalboxState.RowStatus.ROUTE_SET,
                0,
                OptionalLong.of(0),
                0.0,
                true,
                false),
            new SignalboxState.TrainRow(
                "train-c",
                "G9001",
                Optional.of(0x7FCC19),
                Optional.empty(),
                SignalboxState.RowStatus.APPROACHING,
                0,
                OptionalLong.empty(),
                0.0,
                false,
                false)),
        Map.of(
            SignalboxState.Button.SET_ROUTE, true,
            SignalboxState.Button.CANCEL_ROUTE, false,
            SignalboxState.Button.HOLD, true,
            SignalboxState.Button.RELEASE, false,
            SignalboxState.Button.AUTO_PASS, true,
            SignalboxState.Button.MODE, true),
        Optional.of(new SignalboxState.Call(WAITING, "Driver")),
        false,
        Optional.of(
            new SignalboxState.Notice(
                SignalboxState.NoticeKind.ROUTE_BLOCKED, Optional.of(TRAIN_B))),
        Set.of(
            EdgeId.undirected(YardFixtures.SW1, YardFixtures.T1),
            EdgeId.undirected(YardFixtures.T1, YardFixtures.P1)),
        Set.of(EdgeId.undirected(YardFixtures.SW1, YardFixtures.P2)),
        List.of(
            new SignalboxState.EntrySignal(
                WEST_ENTRY, SignalboxState.Aspect.STOP, Optional.of(WAITING), false),
            new SignalboxState.EntrySignal(
                EAST_ENTRY, SignalboxState.Aspect.PROCEED, Optional.empty(), true)),
        List.of(
            new SignalboxState.PlatformChoice(YardFixtures.P1, "1", true),
            new SignalboxState.PlatformChoice(YardFixtures.P2, "2", false)));
  }

  /** 控制台一帧：去向 DEF 查得到站名“东湾”，XYZ 查不到。 */
  public static SignalboxView view(PidsYardView yard, Optional<SignalboxState> state) {
    return new SignalboxView(
        yard,
        state,
        vocabulary().signalboxLabels(),
        Map.of("DEF", "东湾"),
        state.isPresent() ? Optional.empty() : Optional.of(vocabulary().signalboxNotReady()));
  }

  /** 按内置中文语言文件取文案。 */
  public static PidsVocabulary vocabulary() {
    return new PidsVocabulary(language("zh_CN"));
  }

  /** 读内置语言文件，缺的键原样返回键名（便于断言没有缺键）。 */
  public static Function<String, String> language(String locale) {
    YamlConfiguration yaml = new YamlConfiguration();
    try (InputStreamReader reader =
        new InputStreamReader(
            SignalboxFixtures.class.getClassLoader().getResourceAsStream("lang/" + locale + ".yml"),
            StandardCharsets.UTF_8)) {
      yaml.load(reader);
    } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
      throw new IllegalStateException("读取语言文件失败 " + locale, ex);
    }
    return key -> yaml.getString(key, key);
  }
}
