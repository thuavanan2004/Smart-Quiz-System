#!/usr/bin/env bash
# =============================================================================
# Sinh RSA 2048 keypair cho JWT RS256 (Auth Service ký, service khác verify qua JWKS).
# Chạy 1 lần ở dev/stage. Production dùng KMS/Vault thay thế.
# =============================================================================
set -euo pipefail

KEYS_DIR="$(dirname "$0")/keys"
mkdir -p "$KEYS_DIR"

PRIVATE="$KEYS_DIR/jwt.private.pem"
PUBLIC="$KEYS_DIR/jwt.public.pem"

if [[ -f "$PRIVATE" ]]; then
    echo "⚠️  $PRIVATE đã tồn tại — huỷ để tránh ghi đè vô tình."
    echo "    Xoá thủ công nếu thực sự muốn sinh lại."
    exit 1
fi

# Private key
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE"
chmod 600 "$PRIVATE"

# Public key (để Auth expose JWKS)
openssl rsa -pubout -in "$PRIVATE" -out "$PUBLIC"

echo "✓ Keypair tạo tại:"
echo "  $PRIVATE (chỉ Auth service đọc)"
echo "  $PUBLIC  (public — phục vụ /.well-known/jwks.json)"
echo
echo "Nhắc: đưa $PRIVATE vào .gitignore (đã có) và Vault ở môi trường stage+."
