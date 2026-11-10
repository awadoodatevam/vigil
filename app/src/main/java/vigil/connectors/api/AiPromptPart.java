package vigil.connectors.api;

/** One part of a completion request: its label, its category's display name and its text. */
public record AiPromptPart(String label, String categoryName, String text) {}
