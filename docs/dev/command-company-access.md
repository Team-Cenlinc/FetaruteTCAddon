# 命令层：存储就绪与公司权限判定

本项目的命令实现常见两类样板代码：

- StorageProvider 是否 ready（存储未就绪时要给用户统一报错/或静默返回）
- 公司可见性/管理权限（管理员/成员/Owner/Manager），以及 Tab 补全阶段的“禁止创建身份”

为避免每个 Command 文件里复制一套逻辑，命令层统一使用以下工具类：

## StorageProvider

- `CommandStorageProviders.providerIfReady(plugin)`
  - 仅做 ready 判定；未就绪返回 `Optional.empty()`；不输出消息
  - 适合 Tab 补全或“可选依赖存储”的场景
- `CommandStorageProviders.readyProvider(sender, plugin)`
  - 在未 ready 时向用户输出 `error.storage-unavailable`
  - 适合命令执行入口（handler）

## 公司权限（CompanyAccessChecker）

统一规则：

- `fetarute.admin`：可见/可管所有公司
- 普通玩家：必须是公司成员才可读取；具备 `OWNER/MANAGER` 才可管理

注意 Tab 补全阶段不得触发写入（尤其是自动创建 `PlayerIdentity`），因此分成两类 API：

- 执行阶段（允许创建身份）
  - `CompanyAccessChecker.canReadCompany(sender, provider, companyId)`
  - `CompanyAccessChecker.canManageCompany(sender, provider, companyId)`
- 补全/只读阶段（不创建身份）
  - `CompanyAccessChecker.canReadCompanyNoCreateIdentity(sender, provider, companyId)`
  - `CompanyAccessChecker.canManageCompanyNoCreateIdentity(sender, provider, companyId)`

## 约定

- 所有可缺失值使用 `Optional` 表达，禁止返回 `null` 或“模糊默认值”
- Command 中允许保留少量私有包装方法（例如 `readyProvider(sender)`），但实现必须委托到上述工具类，避免逻辑分叉
