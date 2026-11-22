#!/usr/bin/env bash
# Move the `pgdata` volume's cluster to the PostgreSQL major version that
# docker-compose.yml now runs (dump and restore). Run it on the host after
# pulling a commit that bumps the postgres image, with the stack stopped:
#
#   docker compose down                      # keeps volumes
#   scripts/postgres-major-upgrade.sh        # dev: COMPOSE_FILE=docker-compose.yml:docker-compose.dev.yml
#   docker compose up -d
#
# Each major version has its own cluster directory in the volume, PGDATA=
# /var/lib/postgresql/data/<major> (the pre-19 cluster sits at the volume root).
# The script:
#   1. starts the newest older cluster alone in a throwaway container with no
#      network, and dumps its roles (no passwords) and every database,
#   2. starts only compose's postgres (--no-deps, so web/Liquibase and OpenBao
#      can't touch the new cluster yet). Its first start runs initdb with the
#      same pg_root_password, so the superuser password matches certs_postgres
#      and certs_openbao as before,
#   3. restores roles, then each database (--create: original owner, settings,
#      grants), runs ANALYZE, and compares exact row counts old vs. new.
# The old cluster is never modified or deleted. To roll back, put the old image
# and PGDATA back in docker-compose.yml. The dumps hold all data: keep them
# private (0700/0600) and delete them once the new cluster is in use.
#
# Env: COMPOSE_FILE / COMPOSE_PROJECT_NAME as for docker compose;
#      PG_UPGRADE_DUMP_DIR (default ./pg-upgrade-<old>-to-<new>-<UTC time>).
set -euo pipefail

die() { echo "postgres-major-upgrade: $*" >&2; exit 1; }
say() { echo "=== $* ==="; }

root="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
for tool in docker jq; do command -v "$tool" >/dev/null || die "$tool is required"; done
compose=(docker compose)

cfg="$("${compose[@]}" config --format json)"
volume="$(jq -r '.volumes.pgdata.name' <<<"$cfg")"
image="$(jq -r '.services.postgres.image' <<<"$cfg")"
pgdata="$(jq -r '.services.postgres.environment.PGDATA' <<<"$cfg")"
superuser="$(jq -r '.services.postgres.environment.POSTGRES_USER' <<<"$cfg")"
mount="$(jq -r '.services.postgres.volumes[] | select(.source == "pgdata") | .target' <<<"$cfg")"
[[ -n "$volume" && "$volume" != null && -n "$mount" ]] || die "no pgdata volume on the postgres service"
new="${pgdata#"$mount"/}"
[[ "$new" =~ ^[0-9]+$ ]] || die "PGDATA ($pgdata) should be $mount/<major>"
image_major="$(docker run --rm --entrypoint postgres "$image" --version | sed -E 's/.* ([0-9]+)(\.[0-9]+)*.*/\1/')"
[[ "$image_major" == "$new" ]] || die "$image is PostgreSQL $image_major, but PGDATA says $new"

