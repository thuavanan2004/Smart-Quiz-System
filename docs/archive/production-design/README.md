# Production Design (archived)

Các doc trong thư mục này là **thiết kế production-ready ban đầu** của SmartQuizSystem,
được lưu lại làm tham chiếu và cho phần defend future-work khi bảo vệ đồ án.

**KHÔNG implement theo các doc này.** Scope thực tế áp dụng cho DATN là
`docs/scope-datn.md` + các doc service trong `docs/*-service-design.md`.

## Chuyển đổi sang DATN scope

| Doc production                              | Thay thế bằng                                                |
| ------------------------------------------- | ------------------------------------------------------------ |
| `design.md` (6 service, full NFR)           | `docs/design.md` (4 service, DATN scope)                     |
| `database.md` (PG + Mongo + CH + ES)        | `docs/database.md` (PG only + pgvector)                      |
| `auth-service-design.md`                    | `docs/auth-service-design.md` (lite)                         |
| `exam-service-design.md`                    | `docs/core-service-design.md` (gộp)                          |
| `question-service-design.md`                | `docs/core-service-design.md` (gộp)                          |
| `analytics-service-design.md`               | `docs/core-service-design.md` (view trong PG)                |
| `ai-service-design.md`                      | `docs/ai-service-design.md` (Combo A)                        |
| `cheating-detection-service-design.md`      | `docs/proctoring-service-design.md` (L1–L3)                  |

Xem `docs/adr/ADR-003-datn-scope.md` cho quyết định + alternatives đã cân nhắc.

## Khi nào đọc doc này

- Hội đồng hỏi "scale thật thì làm thế nào" → dẫn sang doc tương ứng.
- Post-DATN, muốn biến DATN thành production → dùng làm roadmap migration.
- Tham khảo thuật toán đo lường giáo dục (IRT, DIF) cho future work.
