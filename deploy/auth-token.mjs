#!/usr/bin/env node
/**
 * 登录会话令牌的命令行助手（配套 v11 之后的鉴权改造）。
 *
 * 为什么需要它：服务端拦截器现在只认 `Authorization: Bearer <token>`，而令牌是 `SessionToken`
 * 用 HMAC-SHA256 签出来的——旧的 `curl -H 'X-User-Id: 1'` 直连调试路径已经堵死（那正是越权洞）。
 * 这个脚本就是那条被堵掉的路的替代入口：给一个 userId，按服务端同一套格式现签一枚 dev 令牌，
 * 让 deploy/dev-seed.sh 与手工 curl 还能以某个账号的身份打接口。
 *
 * 格式必须与服务端 `com.familyhome.common.auth.SessionToken` 逐字对齐：
 *   v1.<userId>.<过期秒(epoch)>.<base64url_nopad(HMAC-SHA256(前三段, secret))>
 * 改一边就要改两边。
 *
 * 用法：
 *   node deploy/auth-token.mjs 1            # 给 userId=1 签一枚 7 天有效的令牌
 *   node deploy/auth-token.mjs --verify <t> # 校验一枚令牌，打印里面的 userId（验格式对不对）
 *
 * 密钥从 `FH_AUTH_TOKEN_SECRET` 取，没设就用 dev 那串（与服务端 application-dev.yml 的默认值相同）。
 * 注意 dev 密钥是公开的，只用来看链路通不通、跑本地联调，**绝不能**拿它签生产令牌。
 */
import { createHmac, timingSafeEqual } from 'node:crypto';

const TTL_SECONDS = 7 * 24 * 60 * 60; // 与 SessionToken.TTL_SECONDS 一致
const VERSION = 'v1';

const DEV_TOKEN_SECRET = 'fh-dev-2f6c9a41d8e7b305a1c6f9e2d7b4081c3a5e9f6d2b7c4081';

function resolveSecret() {
    const fromEnv = process.env.FH_AUTH_TOKEN_SECRET?.trim();
    if (fromEnv) return fromEnv;
    console.error('# 未设 FH_AUTH_TOKEN_SECRET，使用 dev 密钥（仅限本地联调）');
    return DEV_TOKEN_SECRET;
}

function sign(payload, secret) {
    return createHmac('sha256', secret).update(payload, 'utf8').digest('base64url');
}

function issue(userId, secret) {
    const exp = Math.floor(Date.now() / 1000) + TTL_SECONDS;
    const payload = `${VERSION}.${userId}.${exp}`;
    return `${payload}.${sign(payload, secret)}`;
}

function verify(token, secret) {
    const trimmed = String(token).trim();
    const lastSep = trimmed.lastIndexOf('.');
    if (lastSep <= 0 || lastSep === trimmed.length - 1) return null;
    const payload = trimmed.slice(0, lastSep);
    const givenSig = trimmed.slice(lastSep + 1);
    const expected = sign(payload, secret);
    const a = Buffer.from(expected, 'utf8');
    const b = Buffer.from(givenSig, 'utf8');
    if (a.length !== b.length || !timingSafeEqual(a, b)) return null;
    const parts = payload.split('.');
    if (parts.length !== 3 || parts[0] !== VERSION) return null;
    const exp = Number(parts[2]);
    if (!Number.isFinite(exp) || exp < Math.floor(Date.now() / 1000)) return null;
    return parts[1];
}

const secret = resolveSecret();
const [mode, value] = process.argv.slice(2);

if (mode === '--verify') {
    if (!value) {
        console.error('用法: node deploy/auth-token.mjs --verify <token>');
        process.exit(2);
    }
    const userId = verify(value, secret);
    if (userId == null) {
        console.error('FAIL: 令牌无效（签名/格式/有效期任一不过）');
        process.exit(1);
    }
    console.log(userId);
} else if (mode && /^\d+$/.test(mode)) {
    console.log(issue(mode, secret));
} else {
    console.error('用法: node deploy/auth-token.mjs <userId> | --verify <token>');
    process.exit(2);
}
