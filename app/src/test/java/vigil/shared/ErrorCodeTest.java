package vigil.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  @DisplayName("AR-R-06 the error type holds exactly the codes of AR-I-03")
  void errorCodes() {
    assertThat(
            Arrays.stream(VigilError.Code.values())
                .map(c -> c + " " + c.status() + " " + c.wireText()))
        .containsExactly(
            "BAD_REQUEST 400 bad_request",
            "SESSION_ENDED 401 session_ended",
            "NOT_SIGNED_IN 401 not_signed_in",
            "BAD_TOKEN 401 bad_token",
            "FORBIDDEN 403 forbidden",
            "NOT_FOUND 404 not_found",
            "UNKNOWN_HOST 404 unknown_host",
            "CONFLICT 409 conflict",
            "TOO_LARGE 413 too_large",
            "REFUSED 422 refused",
            "INVALID 422 invalid",
            "INTERNAL_ERROR 500 internal_error",
            "DEPENDENCY_FAILED 503 dependency_failed",
            "STARTING 503 starting");
    VigilError error = new VigilError(VigilError.Code.STARTING);
    assertThat(error.code()).isEqualTo(VigilError.Code.STARTING);
    assertThat(error.message()).isNull();
    assertThat(error.details()).isEmpty();
    assertThat(new VigilError(VigilError.Code.REFUSED, "No.", Map.of("refusal", "x")).details())
        .containsEntry("refusal", "x");
  }
}
