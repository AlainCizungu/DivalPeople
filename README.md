# Dival Intelligence Platform (DIP)

Monorepo for the Dival Intelligence Platform and the products built on it.

| Product | Status | Description |
|---|---|---|
| **Dival People** | In development | Bilingual, AI-enabled HR, workforce intelligence, employee financial services, and fraud prevention. |
| **TIX** — Telecom Information Exchange | Module of Dival People | Customer risk and identity exchange for telecommunications operators: shared verified debt information, identity matching, and fraud detection. Delivered as part of the Telecom edition. |

## Repository layout

```
DIP/
├── AGENTS.md                 Rules AI coding agents must follow
├── docs/                     Product, architecture, security, and design documentation
│   └── adr/                  Architecture decision records
├── backend/                  Java 21 + Spring Boot modular monolith
├── frontend/                 Next.js + TypeScript + Tailwind application shell
├── infra/                    Local development infrastructure
└── .github/workflows/        Continuous integration
```

## Prerequisites

- **JDK 21+** (`java -version`)
- **Node.js 20.9+** (`node -v`) — the repo pins a version in `.nvmrc`, so `nvm use` picks it up
- **Docker** with Compose

## Getting started

```bash
# 1. One-time backend bootstrap (creates ./gradlew, checks your JDK)
cd backend && ./bootstrap.sh && cd ..

# 2. Start infrastructure and wait for the Keycloak realm to import
./infra/dev.sh up

# 3. Run the backend — http://localhost:8080  (local profile is automatic)
cd backend && ./gradlew bootRun

# 4. Run the frontend — http://localhost:3000
cd frontend && cp .env.local.example .env.local && npm install && npm run dev

# 5. Verify the whole chain
./infra/dev.sh check
```

`dev.sh check` fetches a real token from Keycloak, confirms the `tenant_id` claim is present,
calls the TIX API, and asserts that an unauthenticated request gets 401 and a user without TIX
roles gets 403.

The backend applies Flyway migrations on startup and, under the `local` profile, seeds two demo
tenants whose IDs match the `tenant_id` attributes of the Keycloak users.

### Local identities

| User | Password | Tenant | Roles |
|---|---|---|---|
| `operator-a` | `password` | `1111…1111` | TIX_INQUIRER, TIX_DECLARANT, TENANT_ADMIN |
| `operator-b` | `password` | `2222…2222` | TIX_INQUIRER, TIX_DECLARANT, TENANT_ADMIN |
| `no-roles` | `password` | `1111…1111` | EMPLOYEE only — used to prove authorization denies |

Keycloak admin console: http://localhost:8081 (`admin` / `admin`). These are local-only fixtures.

Signing into the frontend as `operator-a` and running a verification on the **Telecom Exchange**
page exercises the whole chain: authorization code + PKCE against Keycloak, a bearer token on the
API call, server-side role and tenant enforcement, and an audited lookup. Signing in as
`no-roles` should produce a permission message rather than a result — that denial is the point.

```bash
./infra/dev.sh token operator-b          # print a token
./infra/dev.sh down                      # stop everything
```

Editing `infra/keycloak/realm-dip.json` requires recreating the container, since the realm is
imported only on first start:

```bash
docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak
```

## Verify the setup

```bash
cd backend  && ./gradlew test          # includes mandatory tenant-isolation tests
cd frontend && npm run typecheck && npm run build
```

## Architectural ground rules

These are enforced in review and in CI. Full detail lives in [`AGENTS.md`](AGENTS.md) and [`docs/`](docs).

- **Modular monolith first.** No new microservice without an approved ADR in `docs/adr/`.
- **Tenant isolation is absolute.** Every tenant-owned row carries `tenant_id`; tenant context comes from the authenticated identity and never from request input.
- **Authorization is server-side.** Hiding a frontend control is not authorization.
- **Bilingual from launch.** No hard-coded user-facing strings; English and French keys ship together.
- **AI is advisory.** It may summarize, match, explain, and recommend. It may not make final employment, fraud, lending, insurance, discipline, or payroll decisions.

## Documentation

| Document | Purpose |
|---|---|
| [PRODUCT_REQUIREMENTS.md](docs/PRODUCT_REQUIREMENTS.md) | Product scope, personas, modules, MVP |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture and stack |
| [TIX_MODULE.md](docs/TIX_MODULE.md) | Telecom Information Exchange module specification |
| [DATABASE_DESIGN.md](docs/DATABASE_DESIGN.md) | Data model conventions |
| [SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | Security and privacy controls |
| [API_STANDARDS.md](docs/API_STANDARDS.md) | API conventions |
| [UI_DESIGN_SYSTEM.md](docs/UI_DESIGN_SYSTEM.md) | Visual language and components |
| [INTERNATIONALIZATION.md](docs/INTERNATIONALIZATION.md) | Bilingual requirements |
| [TESTING_STRATEGY.md](docs/TESTING_STRATEGY.md) | Test approach |
| [DEVELOPMENT_RULES.md](docs/DEVELOPMENT_RULES.md) | Engineering rules |
| [ROADMAP.md](docs/ROADMAP.md) | Delivery phases |

© Dival AI — Confidential.
