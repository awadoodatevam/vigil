package vigil.servicemap.api;

/** The generation a file import created and its counts. */
public record ImportResult(long generation, Counts counts) {}
