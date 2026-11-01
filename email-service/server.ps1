# ============================================================================
# MDT 邮箱验证码服务（本地 HTTP API）
# 用途：给服务器插件/协作者提供一个"发 QQ 邮箱验证码"的本地接口。
#   - 只允许 QQ 邮箱（qq.com / foxmail.com，可配置）
#   - 只负责"按调用方给的验证码发信"，不生成/不校验验证码（绑定逻辑由调用方负责）
#   - 阿里云 DirectMail 密钥只保存在本机 config.json，不进入仓库
# 启动：powershell.exe -NoProfile -ExecutionPolicy Bypass -File server.ps1 -Config config.json
# ============================================================================
param(
    [string]$Config = (Join-Path $PSScriptRoot 'config.json')
)

$ErrorActionPreference = 'Stop'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$script:Root = $PSScriptRoot
$script:Version = '1.0.0'

# ---------------------------- 配置 ----------------------------
if (-not (Test-Path -LiteralPath $Config)) {
    throw "找不到配置文件：$Config"
}
$rawConfig = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json

$script:ListenHost = if ($rawConfig.listen -and $rawConfig.listen.host) { [string]$rawConfig.listen.host } else { '127.0.0.1' }
$script:ListenPort = if ($rawConfig.listen -and $rawConfig.listen.port) { [int]$rawConfig.listen.port } else { 40150 }
$script:InternalToken = if ($rawConfig.internalToken) { [string]$rawConfig.internalToken } else { '' }
$script:AllowDomains = @()
if ($rawConfig.allowDomains) { $script:AllowDomains = @($rawConfig.allowDomains | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() }) }
$script:MockSend = [bool]$rawConfig.mockSend

$limits = $rawConfig.limits
$script:CooldownSeconds = if ($limits -and $limits.perEmailCooldownSeconds) { [int]$limits.perEmailCooldownSeconds } else { 60 }
$script:EmailDailyLimit = if ($limits -and $limits.perEmailDailyLimit) { [int]$limits.perEmailDailyLimit } else { 5 }
$script:GlobalDailyLimit = if ($limits -and $limits.globalDailyLimit) { [int]$limits.globalDailyLimit } else { 150 }

$aliyun = $rawConfig.aliyun
if (-not $aliyun) { throw '配置缺少 aliyun 段' }
foreach ($key in @('accessKeyId', 'accessKeySecret', 'senderAddress')) {
    if (-not $aliyun.$key) { throw "配置 aliyun.$key 不能为空" }
}
if (-not $script:MockSend -and ([string]$aliyun.accessKeyId).Contains('请填写')) { throw '请先在 config.json 填写阿里云 AccessKey/发信地址，或设置 mockSend=true 做联调' }

$script:LogDir = if ($rawConfig.logDir) { [string]$rawConfig.logDir } else { Join-Path $script:Root 'logs' }
if (-not [IO.Path]::IsPathRooted($script:LogDir)) { $script:LogDir = Join-Path $script:Root $script:LogDir }
New-Item -ItemType Directory -Force -Path $script:LogDir | Out-Null
$script:LogFile = Join-Path $script:LogDir ('email-service-' + (Get-Date -Format 'yyyyMMdd') + '.log')
$script:LogEncoding = New-Object System.Text.UTF8Encoding($false)

. (Join-Path $script:Root 'Send-Email.ps1')

# ---------------------------- 日志 ----------------------------
function Write-SvcLog {
    param([string]$Level, [string]$Message)
    $line = '[{0}] [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    try { [IO.File]::AppendAllText($script:LogFile, $line + "`r`n", $script:LogEncoding) } catch { }
    Write-Host $line
}

# ---------------------------- 限流状态 ----------------------------
$script:EmailState = @{}
$script:GlobalDay = (Get-Date -Format 'yyyyMMdd')
$script:GlobalSent = 0

function Get-EmailState([string]$Email) {
    if (-not $script:EmailState.ContainsKey($Email)) {
        $script:EmailState[$Email] = @{ Day = ''; DailySent = 0; LastSentAt = $null }
    }
    return $script:EmailState[$Email]
}

