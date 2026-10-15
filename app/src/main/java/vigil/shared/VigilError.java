package vigil.shared;

import java.util.Map;

/** The error type of AR-I-03. Mapping to an HTTP body is done by vigil-api. */
public class VigilError extends RuntimeException {

  /** The error codes of AR-I-03 with their HTTP status and wire text (complete list). */
  public enum Code {
    BAD_REQUEST(400, "bad_request"),
    SESSION_ENDED(401, "session_ended"),
    NOT_SIGNED_IN(401, "not_signed_in"),
    BAD_TOKEN(401, "bad_token"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not_found"),
    UNKNOWN_HOST(404, "unknown_host"),
    CONFLICT(409, "conflict"),
    TOO_LARGE(413, "too_large"),
    REFUSED(422, "refused"),
    INVALID(422, "invalid"),
    DEPENDENCY_FAILED(503, "dependency_failed"),
    STARTING(503, "starting");

    private final int status;
    private final String wireText;

    Code(int status, String wireText) {
      this.status = status;
      this.wireText = wireText;
    }

    public int status() {
      return status;
    }

    public String wireText() {
      return wireText;
    }
  }

  private final Code code;
  private final transient Map<String, Object> details;

  public VigilError(Code code, String message, Map<String, Object> details) {
    super(message);
    if (code == null) {
      throw new IllegalArgumentException("error code missing");
    }
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public VigilError(Code code, String message) {
    this(code, message, null);
  }

  public VigilError(Code code) {
    this(code, null, null);
  }

  public Code code() {
    return code;
  }

  /** The message, or {@code null}. */
  public String message() {
    return getMessage();
  }

  /** The details, never {@code null}. */
  public Map<String, Object> details() {
    return details;
  }
}
