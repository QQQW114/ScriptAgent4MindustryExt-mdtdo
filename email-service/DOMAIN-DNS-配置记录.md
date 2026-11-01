# 邮箱发信域名与 DNS 配置记录（dm.mdtdomindustry.cn）

> 本文件记录发信所需的域名与 DNS 信息，不包含任何密钥。迁移 DNS/换服务商时按此重建记录，否则会无法发信。

## 域名规划

- 根域 `mdtdomindustry.cn`：**保留原有阿里企业邮箱配置，不要改**（MX = `mx1/mx2/mx3.qiye.aliyun.com`，`mail` CNAME = `qiye.aliyun.com`）。
- 发信用独立子域 **`dm.mdtdomindustry.cn`**（DirectMail 发信域名），与根域收信互不影响。
- DNS 托管：阿里云云解析（NS 示例 `dns31.hichina.com` / `dns32.hichina.com`）。

## 必需 DNS 记录（记录名相对于 mdtdomindustry.cn）

| 类型 | 主机记录 | 记录值 | 用途 |
|---|---|---|---|
| TXT | `dm` | `v=spf1 include:spf1.dm.aliyun.com -all` | SPF |
| TXT | `aliyun-cn-hangzhou._domainkey.dm` | 以 DirectMail 控制台「发信域名 → 配置」页显示为准（`v=DKIM1; k=rsa; p=...`） | DKIM |
| TXT | `_dmarc.dm` | `v=DMARC1;p=none;rua=mailto:dmarc_report@service.aliyun.com` | DMARC |
| MX | `dm` | `mx01.dm.aliyun.com`（优先级 10） | 阿里云要求保留，否则无法发信 |

## 发信身份

- 发信地址：`noreply@dm.mdtdomindustry.cn`（DirectMail 控制台类型：**触发类邮件**；状态正常）
- 发件人显示名：`MDT 服务器`
- 服务商：阿里云邮件推送 DirectMail（API `SingleSendMail`，HTTPS `https://dm.aliyuncs.com/`）
- 免费额度：共 2000 封；每天 200 封；服务端默认全局限 150 封/天。

## 变更注意

- 修改 DNS 后等待生效（TTL 600 秒）再用 DirectMail「验证」；四条记录缺一不可；
- 不要为该子域配置其他 MX/SPF 覆盖；根域 SPF 不用于本服务；
- 若换发信服务商，需要重新配置并重新验证域名。