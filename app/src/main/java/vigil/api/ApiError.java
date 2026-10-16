package vigil.api;

import java.util.Map;

/**
 * One error answer of AR-I-03: status, wire code, message ({@code null} for {@code starting}) and
 * details (empty when none).
 */
record ApiError(int status, String code, String message, Map<String, Object> details) {}
