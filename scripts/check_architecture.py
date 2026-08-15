#!/usr/bin/env python3
"""Enforce the architecture rules stated in AGENTS.md.

Rules that are written down but never checked decay quietly: the first violation is a small
convenience, the tenth is the architecture. These are cheap to check, so they are checked.

    python3 scripts/check_architecture.py

Exits non-zero on the first rule that fails, printing the offending file and line.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA_MAIN = REPO / "backend/src/main/java/ai/dival/dip"
JAVA_TEST = REPO / "backend/src/test/java/ai/dival/dip"
MIGRATIONS = REPO / "backend/src/main/resources/db/migration"

BASE_PACKAGE = "ai.dival.dip"


class Failure(Exception):
    """A rule was violated. The message is the report."""


def java_sources() -> list[Path]:
    return sorted(JAVA_MAIN.rglob("*.java"))


def module_of(path: Path) -> str | None:
    """The module a source file belongs to, or None if it is shared code."""
    parts = path.relative_to(JAVA_MAIN).parts
    if len(parts) >= 2 and parts[0] == "modules":
        return parts[1]
    return None


def is_shared(path: Path) -> bool:
    return path.relative_to(JAVA_MAIN).parts[0] == "common"


# ---------------------------------------------------------------------------
# Rule 1 — shared code must not depend on modules.
#
# common/ is the foundation every module builds on. The moment it imports a module, the
# dependency graph has a cycle and the module can no longer be changed or removed in isolation.
# ---------------------------------------------------------------------------
def check_common_does_not_import_modules() -> None:
    violations = []
    for path in java_sources():
        if not is_shared(path):
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if line.startswith(f"import {BASE_PACKAGE}.modules."):
                violations.append((path, number, line.strip()))

    if violations:
        report = "\n".join(
            f"    {p.relative_to(REPO)}:{n}  {line}" for p, n, line in violations)
        raise Failure(
            "common/ must not import from modules/.\n"
            "Move the shared abstraction into common/ and have the module depend on it.\n"
            f"{report}")


# ---------------------------------------------------------------------------
# Rule 2 — a module must not reach into another module's persistence.
#
# Cross-module access goes through the other module's service. Repositories and entities are
# internals; depending on them makes every schema change a cross-module change.
# ---------------------------------------------------------------------------
INTERNAL_SUFFIXES = ("Repository",)


def check_no_cross_module_persistence() -> None:
    violations = []
    for path in java_sources():
        module = module_of(path)
        if module is None:
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            match = re.match(rf"import {re.escape(BASE_PACKAGE)}\.modules\.(\w+)\.(\w+);", line)
            if not match:
                continue
            other_module, type_name = match.group(1), match.group(2)
            if other_module == module:
                continue
            if type_name.endswith(INTERNAL_SUFFIXES):
                violations.append((path, number, line.strip()))

    if violations:
        report = "\n".join(
            f"    {p.relative_to(REPO)}:{n}  {line}" for p, n, line in violations)
        raise Failure(
            "A module must not import another module's repository.\n"
            "Go through that module's service instead.\n"
            f"{report}")


# ---------------------------------------------------------------------------
# Rule 3 — every tenant-owned table carries a row-level security policy.
#
# The application-level scoping and the database policy are independent controls. Adding a
# tenant-owned table without a policy silently drops one of them, and nothing else would notice.
# ---------------------------------------------------------------------------
TABLE_ANNOTATION = re.compile(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"')


TENANT_COLUMN = re.compile(r'@Column\s*\(\s*name\s*=\s*"tenant_id"')


def tenant_owned_tables() -> dict[str, Path]:
    """
    Table names of entities that belong to a tenant.

    Two ways to qualify, and the second was added in August 2026 after three tenant-owned tables
    were written that this rule did not cover. Extending TenantOwnedEntity was the only signal it
    looked for, so an entity that declared its own tenant_id column — because it had no version
    column, or used received_at rather than created_at — was invisible to the check that exists
    precisely to catch a missing policy. The rule now asks what the entity *is* rather than what
    it inherits from.
    """
    tables: dict[str, Path] = {}
    for path in java_sources():
        source = path.read_text(encoding="utf-8")
        # A @MappedSuperclass has no table of its own — TenantOwnedEntity declares the tenant_id
        # column that every subclass inherits, and asking for its policy is asking about a table
        # that does not exist.
        if "@MappedSuperclass" in source:
            continue
        inherits = "extends TenantOwnedEntity" in source
        declares = bool(TENANT_COLUMN.search(source))
        if not (inherits or declares):
            continue
        match = TABLE_ANNOTATION.search(source)
        if not match:
            reason = ("extends TenantOwnedEntity" if inherits
                      else 'declares a "tenant_id" column')
            raise Failure(
                f"{path.relative_to(REPO)} {reason} but declares no "
                '@Table(name = "..."), so its policy cannot be verified.')
        tables[match.group(1)] = path
    return tables


def check_tenant_owned_tables_have_policies() -> None:
    sql = "\n".join(p.read_text(encoding="utf-8") for p in sorted(MIGRATIONS.glob("*.sql")))
    problems = []

    for table, source in sorted(tenant_owned_tables().items()):
        if not re.search(rf"ALTER TABLE\s+{table}\s+ENABLE ROW LEVEL SECURITY", sql, re.IGNORECASE):
            problems.append(
                f"    {table} (from {source.relative_to(REPO)}) — no ENABLE ROW LEVEL SECURITY")
            continue

        # Policies are recreated across migrations; any one of them carrying WITH CHECK is enough,
        # since the latest definition wins and CI runs migrations in order.
        policies = re.findall(
            rf"CREATE POLICY\s+\w+\s+ON\s+{table}\b(.*?);", sql, re.IGNORECASE | re.DOTALL)
        if not policies:
            problems.append(
                f"    {table} (from {source.relative_to(REPO)}) — no CREATE POLICY")
        elif not any("with check" in policy.lower() for policy in policies):
            problems.append(
                f"    {table} (from {source.relative_to(REPO)}) — policy has no WITH CHECK, "
                "so writes are unconstrained")

    if problems:
        raise Failure(
            "Every tenant-owned table needs row-level security with USING and WITH CHECK,\n"
            "added in the same migration that creates the table.\n"
            + "\n".join(problems))


# ---------------------------------------------------------------------------
# Rule 4 — no fixed-width CHAR columns.
#
# PostgreSQL reports CHAR(n) as a different JDBC type code than VARCHAR, so Hibernate's schema
# validation rejects it against a plain String field and the whole application context fails to
# start. This has cost two full test runs; it costs nothing to check.
#
# CHAR also pads values with trailing spaces on read, which surprises equality comparisons.
# ---------------------------------------------------------------------------
CHAR_COLUMN = re.compile(r"^\s*(\w+)\s+CHAR\s*\(", re.IGNORECASE)


def check_no_char_columns() -> None:
    violations = []
    for path in sorted(MIGRATIONS.glob("*.sql")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if line.strip().startswith("--"):
                continue
            # VARCHAR contains "CHAR", so anchor on the column-definition shape instead.
            if CHAR_COLUMN.match(line) and "VARCHAR" not in line.upper():
                violations.append((path, number, line.strip()))

    if violations:
        report = "\n".join(
            f"    {p.relative_to(REPO)}:{n}  {line}" for p, n, line in violations)
        raise Failure(
            "Use VARCHAR(n), not CHAR(n).\n"
            "CHAR is a distinct JDBC type code and fails Hibernate schema validation against a\n"
            "String field, and it pads values with trailing spaces.\n"
            f"{report}")


# ---------------------------------------------------------------------------
# Rule 5 — every endpoint states who may call it.
#
# SecurityConfig ends with `.anyRequest().authenticated()`. That makes omission *permissive*: an
# endpoint with no @PreAuthorize is reachable by every signed-in user in the tenant. The August
# 2026 security review found nine modules in that state, including one where any employee could
# read and rewrite a colleague's performance review.
#
# So the annotation is not optional and forgetting it is not a style problem. A method is covered
# by its own @PreAuthorize, by one on its class, or — for the deliberate case where any
# authenticated caller is correct — by an explicit @PreAuthorize("isAuthenticated()"). There is no
# way to say nothing.
# ---------------------------------------------------------------------------
MAPPING = re.compile(r"^\s*@(Get|Post|Put|Patch|Delete|Request)Mapping\b")
AUTHORIZATION = re.compile(r"^\s*@(PreAuthorize|PostAuthorize|Secured|RolesAllowed|DenyAll)\b")
METHOD_SIGNATURE = re.compile(r"^\s*(public|protected|private)\s")


def check_every_endpoint_declares_authorization() -> None:
    violations = []

    for path in java_sources():
        text = path.read_text(encoding="utf-8")
        if "@RestController" not in text and "@Controller" not in text:
            continue

        lines = text.splitlines()

        # A class-level annotation covers everything in the file. Found by looking for an
        # authorization annotation before the type declaration.
        class_line = next((i for i, line in enumerate(lines)
                           if re.match(r"^(public\s+)?(final\s+)?class\s", line)), len(lines))
        if any(AUTHORIZATION.match(line) for line in lines[:class_line]):
            continue

        # Otherwise every mapped method needs its own. Annotations may appear in any order and
        # with arguments spanning lines, so the scan collects the annotation block that precedes
        # each method signature rather than looking only at the line above the mapping.
        block: list[str] = []
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith("@"):
                block.append(line)
                continue
            if not stripped or stripped.startswith("//") or stripped.startswith("*") \
                    or stripped.startswith("/*"):
                continue

            # A class declaration also starts with a visibility modifier and is preceded by the
            # class-level @RequestMapping. It is not an endpoint.
            is_type_declaration = re.search(r"\b(class|interface|enum|record)\s+\w", line)

            if METHOD_SIGNATURE.match(line) and not is_type_declaration \
                    and any(MAPPING.match(b) for b in block):
                if not any(AUTHORIZATION.match(b) for b in block):
                    mapping = next(b.strip() for b in block if MAPPING.match(b))
                    violations.append((path, number, f"{mapping}  {stripped[:70]}"))
            block = []

    if violations:
        report = "\n".join(
            f"    {p.relative_to(REPO)}:{n}\n        {line}" for p, n, line in violations)
        raise Failure(
            "Every endpoint must say who may call it.\n"
            "SecurityConfig authenticates every request but authorizes none, so a method with no\n"
            "@PreAuthorize is reachable by every signed-in user in the tenant. If that is genuinely\n"
            "what you want, write @PreAuthorize(\"isAuthenticated()\") and mean it.\n"
            f"{report}")


# ---------------------------------------------------------------------------
# Rule 6 — a raw INSERT names every column the schema demands.
#
# Some tests write SQL by hand on purpose: RowLevelSecurityTest checks the policies themselves, so
# it must bypass every Java guard and talk to PostgreSQL as the application role. The cost is that
# it breaks whenever a NOT NULL column is added — retention_until in V19, origin in V20 — and it
# breaks by failing the whole suite rather than by failing to compile.
#
# Both times the fix was one line and the diagnosis took a full test run. The second time there was
# already a script that would have found it, written after the first, and never wired in here. A
# check that lives in somebody's shell history is not a check.
# ---------------------------------------------------------------------------
def _split_top_level(body: str) -> list[str]:
    """Split a CREATE TABLE body on commas at bracket depth zero.

    Splitting on every comma tears NUMERIC(18, 2) in half and turns CHECK (x IN ('a','b')) into
    fragments — which is how an earlier version of this concluded that performance_review had a
    column named "OR".
    """
    depth, item, items = 0, [], []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            items.append("".join(item))
            item = []
        else:
            item.append(ch)
    items.append("".join(item))
    return items


def required_columns() -> dict[str, set[str]]:
    """Columns an INSERT must name: NOT NULL, with no default and not generated."""
    required: dict[str, set[str]] = {}
    defaulted: dict[str, set[str]] = {}

    migrations = sorted(MIGRATIONS.glob("*.sql"),
                        key=lambda path: int(re.match(r"V(\d+)__", path.name).group(1)))
    for path in migrations:
        sql = re.sub(r"--[^\n]*", "", path.read_text(encoding="utf-8"))

        for match in re.finditer(
                r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\((.*?)\n\);", sql, re.S | re.I):
            table, body = match.group(1), match.group(2)
            required.setdefault(table, set())
            defaulted.setdefault(table, set())
            for line in _split_top_level(body):
                line = line.strip()
                declaration = re.match(r"^(\w+)\s+[A-Za-z]", line)
                if not declaration:
                    continue
                column = declaration.group(1)
                if column.upper() in ("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "LIKE"):
                    continue
                if re.search(r"\bDEFAULT\b|\bGENERATED\b|PRIMARY KEY", line, re.I):
                    defaulted[table].add(column)
                elif re.search(r"\bNOT NULL\b", line, re.I):
                    required[table].add(column)

        # Added later and then made mandatory: required from that migration onwards.
        for match in re.finditer(
                r"ALTER TABLE\s+(\w+)\s+ALTER COLUMN\s+(\w+)\s+SET NOT NULL", sql, re.I):
            table, column = match.group(1), match.group(2)
            if column not in defaulted.get(table, set()):
                required.setdefault(table, set()).add(column)

        for match in re.finditer(
                r"ALTER TABLE\s+(\w+)\s+ALTER COLUMN\s+(\w+)\s+DROP NOT NULL", sql, re.I):
            required.get(match.group(1), set()).discard(match.group(2))

        for match in re.finditer(
                r"ALTER TABLE\s+(\w+)\s+DROP COLUMN\s+(?:IF EXISTS\s+)?(\w+)", sql, re.I):
            required.get(match.group(1), set()).discard(match.group(2))

    return required


def check_raw_inserts_name_required_columns() -> None:
    required = required_columns()
    problems = []

    sources = list(JAVA_MAIN.rglob("*.java"))
    if JAVA_TEST.exists():
        sources += list(JAVA_TEST.rglob("*.java"))

    for path in sorted(sources):
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"INSERT INTO\s+(\w+)\s*\(([^)]*)\)", text, re.I | re.S):
            table = match.group(1)
            if table not in required:
                continue
            named = {c.strip() for c in match.group(2).replace("\n", " ").split(",") if c.strip()}
            missing = required[table] - named
            if missing:
                problems.append(
                    f"    {path.relative_to(REPO)} — INSERT INTO {table} omits "
                    f"{', '.join(sorted(missing))}")

    if problems:
        raise Failure(
            "A hand-written INSERT must name every NOT NULL column that has no default.\n"
            "These fail at runtime, not at compile time, so the whole suite runs before you\n"
            "find out.\n"
            + "\n".join(problems))


# ---------------------------------------------------------------------------
# Rule 7 — an annotation is attached to the thing below it.
#
# Added after a scripted edit inserted a constant and its javadoc between
# @Transactional(readOnly = true) and the method it annotated. javac calls that "annotation
# interface not applicable to this kind of declaration", which is clear enough once you see it —
# but every check written here at the time passed, because braces balanced, symbols existed and
# arities matched. The annotation was attached to the wrong thing, and nothing was looking.
#
# The rule is narrow on purpose: an annotation line followed immediately by the opening of a
# javadoc comment always means something was inserted in between. Ordinary code never does it.
# ---------------------------------------------------------------------------
def _is_standalone_annotation(text: str) -> bool:
    """An annotation occupying its whole line, with nothing after it.

    Excludes parameter annotations — {@code @Param("to") LocalDate to);} and friends — which are
    followed by a javadoc for the *next* member perfectly legitimately. Those carry a declaration
    on the same line, so requiring the line to end at the annotation is the whole distinction.
    """
    if not text.startswith("@"):
        return False
    rest = text[1:]
    name_length = 0
    while name_length < len(rest) and (rest[name_length].isalnum() or rest[name_length] == "."):
        name_length += 1
    rest = rest[name_length:]
    if not rest:
        return True          # a bare @Override
    if not rest.startswith("("):
        return False
    depth = 0
    for index, character in enumerate(rest):
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return rest[index + 1:].strip() == ""
    return False


def check_annotations_are_attached() -> None:
    violations = []
    for path in java_sources() + sorted(JAVA_TEST.rglob("*.java")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            if not _is_standalone_annotation(stripped):
                continue
            following = next((nxt.strip() for nxt in lines[number:] if nxt.strip()), "")
            if following.startswith("/*"):
                violations.append((path, number, stripped))

    if violations:
        raise Failure(
            "An annotation is separated from what it annotates by a comment. Something was\n"
            "inserted between them:\n"
            + "\n".join(
                f"  {path.relative_to(REPO)}:{number}  {text}"
                for path, number, text in violations))



# ---------------------------------------------------------------------------
# Rule 8 — a module only calls another module's public members.
#
# Added after ImportDeriver, in tix, called SourceMapping.cell(...), which is package-private in
# ingest. javac catches it; nothing before javac did. That is the third accessibility mistake in
# this project — retainUntil was widened and then called from another package, SubjectRequest's
# verifier was reached from outside, and now this — and each one cost a round trip to a machine
# that can compile.
#
# The check is deliberately approximate. It does not resolve types; it takes the classes a file
# imports from another module, and for each one asks whether every method the file appears to call
# by that name is declared public there. A same-named method on an unrelated local type will make
# this look, find a public declaration, and pass — so it under-reports rather than over-reports,
# which is the right direction for a rule that must never block a correct change.
# ---------------------------------------------------------------------------
# Java words that look like a method call and are not one.
NOT_A_METHOD = {
    "if", "for", "while", "switch", "catch", "return", "new", "this", "super", "try",
    "synchronized", "do", "else",
}


def check_cross_module_calls_are_public() -> None:
    declarations: dict[str, dict[str, bool]] = {}
    for path in java_sources():
        members: dict[str, bool] = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            # A member declaration in this codebase sits at exactly four spaces of indent, inside
            # the class body. Requiring that is what keeps `name.trim()` on line 12 of a method
            # from being recorded as a package-private member called "trim" — which it was, along
            # with eighteen other phantoms, when this rule was first written.
            match = re.match(
                r"^    (?:(public|protected|private) )?"
                r"(?:static |final |abstract |synchronized )*"
                r"[\w<>\[\],.? ]+ (\w+)\(", line)
            if not match:
                continue
            visibility, name = match.group(1), match.group(2)
            if name in NOT_A_METHOD:
                continue
            members[name] = members.get(name, False) or (visibility == "public")
        declarations[path.stem] = members

    violations = []
    for path in java_sources():
        module = module_of(path)
        if module is None:
            continue
        text = path.read_text(encoding="utf-8")

        for imported in re.finditer(
                rf"^import {re.escape(BASE_PACKAGE)}\.modules\.(\w+)\.(\w+);", text, re.M):
            other_module, other_class = imported.group(1), imported.group(2)
            if other_module == module or other_class not in declarations:
                continue

            # Which local names actually hold one of these. Without this the rule flagged
            # String.trim() against a private helper called trim, and a record accessor called
            # overtime() against an unrelated private method of the same name — five phantoms out
            # of five reports. A check that cries wolf gets switched off, so it now asks what the
            # variable is rather than only what the method is called.
            holders = set(re.findall(rf"\b{other_class}\s+(\w+)\s*[=;,)]", text))
            holders |= set(re.findall(rf"\b{other_class}\s+(\w+)\s*\)", text))
            if not holders:
                continue

            for holder in holders:
                for name in re.findall(rf"\b{re.escape(holder)}\.(\w+)\s*\(", text):
                    if name in NOT_A_METHOD:
                        continue
                    if name in declarations[other_class] and not declarations[other_class][name]:
                        violations.append((path, other_class, name))

    if violations:
        raise Failure(
            "A module calls a member that is not public in another module's class. javac will\n"
            "say the same thing a round trip later:\n"
            + "\n".join(
                f"  {path.relative_to(REPO)}  ->  {cls}.{name}(...) is not public"
                for path, cls, name in sorted(set(violations))))


def check_every_table_is_granted_to_the_app() -> None:
    """
    Every table a migration creates must be granted to dip_app.

    Added in August 2026 after two tables shipped without a grant. Both were correct in every way
    this file already checked — tenant column, row-level security, a WITH CHECK policy — and both
    answered every query with "permission denied for table". The harder half of the isolation story
    was enforced and the trivial half was not.

    The tests cannot catch this. Testcontainers runs as the schema owner, so a missing grant is
    invisible to the whole suite and appears only in a running deployment, as a 500 on the first
    request. That is precisely the class of defect a static check is for.
    """
    created: dict[str, Path] = {}
    granted: set[str] = set()
    for path in sorted(MIGRATIONS.glob("*.sql"), key=lambda p: int(re.search(r"V(\d+)", p.name).group(1))):
        sql = path.read_text(encoding="utf-8")
        for table in re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)", sql, re.IGNORECASE):
            created.setdefault(table, path)
        granted.update(re.findall(r"GRANT [^;]*? ON (\w+) TO dip_app", sql, re.IGNORECASE))

    missing = [(t, p) for t, p in sorted(created.items()) if t not in granted]
    if missing:
        raise Failure("\n".join(
            f"    {table} (created in {path.name}) — no GRANT ... TO dip_app"
            for table, path in missing)
            + "\n  The application owns no tables. Without a grant every query on this table "
              "fails with SQLSTATE 42501, which no test can see because tests run as the owner.")


def _declaration_annotations(source: str):
    """
    Annotation names at declaration level, and where each declaration ends.

    Yields ``("@", name, line)`` for an annotation written at depth zero and ``("end", None, line)``
    where a declaration finishes. Everything inside parentheses is skipped, which is what keeps a
    parameter list out of it — ``@Param`` legitimately repeats once per parameter and is not the
    thing being looked for.
    """
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    # Strings can hold parentheses — a JPQL query is full of them — so they go before depth counting.
    source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source)

    depth = 0
    line = 1
    index = 0
    while index < len(source):
        char = source[index]
        if char == "\n":
            line += 1
        elif char in "([":
            depth += 1
        elif char in ")]":
            depth -= 1
        elif depth == 0 and char == "@":
            match = re.match(r"@(\w+)", source[index:])
            if match:
                yield "@", match.group(1), line
                index += match.end()
                continue
        elif depth == 0 and char in ";{":
            yield "end", None, line
        index += 1


def check_no_repeated_annotation() -> None:
    """
    The same annotation twice on one declaration.

    Added in August 2026 after the second time an edit landed between an annotation and the thing
    it annotates. The comment rule above catches one shape of that mistake; it cannot see this one,
    because what separated them was a javadoc block and a second @Query, and two annotations in a
    row is ordinary Java right up until the compiler decides the type is not repeatable.

    The symptom is a compile failure rather than a silent defect, so this prevents a round trip
    rather than a bug. Working without a compiler, the round trip is the expensive part.
    """
    problems = []
    tests = sorted((REPO / "backend/src/test/java").rglob("*.java"))
    for path in java_sources() + tests:
        seen: dict[str, int] = {}
        for kind, name, line in _declaration_annotations(path.read_text(encoding="utf-8")):
            if kind == "end":
                seen = {}
            elif name in seen:
                problems.append(
                    f"    {path.relative_to(REPO)}:{line} — @{name} again, first at line "
                    f"{seen[name]}")
            else:
                seen[name] = line

    if problems:
        raise Failure("\n".join(problems)
                      + "\n  Something was almost certainly inserted between an annotation and "
                        "the declaration it belongs to.")


CHECKS = [
    ("common/ does not import modules/", check_common_does_not_import_modules),
    ("annotations are attached to a declaration", check_annotations_are_attached),
    ("no cross-module repository access", check_no_cross_module_persistence),
    ("cross-module calls reach only public members", check_cross_module_calls_are_public),
    ("tenant-owned tables have RLS policies", check_tenant_owned_tables_have_policies),
    ("no fixed-width CHAR columns", check_no_char_columns),
    ("every endpoint declares authorization", check_every_endpoint_declares_authorization),
    ("raw INSERTs name every required column", check_raw_inserts_name_required_columns),
    ("every table is granted to the application role", check_every_table_is_granted_to_the_app),
    ("no annotation is repeated on one declaration", check_no_repeated_annotation),
]


def main() -> int:
    failed = False
    for name, check in CHECKS:
        try:
            check()
        except Failure as failure:
            print(f"FAIL  {name}\n{failure}\n", file=sys.stderr)
            failed = True
        else:
            print(f"ok    {name}")

    if failed:
        print("Architecture check failed. See AGENTS.md for the rules.", file=sys.stderr)
        return 1

    print(f"\nAll {len(CHECKS)} architecture checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
