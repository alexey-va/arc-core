#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_URL="${REPOSILITE_PUBLISH_URL:-https://repo.rus-crafting.ru/grocermc}"
AUTH_URL="${REPOSILITE_AUTH_URL:-https://repo.rus-crafting.ru/api/auth/me}"
KEYCHAIN_SERVICE="ru.ruscrafting.reposilite.publisher"
DEFAULT_USERNAME="arc-publisher"
PUBLICATION_GROUP="ru.ruscrafting.arc"
EXPECTED_JAVA_RELEASE=25
EXPECTED_CLASS_MAJOR=69
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage: publish-release.sh VERSION [--dry-run]

Builds and tests every arc-core module, stages complete Maven publications,
then immutably uploads them to RusCrafting Reposilite. Existing identical
files are skipped so an interrupted release can resume; conflicting files
fail closed before any upload.

Credentials:
  REPOSILITE_PUBLISH_USERNAME  Defaults to arc-publisher
  REPOSILITE_PUBLISH_PASSWORD  Secret override for CI/Linux
  REPOSILITE_AUTH_URL          Read-only credential check endpoint

On macOS the password is otherwise read from Keychain service:
  ru.ruscrafting.reposilite.publisher
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || { usage >&2; exit 2; }
VERSION="$1"
if [[ $# -eq 2 ]]; then
  [[ "$2" == "--dry-run" ]] || { usage >&2; exit 2; }
  DRY_RUN=true
fi

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] ||
  die "VERSION must be semantic and must not include the leading v"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STAGING_ROOT="$ROOT_DIR/build/release-repository"
GROUP_PATH="${PUBLICATION_GROUP//.//}"

cd "$ROOT_DIR"
./gradlew clean testAll stageRelease \
  -PreleaseVersion="$VERSION" \
  -PpublicationGroup="$PUBLICATION_GROUP" \
  --no-daemon

module_dirs=("$STAGING_ROOT/$GROUP_PATH"/*)
[[ -d "${module_dirs[0]}" ]] || die "Release staging did not produce any Maven modules"

release_files=()
for module_dir in "${module_dirs[@]}"; do
  [[ -d "$module_dir" ]] || continue
  module="$(basename "$module_dir")"
  module_dir="$STAGING_ROOT/$GROUP_PATH/$module/$VERSION"
  [[ -d "$module_dir" ]] || die "Staged publication is missing module $module"
  for expected in \
    "$module-$VERSION.jar" \
    "$module-$VERSION-sources.jar" \
    "$module-$VERSION.pom" \
    "$module-$VERSION.module"; do
    [[ -f "$module_dir/$expected" ]] || die "Staged publication is missing $module/$VERSION/$expected"
    release_files+=("$module_dir/$expected")
  done
done

module_count="$(find "$STAGING_ROOT/$GROUP_PATH" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"

for pom in "$STAGING_ROOT/$GROUP_PATH"/*/"$VERSION"/*.pom; do
  ! grep -q '<groupId>ru\.arc</groupId>\|<version>1\.0-SNAPSHOT</version>' "$pom" ||
    die "Staged POM contains an unpublished composite-build coordinate: $pom"
  grep -q '<name>Apache License, Version 2.0</name>' "$pom" ||
    die "Staged POM is missing Apache-2.0 metadata: $pom"
  grep -q '<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>' "$pom" ||
    die "Staged POM is missing the Apache-2.0 license URL: $pom"
done

for jar in "$STAGING_ROOT/$GROUP_PATH"/*/"$VERSION"/*.jar; do
  jar tf "$jar" | grep -qx 'META-INF/LICENSE-arc-core.txt' ||
    die "Staged JAR is missing META-INF/LICENSE-arc-core.txt: $jar"
done

major_version() {
  javap -verbose -classpath "$1" "$2" |
    awk '/major version:/ { print $3; exit }'
}

core_major="$(major_version "$STAGING_ROOT/$GROUP_PATH/arc-core/$VERSION/arc-core-$VERSION.jar" ru.arc.persistence.AtomicFileStore)"
testing_major="$(major_version "$STAGING_ROOT/$GROUP_PATH/arc-core-paper-testing/$VERSION/arc-core-paper-testing-$VERSION.jar" ru.arc.paper.testing.MockBukkitTestRuntime)"
[[ "$core_major" == "$EXPECTED_CLASS_MAJOR" ]] ||
  die "arc-core release bytecode must target Java $EXPECTED_JAVA_RELEASE (major $EXPECTED_CLASS_MAJOR), found $core_major"
[[ "$testing_major" == "$EXPECTED_CLASS_MAJOR" ]] ||
  die "arc-core-paper-testing release bytecode must target Java $EXPECTED_JAVA_RELEASE (major $EXPECTED_CLASS_MAJOR), found $testing_major"

for module_dir in "$STAGING_ROOT/$GROUP_PATH"/*-testing; do
  [[ -d "$module_dir/$VERSION" ]] || continue
  module="$(basename "$module_dir")"
  jar="$module_dir/$VERSION/$module-$VERSION.jar"
  first_class="$(jar tf "$jar" | awk '/\.class$/ { sub(/\.class$/, ""); gsub(/\//, "."); print; exit }')"
  [[ -n "$first_class" ]] || die "$module release JAR does not contain a class"
  module_major="$(major_version "$jar" "$first_class")"
  [[ "$module_major" == "$EXPECTED_CLASS_MAJOR" ]] ||
    die "$module release bytecode must target Java $EXPECTED_JAVA_RELEASE (major $EXPECTED_CLASS_MAJOR), found $module_major"
done

work_dir="$(mktemp -d)"
cleanup() {
  unset password
  rm -rf "$work_dir"
}
trap cleanup EXIT
chmod 700 "$work_dir"

missing_files=()
for file in "${release_files[@]}"; do
  relative="${file#"$STAGING_ROOT/"}"
  url="${REPOSITORY_URL%/}/$relative"
  remote_file="$work_dir/remote"
  status="$(curl -sS -o "$remote_file" -w '%{http_code}' --max-time 30 "$url")" ||
    die "Could not inspect public artifact $relative"
  case "$status" in
    200)
      local_sha="$(shasum -a 256 "$file" | awk '{print $1}')"
      remote_sha="$(shasum -a 256 "$remote_file" | awk '{print $1}')"
      [[ "$local_sha" == "$remote_sha" ]] || die "Immutable coordinate conflict at $relative"
      ;;
    404) missing_files+=("$file") ;;
    *) die "Could not inspect public artifact $relative (HTTP $status)" ;;
  esac
done

printf 'Release: %s:%s (%s modules, %s files, %s missing)\n' \
  "$PUBLICATION_GROUP" "$VERSION" "$module_count" "${#release_files[@]}" "${#missing_files[@]}"

if $DRY_RUN; then
  printf 'Dry run: no credential lookup or upload performed.\n'
  exit 0
fi

username="${REPOSILITE_PUBLISH_USERNAME:-$DEFAULT_USERNAME}"
password="${REPOSILITE_PUBLISH_PASSWORD:-}"
if [[ -z "$password" ]] && command -v security >/dev/null 2>&1; then
  password="$(security find-generic-password -a "$username" -s "$KEYCHAIN_SERVICE" -w 2>/dev/null || true)"
fi
[[ -n "$password" ]] || die "Publisher credential not found in env or macOS Keychain"

netrc_file="$work_dir/netrc"
printf 'machine repo.rus-crafting.ru\nlogin %s\npassword %s\n' "$username" "$password" > "$netrc_file"
chmod 600 "$netrc_file"
unset password

curl -fsS --max-time 30 --netrc-file "$netrc_file" "$AUTH_URL" -o /dev/null ||
  die "Publisher credential rejected by Reposilite"

if [[ ${#missing_files[@]} -gt 0 ]]; then
  for file in "${missing_files[@]}"; do
    relative="${file#"$STAGING_ROOT/"}"
    url="${REPOSITORY_URL%/}/$relative"
    curl -fsS --max-time 120 --netrc-file "$netrc_file" --upload-file "$file" "$url"
  done
fi

for file in "${release_files[@]}"; do
  relative="${file#"$STAGING_ROOT/"}"
  url="${REPOSITORY_URL%/}/$relative"
  remote_file="$work_dir/verified"
  curl -fsS --max-time 120 "$url" -o "$remote_file"
  local_sha="$(shasum -a 256 "$file" | awk '{print $1}')"
  remote_sha="$(shasum -a 256 "$remote_file" | awk '{print $1}')"
  [[ "$local_sha" == "$remote_sha" ]] || die "Public readback checksum mismatch at $relative"
done

printf 'Published and verified %s immutable files for %s:%s\n' \
  "${#release_files[@]}" "$PUBLICATION_GROUP" "$VERSION"
