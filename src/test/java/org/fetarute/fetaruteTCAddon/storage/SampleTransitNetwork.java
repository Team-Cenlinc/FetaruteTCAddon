package org.fetarute.fetaruteTCAddon.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;

/**
 * 样例路网（贴近实服数据形态）：停靠点都不绑定 stationId，只写节点；DYNAMIC 停靠只写在 notes 里。
 *
 * <ul>
 *   <li>SURC 公司 / SURC 运营商：车站 KPO 葵坪、PPK 平坪口、HHU 海湖、WYB 湾油埠；线路 WS（有色）、DS（无色，回退运营商主题色）
 *   <li>FTA 公司 / FTA 运营商：车站 PPK 坪洲（与 SURC 同站码）、SLA 狮岭；线路 SL
 *   <li>车站组 PPK（归 SURC）：SURC PPK（站内，排序 0）+ FTA PPK（出站，步行 90 秒，排序 1）
 * </ul>
 *
 * <p>交路：
 *
 * <ul>
 *   <li>WS-1（各停 / 运营）：KPO 停 → PPK 咽喉 过 → PPK 停 → 区间点 过 → HHU 过 → ZZZ 停（没有车站记录）→ DYNAMIC WYB 终到 →
 *       LWN 车库 过
 *   <li>DS-R（新快速 / 回库）：WYB:2 停 → HHU:2 终到 → LWN 车库 过
 *   <li>SL-1（限定特急 / 出库）：FTA PPK 停 → SLA 终到
 * </ul>
 */
public final class SampleTransitNetwork {

  public final Company surc;
  public final Company fta;
  public final Operator surcOp;
  public final Operator ftaOp;
  public final Station kpo;
  public final Station ppk;
  public final Station hhu;
  public final Station wyb;
  public final Station ftaPpk;
  public final Station sla;
  public final Line ws;
  public final Line ds;
  public final Line sl;
  public final Route ws1;
  public final Route dsR;
  public final Route sl1;
  public final StationGroup ppkGroup;

  public SampleTransitNetwork(TransitTestStorage storage) {
    surc = storage.company("SURC");
    fta = storage.company("FTA");
    surcOp = storage.operator(surc, "SURC", "#00AAFF");
    ftaOp = storage.operator(fta, "FTA", "#FF8800");
    kpo = storage.station(surcOp, "KPO", "葵坪");
    ppk = storage.station(surcOp, "PPK", "平坪口");
    hhu = storage.station(surcOp, "HHU", "海湖");
    wyb = storage.station(surcOp, "WYB", "湾油埠");
    ftaPpk = storage.station(ftaOp, "PPK", "坪洲");
    sla = storage.station(ftaOp, "SLA", "狮岭");
    ws = storage.line(surcOp, "WS", "西海线", "#E60012");
    ds = storage.line(surcOp, "DS", "东山线", null);
    sl = storage.line(ftaOp, "SL", "狮岭线", "#00A0E9");

    ws1 = storage.route(ws, "WS-1", RoutePatternType.LOCAL, RouteOperationType.OPERATION);
    storage.stops(
        ws1,
        new String[] {"STOP", "SURC:S:KPO:1"},
        new String[] {"PASS", "SURC:S:PPK:1:001"},
        new String[] {"STOP", "SURC:S:PPK:1"},
        new String[] {"PASS", "SURC:PPK:HHU:1:001"},
        new String[] {"PASS", "SURC:S:HHU:1"},
        new String[] {"STOP", "SURC:S:ZZZ:1"},
        new String[] {"TERMINATE", null, "DYNAMIC:SURC:S:WYB:[1:2]"},
        new String[] {"PASS", "SURC:D:LWN:1"});
    dsR = storage.route(ds, "DS-R", RoutePatternType.NEO_RAPID, RouteOperationType.RETURN);
    storage.stops(
        dsR,
        new String[] {"STOP", "SURC:S:WYB:2"},
        new String[] {"TERMINATE", "SURC:S:HHU:2"},
        new String[] {"PASS", "SURC:D:LWN:1"});
    sl1 = storage.route(sl, "SL-1", RoutePatternType.LIMITED_EXPRESS, RouteOperationType.CREATE);
    storage.stops(
        sl1, new String[] {"STOP", "FTA:S:PPK:1"}, new String[] {"TERMINATE", "FTA:S:SLA:1"});

    Instant now = Instant.parse("2026-09-27T00:00:00Z");
    ppkGroup =
        new StationGroup(
            UUID.randomUUID(),
            surc.id(),
            "PPK",
            "平坪口",
            Optional.of("Ping Ping Hau"),
            Map.of(),
            now,
            now);
    storage.provider().stationGroups().save(ppkGroup);
    storage
        .provider()
        .stationGroups()
        .saveMember(
            new StationGroupMember(
                ppkGroup.id(), ppk.id(), StationTransferType.IN_STATION, Optional.empty(), 0));
    storage
        .provider()
        .stationGroups()
        .saveMember(
            new StationGroupMember(
                ppkGroup.id(),
                ftaPpk.id(),
                StationTransferType.OUT_OF_STATION,
                Optional.of(90),
                1));
  }
}
