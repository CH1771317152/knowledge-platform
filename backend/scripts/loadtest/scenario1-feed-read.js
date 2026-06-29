// scenario1-feed-read.js — Feed 公开页读取（带 think time，模拟真实用户浏览）
// 目的：测三级缓存在全栈运行（后台调度器竞争资源）下的延迟和命中率
// 对比：之前 integration profile 的结果（p95=1.07ms@1684QPS）
// 现在 local profile 多了：reconciliation scheduler(30s)、hotkey rotation(10s)、outbox relay(500ms)
// 运行：k6 run backend/scripts/loadtest/scenario1-feed-read.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 50 },
        { duration: '2m',  target: 50 },
        { duration: '30s', target: 100 },
        { duration: '2m',  target: 100 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<100'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/api/feed/public?size=20`);
    check(res, { '200': (r) => r.status === 200 });
    sleep(Math.random() * 1.5 + 0.5); // 0.5-2s think time
}
