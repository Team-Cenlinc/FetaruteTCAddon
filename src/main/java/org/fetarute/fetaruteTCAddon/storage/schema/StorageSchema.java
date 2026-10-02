package org.fetarute.fetaruteTCAddon.storage.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;

/** 针对 Railway Company 模型的 DDL 生成器，默认生成 SQLite 语句，可按方言调整。 */
public final class StorageSchema {

  private static final String DEFAULT_PREFIX = "fta_";
  private final String tablePrefix;

  public StorageSchema() {
    this(DEFAULT_PREFIX);
  }

  public StorageSchema(String tablePrefix) {
    this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
  }

  public String tablePrefix() {
    return tablePrefix;
  }

  /** 生成 SQLite 所需的所有建表语句。 */
  public List<String> sqliteStatements() {
    return statements(new SqliteDialect());
  }

  /** 根据指定方言生成建表语句，供启动时迁移。 */
  public List<String> statements(SqlDialect dialect) {
    Objects.requireNonNull(dialect, "dialect");
    List<String> ddl = new ArrayList<>();
    ddl.add(playerIdentities(dialect));
    ddl.add(uniqueIndex("player_identities_uuid", "player_identities", "player_uuid"));
    ddl.add(companies(dialect));
    ddl.add(uniqueIndex("companies_code", "companies", "code"));
    ddl.add(companyMembers(dialect));
    ddl.add(index("company_members_player", "company_members", "player_identity_id"));
    ddl.add(companyMemberInvites(dialect));
    ddl.add(index("company_member_invites_player", "company_member_invites", "player_identity_id"));
    ddl.add(operators(dialect));
    ddl.add(uniqueIndex("operators_code", "operators", "company_id, code"));
    ddl.add(lines(dialect));
    ddl.add(uniqueIndex("lines_code", "lines", "operator_id, code"));
    ddl.add(stations(dialect));
    ddl.add(uniqueIndex("stations_code", "stations", "operator_id, code"));
    ddl.add(stationGroups(dialect));
    ddl.add(stationGroupMembers(dialect));
    ddl.add(routes(dialect));
    ddl.add(uniqueIndex("routes_code", "routes", "line_id, code"));
    ddl.add(routeStops(dialect));
    ddl.add(index("route_stops_route", "route_stops", "route_id"));
    ddl.add(timetables(dialect));
    ddl.add(uniqueIndex("timetables_code", "timetables", "line_id, code"));
    ddl.add(index("timetables_status", "timetables", "status"));
    ddl.add(timetableTrips(dialect));
    ddl.add(uniqueIndex("timetable_trips_code", "timetable_trips", "timetable_id, trip_code"));
    ddl.add(index("timetable_trips_route", "timetable_trips", "route_id"));
    ddl.add(timetableDuties(dialect));
    ddl.add(uniqueIndex("timetable_duties_code", "timetable_duties", "timetable_id, duty_code"));
    ddl.add(timetableBaselines(dialect));
    ddl.add(timetablePlatformPlans(dialect));
    ddl.add(index("timetable_baselines_neighbor", "timetable_baselines", "neighbor_timetable_id"));
    ddl.add(hudTemplates(dialect));
    ddl.add(uniqueIndex("hud_templates_key", "hud_templates", "company_id, type, name"));
    ddl.add(hudLineBindings(dialect));
    ddl.add(pidsScreens(dialect));
    ddl.add(railNodes(dialect));
    ddl.add(index("rail_nodes_world", "rail_nodes", "world_id"));
    ddl.add(railEdges(dialect));
    ddl.add(index("rail_edges_world", "rail_edges", "world_id"));
    ddl.add(railInterlockingSnapshots(dialect));
    ddl.add(railEdgeOverrides(dialect));
    ddl.add(index("rail_edge_overrides_world", "rail_edge_overrides", "world_id"));
    ddl.add(railComponentCautions(dialect));
    ddl.add(index("rail_component_cautions_world", "rail_component_cautions", "world_id"));
    ddl.add(railGraphSnapshots(dialect));
    return Collections.unmodifiableList(ddl);
  }

