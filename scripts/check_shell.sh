#!/bin/sh
# Every shell script parses under the shell it says it needs.
#
# WHY THIS EXISTS. The deployment host is Ubuntu, where /bin/sh is dash, and the setup scripts are
# invoked as `sh infra/keycloak/...`. Bash accepts a great deal that dash refuses, so a script
# written and re-read carefully on a laptop can still be a syntax error on the server — which is
# exactly what happened to setup-email.sh, twice in one afternoon, on a deployment somebody was
# waiting on.
#
# The specific failure both times was invisible by reading: a here-document whose delimiter is
# unquoted performs command substitution over its ENTIRE body, comments included. There are no
# comments in a heredoc, only text. A comment that quoted a command in backticks was therefore a
# command, and the braces inside it were a parse error. Under a more permissive shell it would not
# have been an error at all — it would have silently run.
#
# THE SHEBANG DECIDES WHICH SHELL. Checking everything with dash would fail infra/dev.sh, which
# declares bash and legitimately uses arrays; checking everything with bash would pass precisely
# the scripts this exists to catch. So each file is judged against what it asks for, which is also
# the thing a reviewer would otherwise have to remember.
set -eu

status=0
checked=0

for f in infra/*.sh infra/keycloak/*.sh scripts/*.sh; do
    [ -f "$f" ] || continue
    checked=$((checked + 1))

    case "$(head -1 "$f")" in
        *bash) shell=bash ;;
        *)     shell=dash ;;
    esac

    # dash may be absent on a developer's machine. Falling back to bash and saying so is honest;
    # silently skipping would let the one check that matters disappear from a laptop and stay
    # green.
    if [ "$shell" = dash ] && ! command -v dash > /dev/null 2>&1; then
        echo "warn  $f  (dash not installed; checked with bash, which is more permissive)"
        bash -n "$f" || status=1
        continue
    fi

    if "$shell" -n "$f" 2>/dev/null; then
        echo "ok    $f  ($shell)"
    else
        echo "FAIL  $f  ($shell)" >&2
        "$shell" -n "$f" || true
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo
    echo "All $checked shell scripts parse under the shell they declare."
fi

exit $status
