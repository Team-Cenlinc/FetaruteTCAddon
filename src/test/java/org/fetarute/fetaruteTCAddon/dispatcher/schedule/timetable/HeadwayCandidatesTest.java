package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 最小可行间隔的搜索顺序：可行性不随间隔单调（实测 WS 132 可行、140 不可行、150 又可行），原来 10 秒一档从 120 直接跳到 150。 */
class HeadwayCandidatesTest {

  /** 没有结构预筛：只交格点，升序。 */
  @Test
  void withoutPrescreenOnlyLatticePointsAreTried() {
    assertEquals(
        List.of(125, 130, 135, 140),
        drain(new TimetableBuilder.HeadwayCandidates(120, 140, 5, null)));
  }

  /** 有结构预筛：错不开的整段跳过（计数），每段错得开的区间交出第一秒，其余只交格点。 仿 WS：121–131 错不开、132–139 错得开、140–144 错不开、145 起错得开。 */
  @Test
  void prescreenSkipsInfeasibleRangesAndTriesEachIslandStart() {
    TimetableBuilder.HeadwayCandidates candidates =
        new TimetableBuilder.HeadwayCandidates(120, 150, 5, h -> (h >= 132 && h < 140) || h >= 145);

    assertEquals(List.of(132, 135, 145, 150), drain(candidates));
    assertEquals(11 + 5, candidates.skipped());
    assertEquals(121, candidates.skippedFrom());
    assertEquals(144, candidates.skippedTo());
  }

  /** 惰性：交出一个之后不再往后问预筛——找到可行间隔就停，不为后面的档白跑相位层。 */
  @Test
  void prescreenIsEvaluatedLazily() {
    List<Integer> asked = new ArrayList<>();
    TimetableBuilder.HeadwayCandidates candidates =
        new TimetableBuilder.HeadwayCandidates(
            120,
            480,
            5,
            h -> {
              asked.add(h);
              return h >= 132;
            });

    assertEquals(OptionalInt.of(132), candidates.next());
    assertEquals(12, asked.size(), "只问到 132 为止");
  }

  private static List<Integer> drain(TimetableBuilder.HeadwayCandidates candidates) {
    List<Integer> out = new ArrayList<>();
    for (OptionalInt next = candidates.next(); next.isPresent(); next = candidates.next()) {
      out.add(next.getAsInt());
    }
    return out;
  }
}
