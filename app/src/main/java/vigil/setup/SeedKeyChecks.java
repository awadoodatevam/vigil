package vigil.setup;

import java.util.List;
import org.springframework.stereotype.Component;

/** Every {@link SeedKeyCheck} bean, in bean order (DM-R-60). */
@Component
class SeedKeyChecks {

  private final List<SeedKeyCheck> checks;

  SeedKeyChecks(List<SeedKeyCheck> checks) {
    this.checks = List.copyOf(checks);
  }

  List<SeedKeyCheck> all() {
    return checks;
  }
}
