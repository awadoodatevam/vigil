package vigil.aigateway.api;

/** One part of a request (DM-R-43): label, data category and text. */
public record AiPart(String label, DataCategory dataCategory, String text) {}