function Test-SendAllowed {
    param([string]$Email)
    $now = Get-Date
    $today = Get-Date -Format 'yyyyMMdd'
    $state = Get-EmailState $Email

    if ($state.Day -ne $today) {
        $state.Day = $today
        $state.DailySent = 0
    }
    if ($script:GlobalDay -ne $today) {
        $script:GlobalDay = $today
        $script:GlobalSent = 0
    }

    if ($state.LastSentAt) {
        $elapsed = ($now - [datetime]$state.LastSentAt).TotalSeconds
        if ($elapsed -lt $script:CooldownSeconds) {
            return @{ Ok = $false; Code = 'EMAIL_COOLDOWN'; RetryAfterSeconds = [int][math]::Ceiling($script:CooldownSeconds - $elapsed) }
        }
    }
    if ($state.DailySent -ge $script:EmailDailyLimit) {
        return @{ Ok = $false; Code = 'EMAIL_DAILY_LIMIT'; RetryAfterSeconds = 3600 }
    }
    if ($script:GlobalSent -ge $script:GlobalDailyLimit) {
        return @{ Ok = $false; Code = 'SERVICE_DAILY_LIMIT'; RetryAfterSeconds = 3600 }
    }
    return @{ Ok = $true }
}

function Register-SendAttempt {
    param([string]$Email, [bool]$Success)
    $state = Get-EmailState $Email
    $state.LastSentAt = Get-Date
    if ($Success) {
        $state.DailySent = [int]$state.DailySent + 1
        $script:GlobalSent = [int]$script:GlobalSent + 1
    }
}

# ---------------------------- HTTP 工具 ----------------------------
function Send-JsonResponse {
    param($Context, [int]$StatusCode, $Body)
    $json = ($Body | ConvertTo-Json -Depth 6 -Compress)
    if ([string]::IsNullOrEmpty($json)) { $json = '{}' }
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $Context.Response.StatusCode = $StatusCode
    $Context.Response.ContentType = 'application/json; charset=utf-8'
    $Context.Response.ContentLength64 = $bytes.Length
    $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Context.Response.OutputStream.Flush()
}

