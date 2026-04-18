package vn.smartquiz.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Load JWT keypair từ file PEM lúc startup. Dev-only — prod phải lấy key từ Vault Transit Engine
 * (xem ADR-001 §Implementation notes và auth-service-design.md §13.5).
 */
@Configuration
@EnableConfigurationProperties({AuthJwtProperties.class, AuthDevProperties.class})
public class JwkConfig {

  private final AuthJwtProperties props;

  public JwkConfig(AuthJwtProperties props) {
    this.props = props;
  }

  @Bean
  public RSAPublicKey rsaPublicKey() throws Exception {
    return loadPublicKey(props.publicKeyPath());
  }

  @Bean
  public RSAPrivateKey rsaPrivateKey() throws Exception {
    return loadPrivateKey(props.privateKeyPath());
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    RSAKey rsaKey =
        new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(props.keyId())
            .algorithm(JWSAlgorithm.RS256)
            .keyUse(KeyUse.SIGNATURE)
            .build();
    return new ImmutableJWKSet<>(new JWKSet(rsaKey));
  }

  private static RSAPrivateKey loadPrivateKey(Path path)
      throws IOException, java.security.GeneralSecurityException {
    String pem = Files.readString(path);
    byte[] der = pemToDer(pem, "PRIVATE KEY");
    return (RSAPrivateKey)
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private static RSAPublicKey loadPublicKey(Path path)
      throws IOException, java.security.GeneralSecurityException {
    String pem = Files.readString(path);
    byte[] der = pemToDer(pem, "PUBLIC KEY");
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
  }

  private static byte[] pemToDer(String pem, String type) {
    String stripped =
        pem.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s+", "");
    return Base64.getDecoder().decode(stripped);
  }
}
