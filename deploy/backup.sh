#!/bin/sh
# Daily database backup. Run from the deploy folder:  ./backup.sh
# Schedule it with cron, e.g. at 02:30 every night:
#   30 2 * * * cd /home/ubuntu/backend/deploy && ./backup.sh >> backup.log 2>&1
set -eu
mkdir -p backups
FILE="backups/cherrytrack-$(date +%F).sql.gz"
docker compose -f docker-compose.prod.yml exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines cherrytrack' | gzip > "$FILE"
echo "saved $FILE ($(du -h "$FILE" | cut -f1))"
# keep the last 14 days
find backups -name 'cherrytrack-*.sql.gz' -mtime +14 -delete
