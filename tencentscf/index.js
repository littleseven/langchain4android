/**
 * 腾讯云 SCF Web 函数 - DeepSeek AI Gateway 代理（分层防御版）
 *
 * 运行环境：Node.js 18.15（Web 函数类型）
 * 启动方式：scf_bootstrap 启动文件 → 监听 0.0.0.0:9000
 *
 * 安全设计：
 * - Cloudflare AI Gateway 代理：DeepSeek API Key 由 Cloudflare 网关维护
 * - 仅需 CLOUDFLARE_AIG_TOKEN 认证网关访问
 * - 第一层防护（腾讯云）：速率限制、请求大小限制、IP 日志
 * - 第二层防护（Cloudflare）：网关级配额管理、上游密钥保护
 */

const http = require('http');
const https = require('https');
const url = require('url');

// 配置区
const PORT = process.env.PORT || 9000;
const GATEWAY_URL = 'https://gateway.ai.cloudflare.com/v1/a7656feec717409a19fa5217f0f7b2f9/picme/compat/chat/completions';
const MODEL = 'deepseek/deepseek-chat';
const APP_TOKEN = process.env.APP_TOKEN || 'com-picme'; // 客户端鉴权 Token（环境变量可覆盖）

// 速率限制配置
const RATE_LIMIT = {
    windowMs: 60 * 1000, // 1分钟窗口
    maxRequests: 10,      // 单 IP 每分钟最多 10 次
    maxTokens: 4096      // 单次请求最大 token 数
};

// 内存级请求日志：IP -> [timestamp, ...]
// 注意：SCF 无状态，函数实例回收后日志清零。生产环境建议用 Redis。
const requestLog = new Map();

/**
 * 检查速率限制
 * @param {string} ip - 客户端 IP
 * @returns {boolean} - 是否允许请求
 */
function checkRateLimit(ip) {
    const now = Date.now();
    const windowStart = now - RATE_LIMIT.windowMs;

    if (!requestLog.has(ip)) {
        requestLog.set(ip, [now]);
        return true;
    }

    const requests = requestLog.get(ip).filter(function(t) { return t > windowStart; });
    requests.push(now);
    requestLog.set(ip, requests);

    return requests.length <= RATE_LIMIT.maxRequests;
}

/**
 * 清理过期的请求日志（防止内存泄漏）
 */
function cleanExpiredLogs() {
    const now = Date.now();
    const windowStart = now - RATE_LIMIT.windowMs;
    requestLog.forEach(function(timestamps, ip) {
        const valid = timestamps.filter(function(t) { return t > windowStart; });
        if (valid.length === 0) {
            requestLog.delete(ip);
        } else {
            requestLog.set(ip, valid);
        }
    });
}

// 每 5 分钟清理一次过期日志
setInterval(cleanExpiredLogs, 5 * 60 * 1000);

/**
 * 解析请求体
 * @param {http.IncomingMessage} req
 * @returns {Promise<Object>}
 */
function parseBody(req) {
    return new Promise(function(resolve, reject) {
        let body = '';
        req.on('data', function(chunk) { body += chunk; });
        req.on('end', function() {
            try {
                resolve(body ? JSON.parse(body) : {});
            } catch (e) {
                resolve({});
            }
        });
        req.on('error', reject);
    });
}

/**
 * 发送 JSON 响应
 * @param {http.ServerResponse} res
 * @param {number} statusCode
 * @param {Object} data
 */
function sendJson(res, statusCode, data) {
    const json = JSON.stringify(data);
    res.writeHead(statusCode, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(json),
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
    });
    res.end(json);
}

/**
 * 主请求处理器
 */
