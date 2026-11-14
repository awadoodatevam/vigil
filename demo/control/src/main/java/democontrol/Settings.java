package democontrol;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;

/** The properties of demo-control (spec 6.2 item 1) and the two values read once at start. */
record Settings(
    String vigilUrl,
    String faultsUrl,
    String zabbixUrl,
    String alertmanagerUrl,
    List<String> values,
    Path stateFile,
    Path secrets,
    boolean secureCookies,
    String product,
    String demoDomain) {

  static final String VIGIL_URL = "demo-control.vigil-url";

  /** The settings, or a Failed whose message is the one start log line naming the property. */
  static Settings of(Environment env) throws Failed {
    String vigilUrl = env.getProperty(VIGIL_URL);
    if (vigilUrl == null || vigilUrl.isBlank()) {
      throw new Failed("The property " + VIGIL_URL + " is missing.");
    }
    List<String> values =
        Arrays.stream(env.getRequiredProperty("demo-control.values").split(","))
            .map(String::strip)
            .filter(location -> !location.isEmpty())
            .toList();
    Map<String, String> read;
    try {
      read = Values.read(values);
    } catch (Failed e) {
      throw new Failed("demo-control.values: " + e.getMessage());
    }
    for (String name : List.of("product_display_name", "demo_domain")) {
      if (read.get(name) == null || read.get(name).isEmpty()) {
        throw new Failed("demo-control.values: the values files lack " + name + ".");
      }
    }
    return new Settings(
        strip(vigilUrl),
        strip(env.getRequiredProperty("demo-control.faults-url")),
        env.getRequiredProperty("demo-control.zabbix-url"),
        strip(env.getRequiredProperty("demo-control.alertmanager-url")),
        values,
        Path.of(env.getRequiredProperty("demo-control.state-file")),
        Path.of(env.getRequiredProperty("demo-control.secrets")),
        env.getProperty("vigil.security.secure-cookies", Boolean.class, true),
        read.get("product_display_name"),
        read.get("demo_domain"));
  }

  private static String strip(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
