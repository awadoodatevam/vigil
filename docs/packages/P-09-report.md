# P-09 — Report: Public interface foundation

| Field | Value |
| --- | --- |
| Package | P-09 Public interface foundation (spec `docs/packages/P-09.md`) |
| Branch | `p-09-public-interface-foundation` |
| Pin date (BP-R-08) | 16 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

All classes are in `vigil.api`, package-private, and `@Profile("!migrate")` where they serve requests; `vigil.api.api` stays empty and the module's allowed dependencies are unchanged (it uses `shared`, `tenancy :: api`, `identity :: api`).

| Part | Requirement IDs |
| --- | --- |
| `VigilError.Code.INTERNAL_ERROR(500, "internal_error")` between `INVALID` and `DEPENDENCY_FAILED` | AR-R-06, BP-A-20 |
| `ApiError`, `ApiErrors` (spec 6.3: the mapping table in its order, database failures at any depth before the client-gone rule, the default texts of BP-A-20, the shared writer with `application/json`, no content negotiation, `details` left out when empty, `message` left out for `starting`; an `internal_error` is logged at `ERROR` with its exception, a client gone at `DEBUG`), `ApiExceptionAdvice` (`@RestControllerAdvice` for every handler and interceptor) | AR-I-03, BP-A-20, DC-U-05 |
| `ApiTimes.format` (`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`, UTC, truncated to milliseconds) | AR-I-01 |
| `ApiPaths` (every path decision on `getServletPath()`); `HostTenantFilter` (order `HIGHEST_PRECEDENCE + 10`; only `/api/` paths except `/api/v1/installation[/**]`; host name from the `Host` header without port, `[…]` for IPv6, lower case; `resolveHost`; 404 `unknown_host` with exactly the body of AR-I-02; the `TenantId` in a request attribute) | AR-I-02, AR-B-10, PM-M23-03, PM-R-05, SL-B-01 |
| `SecurityConfiguration`: one `SecurityFilterChain` for servlet paths starting with `/api/` (stateless, form login, HTTP basic, logout, request cache and response headers disabled; `SessionAuthenticationFilter` before `CsrfFilter`; permitted: `/api/v1/public/**`, `POST /api/v1/session`, `/api/v1/installation/**`, `/api/v1/webhooks/**`; everything else authenticated); the entry point (401 `session_ended` with a `vigil_session` cookie, else `not_signed_in`) and the access-denied handler (403 `forbidden`); one `CookieCsrfTokenRepository` bean (`vigil_csrf`, `X-Vigil-CSRF`, path `/`, `HttpOnly` false, `SameSite=Strict`, `Secure` from the property) with a `CsrfTokenRequestAttributeHandler` subclass that reads only the header, the CSRF matcher of spec 6.5 and `NullAuthenticatedSessionStrategy`; the `RequestRejectedHandler` bean (400 `bad_request`, nothing on an `ERROR` dispatch) | AR-T-11, AR-S-03, SL-B-13, BP-A-20, PM-M23-03 |
| `SessionAuthenticationFilter` (not a bean; first `vigil_session` cookie → `IdentityService.authenticate`; `ACTIVE` → `ApiAuthentication` with the `ApiCaller(tenantId, sessionId, user)` principal and no authorities; module failures answered with the mapping) | AR-T-11, AR-S-02, SL-B-01, AR-S-11 |
| `ApiCookies` (`vigil_session`: `Path=/`, `HttpOnly`, `SameSite=Lax`, `Secure` from `vigil.security.secure-cookies`, no `Max-Age`; cleared with `Max-Age=0`) | AR-S-02, PM-M23-05 |
| `@Public`, `@LoggedIn`, `@RequiresPermission(Permission[])`; `AccessInterceptor` for `/api/**` (only handler methods of `vigil.api`; permission asked of `vigil-identity` on every request; no annotation → 403); `ApiWebConfiguration` (the interceptor, the `TenantId` and `ApiCaller` argument resolvers, the `liveTimings()` bean of 15 s and 300 s) | AR-I-07, PM-M23-02, PM-R-09, DC-U-05, AR-TM-04 |
| `PublicController` (AR-I-10 branding, AR-I-11 logo with its media type and `Cache-Control: no-cache`), `SessionController` (AR-I-12 login read as a Jackson 3 `JsonNode`, one message "Username or password is incorrect." for every refused login, new session cookie and CSRF token; AR-I-13; AR-I-14 logout clearing both cookies), `TenantController` (AR-I-15); answer records `TenantSettings` and `User` with `@JsonNaming(SnakeCaseStrategy)` | AR-I-10 to AR-I-15, AR-I-01, AR-I-06, DC-R-06 |
| `LiveTopic` (the 9 kinds of AR-I-04, `text()`, `parse`), `LiveHub` (`SmartLifecycle`, default phase; `publish` never blocks; a bounded queue of 1,000 notices per stream, a full queue ends the stream; `openStreams()`; `stop()` ends every stream), `LiveTimings`, `LiveController` (AR-I-16 on the request's virtual thread: registered before `retry: 1000`, notices as exactly the bytes of spec 6.8, `: ping` every interval, the end at the open time, removal from the hub when it ends) | AR-I-04, AR-I-16, AR-T-10, AR-TM-04, AR-R-33, PM-M23-04 |
| `app/src/main/resources/openapi/vigil-api.yaml` (OpenAPI 3.1.0, the seven operations and the schemas of spec 6.9) | AR-I-08, BP-R-13 |
| `app/pom.xml`: `spring-boot-starter-security`; test scope `json-schema-validator` 3.0.6; failsafe `argLine` `-Djdk.httpclient.allowRestrictedHeaders=host`. `application.properties`: `vigil.security.secure-cookies=true`, the exclusion of `UserDetailsServiceAutoConfiguration` | AR-T-11, PM-M23-05 |
| Test support: `VigilTestApp.start(database, extraArgs, configurations)`, `ApiClient`, `TestTenants` | — |
| `README.md` "Module code": the line of spec 6.10 and the interfaces of spec 6.11 | P09-DC-02 |

Checked by review (spec part 4): AR-I-06 (no migration, table or domain check in `vigil.api`; each operation calls one module request: `settings`, `logo`, `login`, `logout`, or none for AR-I-13 and AR-I-16); PM-R-01 (no migration); PM-R-05 (every module call passes the `TenantId` of the host filter: the argument resolver's value or `ApiCaller.tenantId()`, which the session filter took from it); PM-R-08 (`SeparationTest` passes); PM-R-02 (`ModuleStructureTest` passes); PM-R-07 (the webhook path passes as spec 6.5 says); DC-R-06 (the two texts compared in `SessionIT` and `PermissionIT`); AR-S-11 (the session filter, login and logout log nothing; the only log lines of the package are `ERROR` for an `internal_error`, `DEBUG` for a client that went away).

## 2. Tests added

| Class | Layer | Test methods (display names start with) |
| --- | --- | --- |
| `vigil.shared.ErrorCodeTest` (changed) | unit | AR-R-06 |
| `vigil.api.ApiErrorsTest` | unit | AR-I-03, BP-A-20 |
| `vigil.api.ApiTimesTest` | unit | AR-I-01 |
| `vigil.api.LiveTopicTest` | unit | AR-I-04, AR-TM-04 |
| `vigil.api.EntryPointTest` | unit (ArchUnit) | PM-M23-01, PM-M23-02 |
| `vigil.api.HostResolutionIT` | IT | AR-I-02, AR-B-10, PM-M23-03, AR-I-10, SL-B-01, SL-B-13 |
| `vigil.api.SessionIT` | IT | AR-I-01, AR-I-12, AR-S-02, PM-M23-05, AR-I-13, AR-I-14, AR-S-03, AR-I-15, AR-I-11, BP-A-20, AR-T-11 |
| `vigil.api.PermissionIT` (with `vigil.api.PermissionProbe` in the test sources) | IT | AR-I-07, PM-R-09, DC-U-05 |
| `vigil.api.LiveStreamIT` | IT | AR-I-16, AR-T-10, AR-I-04, AR-TM-04, PM-M23-04 |
| `vigil.api.LiveLoadIT` | IT | AR-R-33 |
| `vigil.api.ContractIT` | IT | AR-I-08, BP-R-13 |

Unchanged tests that cover this package and pass: `ModuleStructureTest`, `SchemaRuleTest`, `SeparationTest`, `ConnectorLibraryRuleTest`, `AppStartIT`, `WebAppServingIT` (`/api/v1/anything` on `127.0.0.1` now answers 404 `unknown_host`), `MigrateIT`. `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `SessionIT`'s AR-I-14 check accepts, for the cleared `vigil_csrf` cookie, an `Expires` in the past in place of `Max-Age=0`; the cleared `vigil_session` cookie has `Max-Age=0` as specified. | Spec 6.7 clears the CSRF cookie with `CookieCsrfTokenRepository.saveToken(null, …)`, which writes a servlet `Cookie` with max age 0 through `response.addCookie`. Tomcat 11.0.24 (`Rfc6265CookieProcessor`) writes such a cookie as `vigil_csrf=; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/; Secure; SameSite=Strict`, without `Max-Age` (it writes `Max-Age` only for a positive max age). The cookie is cleared all the same (empty value, expiry in the past). Document feedback 1. |
| 2 | `SessionIT` has its AR-T-11 method last (`@Order`), checking every answer the class received for `JSESSIONID`. | The check covers every answer of the class, so it runs after the other methods. |
| 3 | `ContractIT`'s BP-R-13 method first checks that the validator refuses a body that does not match the login answer's schema. | Guards against a schema location that resolves to an empty schema, which would let every answer pass. |
| 4 | `ApiClient` also offers `login(host, username, password)` and `Response.json()`. | Conveniences used by every `*IT` of this package; no behaviour of Vigil depends on them. |

## 4. Document feedback (CTX-Q-8)

1. Spec 7 (`SessionIT`, AR-I-14) says logout "clears both cookies (`Max-Age=0`)". With `saveToken(null, …)` (spec 6.7) on Tomcat 11.0.24, the CSRF cookie is cleared with `Expires=Thu, 01 Jan 1970 00:00:10 GMT` and no `Max-Age` (deviation 1). Proposal: the check reads "clears both cookies (empty value; `Max-Age=0` or an `Expires` in the past)".

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 16 October 2026)

| Item | Version |
| --- | --- |
| `org.springframework.boot:spring-boot-starter-security` | 4.1.1 (Spring Boot BOM); brings `spring-security-config`, `spring-security-core` and `spring-security-web` 7.1.1 (`spring-security.version` of the Spring Boot 4.1.1 BOM) |
| `com.networknt:json-schema-validator` (test) | 3.0.6 (spec 6.2: the latest release built on Jackson 3.1.x); it brings `tools.jackson.dataformat:jackson-dataformat-yaml` 3.1.5 (version from the Spring Boot BOM) and `org.snakeyaml:snakeyaml-engine` 3.0.1 to the test classpath; Spring Boot's managed Jackson (3.1.5) stays |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Recorded by this package (spec Section 9):

| Item | Result |
| --- | --- |
| Versions introduced | Section 6. |
| Largest delay between `publish` and arrival (`LiveStreamIT`, PM-M23-04, 20 notices 100 ms apart, measured at the arrival of each `data:` line) | GATE_DELAY |

Assumptions of spec 11: `json-schema-validator` 3.0.6 runs with Jackson 3.1.5 and resolves the `$ref`s inside the OpenAPI document by JSON pointer (`ContractIT` passes); Tomcat on virtual threads serves 250 blocking streams at once and the hub ends them at shutdown (`LiveLoadIT` passes; `close()` returned within 5 s); `java.net.http.HttpClient` sends the test's own `Host` header with `-Djdk.httpclient.allowRestrictedHeaders=host` (`HostResolutionIT` passes), so `ApiClient` needs no socket fallback.

## 9. Lines of production code added and changed (P09-DC-01)

`tools/loc.sh origin/main HEAD` prints **GATE_LOC** (at most 2,000).

## 10. Gate command result

GATE_RESULT

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1), BP-DC-03 (no operation, field, setting or behaviour beyond the spec; the OpenAPI file holds only the content of spec 6.9 and response descriptions), BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-03 `ConnectorLibraryRuleTest` pass; no new table, event listener or external call), BP-DC-05 (the seven operations are in `openapi/vigil-api.yaml` and `ContractIT` passes), BP-DC-06 (`README.md`; no command added or changed), BP-DC-07 (this report; no owner-run step) hold; BP-DC-02 holds in the session, the runner pending. P09-DC-01 holds (GATE_LOC). P09-DC-02 holds. P09-DC-03: the session part holds, the runner pending.

## 11. Follow-up items for later packages

1. P-11: `/api/v1/installation/**` reaches Spring MVC on any host without session or CSRF; the bearer-token check and its access annotation are P-11's, and `EntryPointTest` must accept that annotation. Today such a path answers 404 `not_found`.
2. P-13: `/api/v1/webhooks/**` is tied to the host's tenant (`TenantId` parameter) and passes without session and CSRF; today it answers 404 `not_found`.
3. P-25: each new operation carries `@RequiresPermission` (or `@LoggedIn`), takes `TenantId` and `ApiCaller` as parameters, writes timestamps with `ApiTimes.format`, throws `VigilError` for refusals, is added to `openapi/vigil-api.yaml` and gets `ContractIT` scenarios; its producers call `LiveHub.publish(tenantId, topic, id, at)` after the commit of the change.
4. P-26: the local installation sets `vigil.security.secure-cookies=false` (and `vigil.http.bind-address=0.0.0.0`).
5. P-28: the cookie names `vigil_session` and `vigil_csrf`, the header `X-Vigil-CSRF` (the page reads the `vigil_csrf` cookie), the error body and default messages; a refused login answers 401 `not_signed_in` "Username or password is incorrect.".
