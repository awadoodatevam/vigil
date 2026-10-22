package vigil.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.intake.api.IntakeService;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/** The monitoring webhook AR-I-90, on the host of the source's tenant (AR-I-02, AR-R-16 (1)). */
@RestController
@Profile("!migrate")
class WebhookController {

  /** AR-TM-02: 5 MiB. */
  static final int BODY_MAX = 5 * 1024 * 1024;

  private static final String BEARER = "Bearer";

  private final IntakeService intake;

  WebhookController(IntakeService intake) {
    this.intake = intake;
  }

  /** The body is read from the input stream, so no message converter reads it. */
  @PostMapping("/api/v1/webhooks/{source_key}")
  @WebhookToken
  Entries receive(
      TenantId tenant, @PathVariable("source_key") String sourceKey, HttpServletRequest request)
      throws IOException {
    byte[] body = request.getInputStream().readNBytes(BODY_MAX + 1);
    if (body.length > BODY_MAX) {
      throw new VigilError(Code.TOO_LARGE);
    }
    int entries =
        intake.receiveWebhook(
            tenant,
            sourceKey,
            token(request.getHeader("Authorization")),
            request.getHeader("Content-Type"),
            body);
    return new Entries(entries);
  }

  /** The word Bearer (any case), one space and a token without spaces; otherwise {@code null}. */
  private static String token(String header) {
    int space = header == null ? -1 : header.indexOf(' ');
    if (space < 0
        || !header.substring(0, space).equalsIgnoreCase(BEARER)
        || space == header.length() - 1
        || header.indexOf(' ', space + 1) >= 0) {
      return null;
    }
    return header.substring(space + 1);
  }

  record Entries(int entries) {}
}