async function handleRequest(req, res) {
    const clientIp = req.headers['x-forwarded-for'] ||
                     (req.connection && req.connection.remoteAddress) ||
                     'unknown';
    console.log('[Web] Request received, method=' + req.method +
                ', path=' + req.url + ', ip=' + clientIp);

    // 处理 CORS 预检请求
    if (req.method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
        });
        res.end();
        return;
    }

    // 1. 只允许 POST 请求
    if (req.method !== 'POST') {
        sendJson(res, 405, { error: 'Method Not Allowed' });
        return;
    }

    // 2. 第一层防护：速率限制
    if (!checkRateLimit(clientIp)) {
        console.warn('[Web] Rate limit exceeded, ip=' + clientIp);
        sendJson(res, 429, {
            error: 'Rate limit exceeded. Max ' + RATE_LIMIT.maxRequests +
                   ' requests per ' + (RATE_LIMIT.windowMs / 1000) + ' seconds.'
        });
        return;
    }

    // 3. 简单鉴权（生产环境建议开启）
    const authToken = req.headers['x-app-token'];
    if (authToken !== APP_TOKEN) {
        // 如需开启鉴权，取消下面注释
        // sendJson(res, 401, { error: 'Unauthorized' });
        // return;
    }

    // 4. 解析请求体
    const body = await parseBody(req);

    // 5. 请求大小限制（防盗刷大 token 请求）
    if (body.max_tokens && body.max_tokens > RATE_LIMIT.maxTokens) {
        sendJson(res, 400, {
            error: 'max_tokens exceeds limit of ' + RATE_LIMIT.maxTokens
        });
        return;
    }

    // 6. 构建转发请求体
    const payload = {
        model: MODEL,
        messages: body.messages || [],
        temperature: body.temperature || 0.7,
        max_tokens: body.max_tokens || 2048,
        stream: false
    };

    // 7. 从环境变量读取 Cloudflare AIG Token（网关认证）
    const cloudflareAigToken = process.env.CLOUDFLARE_AIG_TOKEN;

    if (!cloudflareAigToken) {
        console.error('[Web] CLOUDFLARE_AIG_TOKEN not configured');
        sendJson(res, 500, {
            error: 'Server configuration error: CLOUDFLARE_AIG_TOKEN missing'
        });
        return;
    }

    try {
        console.log('[Web] Forwarding to AI Gateway, model=' + MODEL +
                    ', ip=' + clientIp);

        // 8. 发送请求到 Cloudflare AI Gateway
        // DeepSeek API Key 由 Cloudflare 网关维护，无需在腾讯云函数中配置
        const requestHeaders = {
            'Content-Type': 'application/json',
            'cf-aig-authorization': 'Bearer ' + cloudflareAigToken
        };

        const response = await new Promise(function(resolve, reject) {
            const parsedUrl = url.parse(GATEWAY_URL);
            const postData = JSON.stringify(payload);

            const options = {
                hostname: parsedUrl.hostname,
                path: parsedUrl.path,
                method: 'POST',
                headers: {
                    'Content-Type': requestHeaders['Content-Type'],
                    'cf-aig-authorization': requestHeaders['cf-aig-authorization'],
                    'Content-Length': Buffer.byteLength(postData)
                }
            };

            const apiReq = https.request(options, function(apiRes) {
                let data = '';
                apiRes.on('data', function(chunk) { data += chunk; });
                apiRes.on('end', function() {
                    resolve({
                        statusCode: apiRes.statusCode,
                        headers: apiRes.headers,
                        body: data
                    });
                });
            });

            apiReq.on('error', function(err) {
                reject(err);
            });

            apiReq.write(postData);
            apiReq.end();
        });

        console.log('[Web] Gateway response status=' + response.statusCode +
                    ', ip=' + clientIp);

        // 9. 返回响应给客户端
        res.writeHead(response.statusCode, {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type,X-App-Token'
        });
        res.end(response.body);

    } catch (error) {
        console.error('[Web] Request failed, ip=' + clientIp +
                      ', error=' + error.message);
        sendJson(res, 500, { error: error.message });
    }
}

// 创建 HTTP Server
const server = http.createServer(function(req, res) {
    handleRequest(req, res).catch(function(err) {
        console.error('[Web] Unhandled error:', err);
        sendJson(res, 500, { error: 'Internal Server Error' });
    });
});

// 监听 0.0.0.0:9000（腾讯云 Web 函数要求）
// 仅在非测试环境自动启动
if (process.env.NODE_ENV !== 'test') {
    server.listen(PORT, '0.0.0.0', function() {
        console.log('[Web] Server listening on http://0.0.0.0:' + PORT);
    });
}

module.exports = { server, handleRequest };
