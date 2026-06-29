// scenario6-auth-throughput.js — 认证读+写混合吞吐（全栈）
// 目的：JWT 解析 + hasActedBatch overlay + 点赞 SETBIT + Kafka send(acks=all) 的真实开销
// 这是全栈运行下最有价值的测试——之前 integration 的 Kafka 是空转的
// 运行：k6 run backend/scripts/loadtest/scenario6-auth-throughput.js

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, authHeader, POST_IDS } from './config.js';

export const options = {
    scenarios: {
        // 认证 Feed 读
        auth_feed: {
            executor: 'ramping-arrival-rate',
            startRate: 100,
            timeUnit: '1s',
            preAllocatedVUs: 500,
            maxVUs: 800,
            stages: [
                { duration: '30s', target: 100 },
                { duration: '30s', target: 500 },
                { duration: '30s', target: 1000 },
                { duration: '30s', target: 2000 },
                { duration: '30s', target: 0 },
            ],
            exec: 'authFeed',
        },
        // 点赞写入（真实 Kafka acks=all）
        likes: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 300,
            maxVUs: 500,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '30s', target: 200 },
                { duration: '30s', target: 500 },
                { duration: '30s', target: 1000 },
                { duration: '30s', target: 0 },
            ],
            exec: 'likeBurst',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

export function setup() {
    return {
        tokenA: login('loadtester', 'loadtest123!'),
        tokenB: login('loadtester2', 'loadtest2123!'),
    };
}

let likeIdx = 0;

export function authFeed(data) {
    const res = http.get(`${BASE_URL}/api/feed/me?size=20`, authHeader(data.tokenA));
    check(res, { '200': (r) => r.status === 200 });
}

export function likeBurst(data) {
    const postId = POST_IDS[likeIdx % POST_IDS.length];
    likeIdx++;
    const method = likeIdx % 2 === 0 ? 'POST' : 'DELETE';
    const res = http.request(method, `${BASE_URL}/api/posts/${postId}/likes`, null, authHeader(data.tokenB));
    check(res, { '200': (r) => r.status === 200 });
}