# Clusters in the volume: "<dir relative to the mount> <major>" per line.
clusters="$(docker run --rm -v "$volume:/v:ro" --entrypoint sh "$image" -c \
  'for d in /v /v/*; do [ -f "$d/PG_VERSION" ] && echo "${d#/v} $(cat "$d/PG_VERSION")"; done; true')"
if grep -qx "/$new $new" <<<"$clusters"; then
  echo "pgdata already has a PostgreSQL $new cluster at $pgdata; nothing to do."
  exit 0
fi
old_line="$(awk -v n="$new" '$2 < n' <<<"$clusters" | sort -k2,2n | tail -1)"
if [[ -z "$old_line" ]]; then
  echo "pgdata has no older cluster; postgres $new will initialise a fresh one on start."
  exit 0
fi
old_dir="${old_line% *}"; old="${old_line##* }"
old_image="postgres:${old}-alpine"
[[ -z "$(docker ps -q --filter "volume=$volume")" ]] \
  || die "a running container uses $volume; stop the stack first (docker compose down keeps volumes)"

dump="${PG_UPGRADE_DUMP_DIR:-$PWD/pg-upgrade-$old-to-$new-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -m 700 "$dump" || die "$dump exists or can't be created"
umask 077
say "PostgreSQL $old ($mount$old_dir) -> $new ($pgdata) in $volume; dumps in $dump"

old_ctr="pg-upgrade-old-$$"
cleanup() { docker rm -f "$old_ctr" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Old cluster: its own image, no network, socket only (initdb's pg_hba trusts local).
docker run -d --name "$old_ctr" --network none -v "$volume:$mount" -e "PGDATA=$mount$old_dir" \
  "$old_image" postgres -c listen_addresses= >/dev/null
for _ in $(seq 60); do docker exec -u postgres "$old_ctr" pg_isready -q && break; sleep 1; done
docker exec -u postgres "$old_ctr" pg_isready -q || die "old cluster didn't start: docker logs $old_ctr"

psql_old() { docker exec -u postgres "$old_ctr" psql -XAtq -U "$superuser" -v ON_ERROR_STOP=1 "$@"; }
[[ "$(psql_old -d postgres -c 'SHOW standard_conforming_strings')" == on ]] \
  || die "standard_conforming_strings is off: PostgreSQL 19 can't load such a dump"
databases=()   # no mapfile: macOS still ships bash 3.2
while IFS= read -r db; do databases+=("$db"); done < <(psql_old -d postgres -c \
  "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> 'postgres' ORDER BY 1")
((${#databases[@]})) || die "the old cluster has no databases besides postgres"

# Exact row counts of every table, to compare after the restore.
COUNTS_SQL="SELECT format('%I.%I', table_schema, table_name) || ' ' ||
  (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text
  FROM information_schema.tables
  WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY 1"

say "Dumping roles and ${#databases[@]} database(s): ${databases[*]}"
# Roles without passwords: the superuser keeps the one initdb sets from
# pg_root_password, app_owner has none, and the v-token-app-* roles are
# OpenBao's leases (web gets new ones; OpenBao still revokes the old ones).
docker exec -u postgres "$old_ctr" pg_dumpall -U "$superuser" --roles-only --no-role-passwords > "$dump/roles.sql"
for db in "${databases[@]}"; do
  docker exec -u postgres "$old_ctr" pg_dump -U "$superuser" -Fc --create -d "$db" > "$dump/$db.dump"
  psql_old -d "$db" -c "$COUNTS_SQL" > "$dump/$db.counts-old"
done
docker stop -t 60 "$old_ctr" >/dev/null
cleanup

say "Starting postgres $new alone (first start runs initdb)"
# The volume root is the old cluster's directory (postgres-owned, 0700), and
# compose runs postgres's entrypoint as root without DAC_OVERRIDE, so it can't
# create $pgdata in there itself. A fresh volume doesn't have this problem.
docker run --rm -v "$volume:$mount" --entrypoint install "$image" -d -o postgres -g postgres -m 700 "$pgdata"
"${compose[@]}" up -d --no-deps postgres
ctr="$("${compose[@]}" ps -q postgres)"
for _ in $(seq 120); do
  # Fresh init: the image's temporary server answers pg_isready too, so also
  # wait for the entrypoint to finish before trusting it.
  if docker logs "$ctr" 2>&1 | grep -q 'PostgreSQL init process complete' &&
     "${compose[@]}" exec -T -u postgres postgres pg_isready -q -U "$superuser"; then break; fi
  sleep 1
done
"${compose[@]}" exec -T -u postgres postgres pg_isready -q -U "$superuser" \
  || die "postgres $new didn't come up: docker compose logs postgres"
psql_new() { "${compose[@]}" exec -T -u postgres postgres psql -XAtq -U "$superuser" -v ON_ERROR_STOP=1 "$@"; }

say "Restoring roles"
# The superuser already exists (initdb); everything else must restore cleanly.
grep -vxF "CREATE ROLE $superuser;" "$dump/roles.sql" | psql_new -d postgres >/dev/null
for db in "${databases[@]}"; do
  say "Restoring $db"
  "${compose[@]}" exec -T -u postgres postgres \
    pg_restore -U "$superuser" --clean --if-exists --create --exit-on-error -d postgres < "$dump/$db.dump"
  psql_new -d "$db" -c ANALYZE
  psql_new -d "$db" -c "$COUNTS_SQL" > "$dump/$db.counts-new"
  diff -u "$dump/$db.counts-old" "$dump/$db.counts-new" || die "row counts differ in $db (see above)"
done

say "Done: PostgreSQL $new holds the same data (row counts match)"
echo "Old cluster left untouched at $mount$old_dir in $volume (rollback: old image + PGDATA in docker-compose.yml)."
echo "Dumps: $dump (contain all data; delete once you're satisfied)."
echo "Next: docker compose up -d"
