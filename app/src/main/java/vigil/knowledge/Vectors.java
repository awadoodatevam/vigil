package vigil.knowledge;

import java.util.List;

/** The checks of embedding vectors and their text form for pgvector (P-20 spec Section 6.6). */
final class Vectors {

  static final int MAX_DIMENSIONS = 16_000;

  private Vectors() {}

  /**
   * True when there is at least one vector, each holds 1 to 16,000 numbers, all have the same
   * length, every number is finite and not every number of a vector is 0.
   */
  static boolean usable(List<float[]> vectors) {
    if (vectors == null || vectors.isEmpty()) {
      return false;
    }
    int length = -1;
    for (float[] vector : vectors) {
      if (vector == null || vector.length < 1 || vector.length > MAX_DIMENSIONS) {
        return false;
      }
      if (length >= 0 && vector.length != length) {
        return false;
      }
      length = vector.length;
      boolean nonZero = false;
      for (float number : vector) {
        if (!Float.isFinite(number)) {
          return false;
        }
        nonZero |= number != 0f;
      }
      if (!nonZero) {
        return false;
      }
    }
    return true;
  }

  /** {@code [n1,n2,…]} with each number in {@code Float.toString} form. */
  static String text(float[] vector) {
    StringBuilder text = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        text.append(',');
      }
      text.append(Float.toString(vector[i]));
    }
    return text.append(']').toString();
  }
}
