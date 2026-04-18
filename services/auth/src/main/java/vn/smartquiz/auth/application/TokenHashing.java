package vn.smartquiz.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hash tiện ích cho token opaque (email verify, password reset). Plaintext không được log. */
final class TokenHashing {

  private TokenHashing() {}

  // V0001 baseline lưu token_hash dưới dạng BYTEA raw (32 bytes) — nhẹ + so sánh nhanh hơn hex.
  static byte[] sha256Raw(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
