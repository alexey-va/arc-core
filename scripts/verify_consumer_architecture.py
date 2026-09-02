#!/usr/bin/env python3
"""Verify that a sibling plugin composes arc-core instead of rebuilding it.

The contract is intentionally dependency- and source-based.  A new plugin
declares the infrastructure it needs in ``arc-core-consumer.toml``; this
verifier then checks the matching Maven modules, canonical API evidence, and a
small set of high-signal forbidden local implementations.
"""

from __future__ import annotations

import argparse
import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


SCHEMA_VERSION = 1
PUBLIC_GROUP = "ru.ruscrafting.arc"
CORE_MODULES = {
    "arc-core",
    "arc-core-ai",
    "arc-core-integration-testing",
    "arc-core-logging",
    "arc-core-metrics",
    "arc-core-menu",
    "arc-core-ops",
    "arc-core-ops-paper",
    "arc-core-ops-velocity",
    "arc-core-paper",
    "arc-core-paper-menu",
    "arc-core-paper-testing",
    "arc-core-redis",
    "arc-core-sql",
    "arc-core-testing",
    "arc-core-velocity",
}

PLATFORM_MODULES = {
    "paper": {
        "runtime": {"arc-core", "arc-core-logging", "arc-core-paper"},
        "testing": {"arc-core-paper-testing"},
    },
    "velocity": {
        "runtime": {"arc-core", "arc-core-logging", "arc-core-velocity"},
        "testing": set(),
    },
}

REQUIRED_CAPABILITIES = {
    "paper": {"runtime", "health", "localized-text", "logging", "paper-testing"},
    "velocity": {"runtime", "health", "logging"},
}

CAPABILITY_MODULES = {
    "ai": {"arc-core-ai"},
    "chunk-tickets": {"arc-core-paper"},
    "deterministic-testing": {"arc-core-testing"},
    "integration-testing": {"arc-core-integration-testing"},
    "metrics": {"arc-core-metrics"},
    "one-time-use": {"arc-core-sql", "arc-core-integration-testing"},
    "paper-audience": {"arc-core-paper", "arc-core-paper-testing"},
    "paper-menu": {"arc-core-menu", "arc-core-paper-menu"},
    "paper-teleport": {"arc-core-paper", "arc-core-paper-testing"},
    "redis-networking": {"arc-core-redis", "arc-core-integration-testing"},
    "sql": {"arc-core-sql", "arc-core-integration-testing"},
}

PLATFORM_ONLY_CAPABILITIES = {
    "paper": {
        "backend-transfer",
        "chunk-tickets",
        "paper-audience",
        "paper-menu",
        "paper-teleport",
        "paper-testing",
        "player-state",
        "scoped-teleport",
    },
    "velocity": set(),
}

KNOWN_CAPABILITIES = {
    "ai",
    "atomic-files",
    "backend-transfer",
    "chunk-tickets",
    "coalesced-writes",
    "durable-recovery",
    "deterministic-testing",
    "health",
    "integration-testing",
    "localized-text",
    "logging",
    "metrics",
    "one-time-use",
    "paper-audience",
    "paper-menu",
    "paper-teleport",
    "paper-testing",
    "player-state",
    "redis-networking",
    "runtime",
    "scheduling",
    "scoped-teleport",
    "sql",
    "structured-debug",
}

