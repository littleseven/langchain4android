/**
 * 本地测试脚本
 *
 * 启动本地 Web Server 测试分层防御逻辑
 *
 * 运行方式：
 *   export DEEPSEEK_API_KEY="your-key"
 *   export CLOUDFLARE_AIG_TOKEN="your-token"
 *   node test.js
 */

const http = require('http');

const TEST_PORT = 19000;
const TEST_HOST = '127.0.0.1';

/**
 * 发送 HTTP 请求到本地 Server
 */
function sendRequest(path, method, headers, body) {
    return new Promise(function(resolve, reject) {
        const postData = body ? JSON.stringify(body) : '';
        const options = {
            hostname: TEST_HOST,
            port: TEST_PORT,
            path: path,
            method: method,
            headers: Object.assign({
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }, headers || {})
        };

        const req = http.request(options, function(res) {
            let data = '';
            res.on('data', function(chunk) { data += chunk; });
            res.on('end', function() {
                resolve({
                    statusCode: res.statusCode,
                    headers: res.headers,
                    body: data
                });
            });
        });

        req.on('error', reject);
        if (postData) req.write(postData);
        req.end();
    });
}

// 测试 1：正常请求
async function testNormalRequest() {
    console.log('\n========== Test 1: Normal Request ==========');
    const body = {
        messages: [
            { role: 'system', content: 'You are a helpful assistant.' },
            { role: 'user', content: 'Hello, how are you?' }
        ],
        temperature: 0.7,
        max_tokens: 1024
    };
    const result = await sendRequest(
        '/chat/completions',
        'POST',
        { 'x-app-token': 'com-picme' },
        body
    );
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body.substring(0, 500) +
                (result.body.length > 500 ? '...' : ''));
}

// 测试 2：超大 max_tokens（触发大小限制）
async function testOversizedRequest() {
    console.log('\n========== Test 2: Oversized max_tokens ==========');
    const body = {
        messages: [{ role: 'user', content: 'Hi' }],
        max_tokens: 99999
    };
    const result = await sendRequest(
        '/chat/completions',
        'POST',
        { 'x-app-token': 'com-picme' },
        body
    );
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body);
}

// 测试 3：GET 请求（方法不允许）
async function testMethodNotAllowed() {
    console.log('\n========== Test 3: GET Request (Method Not Allowed) ==========');
    const result = await sendRequest(
        '/chat/completions',
        'GET',
        { 'x-app-token': 'com-picme' },
        null
    );
    console.log('Status:', result.statusCode);
    console.log('Body:', result.body);
}

// 顺序执行测试
async function runTests() {
    console.log('=== Web Function Local Test (Layered Defense) ===');
    console.log('Server: http://' + TEST_HOST + ':' + TEST_PORT);

    try {
        await testNormalRequest();
    } catch (e) {
        console.error('Test 1 failed:', e.message);
    }

    try {
        await testOversizedRequest();
    } catch (e) {
        console.error('Test 2 failed:', e.message);
    }

    try {
        await testMethodNotAllowed();
    } catch (e) {
        console.error('Test 3 failed:', e.message);
    }

    console.log('\n=== All Tests Completed ===');
}

// 启动测试
process.env.NODE_ENV = 'test';
process.env.PORT = TEST_PORT;

const { server, handleRequest } = require('./index');

// 手动启动测试服务器
server.listen(TEST_PORT, TEST_HOST, function() {
    console.log('[Test] Server listening on http://' + TEST_HOST + ':' + TEST_PORT);

    runTests().then(function() {
        console.log('\nShutting down test server...');
        server.close();
        process.exit(0);
    }).catch(function(err) {
        console.error('Test error:', err);
        server.close();
        process.exit(1);
    });
});
