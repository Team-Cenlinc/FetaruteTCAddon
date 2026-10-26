# 只读 HTTP 接口（web-api）

把公开 API 的线路、车站、站牌与在线列车以 JSON 提供给外部的缓存中转（例如 Cloudflare Worker）定时拉取，App 再从中转那里取；同一套服务还发布 GTFS 静态包与 GTFS-Realtime。只读、不含玩家名等个人信息。代码在 `org.fetarute.fetaruteTCAddon.web`（GTFS 生成在 `web/gtfs` 与 `web/gtfs/realtime`）。

## 线程

- 用 JDK 自带的 `com.sun.net.httpserver.HttpServer`，请求在插件自己的两条后台线程（`FetaruteTCAddon-web-N`，守护线程）上处理，网络收发、序列化、缓存都不在服务器主线程。
- 运营商、线路、车站取自车站目录（`StationDirectory`）的内存快照，不读库：SQLite 只有一条连接，后台线程读库会让主线程等。车站组、停靠线路取自公开 API 的内存快照；在线列车取自运行时列车快照（`TrainSnapshotStore`，并发安全，`listAllActiveTrains(false)` 不算到站预测）；线路运行状况取自站台屏的汇总（`PidsService#lineStatus`，站台屏没启用时不写）。
- 站牌在后台线程直接调 `EtaApi#getBoard`。为此这条链上的几处已改成可在任意线程调用：
  - 读有效节点（`RuntimeDispatchService#resolveEffectiveWaypointsForEvent`、`#hasEffectiveNode`）遇到失效覆盖只当作没有、不删，清理留给主线程的调度调用；
  - 车库牌子（`DepotSpawnPattern#readLoaded`）不在主线程时不读（`SignRead#notOnMainThread`），车型推断按上次的结果或默认车种，不写缓存也不续期；
  - 解编支线交路现拼发车服务时的公司代码先查车站目录，只有主线程才退回查库；
  - 出车层（`StorageSpawnManager`）公开方法在本对象上同步，预测意图（`TimetableSpawnManager`）每次预测并进来而不是整张替换，站台屏与 HTTP 交错预测不互相冲掉；已发布时刻表一变（按各表 ID 与更新时刻）整本作废；
  - 线路运行状况的取消缓存被作废时，正在汇总的那一份不写回；
  - 车次绑定的 tick 内缓存（`TimetableApiImpl`）在锁外算，后台线程算的时候主线程不用等。
- 每个接口的回应短时缓存（`WebResponseCache`）：有效期内同一个键只算一次，同时来的请求等同一次结果，不会一起扑到数据上。站牌先按车站目录认站与线路（不存在 404，不算），缓存键与回显用目录里的写法，时间窗取整到 5 分钟一档，换个写法或编个站码不会绕过缓存。JSON 接口的 ETag 只按内容算（不含顶层的 `generatedAt`，也不含 `/v1/meta` 里的 `gtfs.generatedAt`；静态包版本看 `gtfs.feedVersion`），内容没变时复验回 304；GTFS-RT 的 ETag 还含报文头生成时刻（时间戳本身是表示的一部分），见 [`docs/dev/gtfs.md`](gtfs.md)。`If-None-Match` 按弱比较，认多个与 `*`。
- 插件停用、重载时先停 HTTP（正在算的请求最多等 1 秒），再拆各服务。

## 配置（`config.yml` 的 `web-api:`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启动 |
| `host` | `127.0.0.1` | 监听地址；默认只听本机，推荐配合 Cloudflare Tunnel 等反向代理（只转发完整的请求）。改成 `0.0.0.0` 让别的机器直接来拉时设置 `token`；JDK 自带的 HTTP 服务读请求头不限时，直接暴露在公网时几个慢速连接就能占满处理线程（接口卡住，不影响服务器），启动日志会告警 |
| `port` | `8780` | 端口 |
| `token` | `""` | 共享令牌：非空时请求须带 `Authorization: Bearer <令牌>` 或 `X-Fetarute-Token: <令牌>`，否则回 401。监听非本机地址且没设令牌时启动日志告警 |
| `gtfs.publisher-name` | `Fetarute FTCA Universal` | GTFS 发布方（`feed_info.feed_publisher_name`） |
| `gtfs.publisher-url` | `https://www.fetarute.org` | GTFS 发布方地址（`feed_info.feed_publisher_url`，也用作各运营商的 `agency_url`） |

GTFS 的坐标原点固定（西经 30°、赤道），不设配置；具体口径、ID 规则与实时源见 [`docs/dev/gtfs.md`](gtfs.md)。

`/fta reload` 后按新配置启停或重启；地址、端口、令牌、GTFS 发布方都没变时不动（这几项都是 `WebApiSettings` 的一部分）。`server-id` 改了也不重启：它是 `/v1/meta` 缓存键的一部分，下一次请求就换。端口被占用等启动失败只留告警，不影响插件。

## 接口

