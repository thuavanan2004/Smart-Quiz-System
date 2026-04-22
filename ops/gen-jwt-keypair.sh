#!/usr/bin/env bash
# =============================================================================
# ops/gen-jwt-keypair.sh — sinh RSA 2048 keypair cho Auth service (DATN)
# =============================================================================
# Chạy 1 lần lúc setup project. Không rotate trong vòng đời DATN (ADR-003).
# Output:
#   ops/jwt/private.pem   (PKCS#8, Auth service dùng để sign JWT)
#   ops/jwt/public.pem    (X.509 SubjectPublicKeyInfo, phát qua JWKS)
#   ops/jwt/kid.txt       (key id: smartquiz-YYYY-MM)
#
# Usage:
#   bash ops/gen-jwt-keypair.sh
# =============================================================================

set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")" && pwd)/jwt"
mkdir -p "$OUT_DIR"

if [[ -f "$OUT_DIR/private.pem" ]]; then
    echo "[skip] $OUT_DIR/private.pem đã tồn tại. Xoá thủ công nếu muốn sinh lại."
    exit 0
fi

if ! command -v openssl >/dev/null 2>&1; then
    echo "[error] cần openssl (Git Bash trên Windows đã có)." >&2
    exit 1
fi

KID="smartquiz-$(date +%Y-%m)"
echo "[info] sinh keypair, kid=$KID"

# RSA 2048, private key PKCS#8 PEM
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$OUT_DIR/private.pem" \
    -outform PEM 2>/dev/null

# Derive public key (SubjectPublicKeyInfo)
openssl rsa -in "$OUT_DIR/private.pem" \
    -pubout -out "$OUT_DIR/public.pem" 2>/dev/null

echo "$KID" > "$OUT_DIR/kid.txt"

# Lock quyền (best-effort trên Windows/WSL)
chmod 600 "$OUT_DIR/private.pem" 2>/dev/null || true
chmod 644 "$OUT_DIR/public.pem"  2>/dev/null || true

echo "[done] files created in $OUT_DIR"
ls -la "$OUT_DIR"
