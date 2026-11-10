package vigil.investigation.api;

/** A runbook supplied to or cited by an AI investigation, with its version (DM-E-68). */
public record RunbookRef(String runbookKey, long version) {}
