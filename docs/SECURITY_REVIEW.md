# Security review — August 2026

> **Status: partly fixed.** The root cause is closed — an endpoint without an authorization
> annotation now fails the build (`check_architecture.py` rule 5), and `AuthorizationBoundaryTest`
> signs in as an ordinary employee and proves the refusals actually refuse. The Critical finding
> and every High are fixed, each with a test that fails without the fix, along with most
> Mediums. **Still open: M7 (JWT audience), M9 (upload limits and 500s on ordinary client
> errors), M10 (`IllegalArgumentException` messages), and all four Lows.** Read the status
> column, not this paragraph.

Three independent adversarial reviews of authentication, authorization and data exposure, run
after Phase 6 and the hardening work. This document records what they found, unedited in
substance. Findings are not fixed by being written down; the status column is the truth.

**Headline: an ordinary employee could read and alter colleagues' performance reviews, download
every document in the tenant including sick notes and identity scans, book and cancel other
people's leave, and clock them in and out.** Cross-*tenant* isolation held. Everything else did
not.

---

## The root cause

`SecurityConfig` ends with `.anyRequest().authenticated()`. That means **an endpoint with no
`@PreAuthorize` is reachable by every signed-in user in the tenant.** Omission grants access.

Phase 6 built `/api/v1/me` carefully — no endpoint takes an employee id, and the one helper that
does check ownership is in a single place. But the HR endpoints beside it serve the same data
with no check at all, so the careful module was a door with a good lock in a wall with a hole in
it. Fixing self-service was never the work; fixing the routes next to it is.

`SecurityConfig`'s own javadoc claims resource-level authorization "is enforced in the service
layer with method security". There is **not one** `@PreAuthorize` outside a controller in the
whole backend. The comment misled every subsequent review, including mine.

---

## Critical

| # | Finding | Where | Status |
|---|---|---|---|
| C1 | **Performance: no authorization on any endpoint.** Any employee reads any colleague's review — including the reviewer's *unshared* assessment, proposed and calibrated ratings and management-only calibration notes, i.e. strictly more than the subject is allowed to see. They can also overwrite the victim's self-assessment, submit it, file a disagreement in their name, and attribute fabricated peer feedback to a third party (the author id is taken from the request body). | `PerformanceController` lines 89–220; `PerformanceService` 202, 215, 266, 273, 317, 335, 348 | **FIXED**, and proved by `AuthorizationBoundaryTest` |

## High

| # | Finding | Where | Status |
|---|---|---|---|
| H1 | **Files: no authorization of any kind.** `GET /api/v1/files?category=…` lists every document in the tenant with its original filename; `GET /files/{id}/content` returns the bytes. Sick notes, national-ID scans, contracts, certification attachments. There is no owner on `stored_file` at all. | `FileController` 42, 55, 60, 65 | **FIXED** — HR-only until `stored_file` carries an owner |
| H2 | **Attendance: clock-in, clock-out and timesheet submission take an employee id from the body with no check.** Clock a colleague out at 09:01, or submit a timesheet in their name. Timesheets feed payroll. | `AttendanceController` 52, 71, 78, 103, 115, 123, 131 | **FIXED** at role level; the `/me` routes remain the way a person clocks themselves |
| H3 | **Leave: any employee books leave for anyone and cancels anyone's approved leave**, silently rewriting their balance ledger. Reads expose sick-note document ids and reasons. | `LeaveController` 81, 90, 121, 134, 160; `LeaveRequestService.cancel` 190 | **FIXED** at role level |
| H4 | **TIX confidence score is a name-extraction oracle.** The returned score is a pure function of how the submitted name compares to the stored one (0.98 exact / 0.90 shares a token / 0.65 nothing), so a dictionary recovers a competitor's subject's legal name token by token. | `IdentityMatcher` 27–43 | **FIXED** — the score never leaves the server |
| H5 | **TIX weak-identifier guard is bypassable.** The base score is computed from the identifiers *submitted*, not the one that *matched*. Submit a real phone number plus a bogus passport: resolution falls through to the phone, the score is scored as strong, and it clears the automatic threshold. A competitor's debt status is readable from a mobile number alone. | `IdentityMatcher` 45–49 vs `ExchangeService` 113–126 | **FIXED**, with the bypass itself as a test |
| H6 | **No rate limiting anywhere.** With H4 and H5, the exchange can be swept in bulk through the intended API: enumerate identifiers, learn which are registered, extract names, read debt statuses. | whole backend | **FIXED** — 120 inquiries an hour per operator, configurable, failing closed |
| H7 | **The TIX audit trail does not record the purpose, the IP or the request id.** `purpose` is `@NotBlank`, documented as the answer to "why did you look this person up", validated, and then discarded. `AuditService.record` hardcodes `null, null` for request id and IP. This is the compensating control for H4–H6 and it does not exist. | `ExchangeService` 74, 80; `AuditService` 31–42 | **FIXED**, and V18 also makes the append-only claim true |

## Medium

