package vigil.api;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.identity.api.Permission;

/** Two permission-checked operations that exist only in PermissionIT; it counts its calls. */
@RestController
@ConditionalOnProperty(name = "vigil.test.permission-probe", havingValue = "true")
class PermissionProbe {

  static final AtomicInteger CALLS = new AtomicInteger();

  @GetMapping("/api/v1/test-probe")
  @RequiresPermission(Permission.VIEW)
  ResponseEntity<Void> read() {
    CALLS.incrementAndGet();
    return ResponseEntity.ok().build();
  }

  @PostMapping("/api/v1/test-probe")
  @RequiresPermission({Permission.ACKNOWLEDGE, Permission.APPROVE_ACTION})
  ResponseEntity<Void> act() {
    CALLS.incrementAndGet();
    return ResponseEntity.noContent().build();
  }
}