全部 `GET`（`HEAD` 同样处理、不回正文），其他方法 405；未知路径 404。JSON 接口的回应是 `application/json; charset=utf-8`，`/v1/gtfs.zip` 是 `application/zip`，GTFS-Realtime 是 `application/x-protobuf`（加 `?format=json` 时为 JSON）。全部带 `Cache-Control: public, max-age=<秒>` 与 `ETag`，认 `If-None-Match`（回 304），允许跨域读取（`Access-Control-Allow-Origin: *`）。出错时回 `{"error":"<原因>"}`、`Cache-Control: no-store`。字段 camelCase，时刻为 ISO-8601（UTC），没有值的字段不输出。

| 路径 | 缓存 | 内容 |
|---|---|---|
| `/v1/meta` | 10 秒 | `apiVersion`、本服的服务器 ID `serverId`（`config.yml` 的 `server-id`，没配时不输出；聚合几台服务器的中转据此核对来源，配错的来源不并进来。改了 `server-id` 后下一次请求即换，缓存键含 server-id）、`generatedAt`、服务器时区 `timezone`（时刻表按它排）、静态包版本 `gtfs.feedVersion` 与 `gtfs.generatedAt`（静态包还没起来时不输出）、`endpoints` |
| `/v1/operators` | 60 秒 | 运营商：`id`、`code`、`name`、`secondaryName`、`color`、`priority` |
| `/v1/lines` | 60 秒 | 线路：`id`、`code`、`operatorId`、`operatorCode`、`name`、`secondaryName`、`serviceType`、`color`、`status`（主数据：规划中/运营/维护）、`liveStatus`（实时运行状况，与线路运行状况屏同一份：`condition` 为 `SUSPENDED`/`PART_SUSPENDED`/`SEVERE_DELAYS`/`MINOR_DELAYS`/`CANCELLATIONS`/`GOOD`/`ENDED`/`NO_TRAINS`，另带 `maxDelayMinutes`、`cancelledTrips`、`closedFrom`/`closedTo`、`nextServiceAt` 之一） |
| `/v1/stations` | 60 秒 | 车站：`id`、`code`、`operatorCode`、`name`、`world`、`position{x,y,z}`、停靠线路 `lines`（经车站组换乘到达的写 `viaStationId`、`transferType`、`walkSeconds`）；车站组 `groups` |
| `/v1/trains` | 5 秒 | 在线列车：`name`、`operatorCode`、`lineCode`、`routeCode`、`consist`、`inService`、`currentNode`、`nextNode`、`speedBlocksPerSecond`、`signal`、`updatedAt` |
| `/v1/stations/{运营商}/{站码}/board?line=&horizon=` | 10 秒 | 站牌 `departures`：`line`、`routeId`、`destination`、`platform`（未定时 `platformPending`、`platformCandidates`）、`status`、`phase`、`eta`、`etaEpochMillis`、`delaySeconds`、`stopSequence`、`passing`、`terminating`、`inService`、`train`、`cars[{seats,occupied}]`。`line` 为线路代码（不带时全部线路），`horizon` 为秒（默认 600，60–7200，向上取整到 5 分钟一档）；代码只认字母、数字与 `-_.`，否则 400；车站或线路不在车站目录里时 404 |
| `/v1/gtfs.zip` | 300 秒 | 静态 GTFS 包（`application/zip`，`Content-Disposition: attachment; filename="gtfs-{serverId}.zip"`）；每 300 秒先比输入快照指纹，没变只续期不重建（最多 30 分钟强制重建一次）；没有旧包且数据没起来时 503 |
| `/v1/gtfs-rt/trip-updates` | 10 秒 | GTFS-Realtime TripUpdates（`application/x-protobuf`）；加 `?format=json` 出调试 JSON，其余格式 400 |
| `/v1/gtfs-rt/vehicle-positions` | 5 秒 | VehiclePositions，格式同上 |
| `/v1/gtfs-rt/alerts` | 30 秒 | ServiceAlerts，格式同上 |

## Cloudflare Worker 拉取示例

源站只回应带令牌的请求，公众请求由 Worker 的缓存接住；Worker 照源站的 `Cache-Control` 缓存，源站每个接口几秒最多被拉一次。

```js
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method !== "GET" || !url.pathname.startsWith("/v1/")) {
      return new Response('{"error":"not-found"}', { status: 404 });
    }
    const cache = caches.default;
    const cached = await cache.match(request);
    if (cached) return cached;
    const origin = await fetch(env.ORIGIN + url.pathname + url.search, {
      headers: { Authorization: "Bearer " + env.TOKEN },
    });
    const response = new Response(origin.body, origin);
    response.headers.set("Access-Control-Allow-Origin", "*");
    if (origin.ok) ctx.waitUntil(cache.put(request, response.clone()));
    return response;
  },
};
```

`env.ORIGIN` 是源站地址（Cloudflare Tunnel 的主机名，或开放的端口），`env.TOKEN` 与 `web-api.token` 相同。

## 之后

GTFS 静态包与 GTFS-Realtime 已经上线，生成口径、ID 规则、坐标、线程与排障见 [`docs/dev/gtfs.md`](gtfs.md)。仍未做（P3）：shapes、frequencies、逐站预测、未发车车次的晚点、multi_carriage_details、分合编、多世界坐标。
