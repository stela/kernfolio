---
name: openrewrite-migrate
description: >-
  Carry out an upgrade or migration with OpenRewrite and take it through to a green build:
  dry run, hunk-by-hunk review, apply, clean up what the recipe got wrong, compile, test, and
  do by hand what the recipe can't. Use for framework upgrades (Spring Boot, Spring Framework,
  Spring Cloud, Spring Security, Spring Data, Hibernate, Jackson 2 → 3, Testcontainers 2,
  JUnit, Mockito), Java toolchain and Kotlin upgrades, Gradle 8 → 9 build-script migrations,
  and version bumps of third-party libraries or Gradle plugins, alone or several in one batch.
  Takes fully qualified recipe names, with options for the generic ones
  (UpgradeDependencyVersion, UpgradePluginVersion). Run /openrewrite-recipes first if the
  exact name isn't known.
argument-hint: "<fully.qualified.Recipe> [-o key=value ...] [more recipes ...]"
allowed-tools: Bash(${CLAUDE_SKILL_DIR}/scripts/rewrite.sh:*), Bash(python3 -I ${CLAUDE_SKILL_DIR}/../openrewrite-recipes/scripts/catalog.py:*)
---

# Migrate with OpenRewrite

Recipe(s): `$ARGUMENTS`

This skill needs the **openrewrite-recipes** skill installed in the same skills directory,
whether that's `<project>/.claude/skills` or `~/.claude/skills`. Its `catalog.py` resolves
recipes to modules. Set `OPENREWRITE_CATALOG` if it lives elsewhere.

`${CLAUDE_SKILL_DIR}/scripts/rewrite.sh` runs recipes **without changing any build file**:

1. The catalog looks up the module that ships each recipe, together with the newest fully
   published `rewrite-recipe-bom` and the Gradle plugin version that BOM pins.
2. It checks every `-o key=value` against the recipe's declared options, including that all
   required ones are given.
3. It writes a throwaway `rewrite.yml` (one composite recipe with the requested recipes, in
   order) and a Gradle init script that applies the plugin to the root project.
4. It runs the project's `gradlew`.

The project is the git work tree containing the current directory, or `--project-dir DIR` /
`REWRITE_PROJECT_DIR`. It must be a Gradle build.

```bash
${CLAUDE_SKILL_DIR}/scripts/rewrite.sh dry-run RECIPE [-o key=value ...] [RECIPE ...]   # -> <project>/build/reports/rewrite/rewrite.patch
${CLAUDE_SKILL_DIR}/scripts/rewrite.sh run     RECIPE [-o key=value ...] [RECIPE ...]   # applies in place
#   --project-dir DIR    another project    ... -- --info    extra Gradle args after "--"
#   REWRITE_BOM_VERSION=3.37.0  pin the BOM    REWRITE_KEEP_INIT=1  keep the generated files for inspection

# e.g. several library bumps in one batch:
${CLAUDE_SKILL_DIR}/scripts/rewrite.sh dry-run \
  org.openrewrite.gradle.UpgradeDependencyVersion -o groupId=io.mockk -o artifactId=mockk -o newVersion=latest.release \
  org.openrewrite.gradle.plugins.UpgradePluginVersion -o pluginIdPattern=gg.jte.gradle -o newVersion=3.x
```

A run parses the whole build (Kotlin sources, `*.gradle.kts`, YAML, properties), which takes
about a minute. Then the recipes run. Use a 10-minute Bash timeout.

After a dry run, the script tidies the patch so `git apply` accepts it. It keeps the original
as `rewrite.patch.orig` and reports what it dropped:
- copies of the same file's change that OpenRewrite emits once per Gradle project (seen with
  the wrapper);
- binary changes, which can't be applied from this patch;
- content-less sections for files it only re-encoded (CRLF CSV fixtures).

It then runs `git apply --check`.

## 1. Preconditions

- If a recipe name is missing or not fully qualified, run
  `python3 -I ${CLAUDE_SKILL_DIR}/../openrewrite-recipes/scripts/catalog.py show <what you have>`.
  It suggests close matches. For "upgrade X" requests, use one batched
  `catalog.py upgrades "x" "y" ...` call. If it's still ambiguous, ask.
- `git status` must be clean, so the recipe's changes can be told apart from the user's.
  Otherwise stop and ask. On the default branch, create a branch (`openrewrite/<short-name>`)
  before writing anything.
- Read each recipe first (`catalog.py show A B ...`). Compare its target with the versions the
  build uses now: `settings.gradle*`, every `build.gradle*`, `gradle.properties`,
  `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`. If the project is
  already at or past the target, say so. The recipe will mostly add noise, and it may pin
  *older* versions (see step 3).
- **Gradle wrapper:** don't use `UpdateGradleWrapper`, alone or as part of `MigrateToGradle9`.
  It runs once per project, and on this repo it rewrote `gradlew`'s header comments wrongly
  and blanked the template commit hash. Use `./gradlew wrapper --gradle-version X`, then
  `./gradlew wrapper` again so the new version regenerates the scripts and the jar. After a
  `MigrateToGradle9` dry run, drop the wrapper files from the patch
  (`git apply --exclude='gradle/wrapper/*' --exclude=gradlew --exclude=gradlew.bat`).

