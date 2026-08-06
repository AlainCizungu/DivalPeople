# ADR 0003 — Tokens live on the server, behind a backend-for-frontend

Status: **Accepted** — August 2026
Supersedes the browser-held token arrangement described in ADR 0001's frontend notes.

## Context

Until now the browser ran the OIDC authorization code flow itself, using `oidc-client-ts`, and
kept the resulting tokens in `sessionStorage`. The API was called directly from the browser with
an `Authorization: Bearer` header.

This is the standard single-page-application pattern and it was the right thing to start with:
it needed no server, no session store, and no secret. It was recorded as a known gap from the
first week, and the gap was reviewed at the end of every phase.

The problem with it is not subtle. Anything that executes JavaScript on the page can read
`sessionStorage`. A single cross-site scripting flaw — in our code, in a dependency, in a
dependency's transitive dependency — hands an attacker a live access token and a refresh token.
The refresh token is the worse of the two: it lets them mint new access tokens long after the tab
is closed, from anywhere.

The platform now holds employee national identifiers, salaries, sick-leave certificates,
disciplinary-adjacent attendance records and cross-operator debt data. The value of a stolen
session has risen sharply since the decision was first taken, and every module added makes the
migration more expensive. This is the last comfortable moment to do it.

## Decision

The Next.js application becomes a **backend-for-frontend**. Tokens never reach the browser.

- **Login** is initiated by a server route, which builds the authorization request with PKCE and
  keeps the code verifier in a short-lived, `httpOnly` cookie.
- **The callback** is handled by a server route, which exchanges the code for tokens over a
  server-to-server call and stores them in Redis under a random session identifier.
- **The browser holds one thing**: an opaque 256-bit session identifier in a cookie marked
  `httpOnly`, `SameSite=Lax` and `Secure` outside development. It is a bearer credential, but it
  is one JavaScript cannot read and one that is useless anywhere but our own origin.
- **API calls go through `/api/proxy/*`**, a server route that looks up the session, attaches the
  access token, and forwards the request. The browser never sees a token in a response either.
- **Refresh happens server-side**, transparently, when the proxy notices the access token is
  close to expiry.

The Keycloak client becomes **confidential**. It was public only because a browser cannot keep a
secret; now nothing in the browser participates in the flow, so the secret can live where secrets
belong.

## Why Redis rather than an encrypted cookie

The alternative — encrypting the tokens into the session cookie itself — needs no session store
and is genuinely simpler. It was rejected on size: a Keycloak access token, refresh token and id
token together run past 3 KB before encryption and base64, and the 4 KB cookie limit is a cliff
rather than a slope. Splitting across several cookies to stay under it is the kind of cleverness
that fails in production on a token slightly larger than the one it was tested with.

Redis is already in the compose file and had no other purpose. Now it has one.

## Consequences

**What improves.** An XSS flaw can still act as the user *while the page is open* — it can call
the proxy, because the browser attaches the cookie. What it can no longer do is walk away with a
refresh token and keep the session alive afterwards. That is the difference between an incident
bounded by a page load and one bounded by nothing.

**What it costs.**

- The frontend now has a server-side runtime dependency. A statically exported build is no longer
  possible, and the deployment story changes accordingly.
- Redis becomes load-bearing. If it is down, nobody can sign in, and existing sessions break.
  Sessions are deliberately not replicated to the database: a lost Redis means everybody signs in
  again, which is an inconvenience, not a data loss.
- Every API call takes one extra hop. Measured against the risk, that is a good trade.
- Local development needs a client secret in `.env.local`, which is a file that must not be
  committed. `.gitignore` already refuses `.env*` except the examples.

**What this does not solve.** Cross-site request forgery is now possible in a way it was not
before: with a cookie-based credential, a request from another origin carries it automatically.
`SameSite=Lax` blocks the cross-site cases that matter for the state-changing verbs we use, and
the proxy additionally refuses requests whose `Origin` header is not our own. Both are in place.
A token in a header was immune to this by construction; a cookie is not, and pretending otherwise
would be the wrong lesson to take from this change.

It also does not make the UI a security boundary. Authorization is still enforced by the API,
against the token, per `SECURITY_MODEL.md`. Hiding a button proves nothing and never did.
