package vn.smartquiz.auth.controller.dto;

public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresIn) {

  public static TokenPair bearer(String access, String refresh, long expiresIn) {
    return new TokenPair(access, refresh, "Bearer", expiresIn);
  }
}
