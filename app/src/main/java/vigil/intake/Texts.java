package vigil.intake;

/** Lengths in Unicode code points, as {@code varchar(n)} counts them. */
final class Texts {

  private Texts() {}

  /** The first {@code max} code points of {@code text}, or the text when it is not longer. */
  static String cut(String text, int max) {
    if (text == null || text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max));
  }

  static int length(String text) {
    return text.codePointCount(0, text.length());
  }
}
