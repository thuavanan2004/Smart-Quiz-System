#!/usr/bin/env bash
# =============================================================================
# ops/kafka/create-topics.sh — tạo 10 Kafka topic cho SmartQuizSystem (DATN)
# =============================================================================
# Chạy sau khi infra/docker-compose.dev.yml up -d đã khoẻ (Kafka healthy).
# Idempotent: tạo topic nếu chưa có, bỏ qua nếu đã tồn tại.
#
# Topic list: xem docs/design.md §6 (10 topic JSON).
#
# Usage:
#   bash ops/kafka/create-topics.sh
# =============================================================================

set -euo pipefail

KAFKA_CONTAINER="${KAFKA_CONTAINER:-sq-kafka}"
BROKER_INTERNAL="${BROKER_INTERNAL:-localhost:9092}"
PARTITIONS="${PARTITIONS:-3}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-1}"
RETENTION_MS="${RETENTION_MS:-604800000}"   # 7 ngày

TOPICS=(
    "exam.answer.submitted.v1"
    "exam.attempt.submitted.v1"
    "grading.request.v1"
    "grading.result.v1"
    "cheat.event.raw.v1"
    "cheat.alert.v1"
    "question.generation.request.v1"
    "question.generation.result.v1"
    "tutor.explanation.request.v1"
    "tutor.explanation.result.v1"
)

if ! docker ps --format '{{.Names}}' | grep -q "^${KAFKA_CONTAINER}$"; then
    echo "[error] Kafka container '${KAFKA_CONTAINER}' không chạy. Chạy:" >&2
    echo "  docker compose -f infra/docker-compose.dev.yml up -d" >&2
    exit 1
fi

echo "[info] Tạo ${#TOPICS[@]} topic trên broker ${BROKER_INTERNAL}..."
for topic in "${TOPICS[@]}"; do
    docker exec "${KAFKA_CONTAINER}" kafka-topics.sh \
        --bootstrap-server "${BROKER_INTERNAL}" \
        --create \
        --if-not-exists \
        --topic "${topic}" \
        --partitions "${PARTITIONS}" \
        --replication-factor "${REPLICATION_FACTOR}" \
        --config "retention.ms=${RETENTION_MS}" \
        --config "cleanup.policy=delete" \
        --config "min.insync.replicas=1" \
        > /dev/null
    echo "  ✓ ${topic}"
done

echo ""
echo "[done] Danh sách topic hiện có:"
docker exec "${KAFKA_CONTAINER}" kafka-topics.sh \
    --bootstrap-server "${BROKER_INTERNAL}" --list | sort
