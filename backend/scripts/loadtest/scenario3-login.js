// scenario3-login.js — 登录 BCrypt CPU 瓶颈压测
// 目的：测 BCrypt 验证（~100ms/次）对 Tomcat 线程池的压力
// 这是全栈中唯一的重 CPU 路径（不依赖 Kafka/Redis 的异步链路）
// 运行：k6 run backend/scripts/loadtest/scenario3-login.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, TEST_USERS } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 5 },
        { duration: '1m',  target: 5 },
        { duration: '30s', target: 10 },
        { duration: '1m',  target: 10 },
        { duration: '30s', target: 20 },
        { duration: '1m',  target: 20 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.02'],
    },
};

export default function () {
    const user = TEST_USERS[Math.floor(Math.random() * TEST_USERS.length)];
    const res = http.post(
        `${BASE_URL}/api/auth/login/password`,
        JSON.stringify({ principal: user.username, password: user.password }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    check(res, {
        'login 200': (r) => r.status === 200,
        'has token': (r) => r.json('data.tokenPair.accessToken') != null,
    });
    sleep(Math.random() * 3 + 2); // 2-5s think time
}
