#!/usr/bin/env bash
# 造几条 vault 演示数据（M1/M2 前端联调用）。
#
# 为什么用 curl 而不是直接写 SQL：口令必须经后端加密，直插 SQL 会落进 password_enc
# 一列明文或者干脆填不出合法密文。走接口才能验证 create -> list -> reveal 的完整链路。
#
# 前置：后端已在 8080 起来（dev profile 自带一个 dev-only 主密钥）。
#
# 【重要】这些是演示用的假口令。dev 库的加密密钥写在仓库里（application-dev.yml），
# 拿到仓库 + 库就能解密，所以**不要**把真实口令录进 dev 库。
# 如果这个库将来要当真账号本用：先 `DROP DATABASE family_home;` 重来，并 export
# FH_VAULT_PASSWORD_KEY="$(openssl rand -base64 32)" 换成自己的密钥。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"

if ! curl -sf "$BASE/api/b/health" -o /dev/null; then
  echo "后端没起来（$BASE），先跑：java -jar fh-boot/target/family-home-server.jar" >&2
  exit 1
fi

seed() {
  local name="$1" account="$2" password="$3"
  local resp
  resp=$(curl -sS -X POST "$BASE/api/b/vault/accounts" \
    -H 'Content-Type: application/json' \
    -d "$(printf '{"name":"%s","account":"%s","password":"%s"}' "$name" "$account" "$password")")
  echo "  $name -> $resp"
}

echo "写入演示数据："
seed "微信"  "yanxing_demo"      "wx_Demo#2026"
seed "steam" "yanxing_demo@gmail.com" "St#am_Demo77"
seed "QQ"    "123456789"         "Qq-Demo@123"

echo
echo "列表（注意响应里没有任何密码字段）："
curl -sS "$BASE/api/b/vault/accounts?pageNo=1&pageSize=10"; echo
echo
echo "取第 1 条的明文口令（reveal 是唯一返回明文的路径，且是 POST）："
curl -sS -X POST "$BASE/api/b/vault/accounts/1/password/reveal"; echo
echo
echo "库里存的是密文（password_enc 列，base64(iv||ciphertext||tag)）："
echo "  mysql --default-character-set=utf8mb4 -uroot family_home \\"
echo "    -e \"SELECT id, name, LEFT(password_enc, 32) AS enc_prefix, CHAR_LENGTH(password_enc) AS len FROM vault_account;\""
