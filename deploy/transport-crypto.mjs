#!/usr/bin/env node
/**
 * 口令传输层加解密的命令行助手（v11）。
 *
 * 为什么需要它：服务端 `TransportCipher.decrypt()` **刻意不留"传明文就照单收下"的降级分支**，
 * 所以从 v11 起 `curl -d '{"password":"123456"}'` 这一类直连接口调试全部会 400
 * （"口令无法解密，请刷新页面后重试"）。这个脚本就是那条被堵掉的路的替代入口，
 * 也让"前后端算出来的到底是同一串东西"这件事有一条能跑的证据，不用靠读代码对齐。
 *
 * 参数与前端 `packages/shared/src/crypto/transport.ts`、服务端
 * `com.familyhome.common.crypto.TransportCipher` 三方必须一致，改一边就要改三边。
 *
 * 用法：
 *   node deploy/transport-crypto.mjs 明文口令            # 加密，打印 base64 密文
 *   node deploy/transport-crypto.mjs -d 'Base64密文'      # 解密，打印明文（验 reveal 的返回）
 *   node deploy/transport-crypto.mjs --roundtrip          # 自检：加密再解密回到原串
 *
 * 盐从 `FH_TRANSPORT_CRYPTO_SALT` 取，没设就用 dev 那串（与服务端 application-dev.yml 的默认值相同）。
 * 注意 dev 盐是公开的，只用来看链路通不通，不要拿它当真密钥。
 */
import { webcrypto as crypto } from 'node:crypto';

const KDF_ITERATIONS = 100_000;
const KEY_LENGTH_BITS = 256;
const IV_LENGTH_BYTES = 12;

const DEV_TRANSPORT_SALT = 'fh-dev-7184bf06e1a8eaf27d22bd216a3c42f5';

function resolveSalt() {
    const fromEnv = process.env.FH_TRANSPORT_CRYPTO_SALT?.trim();
    if (fromEnv) return fromEnv;
    console.error('# 未设 FH_TRANSPORT_CRYPTO_SALT，使用 dev 盐（仅限本地联调）');
    return DEV_TRANSPORT_SALT;
}

async function deriveKey(salt) {
    const saltBytes = new TextEncoder().encode(salt);
    const baseKey = await crypto.subtle.importKey('raw', saltBytes, 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits(
        { name: 'PBKDF2', salt: saltBytes, iterations: KDF_ITERATIONS, hash: 'SHA-256' },
        baseKey,
        KEY_LENGTH_BITS
    );
    return crypto.subtle.importKey('raw', bits, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
}

function toBase64(bytes) {
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return Buffer.from(binary, 'binary').toString('base64');
}

function fromBase64(text) {
    return new Uint8Array(Buffer.from(text.trim(), 'base64'));
}

async function encrypt(key, plain) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH_BYTES));
    const tail = new Uint8Array(
        await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(plain))
    );
    const packed = new Uint8Array(iv.length + tail.length);
    packed.set(iv, 0);
    packed.set(tail, iv.length);
    return toBase64(packed);
}

async function decrypt(key, cipherText) {
    const packed = fromBase64(cipherText);
    const plain = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: packed.subarray(0, IV_LENGTH_BYTES) },
        key,
        packed.subarray(IV_LENGTH_BYTES)
    );
    return new TextDecoder().decode(plain);
}

const salt = resolveSalt();
const key = await deriveKey(salt);
const [mode, value] = process.argv.slice(2);

if (mode === '--roundtrip') {
    const probe = 'demo#口令-123';
    const back = await decrypt(key, await encrypt(key, probe));
    console.log(back === probe ? `OK: ${probe} -> ${back}` : `FAIL: ${probe} != ${back}`);
    process.exit(back === probe ? 0 : 1);
}

if (mode === '-d') {
    if (!value) {
        console.error('用法: node deploy/transport-crypto.mjs -d <Base64密文>');
        process.exit(2);
    }
    console.log(await decrypt(key, value));
} else if (mode) {
    console.log(await encrypt(key, mode));
} else {
    console.error('用法: node deploy/transport-crypto.mjs <明文口令> | -d <密文> | --roundtrip');
    process.exit(2);
}
