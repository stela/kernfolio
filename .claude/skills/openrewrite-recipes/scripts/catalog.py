#!/usr/bin/env python3
"""Offline-cached catalog of OpenRewrite recipes, built from Maven Central.

Every OpenRewrite recipe jar ships META-INF/rewrite/recipes.csv (one row per
recipe: name, display name, description, options, ...), and declarative
recipes are defined in META-INF/rewrite/*.yml. This script reads those
entries straight out of the jars with HTTP range requests (no full jar
download), for every module the newest *fully published* rewrite-recipe-bom
lists, and caches them under ~/.cache/openrewrite-catalog/.

Usage (run with `python3 -I`). Every argument is one query; a query's words must all match
(regex, case-insensitive), so quote multi-word queries and pass several to batch them:
  catalog.py upgrades ["QUERY" ...]   version-upgrade recipes per family, e.g. upgrades "spring boot" hibernate gradle
  catalog.py search "QUERY" [...]     recipes matching, e.g. search "testcontainers" "junit 5"
  catalog.py show RECIPE [...]        full description, options, and the YAML definition
  catalog.py plan --out DIR RECIPE [-o key=value ...] [RECIPE ...]
                                      write rewrite.yml + Gradle init script (used by openrewrite-migrate)
  catalog.py tidy-patch PATCH         dedupe/clean an OpenRewrite rewrite.patch so git apply takes it
  catalog.py modules                  BOM version and module list
Options: --bom VERSION (default: newest fully published, checked on every run), --refresh, --limit N, --full
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import csv
import io
import json
import os
import re
import shutil
import sys
import textwrap
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

CENTRAL = "https://repo1.maven.org/maven2"
PORTAL = "https://plugins.gradle.org/m2"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "openrewrite-catalog"
UA = {"User-Agent": "openrewrite-catalog/1.0"}

# BOM entries that are tooling, test harnesses or aggregators, not recipe modules.
SKIP = {
    "rewrite-bom", "rewrite-recipe-bom", "plugin", "model", "rewrite-maven-plugin",
    "rewrite-test", "rewrite-java-test", "rewrite-templating", "jgit-gpg-bc",
    "rewrite-all", "rewrite-polyglot",
}
csv.field_size_limit(sys.maxsize)


def http(url: str, rng: str | None = None) -> bytes:
    headers = dict(UA)
    if rng:
        headers["Range"] = rng
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
        return r.read()


def exists(url: str) -> bool:
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers=UA), timeout=30):
            return True
    except urllib.error.HTTPError:
        return False


def path(group: str, artifact: str) -> str:
    return f"{group.replace('.', '/')}/{artifact}"


def versions(group: str, artifact: str, repo: str = CENTRAL) -> list[str]:
    xml = http(f"{repo}/{path(group, artifact)}/maven-metadata.xml")
    vs = [v.text for v in ET.fromstring(xml).iter("version")]
    # Central's maven-metadata.xml can lag the actual releases, so merge the directory listing too.
    if repo == CENTRAL:
        try:
            html = http(f"{repo}/{path(group, artifact)}/").decode()
            vs += re.findall(r'href="(\d[^"/]*)/"', html)
        except urllib.error.URLError:
            pass
    rel = {v for v in vs if re.fullmatch(r"[\d.]+", v)}
    return sorted(rel, key=lambda v: [int(p) for p in v.split(".")])


def pom_deps(group: str, artifact: str, version: str) -> list[tuple[str, str, str]]:
    root = ET.fromstring(http(f"{CENTRAL}/{path(group, artifact)}/{version}/{artifact}-{version}.pom"))
    ns = {"m": root.tag.split("}")[0].strip("{")}
    out = []
    for d in root.findall(".//m:dependencyManagement/m:dependencies/m:dependency", ns):
        out.append(tuple(d.findtext(f"m:{k}", "", ns) for k in ("groupId", "artifactId", "version")))
    return out


def jar_url(g: str, a: str, v: str) -> str:
    return f"{CENTRAL}/{path(g, a)}/{v}/{a}-{v}.jar"


def bom_modules(bom: str) -> dict:
    deps = pom_deps("org.openrewrite.recipe", "rewrite-recipe-bom", bom)
    core = next(v for g, a, v in deps if a == "rewrite-bom")
    plugin = next(v for g, a, v in deps if g == "org.openrewrite" and a == "plugin")
    mods = [(g, a, v) for g, a, v in deps if a not in SKIP]
    mods += [(g, a, v) for g, a, v in pom_deps("org.openrewrite", "rewrite-bom", core) if a not in SKIP]
    return {"bom": bom, "core": core, "plugin": plugin, "modules": sorted(set(mods))}


def pick_bom(requested: str | None) -> dict:
    """The rewrite-recipe-bom to use: the newest release on Maven Central whose listed modules and
    Gradle plugin can all be downloaded. A BOM can appear on Central hours before the jars it
    lists; until they're there, an older BOM is used and a note says which and why.

    The version list is fetched on every call (two small requests), so a new release is picked
    up immediately. Only the per-version "fully published" verdict is cached. A published
    release never changes, so a positive verdict is final; a negative one is re-checked after
    15 minutes. Recipe modules and the plugin are taken as a set from that one BOM, not each at
    its own newest version. The BOM is the combination OpenRewrite releases and tests together:
    recipe modules are compiled against a specific rewrite core."""
    def verdict_file(v): return CACHE / "boms" / f"{v}.json"

    def check(bom: str) -> tuple[dict | None, str]:
        f = verdict_file(bom)
        if f.exists():
            cached = json.loads(f.read_text())
            if cached.get("ok") or time.time() - f.stat().st_mtime < 15 * 60:
                return (cached["info"] if cached.get("ok") else None), cached.get("why", "")
        try:
            info = bom_modules(bom)
        except (urllib.error.URLError, StopIteration) as e:
            where = getattr(e, "url", None) or getattr(e, "filename", None) or ""
            return None, f"{e} {where}".strip()
        urls = [jar_url(*m) for m in info["modules"]]
        urls.append(f"{PORTAL}/org/openrewrite/plugin/{info['plugin']}/plugin-{info['plugin']}.pom")
        with cf.ThreadPoolExecutor(16) as ex:
            missing = [u for u, ok in zip(urls, ex.map(exists, urls)) if not ok]
        why = f"{len(missing)} of its {len(urls)} artifacts aren't on Central/the plugin portal yet" if missing else ""
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps({"ok": not missing, "info": info, "why": why}))
        return (None if missing else info), why

    if requested:
        info, why = check(requested)
        return info or sys.exit(f"error: rewrite-recipe-bom {requested} unusable: {why}")
    try:
        candidates = versions("org.openrewrite.recipe", "rewrite-recipe-bom")[::-1][:5]
    except urllib.error.URLError as e:
        ok = sorted((f for f in (CACHE / "boms").glob("*.json") if json.loads(f.read_text()).get("ok")),
                    key=lambda f: natural(f.stem))
        if not ok:
            sys.exit(f"error: Maven Central unreachable ({e}) and nothing cached")
        print(f"note: Maven Central unreachable, using cached rewrite-recipe-bom {ok[-1].stem}", file=sys.stderr)
        return json.loads(ok[-1].read_text())["info"]
    for bom in candidates:
        info, why = check(bom)
        if info:
            return info
        print(f"note: skipping rewrite-recipe-bom {bom}: {why}", file=sys.stderr)
    sys.exit("error: none of the 5 newest rewrite-recipe-bom releases is fully published")


class RangeFile(io.RawIOBase):
    """Seekable read-only view of a remote file via HTTP Range, so zipfile reads only what it needs."""

    def __init__(self, url: str):
        self.url, self.pos = url, 0
        head = urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers=UA), timeout=30)
        self.size = int(head.headers["Content-Length"])

    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos

    def seek(self, off, whence=0):
        self.pos = {0: off, 1: self.pos + off, 2: self.size + off}[whence]
        return self.pos

    def read(self, n=-1):
        end = self.size if n is None or n < 0 else min(self.size, self.pos + n)
        if end <= self.pos:
            return b""
        data = http(self.url, f"bytes={self.pos}-{end - 1}")
        self.pos += len(data)
        return data

    def readinto(self, b):
        data = self.read(len(b))
        b[: len(data)] = data
        return len(data)


def module_dir(g: str, a: str, v: str) -> Path:
    return CACHE / "modules" / f"{g}:{a}:{v}"


def fetch_module(mod: tuple[str, str, str], refresh: bool) -> tuple[tuple, str | None]:
    d = module_dir(*mod)
    done = d / ".done"
    if done.exists() and not refresh:
        return mod, None
    try:
        with zipfile.ZipFile(io.BufferedReader(RangeFile(jar_url(*mod)), 1 << 16)) as z:
            d.mkdir(parents=True, exist_ok=True)
            for info in z.infolist():
                n = info.filename
                if n == "META-INF/rewrite/recipes.csv" or (
                        re.fullmatch(r"META-INF/rewrite/[^/]+\.ya?ml", n) and not n.endswith("examples.yml")):
                    # Flatten the name; never trust paths from the archive.
                    (d / Path(n).name).write_bytes(z.read(info))
        done.touch()
        return mod, None
    except Exception as e:  # noqa: BLE001 - report and keep going with the other modules
        return mod, f"{type(e).__name__}: {e}"


def load(info: dict, refresh: bool) -> list[dict]:
    todo = info["modules"]
    with cf.ThreadPoolExecutor(8) as ex:
        for mod, err in ex.map(lambda m: fetch_module(tuple(m), refresh), todo):
            if err:
                print(f"warning: {':'.join(mod)}: {err}", file=sys.stderr)
    rows = []
    for g, a, v in todo:
        f = module_dir(g, a, v) / "recipes.csv"
        if f.exists():
            for r in csv.DictReader(f.open(encoding="utf-8")):
                r["coords"] = f"{g}:{a}:{v}"
                rows.append(r)
    seen, out = set(), []
    for r in rows:  # a recipe can appear in several modules' csv; keep the first
        if r["name"] not in seen:
            seen.add(r["name"])
            out.append(r)
    return out


def natural(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def one_line(s: str, width: int) -> str:
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= width else s[: width - 1] + "…"


def print_rows(rows: list[dict], limit: int):
    for r in rows[:limit]:
        n = r.get("recipeCount") or "1"
        print(f"{r['name']}  [{r['coords'].split(':')[1]}, {n} recipe{'s' if n != '1' else ''}]")
        print(f"    {one_line(r['displayName'], 110)}")
        if r["description"]:
            print(f"    {one_line(r['description'], 160)}")
    if len(rows) > limit:
        print(f"... {len(rows) - limit} more (narrow the terms or raise --limit)")
    if not rows:
        print("no matching recipes")


def matcher(query: str, fields=("name", "displayName", "description")):
    """One query = whitespace-separated regexes, all of which must match. CamelCase names also
    match their words with spaces: "spring boot" finds UpgradeSpringBoot_4_0."""
    pats = [re.compile(t, re.I) for t in query.split()]

    def match(r):
        text = "\n".join(r[f] for f in fields)
        last = r["name"].rsplit(".", 1)[-1]
        text += "\n" + re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[a-z])(?=\d)", " ", last)
        return all(p.search(text) for p in pats)
    return match


# Last name segment of a version-migration recipe: UpgradeSpringBoot_4_0, MigrateToGradle9,
# UpgradeToJava25, SpringBootProperties_4_1, MigrateToHibernate71, Testcontainers2Migration,
# JUnit5to6Migration, Mockito1to5Migration, SpringBatch5To6Migration, JettyUpgradeEE10, ...
UPGRADE = re.compile(r"^((Upgrade|MigrateTo?)[A-Za-z]*_?\d|\w*Properties_\d|\w*?\d+[tT]o\d|\w*?\d+Migration$"
                     r"|\w*MigrationTo\w*?\d|\w+Upgrade\w*?\d)")
FROM_TO = re.compile(r"^(.*?[A-Za-z])(\d+(?:_\d+)*)[tT]o(\d+(?:_\d+)*)(\w*)$")
VERSIONED = re.compile(r"^(.*?[A-Za-z])_?(\d+(?:_\d+)*)(\D\w*)?$")


def is_upgrade(name: str) -> bool:
    return ".search." not in name and bool(UPGRADE.match(name.rsplit(".", 1)[-1]))

# Recipes for bumping any library, plugin or the wrapper when there's no dedicated migration recipe.
GENERIC = [
    ("org.openrewrite.gradle.UpgradeDependencyVersion", "groupId=… artifactId=… newVersion=…"),
    ("org.openrewrite.gradle.UpgradeTransitiveDependencyVersion", "groupId=… artifactId=… version=… because=…"),
    ("org.openrewrite.gradle.plugins.UpgradePluginVersion", "pluginIdPattern=… newVersion=…"),
    ("org.openrewrite.gradle.UpdateGradleWrapper", "version=9.x"),
]


def family(name: str):
    """org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0 -> (key, "4.0")."""
    pkg, last = name.rsplit(".", 1)
    pkg = re.sub(r"\d+", "#", pkg)
    m = FROM_TO.match(last)
    if m:
        return (pkg, m.group(1), "to", m.group(4)), f"{m.group(2)}→{m.group(3)}".replace("_", ".")
    m = VERSIONED.match(last)
    if not m:
        return (name, ""), ""
    return (pkg, m.group(1), m.group(3) or ""), m.group(2).replace("_", ".")


def print_upgrades(hits: list[dict], full: bool):
    hits = sorted(hits, key=lambda r: natural(r["name"]))
    if full:
        width = max((len(r["name"]) for r in hits), default=0)
        for r in hits:
            print(f"  {r['name']:<{width}}  {int(r.get('recipeCount') or 1):>5}  {one_line(r['displayName'], 70)}")
        return
    fams: dict = {}
    for r in hits:
        key, ver = family(r["name"])
        fams.setdefault(key, []).append((ver, r))
    steps = [k for k in fams if k[-1].startswith("_")]  # UpgradeJackson_2_3_TypeChanges etc.
    for key in sorted(set(fams) - set(steps), key=lambda k: natural(" ".join(k))):
        members = sorted(fams[key], key=lambda vr: natural(vr[0]))
        latest = members[-1][1]
        vers = " ".join(v for v, _ in members if v)
        print(f"  {one_line(latest['displayName'], 60):<60}  {vers}")
        print(f"      newest: {latest['name']}  ({int(latest.get('recipeCount') or 1)} recipes)")
    if steps:
        print(f"  (+{sum(len(fams[k]) for k in steps)} sub-step recipes of the above; --full lists them)")


def generic_hint(queries: list[str]):
    print(f"\nNo dedicated recipe for: {', '.join(queries)}. Plain version bumps work for any library,")
    print("plugin or the wrapper with these (pass options with -o key=value to openrewrite-migrate):")
    for name, opts in GENERIC:
        print(f"  {name}  {opts}")


def yaml_definition(r: dict) -> str | None:
    g, a, v = r["coords"].split(":")
    d = module_dir(g, a, v)
    for f in sorted(d.glob("*.y*ml")):
        for doc in re.split(r"^---\s*$", f.read_text(encoding="utf-8"), flags=re.M):
            if re.search(rf"^name:\s*{re.escape(r['name'])}\s*$", doc, re.M):
                return f"# {f.name} in {r['coords']}\n{doc.strip()}"
    return None


def find(rows: list[dict], name: str) -> dict:
    exact = [r for r in rows if r["name"] == name]
    if exact:
        return exact[0]
    near = [r for r in rows if name.lower() in r["name"].lower()]
    hint = "\n  ".join(r["name"] for r in sorted(near, key=lambda r: natural(r["name"]))[:15])
    sys.exit(f"error: no recipe named {name}" + (f"; did you mean:\n  {hint}" if near else ""))


def parse_plan(argv: list[str]):
    """plan --out DIR [--bom V] RECIPE [-o key=value ...] [RECIPE [-o ...]] ..."""
    out, bom, recipes = None, None, []
    it = iter(argv)
    for a in it:
        if a == "--out":
            out = next(it)
        elif a == "--bom":
            bom = next(it)
        elif a in ("-o", "--option"):
            if not recipes:
                sys.exit(f"error: {a} must follow a recipe name")
            kv = next(it)
            if "=" not in kv:
                sys.exit(f"error: option must be key=value: {kv}")
            k, v = kv.split("=", 1)
            recipes[-1][1][k] = v
        elif a.startswith("-"):
            sys.exit(f"error: unknown flag {a}")
        else:
            recipes.append((a, {}))
    if not out or not recipes:
        sys.exit("usage: catalog.py plan --out DIR [--bom V] RECIPE [-o key=value ...] ...")
    return Path(out), bom, recipes


def yaml_value(raw: str, typ: str) -> str:
    if raw.startswith(("[", "{")):
        return raw
    if typ.lower() in ("boolean", "integer", "int", "long", "double", "float"):
        return raw
    if typ.startswith(("List", "Set")) or typ.endswith("[]"):
        return json.dumps([x.strip() for x in raw.split(",") if x.strip()])
    return json.dumps(raw)


def plan(argv: list[str]):
    """Write rewrite.yml (one composite of the requested recipes, with options) and an init script."""
    out, bom, recipes = parse_plan(argv)
    info = pick_bom(bom)
    rows = load(info, False)
    mods, entries = [], []
    for name, opts in recipes:
        r = find(rows, name)
        declared = {o["name"]: o for o in json.loads(r["options"] or "[]")}
        unknown = set(opts) - set(declared)
        if unknown:
            sys.exit(f"error: {name} has no option(s) {', '.join(sorted(unknown))}; "
                     f"it has: {', '.join(declared) or 'none'}")
        missing = [k for k, o in declared.items() if o.get("required") and k not in opts]
        if missing:
            sys.exit(f"error: {name} needs option(s) {', '.join(missing)} (-o key=value)")
        if r["coords"] not in mods:
            mods.append(r["coords"])
        if opts:
            lines = [f"  - {name}:"] + [f"      {k}: {yaml_value(v, declared[k].get('type', 'String'))}"
                                         for k, v in opts.items()]
        else:
            lines = [f"  - {name}"]
        entries.append("\n".join(lines))
    out.mkdir(parents=True, exist_ok=True)
    (out / "rewrite.yml").write_text(
        "type: specs.openrewrite.org/v1beta/recipe\n"
        f"name: {LOCAL_RECIPE}\n"
        "displayName: Recipes requested for this run\n"
        "recipeList:\n" + "\n".join(entries) + "\n")
    deps = "\n".join(f'        rewrite("{m.rsplit(":", 1)[0]}")' for m in mods)  # versions from the BOM
    (out / "rewrite.init.gradle").write_text(f"""initscript {{
    repositories {{ gradlePluginPortal() }}
    dependencies {{ classpath("org.openrewrite:plugin:{info['plugin']}") }}
}}
rootProject {{
    plugins.apply(org.openrewrite.gradle.RewritePlugin)
    dependencies {{
        rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:{info['bom']}"))
{deps}
    }}
    rewrite {{
        configFile = file('{out / "rewrite.yml"}')
        activeRecipe("{LOCAL_RECIPE}")
        exportDatatables = true
        failOnDryRunResults = false
    }}
    afterEvaluate {{
        if (repositories.isEmpty()) {{ repositories {{ mavenCentral() }} }}
    }}
}}
""")
    print(f"bom {info['bom']}, plugin {info['plugin']}, modules {', '.join(mods)}")
    print((out / "rewrite.yml").read_text().rstrip())


LOCAL_RECIPE = "local.openrewrite.RequestedRecipes"


def tidy_patch(path: str):
    """Make rewrite.patch git-apply-able. OpenRewrite repeats root-level changes (Gradle wrapper)
    once per Gradle project, emits hunk-less sections for files it only re-encoded (e.g. CRLF -> LF
    in CSV fixtures), and binary changes git can't apply from this patch. The original is kept as
    rewrite.patch.orig."""
    p = Path(path)
    text = p.read_text(encoding="utf-8", errors="surrogateescape")
    sections = re.split(r"(?m)^(?=diff --git )", text)
    head, sections = (sections[0], sections[1:]) if not sections[0].startswith("diff --git") else ("", sections)
    kept, seen, notes = [], set(), []
    for sec in sections:
        file = sec.split("\n", 1)[0].split(" b/", 1)[-1]
        if file in seen:
            notes.append(f"duplicate section dropped: {file}")
            continue
        seen.add(file)
        if "Binary files" in sec:
            notes.append(f"binary change dropped (not appliable from this patch): {file}")
        elif "\n@@" not in sec and not re.search(r"(?m)^(new|deleted) file mode|^rename ", sec):
            notes.append(f"content-less section dropped (line endings/encoding only): {file}")
        else:
            kept.append(sec)
    if notes:
        Path(path + ".orig").write_text(text, encoding="utf-8", errors="surrogateescape")
        p.write_text(head + "".join(kept), encoding="utf-8", errors="surrogateescape")
    counts: dict = {}
    for n in notes:
        counts[n] = counts.get(n, 0) + 1
    for n, c in counts.items():
        print(f"tidy: {n}" + (f" (x{c})" if c > 1 else ""))
    print(f"tidy: {len(kept)} file(s) in {path}" + (f" (original: {path}.orig)" if notes else ""))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "plan":
        return plan(sys.argv[2:])
    if len(sys.argv) == 3 and sys.argv[1] == "tidy-patch":
        return tidy_patch(sys.argv[2])
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["search", "upgrades", "show", "modules"])
    ap.add_argument("args", nargs="*", help="queries; quote multi-word ones")
    ap.add_argument("--bom")
    ap.add_argument("--refresh", action="store_true", help="re-verify BOMs and re-read the jars")
    ap.add_argument("--limit", type=int, default=25, help="search: max results per query")
    ap.add_argument("--full", action="store_true", help="upgrades: every recipe instead of one line per family")
    o = ap.parse_args()
    if o.refresh:
        shutil.rmtree(CACHE / "boms", ignore_errors=True)

    info = pick_bom(o.bom)
    if o.cmd == "modules":
        print(f"rewrite-recipe-bom {info['bom']}  (rewrite core {info['core']}, Gradle plugin {info['plugin']})")
        for m in info["modules"]:
            print("  " + ":".join(m))
        return

    rows = load(info, o.refresh)
    print(f"# rewrite-recipe-bom {info['bom']}, {len(rows)} recipes", file=sys.stderr)

    if o.cmd == "search":
        if not o.args:
            sys.exit("error: search needs at least one query")
        for q in o.args:
            hits = [r for r in rows if matcher(q)(r)]
            # Composite recipes (the "do a whole migration" ones) first, then by name.
            hits.sort(key=lambda r: (int(r.get("recipeCount") or 1) <= 1, natural(r["name"])))
            if len(o.args) > 1:
                print(f"== {q} ({len(hits)}) ==")
            print_rows(hits, o.limit)
    elif o.cmd == "upgrades":
        # Version-upgrade recipes: UpgradeSpringBoot_4_0, MigrateToGradle9, UpgradeToJava25,
        # SpringBootProperties_4_1, UpgradeSpringCloud_2025_1, MigrateToHibernate70, ...
        ups = [r for r in rows if is_upgrade(r["name"])]
        none = []
        for q in o.args or [""]:
            # Match the recipe's own name and display name only: package names all contain "java",
            # and descriptions mention neighbouring projects.
            hits = [r for r in ups if matcher(q, ("displayName",))(r)]
            print(f"== {q or 'all'} ({len(hits)} recipes) ==")
            print_upgrades(hits, o.full)
            if not hits:
                none.append(q)
        if none:
            generic_hint(none)
        print("\n(`show NAME` for details, `--full` for every recipe)")
    elif o.cmd == "show":
        for i, name in enumerate(o.args or sys.exit("error: show needs recipe names")):
            if i:
                print("\n" + "=" * 80 + "\n")
            show(find(rows, name))


def show(r: dict):
    print(f"{r['name']}\n  {r['displayName']}\n  module: {r['coords']}  ({r.get('recipeCount') or 1} recipes)")
    print(textwrap.indent(textwrap.fill(re.sub(r"\s+", " ", r["description"]), 100), "  "))
    if r.get("options"):
        print("  options:")
        for opt in json.loads(r["options"]):
            req = "required" if opt.get("required") else "optional"
            ex = f", e.g. {opt['example']}" if opt.get("example") else ""
            print(f"    {opt['name']} ({opt.get('type', '?')}, {req}{ex}): {one_line(opt.get('description', ''), 120)}")
    y = yaml_definition(r)
    print("\n" + (y or "(imperative recipe: implemented in Java, no YAML definition)"))


if __name__ == "__main__":
    main()
