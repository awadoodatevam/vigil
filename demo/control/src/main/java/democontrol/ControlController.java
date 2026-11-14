package democontrol;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The JSON interface of the page (spec 6.3 item 3, complete list besides {@code GET /}, the static
 * page): every POST needs {@code Content-Type: application/json}, otherwise 415 and nothing done.
 */
@RestController
class ControlController {

  private static final String JSON = "application/json";

  private final Login login;
  private final Panel panel;
  private final Operations operations;
  private final Settings settings;

  ControlController(Login login, Panel panel, Operations operations, Settings settings) {
    this.login = login;
    this.panel = panel;
    this.operations = operations;
    this.settings = settings;
  }

  @GetMapping("/health")
  ResponseEntity<Void> health() {
    return ResponseEntity.ok().build();
  }

  @PostMapping(path = "/api/session", consumes = JSON)
  ResponseEntity<Object> session(@RequestBody(required = false) String body) {
    JsonNode json = body == null ? null : new Http.Answer(200, body).json();
    if (json == null
        || !json.isObject()
        || json.size() != 2
        || !json.path("username").isString()
        || !json.path("password").isString()) {
      return ResponseEntity.badRequest().build();
    }
    if (!login.verify(json.get("username").stringValue(), json.get("password").stringValue())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("message", "Username or password is incorrect."));
    }
    ResponseCookie cookie =
        ResponseCookie.from(Login.COOKIE, login.start())
            .httpOnly(true)
            .sameSite("Strict")
            .path("/")
            .secure(settings.secureCookies())
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
  }

  @GetMapping(path = "/api/state", produces = JSON)
  ResponseEntity<Object> state(@CookieValue(name = Login.COOKIE, required = false) String session) {
    if (!login.valid(session)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("product", panel.product()));
    }
    return ResponseEntity.ok(Http.JSON.writeValueAsString(panel.view()));
  }

  @PostMapping(path = "/api/buttons/{key}", consumes = JSON, produces = JSON)
  ResponseEntity<Object> button(
      @PathVariable String key,
      @CookieValue(name = Login.COOKIE, required = false) String session,
      @RequestBody(required = false) String body) {
    if (!login.valid(session)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of());
    }
    Operations.Press press = operations.press(key);
    if (!press.known()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of());
    }
    if (press.refusal() != null) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", press.refusal()));
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of());
  }
}
