// config.js — k6 压测共享配置（适用于 local profile 全栈运行）
// 所有脚本通过 import { ... } from './config.js' 复用

import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 测试用户（已注册，userId 970-972）
export const TEST_USERS = [
    { username: 'loadtester',  password: 'loadtest123!',  userId: 970 },
    { username: 'loadtester2', password: 'loadtest2123!',  userId: 971 },
    { username: 'loadtester3', password: 'loadtest3123!',  userId: 972 },
];

// 已发布的测试文章 ID（200001-200020，PUBLISHED + PUBLIC）
export const POST_IDS = Array.from({ length: 20 }, (_, i) => 200001 + i);

/**
 * 登录获取 access token。在 setup() 中调用，不能在 init 阶段调用。
 */
export function login(username, password) {
    const res = http.post(
        `${BASE_URL}/api/auth/login/password`,
        JSON.stringify({ principal: username, password }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status !== 200) {
        console.error(`Login failed for ${username}: ${res.status}`);
        return null;
    }
    return res.json('data.tokenPair.accessToken');
}

export function authHeader(token) {
    return { headers: { Authorization: `Bearer ${token}` } };
}

export function randomPostId() {
    return POST_IDS[Math.floor(Math.random() * POST_IDS.length)];
}
