package vigil.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;

/**
 * The paging parameters of AR-I-21 (AR-I-01, DC-U-12): {@code limit} 1 to 50 (default 50) and
 * {@code after}, the decimal {@code number} of a page's last row in base64url without padding.
 */
final class IncidentCursor {

  static final int DEFAULT_LIMIT = 50;

  private static final Pattern LIMIT = Pattern.compile("^[1-9][0-9]?$");
  private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final Pattern NUMBER = Pattern.compile("^[1-9][0-9]{0,17}$");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private IncidentCursor() {}

  /** The limit; absent: 50; anything but the text of a whole number 1 to 50 is bad_request. */
  static int limit(String text) {
    if (text == null) {
      return DEFAULT_LIMIT;
    }
    if (!LIMIT.matcher(text).matches() || Integer.parseInt(text) > DEFAULT_LIMIT) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    return Integer.parseInt(text);
  }

  /** The number of the cursor, or {@code null} for the first page; else bad_request. */
  static Long after(String text) {
    if (text == null) {
      return null;
    }
    if (!BASE64URL.matcher(text).matches()) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    byte[] bytes;
    try {
      bytes = DECODER.decode(text);
    } catch (IllegalArgumentException e) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    String number = new String(bytes, StandardCharsets.ISO_8859_1);
    if (!NUMBER.matcher(number).matches() || !encode(number).equals(text)) {
      throw new VigilError(Code.BAD_REQUEST);
    }
    return Long.parseLong(number);
  }

  static String cursor(long number) {
    return encode(Long.toString(number));
  }

  private static String encode(String number) {
    return ENCODER.encodeToString(number.getBytes(StandardCharsets.US_ASCII));
  }
}