  private String playerIdentities(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    player_uuid %s NOT NULL,
                    name %s NOT NULL,
                    auth_type %s NOT NULL,
                    external_ref %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL
                );
                """,
        table("player_identities"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType());
  }

  private String companies(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    owner_identity_id %s NOT NULL,
                    status %s NOT NULL,
                    balance_minor %s NOT NULL,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (owner_identity_id) REFERENCES %s(id) ON DELETE RESTRICT
                );
                """,
        table("companies"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.bigIntType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("player_identities"));
  }

  private String companyMembers(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    company_id %s NOT NULL,
                    player_identity_id %s NOT NULL,
                    roles %s NOT NULL,
                    joined_at %s NOT NULL,
                    permissions %s,
                    PRIMARY KEY (company_id, player_identity_id),
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_identity_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("company_members"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.jsonType(),
        table("companies"),
        table("player_identities"));
  }

  private String companyMemberInvites(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    company_id %s NOT NULL,
                    player_identity_id %s NOT NULL,
                    roles %s NOT NULL,
                    invited_by_identity_id %s NOT NULL,
                    invited_at %s NOT NULL,
                    PRIMARY KEY (company_id, player_identity_id),
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_identity_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (invited_by_identity_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("company_member_invites"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.jsonType(),
        dialect.uuidType(),
        dialect.timestampType(),
        table("companies"),
        table("player_identities"),
        table("player_identities"));
  }

  private String operators(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    company_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    color_theme %s,
                    priority %s NOT NULL,
                    description %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("operators"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("companies"));
  }

  private String lines(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    operator_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    service_type %s NOT NULL,
                    color %s,
                    status %s NOT NULL,
                    spawn_freq_baseline_sec %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("lines"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("operators"));
  }

  private String stations(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    operator_id %s NOT NULL,
                    primary_line_id %s,
                    name %s NOT NULL,
                    secondary_name %s,
                    world %s,
                    x %s,
                    y %s,
                    z %s,
                    yaw %s,
                    pitch %s,
                    graph_node_id %s,
                    amenities %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (primary_line_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """,
        table("stations"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.doubleType(),
        dialect.doubleType(),
        dialect.doubleType(),
        dialect.doubleType(),
        dialect.doubleType(),
        dialect.stringType(),
        dialect.jsonType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("operators"),
        table("lines"));
  }

  /**
   * 车站组（乘客视角的换乘站）。
   *
   * <p>唯一约束写在表内而不是单独的 {@code CREATE INDEX IF NOT EXISTS}：后者 MySQL 不支持，表内 {@code UNIQUE} 两种后端都能建。
   */
  private String stationGroups(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    company_id %s NOT NULL,
                    code %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    UNIQUE (company_id, code),
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("station_groups"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("companies"));
  }

  /** 车站组成员；{@code station_id} 全局唯一，即一个车站最多属于一个组。 */
  private String stationGroupMembers(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    group_id %s NOT NULL,
                    station_id %s NOT NULL UNIQUE,
                    transfer_type %s NOT NULL DEFAULT 'IN_STATION',
                    walk_secs %s,
                    sort_order %s NOT NULL DEFAULT 0,
                    PRIMARY KEY (group_id, station_id),
                    FOREIGN KEY (group_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (station_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("station_group_members"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.intType(),
        table("station_groups"),
        table("stations"));
  }

  private String routes(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    line_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    pattern_type %s NOT NULL,
                    operation_type %s NOT NULL,
                    distance_m %s,
                    runtime_secs %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (line_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("routes"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.intType(),
        dialect.jsonType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("lines"));
  }

  private String routeStops(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    route_id %s NOT NULL,
                    sequence %s NOT NULL,
                    station_id %s,
                    waypoint_node_id %s,
                    dwell_secs %s,
                    pass_type %s NOT NULL,
                    notes %s,
                    PRIMARY KEY (route_id, sequence),
                    FOREIGN KEY (route_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (station_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """,
        table("route_stops"),
        dialect.uuidType(),
        dialect.intType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.stringType(),
        table("routes"),
        table("stations"));
  }

  /**
   * 时刻表表头，含各 route 的站间时分档案。
   *
   * <p>档案（{@code route_plans}）做成 JSON 而不是独立表，是因为它对时刻表是整体替换的：一条线路下几条
   * route、每条二十来个停靠点，读写永远是整份，拆成子表只会多一次 join 和一套"半份档案"的失败模式。 发车表与车辆交路则相反——它们按趟增删、需要按编号唯一，所以各自单列成表。
   */
  private String timetables(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    company_id %s NOT NULL,
                    operator_id %s NOT NULL,
                    line_id %s NOT NULL,
                    code %s NOT NULL,
                    name %s NOT NULL,
                    status %s NOT NULL,
                    zone_id %s NOT NULL,
                    service_start_second %s NOT NULL,
                    service_end_second %s NOT NULL,
                    route_plans %s NOT NULL,
                    notes %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (line_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("timetables"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.intType(),
        dialect.textType(),
        dialect.stringType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("lines"));
  }

  /** 发车表：一趟车一行，只存起点发车时刻与承担它的车辆交路。 */
  private String timetableTrips(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    timetable_id %s NOT NULL,
                    route_id %s NOT NULL,
                    duty_id %s,
                    sequence %s NOT NULL,
                    trip_code %s NOT NULL,
                    departure_second_of_day %s NOT NULL,
                    FOREIGN KEY (timetable_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("timetable_trips"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.intType(),
        table("timetables"));
  }

  /**
   * 邻表基线：build 时读到的邻表身份（id + updatedAt）与冲突计数。publish 时用它判断邻表集合有没有变、要不要重检。
   *
   * <p>是新表而不是给 {@code fta_timetables} 加列：本项目没有 schema 迁移，新表会被 {@code CREATE TABLE IF NOT EXISTS}
   * 自动建出来。
   */
  private String timetableBaselines(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    timetable_id %s NOT NULL,
                    neighbor_timetable_id %s NOT NULL,
                    neighbor_code %s NOT NULL,
                    neighbor_updated_at %s NOT NULL,
                    shared_resources %s NOT NULL,
                    conflicts_at_target %s NOT NULL,
                    stale_against_graph %s NOT NULL,
                    PRIMARY KEY (timetable_id, neighbor_timetable_id),
                    FOREIGN KEY (timetable_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("timetable_baselines"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.timestampType(),
        dialect.intType(),
        dialect.intType(),
        dialect.intType(),
        table("timetables"));
  }

  /**
   * 计划股道：一趟车在某个动态站台停靠的计划股道（build 时按站台组容量排出），运行时作为选台偏好。
   *
   * <p>新表（{@code CREATE TABLE IF NOT EXISTS}），旧库自动建出；旧表没有计划，运行时照旧选台，重新 build 才有。
   */
  private String timetablePlatformPlans(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    timetable_id %s NOT NULL,
                    trip_id %s NOT NULL,
                    stop_sequence %s NOT NULL,
                    node_id %s NOT NULL,
                    PRIMARY KEY (timetable_id, trip_id, stop_sequence),
                    FOREIGN KEY (timetable_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("timetable_platform_plans"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.intType(),
        dialect.stringType(),
        table("timetables"));
  }

  /**
   * 车辆交路：一辆车从出库到回库之间承担的一串班次。
   *
   * <p>{@code end_depot_node_id} 是"每辆车最终都会回库"这条不变量的物理落点，因此是 NOT NULL—— 没有回库端点的 duty
   * 是一条没有出口的链，不允许落库。{@code create_route_id}/{@code return_route_id} 是两端的走行线路， 为空表示首班/末班 route
   * 本身从车库始发/以销毁收尾；{@code return_second} 是回库票的发出时刻。
   */
  private String timetableDuties(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    timetable_id %s NOT NULL,
                    sequence %s NOT NULL,
                    duty_code %s NOT NULL,
                    start_depot_node_id %s NOT NULL,
                    end_depot_node_id %s NOT NULL,
                    create_route_id %s,
                    return_route_id %s,
                    trip_ids %s NOT NULL,
                    planned_start_second %s NOT NULL,
                    return_second %s NOT NULL,
                    planned_end_second %s NOT NULL,
                    close_reason %s NOT NULL,
                    FOREIGN KEY (timetable_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("timetable_duties"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.textType(),
        dialect.intType(),
        dialect.intType(),
        dialect.intType(),
        dialect.stringType(),
        table("timetables"));
  }

  private String hudTemplates(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    company_id %s NOT NULL,
                    type %s NOT NULL,
                    name %s NOT NULL,
                    content %s NOT NULL,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("hud_templates"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.textType(),
        dialect.timestampType(),
        dialect.timestampType(),
        table("companies"));
  }

  private String hudLineBindings(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    line_id %s NOT NULL,
                    template_type %s NOT NULL,
                    template_id %s NOT NULL,
                    updated_at %s NOT NULL,
                    PRIMARY KEY (line_id, template_type),
                    FOREIGN KEY (line_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (template_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """,
        table("hud_line_bindings"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.uuidType(),
        dialect.timestampType(),
        table("lines"),
        table("hud_templates"));
  }

  /**
   * 站台屏。车站按运营商代码 + 站码记录（与公开 API 一致），不设外键：车站删掉后屏幕仍在墙上，显示为空表。
   *
   * <p>同一世界同一方块同一朝向只能有一块屏幕，唯一约束写在表内（MySQL 不支持 {@code CREATE INDEX IF NOT EXISTS}）。
   */
  private String pidsScreens(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    world_id %s NOT NULL,
                    x %s NOT NULL,
                    y %s NOT NULL,
                    z %s NOT NULL,
                    facing %s NOT NULL,
                    tile_rows %s NOT NULL,
                    tile_cols %s NOT NULL,
                    layout_id %s NOT NULL,
                    operator_code %s,
                    station_code %s,
                    platforms %s,
                    line_codes %s,
                    appearance %s NOT NULL,
                    mode %s NOT NULL,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    UNIQUE (world_id, x, y, z, facing)
                );
                """,
        table("pids_screens"),
        dialect.uuidType(),
        dialect.uuidType(),
        dialect.intType(),
        dialect.intType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.jsonType(),
        dialect.jsonType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.timestampType(),
        dialect.timestampType());
  }

  private String railNodes(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s NOT NULL,
                    node_id %s NOT NULL,
                    node_type %s NOT NULL,
                    x %s NOT NULL,
                    y %s NOT NULL,
                    z %s NOT NULL,
                    tc_destination %s,
                    waypoint_operator %s,
                    waypoint_origin %s,
                    waypoint_destination %s,
                    waypoint_track %s,
                    waypoint_sequence %s,
                    waypoint_kind %s,
                    PRIMARY KEY (world_id, node_id)
                );
                """,
        table("rail_nodes"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.intType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.stringType(),
        dialect.stringType());
  }

  private String railEdges(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s NOT NULL,
                    node_a %s NOT NULL,
                    node_b %s NOT NULL,
                    length_blocks %s NOT NULL,
                    base_speed_limit %s NOT NULL,
                    bidirectional %s NOT NULL,
                    footprint_json %s NOT NULL DEFAULT '',
                    PRIMARY KEY (world_id, node_a, node_b)
                );
                """,
        table("rail_edges"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.intType(),
        dialect.doubleType(),
        dialect.intType(),
        dialect.stringType());
  }

  private String railEdgeOverrides(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s NOT NULL,
                    node_a %s NOT NULL,
                    node_b %s NOT NULL,
                    speed_limit_bps %s,
                    temp_speed_limit_bps %s,
                    temp_speed_limit_until %s,
                    blocked_manual %s NOT NULL,
                    blocked_until %s,
                    updated_at %s NOT NULL,
                    PRIMARY KEY (world_id, node_a, node_b)
                );
                """,
        table("rail_edge_overrides"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.stringType(),
        dialect.doubleType(),
        dialect.doubleType(),
        dialect.timestampType(),
        dialect.intType(),
        dialect.timestampType(),
        dialect.timestampType());
  }

  private String railInterlockingSnapshots(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s PRIMARY KEY,
                    snapshot_json %s NOT NULL
                );
                """,
        table("rail_interlocking_snapshots"), dialect.uuidType(), dialect.textType());
  }

  private String railComponentCautions(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s NOT NULL,
                    component_key %s NOT NULL,
                    caution_speed_bps %s NOT NULL,
                    updated_at %s NOT NULL,
                    PRIMARY KEY (world_id, component_key)
                );
                """,
        table("rail_component_cautions"),
        dialect.uuidType(),
        dialect.stringType(),
        dialect.doubleType(),
        dialect.timestampType());
  }

  private String railGraphSnapshots(SqlDialect dialect) {
    return formatDdl(
        """
                CREATE TABLE IF NOT EXISTS %s (
                    world_id %s PRIMARY KEY,
                    built_at %s NOT NULL,
                    node_count %s NOT NULL,
                    edge_count %s NOT NULL,
                    node_signature %s NOT NULL
                );
                """,
        table("rail_graph_snapshots"),
        dialect.uuidType(),
        dialect.timestampType(),
        dialect.intType(),
        dialect.intType(),
        dialect.stringType());
  }

  private String formatDdl(String template, Object... args) {
    return template.replace("\n", "%n").formatted(args);
  }

  private String uniqueIndex(String name, String table, String columns) {
    return indexInternal(name, table, columns, true);
  }

  private String index(String name, String table, String columns) {
    return indexInternal(name, table, columns, false);
  }

  private String indexInternal(String name, String table, String columns, boolean unique) {
    Objects.requireNonNull(name, "name");
    String qualifier = unique ? "UNIQUE " : "";
    return "CREATE "
        + qualifier
        + "INDEX IF NOT EXISTS "
        + indexName(name)
        + " ON "
        + table(table)
        + " ("
        + columns
        + ");";
  }

  private String table(String raw) {
    return tablePrefix + raw;
  }

  private String indexName(String raw) {
    return tablePrefix + raw;
  }
}
