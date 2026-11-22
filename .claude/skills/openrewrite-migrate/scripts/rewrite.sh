#!/usr/bin/env bash
# Run OpenRewrite recipes against a Gradle build without touching any of its build files:
# the plugin, the recipe modules and a rewrite.yml with the requested recipes are injected
# through a throwaway init script.
#
#   rewrite.sh dry-run [--project-dir DIR] RECIPE [-o key=value ...] [RECIPE ...] [-- GRADLE_ARGS]
#                                         -> DIR/build/reports/rewrite/rewrite.patch
#   rewrite.sh run     [--project-dir DIR] RECIPE [-o key=value ...] [RECIPE ...] [-- GRADLE_ARGS]
#                                         -> applies the changes in place
#
# RECIPE is a fully qualified recipe name; -o sets one of its options (checked against the
# recipe's declared options). Several recipes run as one composite, in the order given.
# The module containing each recipe, a matching rewrite-recipe-bom and the Gradle plugin
# version come from the openrewrite-recipes skill's catalog.py.
#
# Project: --project-dir, else $REWRITE_PROJECT_DIR, else the git work tree containing the
#   current directory, else the current directory. It must hold a Gradle build.
# Catalog: $OPENREWRITE_CATALOG, else the openrewrite-recipes skill installed next to this one
#   (same skills directory, whether that's <project>/.claude/skills or ~/.claude/skills).
# Env: REWRITE_BOM_VERSION pins a BOM, REWRITE_KEEP_INIT=1 keeps the generated files.
set -euo pipefail

die() { echo "rewrite.sh: $*" >&2; exit 2; }
usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

# This script's real directory, following symlinks (the skill may be linked into a skills dir).
script_dir() {
  local src="${BASH_SOURCE[0]}" dir
  while [[ -L "$src" ]]; do
    dir="$(cd -P "$(dirname "$src")" && pwd)"
    src="$(readlink "$src")"
    [[ "$src" == /* ]] || src="$dir/$src"
  done
  cd -P "$(dirname "$src")" && pwd
}
skill_dir="$(dirname "$(script_dir)")"

find_catalog() {
  local c
  for c in "${OPENREWRITE_CATALOG:-}" "$(dirname "$skill_dir")/openrewrite-recipes/scripts/catalog.py"; do
    [[ -n "$c" && -f "$c" ]] && { echo "$c"; return; }
  done
  die "catalog.py not found: install the openrewrite-recipes skill next to openrewrite-migrate, or set OPENREWRITE_CATALOG"
}

mode="${1:-}"; (($#)) && shift
case "$mode" in
  dry-run) task=rewriteDryRun ;;
  run)     task=rewriteRun ;;
  -h|--help|"") usage ;;
  *) die "unknown mode '$mode' (dry-run or run)" ;;
esac

project="${REWRITE_PROJECT_DIR:-}"
args=(); extra=()
while (($#)); do
  case "$1" in
    --) shift; extra=("$@"); break ;;
    --project-dir) (($# >= 2)) || die "--project-dir needs a directory"; project="$2"; shift 2 ;;
    --project-dir=*) project="${1#*=}"; shift ;;
    *) args+=("$1"); shift ;;
  esac
done
((${#args[@]})) || die "no recipe given"

if [[ -z "$project" ]]; then
  project="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
project="$(cd -P "$project" 2>/dev/null && pwd)" || die "project directory not found"
compgen -G "$project/settings.gradle*" >/dev/null || compgen -G "$project/build.gradle*" >/dev/null \
  || die "$project is not a Gradle build (no settings.gradle[.kts] or build.gradle[.kts])"
if [[ -x "$project/gradlew" ]]; then
  gradle=("$project/gradlew")
elif command -v gradle >/dev/null; then
  gradle=(gradle)
else
  die "$project has no gradlew and gradle is not on PATH"
fi

command -v python3 >/dev/null || die "python3 is required"
catalog="$(find_catalog)"

bom_args=()
[[ -n "${REWRITE_BOM_VERSION:-}" ]] && bom_args=(--bom "$REWRITE_BOM_VERSION")

tmp="$(mktemp -d)"   # honours $TMPDIR, like the repo's other scripts
# Remove exactly what plan writes, then the directory: no rm -rf on a computed path.
cleanup() { rm -f -- "$tmp/rewrite.yml" "$tmp/rewrite.init.gradle"; rmdir -- "$tmp"; }
[[ -n "${REWRITE_KEEP_INIT:-}" ]] || trap cleanup EXIT

# Validates recipe names and options, writes $tmp/rewrite.yml and $tmp/rewrite.init.gradle.
python3 -I "$catalog" plan --out "$tmp" ${bom_args[@]+"${bom_args[@]}"} "${args[@]}" >&2

echo "OpenRewrite: $task in $project" >&2
[[ -n "${REWRITE_KEEP_INIT:-}" ]] && echo "  generated files: $tmp" >&2

patch="$project/build/reports/rewrite/rewrite.patch"
[[ "$task" == rewriteDryRun ]] && rm -f "$patch" "$patch.orig"   # never report a stale patch

# The rewrite plugin isn't configuration-cache compatible; parsing a whole build needs heap.
(cd "$project" && "${gradle[@]}" --init-script "$tmp/rewrite.init.gradle" --no-configuration-cache \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:+UseParallelGC" \
  "$task" ${extra[@]+"${extra[@]}"})

if [[ "$task" == rewriteDryRun ]]; then
  if [[ -s "$patch" ]]; then
    python3 -I "$catalog" tidy-patch "$patch" >&2
    if git -C "$project" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      if git -C "$project" apply --check "$patch" >&2; then
        echo "git apply --check: OK" >&2
      else
        echo "git apply --check FAILED (errors above)" >&2
      fi
    fi
    echo "patch: $patch" >&2
  else
    echo "no changes proposed" >&2
  fi
fi
