package vn.smartquiz.auth.domain.password;

public interface PasswordHasher {

  String hash(char[] rawPassword);

  boolean verify(String storedHash, char[] rawPassword);
}
