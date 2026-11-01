# 阿里云邮件推送 DirectMail 发信封装（RPC 签名 v1.0，HMAC-SHA1）
# 仅被 server.ps1 dot-source 使用；不要把真实 AccessKey 写进本文件。

function ConvertTo-Rfc3986([string]$Value) {
    $encoded = [System.Uri]::EscapeDataString($Value)
    return $encoded.Replace('+', '%20').Replace('*', '%2A').Replace('%7E', '~')
}

function Get-MaskedEmail([string]$Email) {
    if ([string]::IsNullOrWhiteSpace($Email)) { return '(empty)' }
    $at = $Email.IndexOf('@')
    if ($at -le 0) { return '***' }
    $name = $Email.Substring(0, $at)
    $domain = $Email.Substring($at)
    if ($name.Length -le 2) { return ('**' + $domain) }
    return ($name.Substring(0, 2) + '***' + $domain)
}

function Invoke-DirectMailSend {
    param(
        [Parameter(Mandatory = $true)]$AliyunConfig,
        [Parameter(Mandatory = $true)][string]$ToAddress,
        [Parameter(Mandatory = $true)][string]$Subject,
        [Parameter(Mandatory = $true)][string]$TextBody
    )

    $endpoint = if ($AliyunConfig.endpoint) { [string]$AliyunConfig.endpoint } else { 'https://dm.aliyuncs.com/' }
    $params = [ordered]@{
        AccessKeyId      = [string]$AliyunConfig.accessKeyId
        Action           = 'SingleSendMail'
        AccountName      = [string]$AliyunConfig.senderAddress
        AddressType      = '1'
        ReplyToAddress   = 'false'
        ToAddress        = $ToAddress
        Subject          = $Subject
        TextBody         = $TextBody
        Format           = 'JSON'
        Version          = '2015-11-23'
        SignatureMethod  = 'HMAC-SHA1'
        SignatureVersion = '1.0'
        SignatureNonce   = [guid]::NewGuid().ToString()
        Timestamp        = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
    if ($AliyunConfig.fromAlias) { $params['FromAlias'] = [string]$AliyunConfig.fromAlias }

    $keys = @($params.Keys | Sort-Object)
    $canonical = (($keys | ForEach-Object { (ConvertTo-Rfc3986 $_) + '=' + (ConvertTo-Rfc3986 ([string]$params[$_])) }) -join '&')
    $stringToSign = 'GET&' + (ConvertTo-Rfc3986 '/') + '&' + (ConvertTo-Rfc3986 $canonical)

    $keyBytes = [Text.Encoding]::UTF8.GetBytes(([string]$AliyunConfig.accessKeySecret) + '&')
    $hmac = New-Object System.Security.Cryptography.HMACSHA1(, $keyBytes)
    try {
        $signature = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($stringToSign)))
    } finally {
        $hmac.Dispose()
    }

    $url = $endpoint.TrimEnd('/') + '/?Signature=' + (ConvertTo-Rfc3986 $signature) + '&' + $canonical

    try {
        $response = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 20 -UseBasicParsing
        return [pscustomobject]@{
            Ok      = $true
            Status  = [int]$response.StatusCode
            Raw     = [string]$response.Content
        }
    } catch {
        $status = 0
        $body = ''
        $exception = $_.Exception
        try {
            if ($exception.Response) { $status = [int]$exception.Response.StatusCode }
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                $body = [string]$_.ErrorDetails.Message
            } elseif ($exception.Response -and $exception.Response.GetResponseStream) {
                $reader = New-Object System.IO.StreamReader($exception.Response.GetResponseStream())
                try { $body = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } elseif ($exception.Response -and $exception.Response.Content) {
                $body = [string]$exception.Response.Content.ReadAsStringAsync().Result
            }
        } catch { }
        if ([string]::IsNullOrWhiteSpace($body)) { $body = [string]$exception.Message }
        return [pscustomobject]@{
            Ok     = $false
            Status = $status
            Raw    = $body
            Error  = $body
        }
    }
}