CAPABILITY_EVIDENCE = {
    "ai": (("main", re.compile(r"\bru\.arc\.ai\.")),),
    "atomic-files": (("main", re.compile(r"\bAtomicFileStore\b")),),
    "backend-transfer": (("main", re.compile(r"\b(?:BackendTransfer|BungeeBackendTransfer)\b")),),
    "chunk-tickets": (("main", re.compile(r"\bPaperChunkTicketRegistry\b")),),
    "coalesced-writes": (("main", re.compile(r"\bCoalescingAsyncWriter\b")),),
    "durable-recovery": (("main", re.compile(r"\b(?:DurableRecoveryWorkflow|DurableRecordJournal)\b")),),
    "deterministic-testing": (("test", re.compile(r"\b(?:DeterministicClock|ControlledExecutor|FailureInjector)\b")),),
    "health": (
        ("main", re.compile(r"\.registerHealth\s*\(")),
        ("main", re.compile(r"\.reportHealthEvery\s*\(")),
    ),
    "integration-testing": (("integration", re.compile(r"\b(?:RedisTestService|MySqlTestService)\b")),),
    "localized-text": (("main", re.compile(r"\bLocalizedMiniMessage\b")),),
    "logging": (("main", re.compile(r"\bArcLogging\b")),),
    "metrics": (("main", re.compile(r"\bArcMetricsRuntime\b")),),
    "one-time-use": (
        ("main", re.compile(r"\bru\.arc\.onetime\.")),
        ("main", re.compile(r"\bMySqlOneTimeUseLedger\b")),
        ("integration", re.compile(r"\bMySqlTestService\b")),
    ),
    "paper-audience": (
        ("main", re.compile(r"\bPaperAudienceEffects\b")),
        ("test", re.compile(r"\bRecordingPaperAudienceEffects\b")),
    ),
    "paper-menu": (("main", re.compile(r"\b(?:PaperMenuService|MenuLayoutParser)\b")),),
    "paper-teleport": (
        ("main", re.compile(r"\bPaperTeleportExecutor\b")),
        ("test", re.compile(r"\bRecordingPaperTeleportExecutor\b")),
    ),
    "paper-testing": (("test", re.compile(r"\bMockBukkitTestRuntime\b")),),
    "player-state": (("main", re.compile(r"\bPaperPlayerStateService\b")),),
    "redis-networking": (
        ("main", re.compile(r"\b(?:ValidatedRedisTopic|RedisRequestReplyChannel|RedisPresenceDirectory)\b")),
        ("integration", re.compile(r"\bRedisTestService\b")),
    ),
    "scheduling": (("main", re.compile(r"\b(?:Tasks\.|LifecycleTaskScope\b|\.tasks\.)")),),
    "scoped-teleport": (("main", re.compile(r"\bScopedTeleportAuthorizer\b")),),
    "sql": (
        ("main", re.compile(r"\bru\.arc\.sql\.")),
        ("integration", re.compile(r"\bMySqlTestService\b")),
    ),
    "structured-debug": (("main", re.compile(r"\bStructuredDebugLine\b")),),
}

PLATFORM_RUNTIME_EVIDENCE = {
    "paper": (
        re.compile(r"\bPaperPluginRuntime\b"),
        re.compile(r"\bPaperArcRuntime\.installScheduling\s*\("),
    ),
    "velocity": (
        re.compile(r"\bPluginRuntime\b"),
        re.compile(r"\bVelocityArcRuntime\.installScheduling\s*\("),
    ),
}


@dataclass(frozen=True)
class Violation:
    rule: str
    message: str
    path: Path | None = None
    line: int | None = None

    def render(self, root: Path) -> str:
        location = "manifest"
        if self.path is not None:
            try:
                location = str(self.path.relative_to(root))
            except ValueError:
                location = str(self.path)
            if self.line is not None:
                location = f"{location}:{self.line}"
        return f"CONSUMER_CONTRACT_ERROR rule={self.rule} location={location} message={self.message}"


@dataclass(frozen=True)
class Dependency:
    configuration: str
    module: str
    version: str
    path: Path
    line: int


@dataclass(frozen=True)
class ForbiddenRule:
    name: str
    scopes: frozenset[str]
    pattern: re.Pattern[str]
    remediation: str


