// scenario5-feed-throughput.js — Feed 纯吞吐极限（固定并发，无 think time）
// 目的：强制高并发连接，找到服务器的真实吞吐天花板
//
// 与之前版本的区别：
//   旧版用 ramping-arrival-rate（控制 QPS）→ 服务器太快，只需 2-3 个 VU 就能达到目标 QPS
//   新版用 ramping-vus（控制并发数）→ 强制 1000 个 VU 同时发请求，逼出真实极限
//
// 运行：k6 run backend/scripts/loadtest/scenario5-feed-throughput.js

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';

export const options = {
    scenarios: {
        // 强制并发：100 → 500 → 1000 → 2000 个 VU 同时发请求
        // 每个 VU 无 sleep，收到响应立刻发下一个 → 纯吞吐
        blast: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },     // 100 并发
                { duration: '1m',  target: 100 },      // 保持 100
                { duration: '30s', target: 500 },      // 500 并发
                { duration: '1m',  target: 500 },      // 保持 500
                { duration: '30s', target: 1000 },     // 1000 并发
                { duration: '1m',  target: 1000 },     // 保持 1000
                { duration: '30s', target: 2000 },     // 2000 并发（如果 k6 支持得了）
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
            exec: 'feedHit',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

export function feedHit() {
    // 无 sleep — 每个 VU 全速发请求
    const res = http.get(`${BASE_URL}/api/feed/public?size=20`);
    check(res, { '200': (r) => r.status === 200 });
}
