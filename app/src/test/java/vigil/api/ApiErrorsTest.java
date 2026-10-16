package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

class ApiErrorsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The default texts of Section 6.3 of the P-09 spec (BP-A-20). */
  private static final Map<Code, String> DEFAULTS =
      Map.ofEntries(
          Map.entry(Code.BAD_REQUEST, "The request is not valid."),
          Map.entry(Code.SESSION_ENDED, "Your session ended. Sign in again."),
          Map.entry(Code.NOT_SIGNED_IN, "You are not signed in."),
          Map.entry(Code.BAD_TOKEN, "The token is not valid."),
          Map.entry(Code.FORBIDDEN, "You do not have permission to do this."),
          Map.entry(Code.NOT_FOUND, "Not found."),
          Map.entry(Code.UNKNOWN_HOST, "This address is not set up."),
          Map.entry(Code.CONFLICT, "This was changed meanwhile. Reload and try again."),
          Map.entry(Code.TOO_LARGE, "The request is too large."),
          Map.entry(Code.REFUSED, "The request was refused."),
          Map.entry(Code.INVALID, "The request has problems."),
          Map.entry(Code.INTERNAL_ERROR, "Something went wrong. Try again."),
          Map.entry(Code.DEPENDENCY_FAILED, "A service is not available. Try again later."));

  @Test
  @DisplayName("AR-I-03 each code maps to its status, wire text and default text or message")
  void codesMapToTheirAnswers() throws IOException {
    for (Code code : Code.values()) {
      if (code == Code.STARTING) {
        continue;
      }
      ApiError error = map(new VigilError(code));
      assertThat(error.status()).as(code.name()).isEqualTo(code.status());
      assertThat(error.code()).isEqualTo(code.wireText());
      assertThat(error.message()).isEqualTo(DEFAULTS.get(code));
      assertThat(body(error))
          .isEqualTo(
              JSON.readTree(
                  "{\"error\":{\"code\":\""
                      + code.wireText()
                      + "\",\"message\":\""
                      + DEFAULTS.get(code)
                      + "\"}}"));

      ApiError withMessage = map(new VigilError(code, "Own text."));
      assertThat(withMessage.message()).isEqualTo("Own text.");

      ApiError withDetails =
          map(new VigilError(code, "Own text.", Map.of("refusal", "already_acknowledged")));
      assertThat(withDetails.details()).isEqualTo(Map.of("refusal", "already_acknowledged"));
      assertThat(body(withDetails))
          .isEqualTo(
              JSON.readTree(
                  "{\"error\":{\"code\":\""
                      + code.wireText()
                      + "\",\"message\":\"Own text.\","
                      + "\"details\":{\"refusal\":\"already_acknowledged\"}}}"));
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    ApiErrors.write(response, map(new VigilError(Code.INVALID, "x", Map.of())));
    assertThat(JSON.readTree(response.getContentAsByteArray()).get("error").has("details"))
        .isFalse();
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  @DisplayName("BP-A-20 framework exceptions, database failures and unexpected errors")
  void frameworkExceptions() throws Exception {
    assertThat(map(new NoResourceFoundException(HttpMethod.GET, "/x", "x")).code())
        .isEqualTo("not_found");
    ApiError wrongMethod = map(new HttpRequestMethodNotSupportedException("PUT", List.of("GET")));
    assertThat(wrongMethod.status()).isEqualTo(404);
    assertThat(wrongMethod.code()).isEqualTo("not_found");
    assertThat(wrongMethod.message()).isEqualTo("Not found.");

    ServletServerHttpRequest input = new ServletServerHttpRequest(new MockHttpServletRequest());
    for (Exception badRequest :
        List.of(
            new HttpMessageNotReadableException("bad", input),
            new HttpMediaTypeNotSupportedException("text/plain"),
            new MissingServletRequestParameterException("topics", "String"))) {
      ApiError error = map(badRequest);
      assertThat(error.status()).as(badRequest.getClass().getName()).isEqualTo(400);
      assertThat(error.code()).isEqualTo("bad_request");
      assertThat(error.message()).isEqualTo("The request is not valid.");
    }
    ApiError tooLarge = map(new ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE));
    assertThat(tooLarge.status()).isEqualTo(413);
    assertThat(tooLarge.code()).isEqualTo("too_large");

    CannotGetJdbcConnectionException noConnection =
        new CannotGetJdbcConnectionException("no connection");
    for (Throwable failure :
        List.of(
            noConnection,
            new RuntimeException(noConnection),
            new CannotGetJdbcConnectionException(
                "no connection",
                new SQLException("refused", new ConnectException("Connection refused"))))) {
      ApiError error = map(failure);
      assertThat(error.status()).isEqualTo(503);
      assertThat(error.code()).isEqualTo("dependency_failed");
      assertThat(error.message()).isEqualTo("A service is not available. Try again later.");
    }

    ApiError unexpected = map(new IllegalStateException("boom"));
    assertThat(unexpected.status()).isEqualTo(500);
    assertThat(unexpected.code()).isEqualTo("internal_error");
    assertThat(unexpected.message()).isEqualTo("Something went wrong. Try again.");

    Optional<ApiError> clientGone =
        ApiErrors.map(new RuntimeException(new IOException("Broken pipe")), true);
    assertThat(clientGone).isEmpty();
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    ApiErrors.answer(response, new RuntimeException(new IOException("Broken pipe")));
    assertThat(response.getContentAsByteArray()).isEmpty();
  }

  private static ApiError map(Throwable error) {
    return ApiErrors.map(error, false).orElseThrow();
  }

  private static JsonNode body(ApiError error) throws IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ApiErrors.write(response, error);
    assertThat(response.getStatus()).isEqualTo(error.status());
    return JSON.readTree(response.getContentAsByteArray());
  }
}
