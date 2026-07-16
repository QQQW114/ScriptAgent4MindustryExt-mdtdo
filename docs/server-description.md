# 服务器介绍轮播

## 作用

`mdtserver/config/scripts/wayzer/ext/serverDescription.kts` 会定时修改 Mindustry 服务端配置 `Config.desc`，让客户端服务器列表中显示的服务器介绍按多条文案轮播。

Mindustry 源码中服务器列表介绍来自 `mindustry.net.Administration.Config.desc`，`NetworkIO.writeServerData()` 会在每次 ping 响应时读取该值；因此脚本运行期间直接更新 `Config.desc` 即可影响后续服务器列表刷新。

## 默认行为

- 脚本首次启用时会把当前 `server.properties` / `desc` 中的介绍作为基础介绍保存，并导入几条默认 MDT DO 文案。
- 默认每 5 分钟切换一次；该间隔由脚本配置 `rotateInterval` 控制。
- Mindustry 服务器列表介绍最多约 100 字节，脚本会按 UTF-8 安全截断，避免过长。
- 关闭轮播时会恢复基础介绍；如果基础介绍为空，则设置为 `off`。

## 管理指令

权限：`wayzer.admin.serverDescription`，默认授予 `@admin`。

- `/descadmin`：显示第一页介绍列表。
- `/descadmin list [页码]`：分页查看介绍。
- `/descadmin add <内容>`：添加一条介绍。
- `/descadmin set <id> <内容>`：修改介绍。
- `/descadmin remove <id>`：删除介绍。
- `/descadmin enable <id>` / `/descadmin disable <id>`：启用/停用单条介绍。
- `/descadmin apply <id>`：立即应用指定介绍。
- `/descadmin lock <id>`：锁定指定介绍；锁定期间自动轮播会持续应用该介绍。
- `/descadmin unlock`：解除介绍锁定，恢复正常轮播。
- `/descadmin next`：立即切到下一条可用介绍。
- `/descadmin on` / `/descadmin off`：开启/关闭自动轮播。
- `/descadmin status`：查看当前状态、锁定项、轮播间隔、当前介绍和基础介绍。
- `/descadmin seed`：重新导入默认介绍，不会重复添加已有文案。

别名：`/serverdesc`、`/descriptionadmin`、`/介绍管理`。

## 存储

使用 `MdtStorage` 的 `MdtSettings`：

- `serverDesc.items`：介绍列表。
- `serverDesc.enabled`：轮播开关，非 `false` 即启用。
- `serverDesc.seeded.v1`：默认介绍导入标记。
- `serverDesc.base`：关闭轮播时恢复用的基础介绍。
- `serverDesc.currentId`：最近应用的介绍 ID。
- `serverDesc.lockedId`：当前锁定的介绍 ID；为空表示不锁定。
