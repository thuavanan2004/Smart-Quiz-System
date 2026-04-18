package vn.smartquiz.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

    private final JWKSource<SecurityContext> jwkSource;

    public JwksController(JWKSource<SecurityContext> jwkSource) {
        this.jwkSource = jwkSource;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() throws Exception {
        JWKSet jwks = new JWKSet(jwkSource.get(null, null));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .body(jwks.toPublicJWKSet().toJSONObject());
    }
}
