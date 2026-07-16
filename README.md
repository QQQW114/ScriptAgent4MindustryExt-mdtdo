# ScriptAgent4MindustryExt-mdtdo

MDT DO 服务器使用的 ScriptAgent 脚本与维护文档开源仓库。

本仓库基于 [way-zer/ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt) 的脚本体系进行修改和扩展，主要包含：

- `scripts/`：当前服务器使用的 ScriptAgent 脚本；
- `docs/`：功能语义、维护约束、兼容说明和排障记录。

## 使用说明

本仓库只发布脚本与文档，不包含服务器 JAR、数据库、地图、运行日志及生产环境账号数据。使用前需自行安装兼容版本的 Mindustry、MindustryX 与 ScriptAgent，并根据实际环境调整配置和依赖。

当前脚本包含较多 MDT DO 专用功能，并不保证复制后可直接运行。建议先阅读：

- `docs/scripts-maintenance.md`
- `docs/official-v159-compat.md`
- `docs/map-scripts.md`

## 上游与版权

上游项目：<https://github.com/way-zer/ScriptAgent4MindustryExt>
说明：当前适配159+版本非sa3.4，仍是sa3.3.2基础上改进而来

本仓库保留上游脚本中的原作者信息。上游脚本、第三方脚本及本仓库新增脚本分别遵循其文件内声明和 `LICENSE.md` 中的说明。
