#!/usr/bin/env bash
# Nightly dump of the run database, from cron on the box (bootstrap-instance.sh installs it).
# Keeps 14 days in ~/backups. With BACKUP_BUCKET set in ~/lilguy-server/.env (and the AWS CLI
# configured with a key that can only write there), each dump is also copied to that bucket
# (Lightsail object storage or S3).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
out="$HOME/backups/lilguy-$(date -u +%Y%m%d-%H%M%S).sql.gz"
docker compose exec -T db pg_dump -U lilguy lilguy | gzip > "$out"
find "$HOME/backups" -name 'lilguy-*.sql.gz' -mtime +14 -delete
if [[ -n "${BACKUP_BUCKET:-}" ]]; then
  aws s3 cp "$out" "s3://$BACKUP_BUCKET/$(basename "$out")"
fi
echo "$(date -u +%FT%TZ) wrote $out ($(du -h "$out" | cut -f1))"