FORBIDDEN_RULES = (
    ForbiddenRule(
        "direct-platform-scheduler",
        frozenset({"main"}),
        re.compile(r"(?:Bukkit\.getScheduler\s*\(|(?:plugin\.)?server\.scheduler\.|proxyServer\.scheduler\.)"),
        "use PaperArcRuntime/VelocityArcRuntime plus Tasks or the runtime LifecycleTaskScope",
    ),
    ForbiddenRule(
        "direct-mockbukkit",
        frozenset({"gradle", "test", "integration"}),
        re.compile(r"(?:org\.mockbukkit|mockbukkit:mockbukkit)"),
        "depend on arc-core-paper-testing and use MockBukkitTestRuntime",
    ),
    ForbiddenRule(
        "direct-testcontainers",
        frozenset({"gradle", "test", "integration"}),
        re.compile(r"org\.testcontainers"),
        "depend on arc-core-integration-testing and use RedisTestService/MySqlTestService",
    ),
    ForbiddenRule(
        "local-sql-pool",
        frozenset({"main"}),
        re.compile(r"\b(?:HikariConfig|HikariDataSource)\s*\("),
        "use the arc-core-sql runtime and its connection/executor lifecycle",
    ),
    ForbiddenRule(
        "local-atomic-file",
        frozenset({"main"}),
        re.compile(r"\bStandardCopyOption\.ATOMIC_MOVE\b"),
        "use AtomicFileStore",
    ),
    ForbiddenRule(
        "local-minimessage-engine",
        frozenset({"main"}),
        re.compile(r"\bMiniMessage\.miniMessage\s*\("),
        "use LocalizedMiniMessage and non-parsing Component placeholders",
    ),
    ForbiddenRule(
        "raw-redis-pubsub",
        frozenset({"main"}),
        re.compile(r"\bJedisPubSub\b"),
        "use ValidatedRedisTopic, RedisRequestReplyChannel, or RedisPresenceDirectory",
    ),
    ForbiddenRule(
        "raw-bungee-transfer",
        frozenset({"main"}),
        re.compile(r"writeUTF\s*\(\s*\"Connect\"|\"BungeeCord\""),
        "use BackendTransfer/BungeeBackendTransfer",
    ),
    ForbiddenRule(
        "local-one-time-ledger",
        frozenset({"main"}),
        re.compile(r"class\s+\w*(?:Voucher|Coupon|Ticket|Book)\w*(?:Ledger|RedemptionStore)\b"),
        "use OneTimeUseLedger with a stable purpose and shared arc_one_time_uses table",
    ),
)

DEPENDENCY_RE = re.compile(
    r"(?P<configuration>[A-Za-z][A-Za-z0-9]*|\"[A-Za-z][A-Za-z0-9]*\")\s*\(\s*"
    r"\"ru\.ruscrafting\.arc:(?P<module>arc-core(?:-[a-z0-9-]+)?):(?P<version>[^\"$]+)\""
)


def relevant_files(root: Path) -> dict[str, list[Path]]:
    result = {"gradle": [], "main": [], "test": [], "integration": []}
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in {".git", ".gradle", "build", "out"} for part in relative.parts):
            continue
        if path.name.endswith((".gradle", ".gradle.kts")):
            result["gradle"].append(path)
        if path.suffix != ".kt" or "src" not in relative.parts:
            continue
        parts = relative.parts
        if "integrationTest" in parts:
            result["integration"].append(path)
        elif "test" in parts:
            result["test"].append(path)
        elif "main" in parts:
            result["main"].append(path)
    for files in result.values():
        files.sort()
    return result


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def mask_comments(text: str) -> str:
    """Replace Kotlin/Gradle comments with spaces while preserving strings and line offsets."""
    masked = list(text)
    state = "code"
    index = 0
    while index < len(text):
        current = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "code":
            if current == '"':
                state = "string"
            elif current == "/" and following == "/":
                masked[index] = masked[index + 1] = " "
                state = "line-comment"
                index += 1
            elif current == "/" and following == "*":
                masked[index] = masked[index + 1] = " "
                state = "block-comment"
                index += 1
        elif state == "string":
            if current == "\\":
                index += 1
            elif current == '"':
                state = "code"
        elif state == "line-comment":
            if current == "\n":
                state = "code"
            else:
                masked[index] = " "
        elif state == "block-comment":
            if current == "*" and following == "/":
                masked[index] = masked[index + 1] = " "
                state = "code"
                index += 1
            elif current != "\n":
                masked[index] = " "
        index += 1
    return "".join(masked)


