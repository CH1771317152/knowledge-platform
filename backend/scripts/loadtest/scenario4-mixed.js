// scenario4-mixed.js — 混合读写压测（模拟真实流量分布）
// 目的：全栈运行下，读写互相影响 + 后台调度器（outbox relay / flush / snapshot / reconciliation）的资源竞争
// 运行：k6 run backend/scripts/loadtest/scenario4-mixed.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, authHeader, randomPostId, TEST_USERS, POST_IDS } from './config.js';

export const options = {
    scenarios: {
        feed_read: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 80 },
                { duration: '3m',  target: 80 },
                { duration: '30s', target: 0 },
            ],
            exec: 'feedRead',
        },
        my_feed: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 30 },
                { duration: '3m',  target: 30 },
                { duration: '30s', target: 0 },
            ],
            exec: 'myFeed',
        },
        likes: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '4m',
            preAllocatedVUs: 200,
            exec: 'likeWrite',
        },
        logins: {
            executor: 'constant-vus',
            vus: 3,
            duration: '4m',
            exec: 'loginFlow',
        },
        details: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 20 },
                { duration: '3m',  target: 20 },
                { duration: '30s', target: 0 },
            ],
            exec: 'detailRead',
        },
    },
    thresholds: {
        'http_req_duration{scenario:feed_read}': ['p(95)<100'],
        'http_req_duration{scenario:my_feed}':   ['p(95)<200'],
        'http_req_duration{scenario:likes}':     ['p(95)<200'],
        'http_req_duration{scenario:logins}':     ['p(95)<500'],
        'http_req_duration{scenario:details}':   ['p(95)<200'],
        'http_req_failed': ['rate<0.03'],
    },
};

export function setup() {
    return {
        tokenA: login('loadtester', 'loadtest123!'),
        tokenB: login('loadtester2', 'loadtest2123!'),
    };
}

export function feedRead() {
    const res = http.get(`${BASE_URL}/api/feed/public?size=20`);
    check(res, { '200': (r) => r.status === 200 });
    sleep(Math.random() * 2 + 0.5);
}

export function myFeed(data) {
    const res = http.get(`${BASE_URL}/api/feed/me?size=20`, authHeader(data.tokenA));
    check(res, { '200': (r) => r.status === 200 });
    sleep(Math.random() * 2 + 1);
}

export function likeWrite(data) {
    const method = Math.random() > 0.5 ? 'POST' : 'DELETE';
    const res = http.request(method, `${BASE_URL}/api/posts/${randomPostId()}/likes`, null, authHeader(data.tokenB));
    check(res, { '200': (r) => r.status === 200 });
}

export function loginFlow() {
    const user = TEST_USERS[Math.floor(Math.random() * TEST_USERS.length)];
    const res = http.post(
        `${BASE_URL}/api/auth/login/password`,
        JSON.stringify({ principal: user.username, password: user.password }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    check(res, { '200': (r) => r.status === 200 });
    sleep(10);
}

export function detailRead() {
    const res = http.get(`${BASE_URL}/api/posts/${randomPostId()}`);
    check(res, { '200': (r) => r.status === 200 });
    sleep(Math.random() * 3 + 2);
}
