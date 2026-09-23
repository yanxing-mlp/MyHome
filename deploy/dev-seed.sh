#!/usr/bin/env bash
# 造几条 vault 演示数据（M1/M2 前端联调用）。
#
# 为什么用 curl 而不是直接写 SQL：口令必须经后端加密，直插 SQL 会落进 password_enc
# 一列明文或者干脆填不出合法密文。走接口才能验证 create -> list -> reveal 的完整链路。
#
# 前置：后端已在 8080 起来（dev profile 自带一个 dev-only 主密钥）。
#
# 【v11 起多一道】请求体里的 password 是**传输层密文**，不是明文（服务端解密后才进业务），
# 所以这里必须先经 `transport-crypto.mjs` 加密一遍再发。那一串盐和前端 bundle 里的是同一份，
# 换生产盐时记得两边一起换，否则这个脚本和页面一起失效。
#
# 【重要】这些是演示用的假口令。dev 库的加密密钥写在仓库里（application-dev.yml），
# 拿到仓库 + 库就能解密，所以**不要**把真实口令录进 dev 库。
# 如果这个库将来要当真密码本用：先 `DROP DATABASE family_home;` 重来，并 export
# FH_VAULT_PASSWORD_KEY="$(openssl rand -base64 32)" 换成自己的密钥。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 写接口的"添加人"来自登录令牌（Authorization: Bearer，CurrentUserInterceptor 验签后 -> CurrentUserHolder），
# 缺这个头新建密码本会直接 401。旧的 `X-User-Id` 头已废弃（那是可手搓冒充的越权洞）。
# 令牌用 auth-token.mjs 按 userId 现签一枚（dev 密钥公开在 application-dev.yml）；1 = V500 种子里的大宝（ADMIN）。
USER_ID="${USER_ID:-1}"
TOKEN="$(node "$HERE/auth-token.mjs" "$USER_ID" 2>/dev/null)"
if [ -z "$TOKEN" ]; then
  echo "签不出登录令牌（node deploy/auth-token.mjs $USER_ID 失败）" >&2
  exit 1
fi
AS_USER=(-H "Authorization: Bearer $TOKEN")

if ! curl -sf "$BASE/api/b/health" -o /dev/null; then
  echo "后端没起来（$BASE），先跑：java -jar fh-boot/target/family-home-server.jar" >&2
  exit 1
fi

# transport-crypto.mjs 会把"用了 dev 盐"这句话打到 stderr，所以 $() 里只拿到密文。
encrypt() { node "$HERE/transport-crypto.mjs" "$1" 2>/dev/null; }

seed() {
  local name="$1" account="$2" password="$3"
  local resp cipher
  cipher=$(encrypt "$password")
  resp=$(curl -sS -X POST "$BASE/api/b/vault/accounts" \
    "${AS_USER[@]}" \
    -H 'Content-Type: application/json' \
    -d "$(printf '{"name":"%s","account":"%s","password":"%s"}' "$name" "$account" "$cipher")")
  echo "  $name -> $resp"
  echo "     （password 送出的是 ${#cipher} 个字符的传输层密文，明文只出现在上面这一行的入参里）"
}

echo "写入演示数据："
seed "微信"  "yanxing_demo"      "wx_Demo#2026"
seed "steam" "yanxing_demo@gmail.com" "St#am_Demo77"
seed "QQ"    "123456789"         "Qq-Demo@123"

echo
echo "列表（注意响应里没有任何密码字段）："
curl -sS "${AS_USER[@]}" "$BASE/api/b/vault/accounts?pageNo=1&pageSize=10"; echo
echo
echo "取第 1 条的口令（reveal 是唯一能把口令带回来的路径，且是 POST）："
REVEAL=$(curl -sS -X POST "${AS_USER[@]}" "$BASE/api/b/vault/accounts/1/password/reveal")
echo "  $REVEAL"
# 响应里那一格是传输层密文，页面上是 shared/crypto 解开后才显示给人看的
CIPHER=$(printf '%s' "$REVEAL" | sed -n 's/.*"password":"\([^"]*\)".*/\1/p')
echo "  把它解回来（也就是页面上那一条口令）：$(node "$HERE/transport-crypto.mjs" -d "$CIPHER" 2>/dev/null)"
echo
echo "库里存的是密文（password_enc 列，base64(iv||ciphertext||tag)，与上面那格**不是同一层**的密文）："
echo "  mysql --default-character-set=utf8mb4 -uroot family_home \\"
echo "    -e \"SELECT id, name, LEFT(password_enc, 32) AS enc_prefix, CHAR_LENGTH(password_enc) AS len FROM vault_account;\""
