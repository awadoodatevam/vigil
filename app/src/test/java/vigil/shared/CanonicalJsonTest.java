package vigil.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  /** RFC 8785 Appendix B: IEEE 754 bit patterns and their canonical number text. */
  private static final String[][] NUMBERS = {
    {"0000000000000000", "0"},
    {"8000000000000000", "0"},
    {"0000000000000001", "5e-324"},
    {"8000000000000001", "-5e-324"},
    {"7fefffffffffffff", "1.7976931348623157e+308"},
    {"ffefffffffffffff", "-1.7976931348623157e+308"},
    {"4340000000000000", "9007199254740992"},
    {"c340000000000000", "-9007199254740992"},
    {"4430000000000000", "295147905179352830000"},
    {"44b52d02c7e14af5", "9.999999999999997e+22"},
    {"44b52d02c7e14af6", "1e+23"},
    {"44b52d02c7e14af7", "1.0000000000000001e+23"},
    {"444b1ae4d6e2ef4e", "999999999999999700000"},
    {"444b1ae4d6e2ef4f", "999999999999999900000"},
    {"444b1ae4d6e2ef50", "1e+21"},
    {"3eb0c6f7a0b5ed8c", "9.999999999999997e-7"},
    {"3eb0c6f7a0b5ed8d", "0.000001"},
    {"41b3de4355555553", "333333333.3333332"},
    {"41b3de4355555554", "333333333.33333325"},
    {"41b3de4355555555", "333333333.3333333"},
    {"41b3de4355555556", "333333333.3333334"},
    {"41b3de4355555557", "333333333.33333343"},
    {"becbf647612f3696", "-0.0000033333333333333333"},
    {"43143ff3c1cb0959", "1424953923781206.2"}
  };

  @Test
  @DisplayName("AR-P-03 the RFC 8785 sorting example gives the published order")
  void sortingExample() {
    String input =
        "{\n"
            + "  \"\\u20ac\": \"Euro Sign\",\n"
            + "  \"\\r\": \"Carriage Return\",\n"
            + "  \"\\ufb33\": \"Hebrew Letter Dalet With Dagesh\",\n"
            + "  \"1\": \"One\",\n"
            + "  \"\\ud83d\\ude00\": \"Emoji: Grinning Face\",\n"
            + "  \"\\u0080\": \"Control\",\n"
            + "  \"\\u00f6\": \"Latin Small Letter O With Diaeresis\"\n"
            + "}";
    String expected =
        "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\","
            + "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\","
            + "\"\ud83d\ude00\":\"Emoji: Grinning Face\","
            + "\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}";
    assertThat(CanonicalJson.canonicalize(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("AR-P-03 the RFC 8785 Appendix B numbers give the published outputs")
  void numberExamples() {
    for (String[] example : NUMBERS) {
      double value = Double.longBitsToDouble(Long.parseUnsignedLong(example[0], 16));
      assertThat(CanonicalJson.canonicalize("[" + value + "]"))
          .as("number %s", example[0])
          .isEqualTo("[" + example[1] + "]");
    }
  }

  @Test
  @DisplayName("AR-P-03 SHA-256 of the canonical form in 64 lowercase hexadecimal characters")
  void hashOfCanonicalForm() {
    String hash = CanonicalJson.sha256HexOfCanonical("{\"b\": [1, 2.0], \"a\": \"x\"}");
    assertThat(hash).matches("[0-9a-f]{64}");
    assertThat(CanonicalJson.sha256HexOfCanonical("{\"a\":\"x\",\"b\":[1,2]}")).isEqualTo(hash);
    assertThat(CanonicalJson.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
