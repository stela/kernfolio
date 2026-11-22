---
name: openrewrite-recipes
description: >-
  Look up which upgrades and migrations OpenRewrite can automate, and the exact recipe names.
  Use whenever a version bump or migration is being considered or planned: Spring Boot,
  Spring Framework, Spring Cloud, Spring Security, Spring Data, Kotlin, the Java toolchain,
  Gradle (wrapper, Gradle 8 → 9 migration, plugin versions), Jackson, Hibernate, JUnit,
  Testcontainers, Mockito, or any other third-party library or Gradle plugin. Plain version
  bumps are covered by generic recipes. Batches several technologies in one call, e.g.
  "spring boot" "spring framework" hibernate gradle. Also answers "is there a recipe for …",
  "can OpenRewrite upgrade X to Y", "what does recipe Z change". Run it before
  /openrewrite-migrate when the recipe name isn't known.
argument-hint: "[upgrades|search|show] [\"query\" ...]"
allowed-tools: Bash(python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py:*)
---

# Find OpenRewrite recipes

`${CLAUDE_SKILL_DIR}/scripts/catalog.py` builds a recipe catalog from the jars listed in the newest
**fully published** `org.openrewrite.recipe:rewrite-recipe-bom` on Maven Central, plus the core
`rewrite-bom` modules (rewrite-gradle, rewrite-kotlin, rewrite-maven, ...).

- **Picking the BOM:** a BOM can appear on Central hours before the jars it lists, so the
  script steps back to the newest BOM whose jars and Gradle plugin all exist.
- **What it reads:** every recipe jar ships `META-INF/rewrite/recipes.csv` and the YAML of its
  declarative recipes. The script reads just those entries from each jar over HTTP range
  requests, a few MB in total.
- **Staying current:** the BOM version list is fetched on every run, so a new OpenRewrite
  release is used as soon as all its artifacts are downloadable. Skipped BOMs are reported
  with the reason. The plugin and every recipe module come from that one BOM, which is the
  set OpenRewrite releases together, rather than each at its own newest version.
- **Cache:** `~/.cache/openrewrite-catalog/` (or `$XDG_CACHE_HOME`) keeps the extracted
  recipe data per module version and the per-BOM "fully published" verdict (a negative
  verdict is re-checked after 15 minutes). `--refresh` drops both.

The script doesn't depend on the current directory. Always run it with `python3 -I`, because
the data comes from downloads.

**Every argument is one query.** A query's words must all match (case-insensitive regex), and
CamelCase names match their words, so `"spring boot"` finds `UpgradeSpringBoot_4_0`. Pass
several queries to check many technologies in one call:

```bash
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py upgrades "spring boot" "spring framework" "spring cloud" hibernate gradle kotlin
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py upgrades                 # every upgrade family
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py upgrades jackson --full  # every recipe, sub-steps included
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py search testcontainers "junit 6" "rest ?template"
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py show org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0 org.openrewrite.gradle.MigrateToGradle9
python3 -I ${CLAUDE_SKILL_DIR}/scripts/catalog.py modules                  # BOM, plugin and module versions
#   --bom 3.37.0 pins a BOM   --limit N  search results per query (default 25)
```

**Each command's output:**

- **`upgrades`:** one section per query and one line per recipe family. Each line has the
  family's display name, the versions it supports (`2.0 … 4.0`, or `1→5` for from-to
  recipes) and the newest recipe's full name. Queries with no dedicated recipe are collected
  at the end, with the generic recipes that handle any plain version bump:
  - `org.openrewrite.gradle.UpgradeDependencyVersion` (groupId, artifactId, newVersion)
  - `org.openrewrite.gradle.UpgradeTransitiveDependencyVersion`
  - `org.openrewrite.gradle.plugins.UpgradePluginVersion` (pluginIdPattern, newVersion)
  - `org.openrewrite.gradle.UpdateGradleWrapper`
- **`search`:** one section per query, composite recipes first.
- **`show`:** one or more recipes, each with its module, options (type, required, example),
  and the full YAML with every sub-recipe. A wrong or partial name prints close matches,
  which is often quicker than `search`.

## What to do with the arguments

`$ARGUMENTS`

- **Empty:** find the versions the build uses now. In a Gradle project, look at
  `settings.gradle*`, `build.gradle*` (root and modules), `gradle.properties`,
  `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`. Then run one
  batched `upgrades` call with a query for each major framework and library found. Report,
  per technology: the current version, the newest recipe target, and whether an upgrade
  recipe applies.
- **Technology names:** one batched `upgrades` call. For queries with no hits, try `search`.
- **A goal** ("move off RestTemplate", "junit 4 to 5"): `search` with 1–3 distinctive words per query.
- **Recipe names:** `show` them.

## Reading the results

Compare each result with the version the build actually uses, then say plainly what fits:

- **Recipes are cumulative and named after their target.** `UpgradeSpringBoot_4_0` includes
  3.5 → 4.0 and everything before it. A new minor often gets only `SpringBootProperties_4_x`
  until a full recipe ships.
- **A recipe for an older target than the project is on** still makes changes, but it pins
  its own, older versions. Run on Boot 4.1.x, `UpgradeSpringBoot_4_0` adds explicit `:4.0.x`
  versions, which override the BOM. Say so.
- **Plain version bumps:** where a library has no migration recipe, `UpgradeDependencyVersion`
  or `UpgradePluginVersion` with `newVersion=latest.release` or a semver selector like `3.x`
  is all OpenRewrite offers. That beats editing by hand only when bumping many artifacts at once.
- **Gradle itself:** for the wrapper, recommend `./gradlew wrapper --gradle-version X` (run it
  twice), not `UpdateGradleWrapper`. The recipe is applied once per Gradle project, and on
  this repo it mangled the `gradlew` script header. Use `MigrateToGradle9` for the build-script
  changes, and drop its wrapper hunks.
- **Kotlin:** OpenRewrite parses `.kt` and `.gradle.kts`, and the Spring and Testcontainers
  recipes do change them. Prefer the Kotlin variants where they exist
  (`UpgradeBuildToJava25ForKotlin`, `UpgradeKotlinJvmTargetVersion`).
- **Sub-recipes:** from the `show` output, point out the ones that matter for this project,
  e.g. property renames that touch keys it uses, or dependency swaps for modules it depends on.
- **The answer:** name the exact recipe(s), with options, to pass to `/openrewrite-migrate`.

If Maven Central can't be reached, say so. https://docs.openrewrite.org/recipes is a fallback
for names only. The catalog is the source of truth for the module and version, because it
matches what the migrate skill will run.
