# 公司管理命令/操作草案

本表列出计划实现的 `/fta` 子命令，方便对应 DAO 层的读写需求。所有命令默认通过 CloudSimpleHandler 注册，同时在 `FtaRootCommand` 下暴露 Tab 补全。`<code>` 参数均指人类可读 ID，若用户输入 UUID 则同样可解析。

## 约定
- `role` 列指调用者需要具备的最小权限（插件权限节点或成员角色）。`ADMIN` 指拥有 `fetarute.admin` 的全局管理员。
- `DAO` 列标记将调用的仓库接口，便于梳理需要实现的存储操作。
- 多数命令在执行前需通过 `PlayerIdentityService` 确认/创建玩家身份。

## Company 级命令
| 命令 | 说明 | 所需角色/权限 | DAO 交互 |
| --- | --- | --- | --- |
| `/fta company create <code> <name> [secondaryName]` | 创建公司，调用者自动成为 OWNER，余额为 0 | 玩家自身 + `fetarute.company.create` | `PlayerIdentityRepository`, `CompanyRepository`, `CompanyMemberRepository` |
| `/fta company list [--owner <identity>]` | 列出玩家拥有/加入的公司 | PLAYER | `CompanyRepository`, `CompanyMemberRepository` |
| `/fta company info <company>` | 查看公司详情、成员、运营商统计 | 成员或 ADMIN | 所有仓库只读 |
| `/fta company rename <company> <name> [secondaryName]` | 更新显示名 | OWNER/MANAGER | `CompanyRepository.save` |
| `/fta company transfer <company> <player>` | 变更 OWNER，自动调整成员角色 | OWNER 或 ADMIN | `CompanyRepository.save`, `CompanyMemberRepository` |
| `/fta company delete <company>` | 解散公司（软删除 status=DELETED） | OWNER + 确认 | `CompanyRepository.save`, 可能触发级联 |
| `/fta company balance deposit/withdraw <company> <amount>` | 财务操作，预留给 Vault 集成 | FINANCE/ADMIN | 未来 `CompanyAccountsRepo` |
| `/fta company admin list` | 查看所有公司（含 DELETED） | ADMIN (`fetarute.admin`) | `CompanyRepository.listAll` |
| `/fta company admin restore <company>` | 把 status=DELETED 的公司恢复为 ACTIVE | ADMIN | `CompanyRepository.save` |
| `/fta company admin purge <company>` | 永久删除公司及所有子实体（危险操作） | ADMIN + 二次确认 | `CompanyRepository.delete` |
| `/fta company admin takeover <company>` | 将自己设为临时 OWNER（用于无人托管的公司） | ADMIN | `CompanyRepository.save`, `CompanyMemberRepository.save` |

## Company 成员管理
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta company member invite <company> <player> [role...]` | 发送成员邀请（需对方确认），默认 STAFF | OWNER/MANAGER | `CompanyMemberInviteRepository.save` |
| `/fta company member accept <company>` | 接受邀请并加入公司 | PLAYER | `CompanyMemberInviteRepository.find/delete`, `CompanyMemberRepository.save` |
| `/fta company member decline <company>` | 拒绝邀请 | PLAYER | `CompanyMemberInviteRepository.find/delete` |
| `/fta company member invites` | 列出待处理邀请 | PLAYER | `CompanyMemberInviteRepository.listInvites` |
| `/fta company member setroles <company> <player> <role...>` | 设置玩家多角色列表 | OWNER/MANAGER | `CompanyMemberRepository.save` |
| `/fta company member remove <company> <player>` | 移除成员 | OWNER/MANAGER 或 SELF | `CompanyMemberRepository.delete` |
| `/fta company member list <company>` | 列出成员与角色 | 成员 | `CompanyMemberRepository.listMembers` |

## Operator 命令
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta operator create <company> <code> <name> [secondary]` | 在公司下创建运营商（如 SURN） | OWNER/MANAGER | `OperatorRepository.save` |
| `/fta operator list <company>` | 列出公司内所有运营商 | 成员 | `OperatorRepository.listByCompany` |
| `/fta operator set <operator> [--name ... --color ... --priority <n>]` | 更新运营商属性 | OWNER/MANAGER | `OperatorRepository.save` |
| `/fta operator delete <operator>` | 删除运营商，级联线路/站点/Route | OWNER/MANAGER + 确认 | `OperatorRepository.delete` |