def line_number(text: str, start: int) -> int:
    return text.count("\n", 0, start) + 1


def parse_dependencies(files: Iterable[Path]) -> list[Dependency]:
    dependencies: list[Dependency] = []
    for path in files:
        text = mask_comments(read_text(path))
        for match in DEPENDENCY_RE.finditer(text):
            dependencies.append(
                Dependency(
                    configuration=match.group("configuration").strip('"'),
                    module=match.group("module"),
                    version=match.group("version"),
                    path=path,
                    line=line_number(text, match.start()),
                )
            )
    return dependencies


def combined_text(files: Iterable[Path]) -> str:
    return "\n".join(mask_comments(read_text(path)) for path in files)


def load_manifest(path: Path) -> tuple[dict[str, object] | None, list[Violation]]:
    if not path.is_file():
        return None, [Violation("manifest-missing", f"create {path.name} from the arc-core template")]
    try:
        data = tomllib.loads(read_text(path))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as failure:
        return None, [Violation("manifest-invalid", f"cannot parse {path.name}: {failure}", path)]
    return data, []


def verify(root: Path, manifest_path: Path) -> list[Violation]:
    violations: list[Violation] = []
    manifest, load_violations = load_manifest(manifest_path)
    violations.extend(load_violations)
    if manifest is None:
        return violations

    allowed_fields = {"schema", "platform", "core_version", "capabilities"}
    unknown_fields = sorted(set(manifest) - allowed_fields)
    if unknown_fields:
        violations.append(Violation("manifest-fields", f"unknown fields: {', '.join(unknown_fields)}", manifest_path))

    schema = manifest.get("schema")
    if schema != SCHEMA_VERSION:
        violations.append(Violation("manifest-schema", f"schema must be {SCHEMA_VERSION}, got {schema!r}", manifest_path))

    platform = manifest.get("platform")
    if platform not in PLATFORM_MODULES:
        violations.append(Violation("manifest-platform", "platform must be 'paper' or 'velocity'", manifest_path))
        return violations

    core_version = manifest.get("core_version")
    if not isinstance(core_version, str) or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", core_version):
        violations.append(Violation("manifest-version", "core_version must be one immutable semantic version", manifest_path))
        core_version = ""

    raw_capabilities = manifest.get("capabilities")
    if not isinstance(raw_capabilities, list) or not all(isinstance(value, str) for value in raw_capabilities):
        violations.append(Violation("manifest-capabilities", "capabilities must be an array of strings", manifest_path))
        capabilities: set[str] = set()
    else:
        capabilities = set(raw_capabilities)
        if len(capabilities) != len(raw_capabilities):
            violations.append(Violation("manifest-capabilities", "capabilities must not contain duplicates", manifest_path))
        unknown = sorted(capabilities - KNOWN_CAPABILITIES)
        if unknown:
            violations.append(Violation("manifest-capabilities", f"unknown capabilities: {', '.join(unknown)}", manifest_path))

    missing_capabilities = sorted(REQUIRED_CAPABILITIES[platform] - capabilities)
    if missing_capabilities:
        violations.append(
            Violation(
                "required-capabilities",
                f"{platform} plugins must declare: {', '.join(missing_capabilities)}",
                manifest_path,
            )
        )

    other_platform = "velocity" if platform == "paper" else "paper"
    invalid_platform_capabilities = sorted(capabilities & PLATFORM_ONLY_CAPABILITIES[other_platform])
    if invalid_platform_capabilities:
        violations.append(
            Violation(
                "capability-platform",
                f"{platform} plugins cannot declare: {', '.join(invalid_platform_capabilities)}",
                manifest_path,
            )
        )

    files = relevant_files(root)
    dependencies = parse_dependencies(files["gradle"])
    by_module: dict[str, list[Dependency]] = {}
    for dependency in dependencies:
        if dependency.module in CORE_MODULES:
            by_module.setdefault(dependency.module, []).append(dependency)
        if core_version and dependency.version != core_version:
            violations.append(
                Violation(
                    "core-version-drift",
                    f"{dependency.module} uses {dependency.version}; every arc-core artifact must use {core_version}",
                    dependency.path,
                    dependency.line,
                )
            )

    required_runtime_modules = set(PLATFORM_MODULES[platform]["runtime"])
    required_test_modules = set(PLATFORM_MODULES[platform]["testing"])
    for capability in capabilities:
        modules = CAPABILITY_MODULES.get(capability, set())
        for module in modules:
            if module.endswith("-testing"):
                required_test_modules.add(module)
            else:
                required_runtime_modules.add(module)

    for module in sorted(required_runtime_modules | required_test_modules):
        found = by_module.get(module, [])
        if not found:
            violations.append(Violation("required-module", f"missing {PUBLIC_GROUP}:{module}:{core_version}"))
            continue
        requires_test_scope = module in required_test_modules
        if requires_test_scope and not any("test" in dependency.configuration.lower() for dependency in found):
            dependency = found[0]
            violations.append(
                Violation("dependency-scope", f"{module} must be test-only", dependency.path, dependency.line)
            )
        if not requires_test_scope and not any("test" not in dependency.configuration.lower() for dependency in found):
            dependency = found[0]
            violations.append(
                Violation("dependency-scope", f"{module} must be a runtime dependency", dependency.path, dependency.line)
            )

    source_text = {scope: combined_text(paths) for scope, paths in files.items() if scope != "gradle"}
    for pattern in PLATFORM_RUNTIME_EVIDENCE[platform]:
        if not pattern.search(source_text["main"]):
            violations.append(
                Violation("runtime-composition", f"missing canonical {platform} runtime evidence: {pattern.pattern}")
            )

    for capability in sorted(capabilities):
        for scope, pattern in CAPABILITY_EVIDENCE.get(capability, ()):
            if not pattern.search(source_text[scope]):
                violations.append(
                    Violation(
                        "capability-evidence",
                        f"{capability} is declared but {scope} sources do not use {pattern.pattern}",
                    )
                )

    for rule in FORBIDDEN_RULES:
        for scope in sorted(rule.scopes):
            for path in files[scope]:
                text = mask_comments(read_text(path))
                for match in rule.pattern.finditer(text):
                    violations.append(
                        Violation(rule.name, rule.remediation, path, line_number(text, match.start()))
                    )

    return violations


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", nargs="?", default=".", help="consumer plugin repository root")
    parser.add_argument(
        "--manifest",
        default="arc-core-consumer.toml",
        help="manifest path relative to the repository root",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    root = Path(args.repository).expanduser().resolve()
    if not root.is_dir():
        print(f"CONSUMER_CONTRACT_ERROR rule=repository location={root} message=repository root does not exist")
        return 2
    manifest_path = (root / args.manifest).resolve()
    violations = verify(root, manifest_path)
    if violations:
        for violation in violations:
            print(violation.render(root))
        print(f"CONSUMER_CONTRACT_SUMMARY status=failed violations={len(violations)}")
        return 1

    manifest = tomllib.loads(read_text(manifest_path))
    dependencies = parse_dependencies(relevant_files(root)["gradle"])
    modules = sorted({dependency.module for dependency in dependencies if dependency.module in CORE_MODULES})
    capabilities = sorted(str(value) for value in manifest["capabilities"])
    print(
        "CONSUMER_CONTRACT_SUMMARY status=ok "
        f"platform={manifest['platform']} version={manifest['core_version']} "
        f"capabilities={','.join(capabilities)} modules={','.join(modules)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