function Read-JsonBody {
    param($Context)
    $contentLength = $Context.Request.ContentLength64
    if ($contentLength -gt 16384) { return $null }
    $reader = New-Object System.IO.StreamReader($Context.Request.InputStream, [Text.Encoding]::UTF8)
    try {
        $text = $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

function Test-Token {
    param($Context)
    if ([string]::IsNullOrEmpty($script:InternalToken)) { return $true } # 未配置 token 时仅本机监听仍有风险，启动时已警告
    $token = $Context.Request.Headers['X-Internal-Token']
    return ($token -ceq $script:InternalToken)
}

# ---------------------------- 业务 ----------------------------
function Get-FailureMessage([string]$Code) {
    switch ($Code) {
        'INVALID_EMAIL' { return '邮箱格式不正确' }
        'DOMAIN_NOT_ALLOWED' { return '只支持 QQ 邮箱（@qq.com 或 @foxmail.com）' }
        'INVALID_CODE' { return '验证码必须是 4-8 位数字' }
        'EMAIL_COOLDOWN' { return '发送太频繁，请稍后再试' }
        'EMAIL_DAILY_LIMIT' { return '该邮箱今日发送次数已达上限' }
        'SERVICE_DAILY_LIMIT' { return '今日验证码发送额度已用完，请明天再试' }
        'QUOTA_EXCEEDED' { return '邮件服务额度不足，请联系管理员' }
        'EMAIL_SERVICE_CONFIG' { return '邮件服务配置异常，请联系管理员' }
        'UNAUTHORIZED' { return '内部鉴权失败' }
        default { return '邮件发送失败，请稍后再试' }
    }
}

function Invoke-SendRequest {
    param($Context)
    if (-not (Test-Token $Context)) {
        Send-JsonResponse $Context 401 @{ ok = $false; error = 'UNAUTHORIZED'; message = (Get-FailureMessage 'UNAUTHORIZED') }
        return
    }
    $body = Read-JsonBody $Context
    if (-not $body) {
        Send-JsonResponse $Context 400 @{ ok = $false; error = 'INVALID_REQUEST'; message = '请求体必须是合法 JSON' }
        return
    }

    $email = ([string]$body.email).Trim().ToLowerInvariant()
    $code = ([string]$body.code).Trim()
    $expireMinutes = if ($body.expireMinutes) { [int]$body.expireMinutes } else { 10 }
    if ($expireMinutes -lt 1 -or $expireMinutes -gt 60) { $expireMinutes = 10 }
    $purpose = if ($body.purpose) { ([string]$body.purpose).Trim() } else { '' }

    if ([string]::IsNullOrWhiteSpace($email) -or $email -notmatch '^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}$') {
        Send-JsonResponse $Context 400 @{ ok = $false; error = 'INVALID_EMAIL'; message = (Get-FailureMessage 'INVALID_EMAIL') }
        return
    }
    if ($script:AllowDomains.Count -gt 0) {
        $domain = $email.Substring($email.LastIndexOf('@') + 1)
        if ($script:AllowDomains -notcontains $domain) {
            Send-JsonResponse $Context 400 @{ ok = $false; error = 'DOMAIN_NOT_ALLOWED'; message = (Get-FailureMessage 'DOMAIN_NOT_ALLOWED') }
            return
        }
    }
    if ($code -notmatch '^\d{4,8}$') {
        Send-JsonResponse $Context 400 @{ ok = $false; error = 'INVALID_CODE'; message = (Get-FailureMessage 'INVALID_CODE') }
        return
    }

    $allowed = Test-SendAllowed $email
    if (-not $allowed.Ok) {
        $status = 429
        $message = Get-FailureMessage $allowed.Code
        Send-JsonResponse $Context $status @{ ok = $false; error = $allowed.Code; message = $message; retryAfterSeconds = $allowed.RetryAfterSeconds }
        Write-SvcLog 'WARN' ("拒绝发送（限流） to={0} code={1}" -f (Get-MaskedEmail $email), $allowed.Code)
        return
    }

    $subject = '【MDT】邮箱绑定验证码'
    $masked = Get-MaskedEmail $email
    $textBody = @(
        "你的邮箱绑定验证码是：$code",
        "",
        "$expireMinutes 分钟内有效。请勿把验证码告诉他人。",
        "如果这不是你本人的操作，请忽略本邮件。",
        "",
        "—— MDT 服务器"
    ) -join "`r`n"

    if ($script:MockSend) {
        Register-SendAttempt $email $true
        $mockId = 'mock-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
        Write-SvcLog 'INFO' ("[mock] 已模拟发送 to={0} code=****** requestId={1}" -f $masked, $mockId)
        Send-JsonResponse $Context 200 @{ ok = $true; mock = $true; requestId = $mockId; envId = $null; message = '模拟发送成功（mockSend=true）' }
        return
    }

    $result = Invoke-DirectMailSend -AliyunConfig $aliyun -ToAddress $email -Subject $subject -TextBody $textBody
    Register-SendAttempt $email $false

    if ($result.Ok) {
        Register-SendAttempt $email $true
        $requestId = ''
        $envId = ''
        try {
            $parsed = $result.Raw | ConvertFrom-Json
            $requestId = [string]$parsed.RequestId
            $envId = [string]$parsed.EnvId
        } catch { }
        Write-SvcLog 'INFO' ("发送成功 to={0} requestId={1} envId={2}" -f $masked, $requestId, $envId)
        Send-JsonResponse $Context 200 @{ ok = $true; requestId = $requestId; envId = $envId; message = '验证码邮件已发送' }
        return
    }

    # 发送失败：解析阿里云错误码并映射
    $aliyunCode = ''
    $aliyunMessage = ''
    try {
        $parsed = $result.Raw | ConvertFrom-Json
        $aliyunCode = [string]$parsed.Code
        $aliyunMessage = [string]$parsed.Message
    } catch { }

    $mapped = 'SEND_FAILED'
    $httpStatus = 502
    switch ($aliyunCode) {
        'InvalidAccessKeyId' { $mapped = 'EMAIL_SERVICE_CONFIG' }
        'SignatureDoesNotMatch' { $mapped = 'EMAIL_SERVICE_CONFIG' }
        'Forbidden' { $mapped = 'EMAIL_SERVICE_CONFIG' }
        'DomainNotVerified' { $mapped = 'EMAIL_SERVICE_CONFIG' }
        'InvalidSender' { $mapped = 'EMAIL_SERVICE_CONFIG' }
        'QuotaExceeded' { $mapped = 'QUOTA_EXCEEDED'; $httpStatus = 429 }
        'DailyLimitExceeded' { $mapped = 'SERVICE_DAILY_LIMIT'; $httpStatus = 429 }
        'InvalidToAddress' { $mapped = 'INVALID_EMAIL'; $httpStatus = 400 }
    }
    $level = if ($mapped -eq 'EMAIL_SERVICE_CONFIG') { 'ERROR' } else { 'WARN' }
    Write-SvcLog $level ("发送失败 to={0} aliyunCode={1} aliyunMessage={2} body={3}" -f $masked, $aliyunCode, $aliyunMessage, $result.Raw)
    Send-JsonResponse $Context $httpStatus @{ ok = $false; error = $mapped; message = (Get-FailureMessage $mapped); aliyunCode = $aliyunCode }
}

# ---------------------------- 启动监听 ----------------------------
$prefix = 'http://{0}:{1}/' -f $script:ListenHost, $script:ListenPort
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add($prefix)
try {
    $listener.Start()
} catch {
    Write-SvcLog 'ERROR' ("监听启动失败 {0}：{1}" -f $prefix, $_.Exception.Message)
    Write-SvcLog 'ERROR' '若提示拒绝访问，请用管理员执行：netsh http add urlacl url=http://127.0.0.1:40150/ user=Everyone'
    throw
}

Write-SvcLog 'INFO' ("MDT 邮箱服务已启动：{0} 版本={1} mockSend={2} allowDomains={3} 日志={4}" -f $prefix, $script:Version, $script:MockSend, ($script:AllowDomains -join ','), $script:LogFile)
if ([string]::IsNullOrEmpty($script:InternalToken)) {
    Write-SvcLog 'WARN' '未配置 internalToken：任何本机进程都能调用发信接口，建议在 config.json 设置一个随机 token'
}

try {
    while ($listener.IsListening) {
        $context = $null
        try {
            $context = $listener.GetContext()
            $request = $context.Request
            $path = $request.Url.AbsolutePath.TrimEnd('/')
            if ($path -eq '') { $path = '/' }
            Write-SvcLog 'DEBUG' ("{0} {1} from {2}" -f $request.HttpMethod, $path, $request.RemoteEndPoint)

            if ($path -eq '/health' -and $request.HttpMethod -eq 'GET') {
                Send-JsonResponse $context 200 @{ ok = $true; service = 'mdt-email-service'; version = $script:Version; time = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') }
            } elseif ($path -eq '/send' -and $request.HttpMethod -eq 'POST') {
                Invoke-SendRequest $context
            } elseif ($path -eq '/send') {
                Send-JsonResponse $context 405 @{ ok = $false; error = 'METHOD_NOT_ALLOWED'; message = '请使用 POST' }
            } else {
                Send-JsonResponse $context 404 @{ ok = $false; error = 'NOT_FOUND'; message = '接口不存在（GET /health；POST /send）' }
            }
        } catch {
            Write-SvcLog 'ERROR' ("请求处理异常：{0}" -f $_.Exception.Message)
            try { Send-JsonResponse $context 500 @{ ok = $false; error = 'INTERNAL_ERROR'; message = '内部错误' } } catch { }
        } finally {
            try { if ($context) { $context.Response.Close() } } catch { }
        }
    }
} finally {
    try { $listener.Stop(); $listener.Close() } catch { }
    Write-SvcLog 'INFO' 'MDT 邮箱服务已停止'
}
