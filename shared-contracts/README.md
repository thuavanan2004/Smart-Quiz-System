# shared-contracts

Hợp đồng (contract) dùng chung giữa các service để tránh dependency lẻ tẻ
và giúp producer/consumer tiến hoá độc lập.

## Bố cục

```
shared-contracts/
├── avro/          # Avro schema cho Kafka events (source of truth)
├── openapi/       # OpenAPI 3.1 cho REST API của từng service
└── proto/         # Protobuf cho inter-service gRPC (nếu có, chưa cần ngay)
```

## Compatibility mode

- **Avro** đăng ký ở Schema Registry với `BACKWARD` compatibility (default):
  - Producer mới có thể thêm field optional, đổi default.
  - Consumer cũ vẫn đọc được.
  - KHÔNG cho phép: xoá field required, đổi type không tương thích.
- Nếu cần breaking change: bump major version schema và tạo topic `v2`,
  song song chạy cả hai cho tới khi consumer migrate xong.

## Quy trình thêm / sửa schema

1. Mở PR sửa file `.avsc` / `.yaml` / `.proto`.
2. CI chạy `schema-registry-ci` kiểm tra compatibility chống lại registry staging.
3. Reviewer check: ownership (service nào produce, service nào consume), deploy order.
4. Merge → CI auto-register schema lên Schema Registry dev, staging, prod (theo tag).

## Deploy order

- Thêm field: **producer deploy trước** (mới push có field), consumer sau (biết cách đọc).
- Xoá field: **consumer deploy trước** (không đọc field đó nữa), producer sau.
- Luôn forward-compatible trong 1 release cycle.

## Code generation

- **Java**: Gradle plugin `com.github.davidmc24.gradle.plugin.avro` sinh POJO.
- **Python**: `avro-python3` + `fastavro` runtime.
- **TypeScript**: `@kafkajs/confluent-schema-registry` + `avsc` — sinh type từ schema.
- **OpenAPI**: `openapi-generator` sinh Java Spring stubs + TS axios client.

Sinh vào thư mục `**/generated/` (đã ignore trong .gitignore).