| # | Finding | Where | Status |
|---|---|---|---|
| M1 | Payroll and timesheet approver identity comes from the request body, so the self-approval control is keyed off a value the caller chooses, and the recorded approver can be somebody who did not approve. | `PayrollService` 309; `TimesheetService` 214, 222 | **FIXED** across payroll, timesheets, leave and recruitment — the approver is the caller |
| M2 | Lifecycle: any employee can mark any checklist item done and attribute it to anyone — including "revoke system access" on an offboarding list. | `LifecycleController` 87, 92, 97, 126 | **FIXED** |
| M3 | Recruitment: any employee can write or overwrite interview feedback and a hire recommendation on any interview. | `RecruitmentController` 192 | **FIXED** — the interviewer check is written, not annotated around |
| M4 | Learning: any employee can read who has and has not completed mandatory compliance training — the exact list the guarded `/compliance` endpoint restricts. | `LearningController` 84, 104 | **FIXED** |
| M5 | `audit_event` is UPDATE-able by `dip_app`. `GRANT SELECT, INSERT, UPDATE ON ALL TABLES` runs after the table is created and the later narrower grant adds nothing — a `GRANT` is not a reset. The "append-only by privilege" comments in V1 and V4 are both false in the built schema. | `V1__baseline.sql` 69–70 | **FIXED** in V18 |
| M6 | Open redirect after sign-in. The `returnTo` guard blocks `//host` but not `/\host`, which the URL parser resolves to an absolute origin. Highest-credibility phishing position: a redirect off the real domain immediately after a real login. | `login/route.ts` 35; `callback/route.ts` 72, 86 | **FIXED** — resolved through the URL parser, not string prefixes |
| M7 | The resource server validates issuer and signature but **not audience**, so any token from any client in the realm is accepted with full roles. Not reachable in the shipped topology — the backend publishes no port — but it defeats the "only the BFF talks to the API" assumption. | `SecurityConfig` 44; `application.yml` 40 | **OPEN** |
| M8 | Personal data in production logs: hired candidate names, and the names of people skipped by payroll for having no salary. Different retention and access path from the database RLS protects. | `OfferService` 157; `PayrollService` 287 | **FIXED** — ids, not names |
| M9 | The configured 20 MB upload limit is unreachable — Spring's 1 MB multipart default rejects first — and exceeding it is a 500 rather than a 413. Malformed JSON and missing params are also 500s. | `application.yml` 81; `GlobalExceptionHandler` | **OPEN** |
| M10 | `IllegalArgumentException`'s message is returned to the client, making every library message on every path part of the public API. | `GlobalExceptionHandler` 80 | **OPEN** |
| M11 | The proxy is not confined to `/api/v1`: decoded `..` segments escape the prefix with the user's bearer token attached. No prize today, but it is a real confinement break. | `proxy/[...path]/route.ts` 61 | **FIXED** — segments validated and the prefix asserted |
| M12 | The proxy strips `Content-Disposition`, removing the `attachment` header the backend sets specifically to stop uploaded bytes rendering inline on the app's origin. | `proxy/[...path]/route.ts` 100 | **FIXED** — forwarded, defaulting to `attachment` |

## Low

| # | Finding | Where | Status |
|---|---|---|---|
| L1 | Self-service returns 403 for "not yours" and 404 for "not there" — exactly the oracle its own comment says was avoided. | `SelfServiceController` 117 | **OPEN** |
| L2 | Login CSRF: the callback validates `state` only against a cookie an attacker on a sibling subdomain could plant, and does not clear an existing session. `__Host-` prefix and a session reset would close it. | `callback/route.ts` 35 | **OPEN** |
| L3 | `NEXT_PHASE=phase-production-build` in a runtime environment disables every production check *and* strips `Secure` from the session cookie, silently. | `server/env.ts` 19 | **OPEN** |
| L4 | The ID token is decoded without verification. Not exploitable — confidential client, code flow, TLS to the token endpoint, and the profile is display-only — but no `nonce` and no signature check. | `oidc.ts` 51 | **OPEN** |

---

## What held up

Stated plainly, because it is the part worth keeping:

- **Cross-tenant isolation.** RLS covers all 30 tenant-owned tables with `FOR ALL` and `WITH CHECK`;
  `dip_app` has no `DELETE` grant anywhere; `app_current_tenant()` fails closed on an unbound
  tenant. The tenant comes only from a validated token claim — there is no header or path anywhere
  that sets it.
- **`TenantContext` does not leak.** Cleared in a `finally`, filter ordered after Spring Security,
  no async or thread pools. All four scheduled jobs bind the tenant outside the transaction, which
  is required given the datasource binds at checkout.
- **Exchange mode cannot be turned on by a caller and cannot survive its transaction.**
  `set_config(..., true)` is `SET LOCAL`; the mechanism is right even though the gate in front of
  it (H5) is not.
- **No SQL or JPQL injection.** All 18 `@Query` annotations use named parameters; both native
  recursive CTEs bind; `TenantAwareDataSource` binds the tenant as a statement parameter rather
  than concatenating — the one place concatenation would have been catastrophic.
- **No mass assignment.** Every request record is destructured field by field; entities are never
  serialised directly or bound from a body.
- **Frontend.** No `dangerouslySetInnerHTML`, no `innerHTML`, no `eval`, no `localStorage`, no
  `NEXT_PUBLIC_`. Tokens live server-side only.
- **PKCE, state entropy, session id entropy, session fixation, logout invalidation, cookie
  attributes.** All correct.
- **Error configuration.** Messages and stack traces suppressed in both profiles; actuator reduced
  to `health` with details hidden; Hibernate SQL and Spring Security logging explicitly turned down.
- **The payroll blind-review and written-blind rules** genuinely hold at the entity level. The
  problem in C1 is that nothing checks *who is asking*, not that the visibility logic is wrong.

---

## The lesson worth keeping

Every defect above sits in code that nothing had ever exercised from the wrong side. The tests
prove the happy path and the domain rules; they do not prove that a stranger is refused, because
no test ever tried. `SelfServiceTest` is the exception — it asks every question from the wrong
side, and self-service is the one module with no authorization findings.

Default-deny at the controller layer, and a test that walks every endpoint as an ordinary
employee, would have caught nearly all of this.
