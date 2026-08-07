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


def tenant_owned_tables() -> dict[str, Path]:
    """Table names of entities extending TenantOwnedEntity."""
    tables: dict[str, Path] = {}
    for path in java_sources():
        source = path.read_text(encoding="utf-8")
        if "extends TenantOwnedEntity" not in source:
            continue
        match = TABLE_ANNOTATION.search(source)
        if not match:
            raise Failure(
                f"{path.relative_to(REPO)} extends TenantOwnedEntity but declares no "
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


CHECKS = [
    ("common/ does not import modules/", check_common_does_not_import_modules),
    ("no cross-module repository access", check_no_cross_module_persistence),
    ("tenant-owned tables have RLS policies", check_tenant_owned_tables_have_policies),
    ("no fixed-width CHAR columns", check_no_char_columns),
    ("every endpoint declares authorization", check_every_endpoint_declares_authorization),
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
