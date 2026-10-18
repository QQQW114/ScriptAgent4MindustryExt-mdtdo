/* ============================================================================
 * MDT DO 服务器门户 - 站点配置（前端唯一需要手改的地方）
 * 说明：
 *  - 本文件为纯静态配置。服务器名 / MOTD / 端口 取自 mdtserver/server.properties；
 *    若后端 /api/status 未来提供 serverName/motd/port 字段，页面会优先使用后端值。
 *  - 端口默认 6567（server.properties port=6567），可在 URL 用 ?port=xxxx 临时覆盖。
 * ==========================================================================*/
(function (global) {
  'use strict';
  global.PORTAL_SITE = {
    serverName: 'MDT DO',
    subtitle: 'Mindustry 开源游戏服务器',
    serverPort: 6567,
    maxPlayersFallback: 16,
    // 取自 mdtserver/server.properties 的 motd（Mindustry 颜色标记会由前端安全渲染）
    motd: '[gold]欢迎来到 MDT DO![]\n[blue]游戏内输入 /post 或 /wiki 以查看最新消息[]\n双击自己或其他玩家，快速打开信息面板！',
    // 每日一言：本地语料 1484 条（游戏/文学/诗词/哲学/影视/动画，来源 hitokoto 开源句子库）
    // rotateSeconds=自动轮换秒数；remoteEnabled=true 时若网络可用会偶尔从一言 API 取新的（失败自动降级本地）
    quote: {
      rotateSeconds: 45,
      remoteEnabled: true
    },
    // 显示相关（第二期）
    display: {
      // 玩家名允许显示的**最大字符数**（按 Mindustry 颜色标记原文计）。
      // 上游事件流 liveEventFeed.kts 的 maxPlayerChars 已上调为 256（最低强制 128）；
      // 前端放宽到 2 倍 = 512，只在这个上限以上才做兜底截断，且始终在 title/弹窗提供完整原名。
      playerNameMaxChars: 512,
      // 疑似被上游截断时（名字末尾出现残缺的 [xxx 颜色标记），是否自动收尾修复
      repairTruncatedMarkup: true,
      // 点击玩家名是否弹出完整名字详情
      playerNameClickDetail: true
    },
    // 服务器日志（管理后台；第二期）
    logs: {
      previewLines: 200,
      // 真实模式下下载方式：
      //  - true  = 直链下载（/api/logs/download?name=...&token=...，不占页面内存；需后端支持 token 查询鉴权）
      //  - false = 鉴权 fetch → Blob（兼容旧后端，但大文件会占用浏览器内存，仅建议小文件）
      directDownloadWithToken: true,
      // 兼容模式（fetch→Blob）允许的最大文件大小；超过则仍走直链并提示
      blobDownloadMaxBytes: 8 * 1024 * 1024
    },
    // 历史消息浏览（第二期）
    history: {
      pageSize: 100,
      maxKeywordChars: 100
    },
    links: {
      upload: 'upload.html',
      admin: 'admin.html',
      home: 'index.html'
    },
    // 前端上传限制（与后端 web-config.json 默认值保持一致）
    upload: {
      maxFileBytes: 10 * 1024 * 1024,
      allowedDpExtensions: ['.zip', '.json', '.hjson', '.json5'],
      allowedMapExtensions: ['.msav'],
      queueLimit: 10,
      titleMaxChars: 200,
      authorMaxChars: 100,
      descMaxChars: 4000
    }
  };
})(window);