## Line 命令
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta line create <operator> <code> <name>` | 新建线路（1号线等） | OWNER/MANAGER | `LineRepository.save` |
| `/fta line set <line> [--name --secondary --service-type --color --status --freq <sec>]` | 更新线路属性 | OWNER/MANAGER | `LineRepository.save` |
| `/fta line list <operator>` | 查看运营商下线路 | 成员 | `LineRepository.listByOperator` |
| `/fta line delete <line>` | 删除线路（需确保无 active route） | OWNER/MANAGER | `LineRepository.delete` |

## Station 命令
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta station register <operator> <code> <name>` | 手动注册站点，不自动定位 | DISPATCHER/MANAGER | `StationRepository.save` |
| `/fta station bind <station> --sign` | 读取玩家看向的轨道牌子位置，写入 location/world/graphNodeId | DISPATCHER/MANAGER | `StationRepository.save` |
| `/fta station set <station> [--name --secondary --primary-line --graph-node <id>]` | 更新站点属性 | DISPATCHER/MANAGER | `StationRepository.save` |
| `/fta station list <operator>` | 列出运营商车站 | 成员 | `StationRepository.listByOperator` |

## Route 命令
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta route create <line> <code> <name> --tc <tcRoute>` | 新建 Route 并绑定 TC route ID | DISPATCHER/MANAGER | `RouteRepository.save` |
| `/fta route set <route> [--name --pattern --tc --dist --runtime --spawn --spawn-clear]` | 更新 Route 属性 | DISPATCHER/MANAGER | `RouteRepository.save` |
| `/fta route list <line>` | 列出线路下 Route | 成员 | `RouteRepository.listByLine` |
| `/fta route stop add <route> <sequence> (--station <station> | --waypoint <node>) [--dwell <sec> --pass <type>]` | 插入停靠点 | DISPATCHER/MANAGER | `RouteStopRepository.save` |
| `/fta route stop remove <route> <sequence>` | 移除停靠 | DISPATCHER/MANAGER | `RouteStopRepository.delete` |
| `/fta route stop list <route>` | 查看停靠表 | 成员 | `RouteStopRepository.listByRoute` |

## Depot 命令
| 命令 | 说明 | 角色 | DAO |
| --- | --- | --- | --- |
| `/fta depot spawn <company> <operator> <line> <route> "<nodeId>" [--pattern "<pattern>"]` | 手动触发车库生成列车（pattern 优先级：flag &gt; route metadata &gt; 牌子第 4 行） | OWNER/MANAGER + `fetarute.depot.spawn` | `RouteRepository`, `RouteStopRepository`, `StationRepository` |

## Player Identity / 管理命令
| 命令 | 说明 | 权限 | DAO |
| --- | --- | --- | --- |
| `/fta identity lookup <player>` | 显示玩家 UUID、authType、隶属公司 | ADMIN | `PlayerIdentityRepository`, `CompanyMemberRepository` |
| `/fta identity relink <player> <uuid> <authType>` | 手动修正身份（离线→在线） | ADMIN | `PlayerIdentityRepository.save` |

## API/Hooks
- 提供 `CompanyQueryService` 供 GUI / REST 调用（根据 code/UUID 查询公司/线路/站点/Route）。
- 事件：`CompanyUpdatedEvent`, `OperatorUpdatedEvent` 等，命令执行成功后在 Bukkit 主线程广播，便于信息屏监听。
- 未来需暴露 `/fta export company <code>` 之类的命令，将结构序列化为 JSON 供外部 API 使用。

> 上述命令为草案，可在实现阶段根据 UI/权限需求再增减。核心是与 DAO 操作一一对应，确保测试时方便 Mock。
