# ============================================================
# MDT DO 服务器门户 - 旧版配置（仅用于旧部署自动迁移）
#
# 新版门户主配置是 web-config.json。
# 当 web-config.json 不存在、而本文件存在时，start-web.ps1 启动会：
#   1) 读取下面的 $WebBind / $WebPort / $WebStatsJson；
#   2) 自动生成 web-config.json（bind/port/statsJson 迁移过来，其余项用默认值）；
#   3) 把本文件改名为 web-config.ps1.bak-<yyyyMMdd-HHmmss> 备份（幂等，不会重复迁移）；
#   4) 打印迁移日志，并提示把默认管理员密码改掉。
# 迁移失败时会回退用本文件的旧配置继续启动，不影响服务可用性。
# 迁移完成后请改用 web-config.json（含管理员账号密码、上传限制、目录等完整配置）。
# ============================================================

# 监听地址：
#   127.0.0.1  = 仅本机访问（默认，最安全）
#   localhost  = 仅本机访问（同上）
#   0.0.0.0    = 对所有网卡开放（公网/局域网访问）
# 注意：0.0.0.0 需要先以【管理员】运行一次：.\start-web.ps1 -InstallAcl
$WebBind = "127.0.0.1"

# 监听端口（公网访问时请在防火墙放行该端口）
$WebPort = 8090

# 服务器侧统计 JSON 文件路径（可选）：
#   留空 = 使用默认：..\mdtserver\config\stats\server-status.json
#   也可写绝对路径，例如："D:\mdt\mdtserver\config\stats\server-status.json"
$WebStatsJson = ""
