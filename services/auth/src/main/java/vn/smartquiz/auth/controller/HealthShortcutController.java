package vn.smartquiz.auth.controller;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * K8s-style shortcuts {@code /health} (liveness) + {@code /ready} (readiness) trên main port 3001.
 * Full diagnostic vẫn ở {@code /actuator/health/**} (management port 9001).
 *
 * <p>Liveness = process còn chạy không (Spring framework chưa crash). Readiness = app có accept
 * traffic được không (DB/Redis đã kết nối, Flyway migrate xong).
 */
@RestController
public class HealthShortcutController {

  private final ApplicationAvailability availability;

  public HealthShortcutController(ApplicationAvailability availability) {
    this.availability = availability;
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    LivenessState state = availability.getLivenessState();
    boolean ok = state == LivenessState.CORRECT;
    return ResponseEntity.status(ok ? 200 : 503)
        .body(Map.of("status", ok ? "UP" : "DOWN", "probe", "liveness"));
  }

  @GetMapping("/ready")
  public ResponseEntity<Map<String, String>> ready() {
    ReadinessState state = availability.getReadinessState();
    boolean ok = state == ReadinessState.ACCEPTING_TRAFFIC;
    return ResponseEntity.status(ok ? 200 : 503)
        .body(Map.of("status", ok ? "UP" : "DOWN", "probe", "readiness"));
  }
}
