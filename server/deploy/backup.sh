#!/usr/bin/env bash
# Nightly backups, from cron on the box (bootstrap-instance.sh installs it): a dump of the run database, and a
# copy of the sim host's accounts.db (viewers' accounts and letters, which never reach Postgres).
# Keeps 14 days in ~/backups. With BACKUP_BUCKET set in ~/lilguy-server/.env (and the AWS CLI
# configured with a key that can only write there), each one is also copied to that bucket
# (Lightsail object storage or S3).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
stamp="$(date -u +%Y%m%d-%H%M%S)"
out="$HOME/backups/lilguy-$stamp.sql.gz"
docker compose exec -T db pg_dump -U lilguy lilguy | gzip > "$out"
accounts="$HOME/backups/accounts-$stamp.db.gz"
# Exit 3: no accounts.db yet (nobody has made an account), so there's nothing to keep.
rc=0; docker compose --profile backup run --rm --no-deps -T sqlite-backup /data/accounts.db > "$accounts" || rc=$?
if [[ $rc -eq 3 ]]; then rm -f "$accounts"; elif [[ $rc -ne 0 ]]; then rm -f "$accounts"; exit "$rc"; fi
find "$HOME/backups" \( -name 'lilguy-*.sql.gz' -o -name 'accounts-*.db.gz' \) -mtime +14 -delete
if [[ -n "${BACKUP_BUCKET:-}" ]]; then
  aws s3 cp "$out" "s3://$BACKUP_BUCKET/$(basename "$out")"
  if [[ -f "$accounts" ]]; then aws s3 cp "$accounts" "s3://$BACKUP_BUCKET/$(basename "$accounts")"; fi
fi
echo "$(date -u +%FT%TZ) wrote $out ($(du -h "$out" | cut -f1))"
if [[ -f "$accounts" ]]; then echo "$(date -u +%FT%TZ) wrote $accounts ($(du -h "$accounts" | cut -f1))"; fi
