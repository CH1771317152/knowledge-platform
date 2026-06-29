// scenario2-likes-write.js — 点赞写入压测（全栈：Kafka acks=all + 消费者 + 聚合链路）
// 目的：测写入路径的真实吞吐——现在每次点赞都走完整链路：
//   SETBIT → Kafka send(acks=all,~5ms) → CounterAggregateConsumer → Redis agg
//   → CounterFlushScheduler(500ms) → CountInt → snapshot outbox → relay → snapshot consumer
// 对比：之前 integration 时 Kafka send 是 fire-and-forget（无消费者、acks 无关）
// 运行：k6 run backend/scripts/loadtest/scenario2-likes-write.js

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, authHeader, randomPostId, POST_IDS } from './config.js';

export const options = {
    scenarios: {
        // 阶梯递增 QPS，找写入拐点
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 300,
            maxVUs: 500,
            stages: [
                { duration: '1m', target: 50 },    // 50/s — 基线
                { duration: '1m', target: 200 },    // 200/s — 正常
                { duration: '1m', target: 500 },    // 500/s — 高负载
                { duration: '1m', target: 1000 },   // 1000/s — 极限
                { duration: '30s', target: 0 },
            ],
            exec: 'likeOrUnlike',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<200'],
    },
};

let token;
let postIndex = 0;

export function setup() {
    return { token: login('loadtester', 'loadtest123!') };
}

export function likeOrUnlike(data) {
    // 轮流点赞/取消，覆盖 SETBIT 的两个方向
    const postId = POST_IDS[postIndex % POST_IDS.length];
    postIndex++;
    const method = postIndex % 2 === 0 ? 'POST' : 'DELETE';

    const res = http.request(method, `${BASE_URL}/api/posts/${postId}/likes`, null, authHeader(data.token));
    check(res, { '200': (r) => r.status === 200 });
}
