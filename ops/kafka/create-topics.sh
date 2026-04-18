#!/usr/bin/env bash
# =============================================================================
# Create Kafka topics cho stack dev local.
# Chạy SAU khi `docker compose -f infra/docker-compose.dev.yml up -d`.
# =============================================================================
set -euo pipefail

BROKER="${KAFKA_BOOTSTRAP_SERVERS:-localhost:29092}"
RF=1            # replication factor dev = 1
KT="docker exec sq_kafka kafka-topics --bootstrap-server kafka:9092"

echo ">>> Creating topics on ${BROKER} (via container sq_kafka)..."

# Helper: tạo topic nếu chưa có (idempotent)
create() {
    local name=$1 partitions=$2 retention_ms=$3 extra=${4:-}
    $KT --create --if-not-exists \
        --topic "$name" \
        --partitions "$partitions" \
        --replication-factor $RF \
        --config retention.ms="$retention_ms" \
        $extra
    echo "  ✓ $name (partitions=$partitions, retention=${retention_ms}ms)"
}

# Retention helpers (ms)
D7=$((7*24*3600*1000))
D3=$((3*24*3600*1000))
D14=$((14*24*3600*1000))
D30=$((30*24*3600*1000))
D90=$((90*24*3600*1000))

create exam.attempt.submitted.v1   20 $D7
create exam.answer.submitted.v1    20 $D7
create grading.request.v1          10 $D3
create grading.request.v1.DLQ       3 $D14
create grading.result.v1           10 $D7
create cheat.event.raw.v1          20 $D30
create cheat.alert.generated.v1    10 $D90
create auth.role.changed.v1         3 $D7

echo ">>> Done. List:"
$KT --list | grep -E '^(exam|grading|cheat|auth)\.'
