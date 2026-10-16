package vigil.tenancy.api;

/** A customer logo: its bytes and media type ({@code image/png} or {@code image/svg+xml}). */
public record TenantLogo(byte[] bytes, String mediaType) {}
