package vigil.connectors.api;

import java.util.List;

/** The result of parsing a received body. */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.Failed {

  /** The body was read; its entries in order. */
  record Parsed(List<ParsedEntry> entries) implements ParseResult {

    public Parsed {
      entries = List.copyOf(entries);
    }
  }

  /** The body cannot be read (DM-E-18 {@code parse_outcome = failed}). */
  record Failed(String reason) implements ParseResult {}
}
