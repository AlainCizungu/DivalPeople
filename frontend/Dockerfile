# Frontend image.
#
# This is not a static site. Since ADR 0003 the Next.js server holds the session and is the only
# thing that ever sees a bearer token, so it runs as a server and is packaged as one.
#
# Tags are pinned to a patch version deliberately; see backend/Dockerfile for why.

# --- dependencies ------------------------------------------------------------
FROM node:22.13.1-bookworm-slim AS deps

WORKDIR /src
COPY package.json package-lock.json ./

# `npm ci` rather than `npm install`: it installs exactly the lockfile and fails if the lockfile
# and package.json disagree, which is the difference between a reproducible build and a hopeful one.
RUN npm ci --ignore-scripts

# --- build -------------------------------------------------------------------
FROM node:22.13.1-bookworm-slim AS build

WORKDIR /src
COPY --from=deps /src/node_modules ./node_modules
COPY . .

ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# --- runtime -----------------------------------------------------------------
FROM node:22.13.1-bookworm-slim AS runtime

# An unprivileged, named account. Running as root inside a container means a container escape
# starts as root, and there is no reason for this process to own anything.
#
# NOT called `dip`: that is a standard Debian and Ubuntu system group (dial-out networking, GID
# 30) and exists in both base images, so groupadd refuses and the build fails.
RUN groupadd --system --gid 10001 dipsvc \
    && useradd --system --uid 10001 --gid dipsvc --no-create-home --shell /usr/sbin/nologin dipsvc

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# The standalone output already contains the server and the modules it reaches. Static assets and
# anything under public/ are not included in it and have to be copied alongside.
COPY --from=build --chown=dipsvc:dipsvc /src/.next/standalone ./
COPY --from=build --chown=dipsvc:dipsvc /src/.next/static ./.next/static
COPY --from=build --chown=dipsvc:dipsvc /src/public ./public

USER dipsvc:dipsvc

EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/3000 && printf 'GET /api/health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q '200'"]

CMD ["node", "server.js"]