## 2. Dry run

```bash
${CLAUDE_SKILL_DIR}/scripts/rewrite.sh dry-run RECIPE [...]
```

Then read **all** of `<project>/build/reports/rewrite/rewrite.patch`. Each file's first hunk
header names the composite (`local.openrewrite.RequestedRecipes`).
`build/reports/rewrite/datatables/<timestamp>/org.openrewrite.table.SourcesFileResults.csv` names the
actual sub-recipe behind each file. Also look for parse failures: a file OpenRewrite couldn't
parse is skipped silently. Re-run with `-- --info` and grep for `ParseExceptionResult`, or
check for a `ParseFailures` data table.

## 3. Review hunk by hunk

Sort every hunk into keep, fix or drop. The recurring problems, all seen on this repo:

- **Search markers** `/*~~(message)~~>*/` are recipe diagnostics, not code. One example was
  `GradleProject has no configuration named 'runtimeOnly'` on the root `build.gradle.kts`,
  which has no Java plugin. Never commit them. Read the message, then remove the marker.
- **Explicit versions on BOM-managed artifacts** (`spring-boot-starter-webmvc-test:4.0.10`).
  The Spring Boot plugin manages these, and an explicit version overrides the BOM. Usually
  that's a **downgrade**. Drop the version. Keep explicit versions only where the build
  already pins (Spring Cloud, jte, mockk, wiremock, ...), and check that the new version
  exists and is newer than the current one.
- **Dependency swaps**: check each one against what's actually used. A swap can take away a
  transitive dependency the code relies on. In the other direction, a recipe sometimes adds a
  starter "just in case" (e.g. `spring-boot-starter-restclient` in a module without a REST
  client). Search the module's sources before keeping it.
- **Redundant annotations or code**: e.g. `@AutoConfigureMockMvc` added to tests that build
  `MockMvc` themselves with `MockMvcBuilders.webAppContextSetup`. Drop it, or switch the test
  to the injected `MockMvc` in a separate, deliberate change. Don't keep both.
- **Kotlin translations of Java-shaped changes**: generic arity changes (`Foo<*>` → `Foo`),
  nested type references (`Outer.Nested.CONST` → `Outer.CONST`), static imports and named
  arguments. These are where Kotlin output goes wrong most often. Check them against the
  library's actual API: `./gradlew :web:dependencies` to get the version, then the jar or its
  docs. Don't guess.
- **Comments and formatting**: OpenRewrite keeps them, but it may reorder imports or touch
  whitespace. Keep diffs minimal. Revert hunks that are purely cosmetic.
- **Project rules still apply** (CLAUDE.md): Spring Data JDBC, no JPA; Jackson 3 under
  `tools.jackson` with annotations in `com.fasterxml.jackson.annotation`; no hardcoded
  currency; CSP/nonce rules in templates. A recipe written for typical Java projects may
  "fix" things in the wrong direction, e.g. adding JPA starters or Jackson 2 coordinates.
  Drop those changes.

Show the user a short summary before applying: files touched, what you'll keep, fix and drop,
and why. If the recipe proposes nothing useful, say so and stop.

## 4. Apply

The patch already holds the reviewed result, so apply it instead of running the recipe again:

```bash
git apply --index build/reports/rewrite/rewrite.patch          # add --exclude=<glob> for dropped files
```

Then make the fixes from step 3 with Edit. Use `rewrite.sh run` instead only when you'll take
the patch as a whole.

## 5. Make it build, then finish the job

1. `./gradlew :web:compileKotlin :web:compileTestKotlin` and the twins'
   `./gradlew :digital-twins:yfinance-fake:compileTestKotlin :digital-twins:frankfurter-fake:compileTestKotlin`.
   Fix the errors at their cause.
2. `./gradlew :web:test` needs Docker (Testcontainers). Report failures with their output,
   and don't paper over them.
3. **Recipes cover the mechanical part only.** For the target version, read the official
   migration guide or release notes (e.g. the Spring Boot 4.x / Framework 7 wiki pages)
   and check what the recipe can't do. Typical gaps: behaviour changes, removed
   auto-configuration, renamed properties in profile-specific `application-*.yml`, and
   APIs used only from `.kte` templates or `META-INF/spring.factories`. Fix those by hand and
   list them separately in the summary.
4. Version pins: CLAUDE.md says to re-check the CVE pins in the root `gradle.properties` and
   the `constraints` in `web/build.gradle.kts` on every Spring Boot or Spring Cloud bump.
   Delete each pin the new BOM already meets or exceeds (`./gradlew :web:dependencyInsight
   --dependency <name> --configuration runtimeClasspath`).
5. Update CLAUDE.md and README where they name versions or APIs the migration changed.
6. If the bump affects Docker images (web via `bootBuildImage`), say that
   `./gradlew dockerBuildDev` and a stack restart are needed to try it. Don't run them unasked.

## 6. Report

What the recipe changed, what you dropped or corrected and why, what you did by hand, build
and test results with any failures quoted, and remaining manual steps. Don't commit unless
asked. When asked, make one commit per logical step (recipe output after review, manual
follow-ups), so the mechanical part can be reviewed on its own.
