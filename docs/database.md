**THIẾT KẾ CƠ SỞ DỮ LIỆU HOÀN THIỆN**

**Hệ Thống Thi Trực Tuyến Thông Minh**

Online Smart Quiz System (AI + Anti-Cheating)

PostgreSQL | MongoDB | Redis | ClickHouse | Elasticsearch

Tháng 4 năm 2026 | Phiên bản 1.0 | Tài liệu Nội bộ

| **PostgreSQL**<br><br>Auth, Thi, Kết Quả | **MongoDB**<br><br>Ngân Hàng Câu Hỏi | **Redis**<br><br>Cache + Session | **ClickHouse**<br><br>Analytics OLAP | **Elasticsearch**<br><br>Tìm Kiếm + Vector |
| ---------------------------------------- | ------------------------------------ | -------------------------------- | ------------------------------------ | ------------------------------------------ |

**I. TỔNG QUAN KIẾN TRÚC DATABASE**

## **1.1 Chiến Lược Lựa Chọn Đa CSDL (Polyglot Persistence)**

Hệ thống áp dụng chiến lược Polyglot Persistence -- mỗi loại dữ liệu được lưu trong kho phù hợp nhất với đặc tính của nó, thay vì ép tất cả vào một CSDL duy nhất. Mỗi lựa chọn được cân nhắc kỹ lưỡng dựa trên tính chất dữ liệu, mô hình truy cập, yêu cầu độ trễ và quy mô.

| **Cơ Sở Dữ Liệu** | **Vai Trò Chính**                                                | **Lý Do Chọn**                                                         | **Quy Mô Dữ Liệu** |
| ----------------- | ---------------------------------------------------------------- | ---------------------------------------------------------------------- | ------------------ |
| PostgreSQL 16     | Dữ liệu quan hệ: người dùng, bài thi, lượt thi, kết quả, tổ chức | ACID đầy đủ; khóa ngoại; join phức tạp; query planner mạnh             | ~500 GB sau 2 năm  |
| MongoDB 7         | Ngân hàng câu hỏi với schema dị nhất                             | Schema linh hoạt cho 8+ loại câu hỏi; text search; vector index native | ~200 GB sau 2 năm  |
| Redis 7 Cluster   | Session thi, cache nóng, pub/sub, hàng đợi                       | Độ trễ dưới 1ms; TTL tự động; Sorted Set cho bảng xếp hạng             | ~50 GB RAM         |
| ClickHouse        | Analytics OLAP: phân tích kết quả, báo cáo, thống kê             | Quét 100+ triệu dòng trong < 2s; nén dữ liệu cột 10:1                  | ~5 TB sau 2 năm    |
| Elasticsearch 8   | Tìm kiếm văn bản + vector (KNN) cho câu hỏi                      | Tìm kiếm phân tán; embedding KNN; faceted search                       | ~100 GB            |

## **1.2 Sơ Đồ Quan Hệ Giữa Các Kho Dữ Liệu**

| +---------------------------+                                |
| ------------------------------------------------------------ |
| \| API SERVICES \|                                           |
| +---+-------+-------+-------+                                |
| \| \| \| \|                                                  |
| +------------------+ +----+ +---+ +--+--------+              |
| \| PostgreSQL 16 \| \|MongoDB\| \|Redis\| \|ClickHouse\|     |
| \| (Source of Truth)\| \| 7.0 \| \| 7.0 \| \| 23.x \|        |
| \| \| \| \| \| \| \| \|                                      |
| \| users \| \|questions\| \|session\| \|exam_facts\|         |
| \| organizations \| \|question \| \|cache \| \|answer_log\|  |
| \| exams \| \|\_versions\| \|rate \| \|cheat_log \|          |
| \| exam_questions \| \|question \| \|limit \| \|daily_agg \| |
| \| exam_attempts \| \|\_tags \| \|leaderb\| \| \|            |
| \| attempt_answers \| \| \| \|oard \| \| \|                  |
| \| cheat_events \| +----+----+ +---+---+ +----+-----+        |
| \| grading_results \| \| \| \|                               |
| \| certificates \| +----+------------+--------+ \|           |
| \| analytics_events \| \| Elasticsearch 8 \| \|              |
| +------------------+ \| question_search_idx \| \|            |
| \| \| (text + vector/KNN) \| \|                              |
| \| +----------------------------+ \|                         |
| \| \|                                                        |
| +--\[CDC: Debezium\]------------------------------+          |
| (PostgreSQL -> Kafka -> ClickHouse)                          |

## **1.3 Nguyên Tắc Thiết Kế CSDL**

- Mọi bảng đều có khóa chính UUID v4 (gen_random_uuid()) -- tránh lộ rõ số lượng bản ghi
- Tất cả thời gian sử dụng TIMESTAMPTZ (UTC) -- tránh vấn đề timezone
- Soft delete với deleted_at TIMESTAMPTZ NULL -- không xóa vật lý dữ liệu quan trọng
- Audit trail: created_at, updated_at, created_by, updated_by trên mỗi bảng chính
- Enum được định nghĩa bằng PostgreSQL native ENUM type -- kiểm tra ràng buộc ở tầng CSDL
- Đặt tên theo quy ước snake_case, mô tả ý nghĩa rõ ràng
- Index: tạo sau khi xác định các query pattern thực tế, không tạo dự phòng
- Khóa ngoại với ON DELETE CASCADE hoặc RESTRICT tùy trường hợp nghiệp vụ

**II. POSTGRESQL -- CƠ SỞ DỮ LIỆU QUAN HỆ CHÍNH**

PostgreSQL là "source of truth" của hệ thống. Chứa toàn bộ dữ liệu quan hệ có giá trị cao nhất: danh tính người dùng, cấu hình bài thi, kết quả thi, chứng chỉ và sự kiện bảo mật.

**Nhóm 1: Tổ Chức & Người Dùng**

### **Bảng: organizations**

Lưu thông tin các tổ chức (trường học, công ty, trung tâm đào tạo) sử dụng hệ thống. Mục tiêu: đa thuê (multi-tenant).

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**                 | **Mô Tả**                                              |
| ---------------------- | ---------------- | ----------------------------- | ------------------------------------------------------ |
| **id**                 | UUID             | PK, DEFAULT gen_random_uuid() | Khóa chính                                             |
| **name**               | VARCHAR(300)     | NOT NULL                      | Tên tổ chức                                            |
| **slug**               | VARCHAR(100)     | UNIQUE NOT NULL               | Định danh URL (vd: dai-hoc-bach-khoa)                  |
| **plan_tier**          | VARCHAR(30)      | DEFAULT 'free'                | Gói dịch vụ: 'free' \| 'pro' \| 'enterprise'           |
| **max_users**          | INT              | DEFAULT 100                   | Giới hạn số lượng người dùng                           |
| **max_exams**          | INT              | DEFAULT 50                    | Giới hạn số bài thi đồng thời                          |
| **ai_enabled**         | BOOLEAN          | DEFAULT false                 | Cho phép dùng tính năng AI sinh câu hỏi                |
| **proctoring_enabled** | BOOLEAN          | DEFAULT false                 | Cho phép tính năng video giám thị                      |
| **settings**           | JSONB            | DEFAULT {}                    | Cấu hình riêng tổ chức (logo, màu sắc, chính sách thi) |
| **is_active**          | BOOLEAN          | DEFAULT true                  | Trạng thái hoạt động                                   |
| **created_at**         | TIMESTAMPTZ      | DEFAULT NOW()                 | Thời điểm tạo                                          |
| **deleted_at**         | TIMESTAMPTZ      | NULL                          | Soft delete                                            |

| \-- Index                                                                        |
| -------------------------------------------------------------------------------- |
| CREATE INDEX idx_orgs_slug ON organizations(slug);                               |
| CREATE INDEX idx_orgs_active ON organizations(is_active) WHERE is_active = true; |

### **Bảng: users**

Bảng trung tâm chứa thông tin người dùng. Liên kết với organizations theo quan hệ nhiều-nhiều qua user_organizations.

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**              | **Mô Tả**                                 |
| ---------------------- | ---------------- | -------------------------- | ----------------------------------------- |
| **id**                 | UUID             | PK                         | Khóa chính                                |
| **email**              | VARCHAR(320)     | UNIQUE NOT NULL            | Email (định danh duy nhất trong hệ thống) |
| **username**           | VARCHAR(80)      | UNIQUE                     | Tên đăng nhập (tùy chọn)                  |
| **full_name**          | VARCHAR(200)     | NOT NULL                   | Họ và tên đầy đủ                          |
| **avatar_url**         | VARCHAR(500)     | NULL                       | URL ảnh đại diện (S3)                     |
| **password_hash**      | VARCHAR(255)     | NULL                       | Argon2id hash (NULL nếu dùng OAuth)       |
| **mfa_secret**         | VARCHAR(64)      | NULL                       | TOTP secret (mã hóa AES-256 lúc lưu)      |
| **mfa_enabled**        | BOOLEAN          | DEFAULT false              | Đã bật MFA chưa                           |
| **email_verified**     | BOOLEAN          | DEFAULT false              | Đã xác thực email chưa                    |
| **locale**             | VARCHAR(10)      | DEFAULT 'vi-VN'            | Ngôn ngữ giao diện                        |
| **timezone**           | VARCHAR(60)      | DEFAULT 'Asia/Ho_Chi_Minh' | Múi giờ                                   |
| **last_login_at**      | TIMESTAMPTZ      | NULL                       | Lần đăng nhập cuối cùng                   |
| **last_login_ip**      | INET             | NULL                       | IP lần đăng nhập cuối                     |
| **failed_login_count** | SMALLINT         | DEFAULT 0                  | Số lần thất bại liên tiếp                 |
| **locked_until**       | TIMESTAMPTZ      | NULL                       | Khóa tài khoản tạm thời đến thời điểm này |
| **is_active**          | BOOLEAN          | DEFAULT true               | Tài khoản còn hoạt động                   |
| **created_at**         | TIMESTAMPTZ      | DEFAULT NOW()              | Thời điểm tạo                             |
| **updated_at**         | TIMESTAMPTZ      | DEFAULT NOW()              | Thời điểm cập nhật cuối                   |
| **deleted_at**         | TIMESTAMPTZ      | NULL                       | Soft delete                               |

| \-- Index                                                                 |
| ------------------------------------------------------------------------- |
| CREATE INDEX idx_users_email ON users(email);                             |
| CREATE INDEX idx_users_active ON users(is_active) WHERE is_active = true; |
| CREATE INDEX idx_users_last_login ON users(last_login_at DESC);           |

### **Bảng: user_organizations**

Quan hệ nhiều-nhiều giữa users và organizations. Một người có thể thuộc nhiều tổ chức với vai trò khác nhau.

| **Tên Cột**    | **Kiểu Dữ Liệu** | **Ràng Buộc**           | **Mô Tả**                                               |
| -------------- | ---------------- | ----------------------- | ------------------------------------------------------- |
| **user_id**    | UUID             | PK, FK -> users         | Khóa ngoại đến bảng users                               |
| **org_id**     | UUID             | PK, FK -> organizations | Khóa ngoại đến bảng organizations                       |
| **role**       | user_role        | NOT NULL                | ENUM: 'student' \| 'instructor' \| 'admin' \| 'proctor' |
| **joined_at**  | TIMESTAMPTZ      | DEFAULT NOW()           | Thời điểm tham gia tổ chức                              |
| **invited_by** | UUID             | FK -> users NULL        | Người mời tham gia                                      |
| **is_active**  | BOOLEAN          | DEFAULT true            | Còn là thành viên không                                 |

> **⚠️ SUPERSEDED:** Thiết kế ban đầu dùng `user_role` ENUM hardcode 4 giá trị đã được **refactor sang RBAC động** (bảng `roles` + `permissions` + `role_permissions`). Xem `database/postgresql/schema.sql` (mục 2a/2b) là nguồn sự thật hiện tại. 4 role mặc định giữ nguyên là system role (seed), có thể thêm custom role per org.

| CREATE INDEX idx_user_orgs_user ON user_organizations(user_id);              |
| ---------------------------------------------------------------------------- |
| CREATE INDEX idx_user_orgs_org ON user_organizations(org_id, role_id);       |

### **Bảng: oauth_providers**

Lưu kết nối đăng nhập qua OAuth (Google, Microsoft, GitHub). Một người dùng có thể có nhiều OAuth provider.

| **Tên Cột**          | **Kiểu Dữ Liệu** | **Ràng Buộc**        | **Mô Tả**                           |
| -------------------- | ---------------- | -------------------- | ----------------------------------- |
| **id**               | UUID             | PK                   | Khóa chính                          |
| **user_id**          | UUID             | FK -> users NOT NULL | Thuộc về người dùng nào             |
| **provider**         | VARCHAR(30)      | NOT NULL             | 'google' \| 'microsoft' \| 'github' |
| **provider_user_id** | VARCHAR(255)     | NOT NULL             | ID người dùng trên provider         |
| **access_token_enc** | TEXT             | NULL                 | Access token mã hóa (để refresh)    |
| **linked_at**        | TIMESTAMPTZ      | DEFAULT NOW()        | Thời điểm liên kết                  |

CREATE UNIQUE INDEX idx_oauth_provider ON oauth_providers(provider, provider_user_id);

### **Bảng: refresh_tokens**

Lưu refresh token để duy trì phiên đăng nhập. Access token (JWT 15 phút) không lưu DB -- chỉ refresh token mới được lưu.

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**        | **Mô Tả**                                          |
| ---------------------- | ---------------- | -------------------- | -------------------------------------------------- |
| **id**                 | UUID             | PK                   | Khóa chính                                         |
| **user_id**            | UUID             | FK -> users NOT NULL | Chủ sở hữu token                                   |
| **token_hash**         | VARCHAR(64)      | UNIQUE NOT NULL      | SHA-256 của refresh token (không lưu plaintext)    |
| **device_fingerprint** | VARCHAR(128)     | NULL                 | Fingerprint thiết bị để phát hiện dùng chung token |
| **user_agent**         | VARCHAR(512)     | NULL                 | Trình duyệt / ứng dụng                             |
| **ip_address**         | INET             | NULL                 | IP khi tạo token                                   |
| **expires_at**         | TIMESTAMPTZ      | NOT NULL             | Token hết hạn khi nào                              |
| **revoked**            | BOOLEAN          | DEFAULT false        | Đã bị thu hồi chưa                                 |
| **revoked_at**         | TIMESTAMPTZ      | NULL                 | Thời điểm thu hồi                                  |
| **created_at**         | TIMESTAMPTZ      | DEFAULT NOW()        | Thời điểm tạo                                      |

| CREATE INDEX idx_refresh_user ON refresh_tokens(user_id, expires_at);   |
| ----------------------------------------------------------------------- |
| CREATE INDEX idx_refresh_token ON refresh_tokens(token_hash);           |
| \-- Xóa tự động các token hết hạn (chạy hàng ngày)                      |
| DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '1 day'; |

**Nhóm 2: Bài Thi & Cấu Hình**

### **Bảng: subjects**

Danh mục môn học / lĩnh vực kiến thức. Được dùng để phân loại câu hỏi và bài thi.

| **Tên Cột**     | **Kiểu Dữ Liệu** | **Ràng Buộc**            | **Mô Tả**                              |
| --------------- | ---------------- | ------------------------ | -------------------------------------- |
| **id**          | UUID             | PK                       | Khóa chính                             |
| **org_id**      | UUID             | FK -> organizations NULL | NULL = dùng chung toàn hệ thống        |
| **name**        | VARCHAR(200)     | NOT NULL                 | Tên môn học (Toán, Vật Lý, Lịch Sử...) |
| **code**        | VARCHAR(30)      | UNIQUE NOT NULL          | Mã môn học (MATH101, PHY201...)        |
| **parent_id**   | UUID             | FK -> subjects NULL      | Chủ đề cha (cây phân cấp)              |
| **description** | TEXT             | NULL                     | Mô tả chi tiết                         |
| **is_active**   | BOOLEAN          | DEFAULT true             | Đang sử dụng                           |

### **Bảng: exams**

Bảng trung tâm cho mỗi bài thi. Chứa toàn bộ cấu hình: loại thi, thời gian, cách thức giám sát, chính sách bảo mật.

| **Tên Cột**                 | **Kiểu Dữ Liệu** | **Ràng Buộc**                | **Mô Tả**                                                      |
| --------------------------- | ---------------- | ---------------------------- | -------------------------------------------------------------- |
| **id**                      | UUID             | PK                           | Khóa chính                                                     |
| **org_id**                  | UUID             | FK -> organizations NOT NULL | Thuộc tổ chức nào                                              |
| **subject_id**              | UUID             | FK -> subjects NULL          | Môn học tương ứng                                              |
| **created_by**              | UUID             | FK -> users NOT NULL         | Giáo viên tạo bài thi                                          |
| **title**                   | VARCHAR(500)     | NOT NULL                     | Tên bài thi                                                    |
| **description**             | TEXT             | NULL                         | Mô tả / hướng dẫn làm bài                                      |
| **instructions**            | TEXT             | NULL                         | Nội quy thi cụ thể                                             |
| **status**                  | exam_status      | DEFAULT draft                | ENUM: draft\|published\|scheduled\|active\|completed\|archived |
| **exam_type**               | VARCHAR(30)      | DEFAULT 'standard'           | 'standard' \| 'adaptive' \| 'practice' \| 'survey'             |
| **duration_minutes**        | INT              | NOT NULL                     | Tổng thời gian làm bài (phút)                                  |
| **max_attempts**            | SMALLINT         | DEFAULT 1                    | Số lần thi tối đa trên người                                   |
| **passing_score**           | NUMERIC(5,2)     | NULL                         | Điểm đạt (%). NULL = không có ngưỡng                           |
| **total_points**            | NUMERIC(8,2)     | NOT NULL                     | Tổng điểm tối đa của bài thi                                   |
| **shuffle_questions**       | BOOLEAN          | DEFAULT false                | Xáo trộn thứ tự câu hỏi                                        |
| **shuffle_options**         | BOOLEAN          | DEFAULT false                | Xáo trộn thứ tự đáp án                                         |
| **show_result_immediately** | BOOLEAN          | DEFAULT true                 | Hiện kết quả ngay sau khi nộp                                  |
| **show_correct_answers**    | BOOLEAN          | DEFAULT false                | Hiện đáp án đúng sau khi thi                                   |
| **allow_review**            | BOOLEAN          | DEFAULT false                | Cho phép xem lại bài sau khi nộp                               |
| **proctoring_level**        | SMALLINT         | DEFAULT 0                    | 0=không \| 1=cơ bản \| 2=có video                              |
| **lockdown_browser**        | BOOLEAN          | DEFAULT false                | Bắt buộc dùng trình duyệt khóa                                 |
| **ip_whitelist**            | INET\[\]         | NULL                         | Chỉ cho phép thi từ danh sách IP này                           |
| **password_protected**      | BOOLEAN          | DEFAULT false                | Có mật khẩu truy cập                                           |
| **access_password_hash**    | VARCHAR(255)     | NULL                         | Hash mật khẩu truy cập bài thi                                 |
| **starts_at**               | TIMESTAMPTZ      | NULL                         | Thời điểm mở bài thi (NULL=mở ngay)                            |
| **ends_at**                 | TIMESTAMPTZ      | NULL                         | Thời điểm đóng bài thi (NULL=không giới hạn)                   |
| **grace_period_minutes**    | SMALLINT         | DEFAULT 0                    | Gia hạn thêm X phút sau ends_at                                |
| **created_at**              | TIMESTAMPTZ      | DEFAULT NOW()                | Thời điểm tạo                                                  |
| **updated_at**              | TIMESTAMPTZ      | DEFAULT NOW()                | Thời điểm cập nhật                                             |
| **published_at**            | TIMESTAMPTZ      | NULL                         | Thời điểm công bố                                              |
| **deleted_at**              | TIMESTAMPTZ      | NULL                         | Soft delete                                                    |

| CREATE TYPE exam_status AS ENUM ('draft','published','scheduled','active','completed','archived'); |
| -------------------------------------------------------------------------------------------------- |
| CREATE INDEX idx_exams_org ON exams(org_id, status);                                               |
| CREATE INDEX idx_exams_creator ON exams(created_by);                                               |
| CREATE INDEX idx_exams_active ON exams(status, starts_at, ends_at)                                 |
| WHERE status IN ('published','scheduled','active');                                                |

### **Bảng: exam_sections**

Một bài thi có thể có nhiều phần (Section A, B, C...). Mỗi phần có thể có cấu hình riêng về thời gian, số câu, điểm số.

| **Tên Cột**             | **Kiểu Dữ Liệu** | **Ràng Buộc**        | **Mô Tả**                                       |
| ----------------------- | ---------------- | -------------------- | ----------------------------------------------- |
| **id**                  | UUID             | PK                   | Khóa chính                                      |
| **exam_id**             | UUID             | FK -> exams NOT NULL | Thuộc bài thi nào                               |
| **title**               | VARCHAR(200)     | NOT NULL             | Tên phần thi (Phần A: Trắc nghiệm...)           |
| **description**         | TEXT             | NULL                 | Hướng dẫn phần này                              |
| **order_index**         | SMALLINT         | NOT NULL             | Thứ tự hiển thị                                 |
| **time_limit_minutes**  | SMALLINT         | NULL                 | Giới hạn thời gian riêng cho phần này           |
| **question_count**      | SMALLINT         | NOT NULL             | Số câu hỏi trong phần này                       |
| **points_per_question** | NUMERIC(6,2)     | NULL                 | Điểm đều cho mỗi câu (NULL=định nghĩa từng câu) |
| **penalty_per_wrong**   | NUMERIC(6,2)     | DEFAULT 0            | Điểm trừ khi trả lời sai (cho trắc nghiệm)      |

### **Bảng: exam_questions**

Bảng trung gian kết nối bài thi và câu hỏi. Chứa thứ tự, điểm số, và cấu hình hiển thị từng câu trong bài thi cụ thể.

| **Tên Cột**          | **Kiểu Dữ Liệu** | **Ràng Buộc**            | **Mô Tả**                                |
| -------------------- | ---------------- | ------------------------ | ---------------------------------------- |
| **id**               | UUID             | PK                       | Khóa chính                               |
| **exam_id**          | UUID             | FK -> exams NOT NULL     | Thuộc bài thi nào                        |
| **section_id**       | UUID             | FK -> exam_sections NULL | Thuộc phần nào (nếu có)                  |
| **question_ref_id**  | VARCHAR(36)      | NOT NULL                 | ID câu hỏi trên MongoDB (không FK)       |
| **question_version** | INT              | DEFAULT 1                | Phiên bản câu hỏi tại thời điểm thêm vào |
| **order_index**      | SMALLINT         | NOT NULL                 | Thứ tự trong bài / trong phần            |
| **points**           | NUMERIC(6,2)     | NOT NULL                 | Điểm cho câu hỏi này                     |
| **is_required**      | BOOLEAN          | DEFAULT true             | Bắt buộc trả lời (với adaptive)          |
| **display_mode**     | VARCHAR(20)      | DEFAULT 'standard'       | 'standard' \| 'one_per_page'             |

| CREATE INDEX idx_eq_exam ON exam_questions(exam_id, order_index);                     |
| ------------------------------------------------------------------------------------- |
| CREATE INDEX idx_eq_section ON exam_questions(section_id);                            |
| \-- Ràng buộc: thứ tự duy nhất trong bài thi                                          |
| CREATE UNIQUE INDEX idx_eq_order ON exam_questions(exam_id, section_id, order_index); |

### **Bảng: exam_enrollments**

Quản lý danh sách học sinh được phép thi. Có thể import hàng loạt. NULL org_id = tất cả người dùng trong org.

| **Tên Cột**                 | **Kiểu Dữ Liệu** | **Ràng Buộc**        | **Mô Tả**                                               |
| --------------------------- | ---------------- | -------------------- | ------------------------------------------------------- |
| **id**                      | UUID             | PK                   | Khóa chính                                              |
| **exam_id**                 | UUID             | FK -> exams NOT NULL | Bài thi cụ thể                                          |
| **user_id**                 | UUID             | FK -> users NOT NULL | Học sinh được đăng ký                                   |
| **enrolled_by**             | UUID             | FK -> users NULL     | Ai đăng ký (NULL = tự đăng ký)                          |
| **enrolled_at**             | TIMESTAMPTZ      | DEFAULT NOW()        | Thời điểm đăng ký                                       |
| **custom_duration_minutes** | INT              | NULL                 | Gia hạn thời gian đặc biệt (hỗ trợ học sinh khuyết tật) |
| **custom_starts_at**        | TIMESTAMPTZ      | NULL                 | Giờ thi đặc biệt cho cá nhân                            |

CREATE UNIQUE INDEX idx_enrollment ON exam_enrollments(exam_id, user_id);

**Nhóm 3: Lượt Thi & Đáp Án**

### **Bảng: exam_attempts**

Mỗi lần học sinh bắt đầu làm một bài thi tạo ra một bản ghi trong bảng này. Đây là bảng hoạt động nhất trong hệ thống.

| **Tên Cột**                | **Kiểu Dữ Liệu** | **Ràng Buộc**          | **Mô Tả**                                                           |
| -------------------------- | ---------------- | ---------------------- | ------------------------------------------------------------------- |
| **id**                     | UUID             | PK                     | Khóa chính -- dùng làm session ID                                   |
| **exam_id**                | UUID             | FK -> exams NOT NULL   | Bài thi đang làm                                                    |
| **user_id**                | UUID             | FK -> users NOT NULL   | Học sinh làm bài                                                    |
| **attempt_number**         | SMALLINT         | DEFAULT 1              | Đây là lần thứ mấy (trong max_attempts)                             |
| **status**                 | attempt_status   | DEFAULT in_progress    | ENUM: in_progress\|submitted\|graded\|suspended\|expired\|cancelled |
| **started_at**             | TIMESTAMPTZ      | NOT NULL DEFAULT NOW() | Thời điểm bắt đầu làm bài                                           |
| **submitted_at**           | TIMESTAMPTZ      | NULL                   | Thời điểm nộp bài (NULL = chưa nộp)                                 |
| **graded_at**              | TIMESTAMPTZ      | NULL                   | Thời điểm chấm xong                                                 |
| **expires_at**             | TIMESTAMPTZ      | NOT NULL               | Thời điểm bài tự động hết hạn (started_at + duration)               |
| **time_spent_seconds**     | INT              | DEFAULT 0              | Thực tế thời gian làm bài (giữ trừ thời gian pause)                 |
| **raw_score**              | NUMERIC(8,2)     | NULL                   | Điểm số thu được                                                    |
| **max_score**              | NUMERIC(8,2)     | NOT NULL               | Điểm tối đa của bài thi này                                         |
| **percentage_score**       | NUMERIC(5,2)     | NULL                   | Phần trăm điểm đạt được                                             |
| **passed**                 | BOOLEAN          | NULL                   | Có đạt không (NULL nếu chưa chấm)                                   |
| **risk_score**             | SMALLINT         | DEFAULT 0              | Tổng điểm rủi ro gian lận (0-100+)                                  |
| **flagged_for_review**     | BOOLEAN          | DEFAULT false          | Đánh dấu cần kiểm tra thủ công                                      |
| **flagged_reason**         | TEXT             | NULL                   | Lý do bị đánh dấu                                                   |
| **ip_address**             | INET             | NULL                   | IP lúc bắt đầu thi                                                  |
| **user_agent**             | VARCHAR(512)     | NULL                   | Trình duyệt / thiết bị                                              |
| **geo_country**            | VARCHAR(3)       | NULL                   | Mã quốc gia (ISO)                                                   |
| **geo_city**               | VARCHAR(100)     | NULL                   | Thành phố (từ GeoIP)                                                |
| **current_question_index** | SMALLINT         | DEFAULT 0              | Vị trí câu hỏi đang hiển thị                                        |
| **question_order**         | UUID\[\]         | NULL                   | Mảng ID câu hỏi đã xáo trộn cho lượt thi này                        |
| **adaptive_theta**         | FLOAT8           | NULL                   | Ước tính năng lực IRT hiện tại (adaptive exam)                      |
| **adaptive_se**            | FLOAT8           | NULL                   | Sai số chuẩn của theta (Standard Error)                             |

| CREATE TYPE attempt_status AS ENUM                                      |
| ----------------------------------------------------------------------- |
| ('in_progress','submitted','graded','suspended','expired','cancelled'); |
|                                                                         |
| CREATE INDEX idx_attempts_exam_user ON exam_attempts(exam_id, user_id); |
| CREATE INDEX idx_attempts_active ON exam_attempts(status, expires_at)   |
| WHERE status = 'in_progress';                                           |
| CREATE INDEX idx_attempts_flagged ON exam_attempts(flagged_for_review)  |
| WHERE flagged_for_review = true;                                        |
| \-- Ràng buộc: tối đa 1 lượt đang làm trên mỗi bài thi                  |
| CREATE UNIQUE INDEX idx_one_active_attempt                              |
| ON exam_attempts(exam_id, user_id)                                      |
| WHERE status = 'in_progress';                                           |

### **Bảng: attempt_answers**

Lưu đáp án của học sinh cho từng câu hỏi trong một lượt thi. Đây là bảng lớn nhất -- cần phân mảnh nếu vượt 10 tỷ bản ghi.

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**                             | **Mô Tả**                           |
| ---------------------- | ---------------- | ----------------------------------------- | ----------------------------------- |
| **id**                 | UUID             | PK                                        | Khóa chính                          |
| **attempt_id**         | UUID             | FK -> exam_attempts NOT NULL              | Thuộc lượt thi nào                  |
| **question_ref_id**    | VARCHAR(36)      | NOT NULL                                  | ID câu hỏi (MongoDB ref)            |
| **exam_question_id**   | UUID             | FK -> exam_questions NOT NULL             | Liên kết với câu hỏi trong bài thi  |
| **answer_data**        | JSONB            | NOT NULL DEFAULT {}                       | Dữ liệu đáp án (xem bên dưới)       |
| **is_correct**         | BOOLEAN          | NULL                                      | Đúng hay sai (NULL = chưa chấm)     |
| **points_earned**      | NUMERIC(6,2)     | NULL                                      | Điểm đạt được cho câu này           |
| **partial_credit**     | BOOLEAN          | DEFAULT false                             | Chấm điểm một phần                  |
| **grading_method**     | VARCHAR(20)      | DEFAULT 'auto'                            | 'auto' \| 'ai_assisted' \| 'manual' |
| **graded_by**          | UUID             | FK -> users NULL                          | Người chấm (NULL = tự động)         |
| **grader_comment**     | TEXT             | NULL                                      | Nhận xét của người chấm             |
| **time_spent_seconds** | SMALLINT         | DEFAULT 0                                 | Thời gian làm câu này               |
| **answered_at**        | TIMESTAMPTZ      | DEFAULT NOW()                             | Thời điểm trả lời lần đầu           |
| **last_modified_at**   | TIMESTAMPTZ      | DEFAULT NOW()                             | Lần chỉnh sửa cuối cùng             |
| **submission_id**      | UUID             | UNIQUE NOT NULL DEFAULT gen_random_uuid() | ID idempotent chống nộp trùng       |

| \-- Cấu trúc answer_data JSONB tùy theo loại câu hỏi:                  |
| ---------------------------------------------------------------------- |
| \-- Trắc nghiệm: { "selected_options": \["opt_b"\] }                   |
| \-- Nhiều chọn: { "selected_options": \["opt_a","opt_c"\] }            |
| \-- Điền chỗ trống:{ "text": "Merge Sort" }                            |
| \-- Essay: { "text": "...", "word_count": 350 }                        |
| \-- Sắp xếp: { "order": \["item_3","item_1","item_2"\] }               |
| \-- Code: { "language": "python", "code": "def solve():..." }          |
|                                                                        |
| CREATE INDEX idx_answers_attempt ON attempt_answers(attempt_id);       |
| CREATE INDEX idx_answers_question ON attempt_answers(question_ref_id); |
| \-- Partition theo tháng để quản lý dữ liệu lớn                        |
| \-- ALTER TABLE attempt_answers PARTITION BY RANGE (answered_at);      |

**Nhóm 4: Chống Gian Lận & Giám Sát**

### **Bảng: cheat_events**

Ghi lại từng sự kiện nghi vấn trong quá trình thi. Mỗi sự kiện có trọng số khác nhau, tổng hợp thành risk_score trong exam_attempts.

| **Tên Cột**          | **Kiểu Dữ Liệu** | **Ràng Buộc**                | **Mô Tả**                                                              |
| -------------------- | ---------------- | ---------------------------- | ---------------------------------------------------------------------- |
| **id**               | UUID             | PK                           | Khóa chính                                                             |
| **attempt_id**       | UUID             | FK -> exam_attempts NOT NULL | Thuộc lượt thi nào                                                     |
| **user_id**          | UUID             | FK -> users NOT NULL         | Học sinh bị ghi nhận                                                   |
| **event_type**       | cheat_event_type | NOT NULL                     | Loại sự kiện (xem enum bên dưới)                                       |
| **event_layer**      | SMALLINT         | NOT NULL                     | Tầng phát hiện: 1=Client 2=Browser 3=Mạng 4=Hành vi 5=Video 6=Thống kê |
| **severity**         | VARCHAR(10)      | NOT NULL                     | 'low' \| 'medium' \| 'high' \| 'critical'                              |
| **risk_delta**       | SMALLINT         | NOT NULL                     | Điểm rủi ro thêm vào (có thể âm để bổ trừ)                             |
| **event_data**       | JSONB            | DEFAULT {}                   | Dữ liệu chi tiết (URL tab, IP mới, tọa độ...)                          |
| **client_timestamp** | TIMESTAMPTZ      | NOT NULL                     | Thời điểm phát sinh phía client                                        |
| **server_timestamp** | TIMESTAMPTZ      | DEFAULT NOW()                | Thời điểm ghi nhận phía server                                         |
| **question_index**   | SMALLINT         | NULL                         | Đang làm câu số bao nhiêu                                              |
| **auto_action**      | VARCHAR(30)      | NULL                         | Hành động tự động: 'warn' \| 'suspend' \| 'terminate'                  |
| **reviewed_by**      | UUID             | FK -> users NULL             | Giám thị đã xem xét                                                    |
| **review_decision**  | VARCHAR(20)      | NULL                         | 'confirmed' \| 'dismissed' \| 'escalated'                              |
| **reviewed_at**      | TIMESTAMPTZ      | NULL                         | Thời điểm xem xét                                                      |

| CREATE TYPE cheat_event_type AS ENUM (                                        |
| ----------------------------------------------------------------------------- |
| 'tab_switch','window_blur','fullscreen_exit','copy_event','paste_event',      |
| 'right_click','devtools_open','keyboard_shortcut','context_menu',             |
| 'ip_change','vpn_detected','multiple_ip','geolocation_change',                |
| 'typing_anomaly','answer_speed_anomaly','idle_too_long','answer_pattern',     |
| 'face_missing','multiple_faces','phone_detected','gaze_off_screen',           |
| 'audio_detected','environment_issue',                                         |
| 'answer_similarity','sync_submission','score_anomaly'                         |
| );                                                                            |
|                                                                               |
| CREATE INDEX idx_cheat_attempt ON cheat_events(attempt_id, server_timestamp); |
| CREATE INDEX idx_cheat_type ON cheat_events(event_type, severity);            |
| CREATE INDEX idx_cheat_unreviewed ON cheat_events(reviewed_by)                |
| WHERE reviewed_by IS NULL AND auto_action IS NOT NULL;                        |

### **Bảng: proctoring_sessions**

Lưu thông tin phiên video giám thị (chỉ khi proctoring_level = 2). Liên kết với file video trên S3.

| **Tên Cột**               | **Kiểu Dữ Liệu** | **Ràng Buộc**              | **Mô Tả**                                   |
| ------------------------- | ---------------- | -------------------------- | ------------------------------------------- |
| **id**                    | UUID             | PK                         | Khóa chính                                  |
| **attempt_id**            | UUID             | FK -> exam_attempts UNIQUE | Mỗi lượt thi chỉ có 1 phiên giám thị        |
| **status**                | VARCHAR(20)      | DEFAULT 'active'           | 'active' \| 'paused' \| 'ended' \| 'failed' |
| **video_s3_key**          | VARCHAR(500)     | NULL                       | Đường dẫn video trên S3                     |
| **thumbnail_s3_key**      | VARCHAR(500)     | NULL                       | Ảnh đại diện phiên giám thị                 |
| **total_frames_analyzed** | INT              | DEFAULT 0                  | Tổng số khung hình đã phân tích             |
| **face_detected_frames**  | INT              | DEFAULT 0                  | Số khung hình có mặt                        |
| **face_missing_frames**   | INT              | DEFAULT 0                  | Số khung hình mất khuất                     |
| **multi_face_frames**     | INT              | DEFAULT 0                  | Số khung hình nhiều hơn 1 mặt               |
| **phone_detected_frames** | INT              | DEFAULT 0                  | Số khung hình có điện thoại                 |
| **ai_risk_summary**       | JSONB            | DEFAULT {}                 | Tóm tắt phân tích AI cuối phiên             |
| **started_at**            | TIMESTAMPTZ      | DEFAULT NOW()              | Bắt đầu giám thị                            |
| **ended_at**              | TIMESTAMPTZ      | NULL                       | Kết thúc giám thị                           |

**Nhóm 5: Kết Quả, Phản Hồi & Chứng Chỉ**

### **Bảng: grading_rubrics**

Định nghĩa thang điểm và tiêu chí chấm cho từng bài thi. Đặc biệt quan trọng cho essay và bài tự luận.

| **Tên Cột**    | **Kiểu Dữ Liệu** | **Ràng Buộc**        | **Mô Tả**                                     |
| -------------- | ---------------- | -------------------- | --------------------------------------------- |
| **id**         | UUID             | PK                   | Khóa chính                                    |
| **exam_id**    | UUID             | FK -> exams NOT NULL | Áp dụng cho bài thi nào                       |
| **name**       | VARCHAR(200)     | NOT NULL             | Tên thang điểm                                |
| **criteria**   | JSONB            | NOT NULL             | Tiêu chí: \[{name, max_points, description}\] |
| **created_by** | UUID             | FK -> users NOT NULL | Giáo viên tạo rubric                          |
| **created_at** | TIMESTAMPTZ      | DEFAULT NOW()        | Thời điểm tạo                                 |

### **Bảng: attempt_feedback**

Phản hồi chi tiết của giáo viên / AI cho từng lượt thi. Học sinh có thể xem sau khi giáo viên công bố.

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**              | **Mô Tả**                      |
| ---------------------- | ---------------- | -------------------------- | ------------------------------ |
| **id**                 | UUID             | PK                         | Khóa chính                     |
| **attempt_id**         | UUID             | FK -> exam_attempts UNIQUE | Cho lượt thi nào               |
| **overall_feedback**   | TEXT             | NULL                       | Nhận xét tổng thể              |
| **strengths**          | TEXT\[\]         | DEFAULT {}                 | Điểm mạnh của học sinh         |
| **weaknesses**         | TEXT\[\]         | DEFAULT {}                 | Điểm cần cải thiện             |
| **recommendations**    | TEXT\[\]         | DEFAULT {}                 | Gợi ý học tập                  |
| **ai_generated**       | BOOLEAN          | DEFAULT false              | AI sinh phản hồi tự động       |
| **created_by**         | UUID             | FK -> users NULL           | Giáo viên viết (NULL = AI)     |
| **created_at**         | TIMESTAMPTZ      | DEFAULT NOW()              | Thời điểm tạo                  |
| **visible_to_student** | BOOLEAN          | DEFAULT false              | Học sinh đã có thể xem         |
| **visible_at**         | TIMESTAMPTZ      | NULL                       | Thời điểm công bố cho học sinh |

### **Bảng: certificates**

Chứng chỉ được cấp cho học sinh đạt ngưỡng passing_score. Có thể xác minh qua QR code hoặc hash.

| **Tên Cột**            | **Kiểu Dữ Liệu** | **Ràng Buộc**                | **Mô Tả**                         |
| ---------------------- | ---------------- | ---------------------------- | --------------------------------- |
| **id**                 | UUID             | PK                           | Khóa chính                        |
| **attempt_id**         | UUID             | FK -> exam_attempts NOT NULL | Căn cứ cấp chứng chỉ              |
| **user_id**            | UUID             | FK -> users NOT NULL         | Người được cấp                    |
| **exam_id**            | UUID             | FK -> exams NOT NULL         | Bài thi đã hoàn thành             |
| **certificate_number** | VARCHAR(50)      | UNIQUE NOT NULL              | Số chứng chỉ (có thể in ra)       |
| **issued_at**          | TIMESTAMPTZ      | DEFAULT NOW()                | Ngày cấp                          |
| **expires_at**         | TIMESTAMPTZ      | NULL                         | Ngày hết hạn (NULL = vĩnh viễn)   |
| **score**              | NUMERIC(5,2)     | NOT NULL                     | Điểm đạt được                     |
| **verification_hash**  | VARCHAR(64)      | UNIQUE NOT NULL              | SHA-256 để xác minh tính xác thực |
| **pdf_s3_key**         | VARCHAR(500)     | NULL                         | File PDF chứng chỉ trên S3        |
| **revoked**            | BOOLEAN          | DEFAULT false                | Bị thu hồi                        |
| **revoked_reason**     | TEXT             | NULL                         | Lý do thu hồi                     |

| CREATE INDEX idx_cert_user ON certificates(user_id);              |
| ----------------------------------------------------------------- |
| CREATE INDEX idx_cert_verify ON certificates(verification_hash);  |
| CREATE INDEX idx_cert_number ON certificates(certificate_number); |

**III. MONGODB -- NGÂN HÀNG CÂU HỎI**

MongoDB lưu ngân hàng câu hỏi vì schema của câu hỏi là dị nhất -- mỗi loại (trắc nghiệm, essay, code, kéo thả...) có cấu trúc options/grading hoàn toàn khác nhau. MongoDB cho phép lưu linh hoạt mà không cần 10+ bảng join.

## **3.1 Collection: questions**

Đây là collection chính, lưu toàn bộ câu hỏi. Mỗi document đại diện cho một câu hỏi độc lập.

| {                                                                                                    |
| ---------------------------------------------------------------------------------------------------- |
| "\_id": ObjectId,                                                                                    |
| "question_id": "UUID v4", // ID đồng nhất với hệ thống                                               |
| "org_id": "UUID", // Tổ chức sở hữu (NULL = dùng chung)                                              |
| "subject_code": "MATH101", // Mã môn học (ref subjects PostgreSQL)                                   |
| "created_by": "UUID", // Giáo viên tạo                                                               |
| "reviewed_by": "UUID \| null", // Giáo viên duyệt                                                    |
|                                                                                                      |
| // ── Trạng thái vòng đời ──────────────────────────                                                 |
| "status": "draft\|review\|active\|deprecated",                                                       |
| "ai_generated": false,                                                                               |
| "ai_quality_score": 92, // 0-100, NULL nếu chưa có AI chấm                                           |
| "ai_quality_flags": \[\], // \["hai_nghia", "nhieu_dap_an_dung"\]                                    |
| "version": 3, // Tăng khi chỉnh sửa nội dung                                                         |
|                                                                                                      |
| // ── Loại câu hỏi ─────────────────────────────────                                                 |
| "type": "multiple_choice", // Xem danh sách loại bên dưới                                            |
|                                                                                                      |
| // ── Nội dung chính ───────────────────────────────                                                 |
| "content": {                                                                                         |
| "text": "Thuật toán sắp xếp nào có độ phức tạp O(n log n) trong mọi trường hợp?",                    |
| "rich_text": "&lt;p&gt;HTML có định dạng...&lt;/p&gt;",                                              |
| "math_latex": null, // Công thức Latex (null nếu không có)                                           |
| "code_snippet": null, // Đoạn code minh họa                                                          |
| "media": \[                                                                                          |
| { "type": "image", "url": "s3://bucket/img.png",                                                     |
| "alt": "Biểu đồ so sánh", "width": 600, "height": 400 }                                              |
| \]                                                                                                   |
| },                                                                                                   |
|                                                                                                      |
| // ── Đáp án (cấu trúc tùy loại) ────────────────────                                                |
| "options": \[                                                                                        |
| { "id": "opt_a", "text": "Bubble Sort", "is_correct": false, "explanation": "O(n2) xấu nhất" },      |
| { "id": "opt_b", "text": "Merge Sort", "is_correct": true, "explanation": "Luôn O(n log n)" },       |
| { "id": "opt_c", "text": "Quick Sort", "is_correct": false, "explanation": "O(n2) xấu nhất" },       |
| { "id": "opt_d", "text": "Insertion Sort","is_correct": false, "explanation": "O(n2) bình thường" }  |
| \],                                                                                                  |
|                                                                                                      |
| // ── Giải thích & Tài liệu ─────────────────────────                                                |
| "explanation": "Merge Sort sử dụng chia để trị, luôn đạt O(n log n)...",                             |
| "hint": "Nghĩ đến thuật toán chia để trị...",                                                        |
| "reference_links": \["https://..."\],                                                                |
|                                                                                                      |
| // ── Metadata phân loại ────────────────────────────                                                |
| "metadata": {                                                                                        |
| "topic": "Thuật toán sắp xếp",                                                                       |
| "subtopic": "Phân tích độ phức tạp",                                                                 |
| "tags": \["thuat_toan", "sap_xep", "do_phuc_tap", "CS"\],                                            |
| "bloom_level": "analysis", // knowledge\|comprehension\|application\|analysis\|synthesis\|evaluation |
| "language": "vi",                                                                                    |
| "estimated_time_seconds": 90 // Thời gian ước tính cần trả lời                                       |
| },                                                                                                   |
|                                                                                                      |
| // ── Tham số IRT (Item Response Theory) ───────────                                                 |
| "irt": {                                                                                             |
| "difficulty_assigned": 3, // Giáo viên gán thủ công 1-5                                              |
| "b": 0.82, // Tham số độ khó (calibrated)                                                            |
| "a": 1.24, // Tham số độ phân biệt                                                                   |
| "c": 0.05, // Tham số đoán ngẫu nhiên                                                                |
| "calibrated": true,                                                                                  |
| "calibrated_at": ISODate,                                                                            |
| "responses_count": 1247 // Số lượt trả lời dùng để tính toán                                         |
| },                                                                                                   |
|                                                                                                      |
| // ── Thống kê sử dụng ─────────────────────────────                                                 |
| "stats": {                                                                                           |
| "times_used": 1247,                                                                                  |
| "correct_count": 791,                                                                                |
| "correct_rate": 0.634,                                                                               |
| "avg_time_seconds": 78,                                                                              |
| "skip_rate": 0.02                                                                                    |
| },                                                                                                   |
|                                                                                                      |
| // ── Nhúng (Embedding) cho tìm kiếm ngữ nghĩa ─────                                                 |
| "embedding": \[0.023, -0.412, 0.187, ...\], // Vector 1536 chiều                                     |
| "embedding_model": "text-embedding-3-large",                                                         |
| "embedding_updated_at": ISODate,                                                                     |
|                                                                                                      |
| "created_at": ISODate,                                                                               |
| "updated_at": ISODate,                                                                               |
| "reviewed_at": ISODate \| null                                                                       |
| }                                                                                                    |

## **3.2 Các Loại Câu Hỏi & Cấu Trúc Đáp Án**

| **Loại Câu Hỏi**           | **type value**         | **Cấu Trúc options / grading_config**                                             |
| -------------------------- | ---------------------- | --------------------------------------------------------------------------------- |
| Trắc nghiệm 1 lựa chọn     | multiple_choice_single | options\[\]: {id, text, is_correct, explanation}                                  |
| Trắc nghiệm nhiều lựa chọn | multiple_choice_multi  | options\[\]: {id, text, is_correct} -- cần chọn TẤT CẢ đúng                       |
| Đúng / Sai                 | true_false             | options: \[{id:"true",text:"Đúng"},{id:"false",text:"Sai"}\]                      |
| Điền vào chỗ trống         | fill_blank             | grading_config: {accepted_answers:\[\], use_regex:bool, case_sensitive:bool}      |
| Nối cặp (Matching)         | matching               | pairs: \[{left_id, left_text, right_id, right_text}\]                             |
| Sắp xếp thứ tự             | ordering               | items: \[{id, text}\]; correct_order: \[id1, id2, ...\]                           |
| Trả lời ngắn               | short_answer           | grading_config: {keywords:\[\], min_similarity:0.75, max_words:50}                |
| Luận văn / Essay           | essay                  | grading_config: {rubric_id, min_words, max_words, dimensions:\[\]}                |
| Chạy code                  | code_execution         | grading_config: {language, test_cases:\[{input,expected,hidden}\], time_limit_ms} |
| Kéo thả (Drag & Drop)      | drag_drop              | zones: \[{id, label}\]; items: \[{id, text, correct_zone}\]                       |
| Điểm theo điều kiện        | hotspot                | image_url; hotspots: \[{id, x, y, radius, is_correct}\]                           |

## **3.3 Collection: question_versions**

Lưu lịch sử toàn bộ phiên bản của mỗi câu hỏi. Khi giáo viên chỉnh sửa câu hỏi đang dùng trong bài thi, phiên bản cũ được giữ lại.

| {                                                                          |
| -------------------------------------------------------------------------- |
| "\_id": ObjectId,                                                          |
| "question_id": "UUID", // Cùng question_id với collection questions        |
| "version": 2, // Số phiên bản                                              |
| "content_snapshot": { ... }, // Toàn bộ nội dung câu hỏi tại thời điểm này |
| "changed_by": "UUID",                                                      |
| "change_reason": "Sửa lại đáp án sai",                                     |
| "created_at": ISODate                                                      |
| }                                                                          |

## **3.4 Collection: question_reports**

Học sinh / giáo viên báo cáo câu hỏi có vấn đề (sai đáp án, câu hỏi mơ hồ, nội dung phản cảm...).

| {                                                                         |
| ------------------------------------------------------------------------- |
| "\_id": ObjectId,                                                         |
| "question_id": "UUID",                                                    |
| "reported_by": "UUID",                                                    |
| "attempt_id": "UUID \| null",                                             |
| "report_type": "wrong_answer \| ambiguous \| offensive \| typo \| other", |
| "description": "Mô tả cụ thể vấn đề...",                                  |
| "status": "pending \| under_review \| resolved \| dismissed",             |
| "resolved_by": "UUID \| null",                                            |
| "resolution": "Đã sửa đáp án B -> C",                                     |
| "created_at": ISODate,                                                    |
| "resolved_at": ISODate \| null                                            |
| }                                                                          |

## **3.5 Index Chiến Lược MongoDB**

| // Collection: questions                                                             |
| ------------------------------------------------------------------------------------ |
| db.questions.createIndex({ "question_id": 1 }, { unique: true })                     |
| db.questions.createIndex({ "org_id": 1, "status": 1, "metadata.subject": 1 })        |
| db.questions.createIndex({ "metadata.tags": 1 })                                     |
| db.questions.createIndex({ "irt.b": 1, "irt.a": 1 })                                 |
| db.questions.createIndex({ "status": 1, "ai_generated": 1, "ai_quality_score": -1 }) |
|                                                                                      |
| // Text search index (cho tìm kiếm nhanh)                                            |
| db.questions.createIndex(                                                            |
| { "content.text": "text", "metadata.tags": "text", "metadata.topic": "text" },       |
| { weights: { "content.text": 10, "metadata.tags": 5, "metadata.topic": 3 },          |
| name: "question_text_search" }                                                       |
| )                                                                                    |
|                                                                                      |
| // Vector search index (Elasticsearch / Atlas Vector Search)                         |
| // Trên Elasticsearch: dense_vector field với dims:1536, similarity:cosine           |

**IV. REDIS -- CACHE, SESSION & REAL-TIME**

Redis được dùng cho 5 nhiệm vụ chính: quản lý session thi (real-time), cache nóng, giới hạn luồng (rate limiting), pub/sub cho WebSocket, và bảng xếp hạng. Sử dụng Redis Cluster mode với 3 shard x 2 replica.

## **4.1 Nhóm 1: Session Bài Thi (Real-Time Critical)**

| **Key Pattern**           | **Kiểu** | **Nội Dung**                                                         | **TTL**                   | **Ghi Chú**                       |
| ------------------------- | -------- | -------------------------------------------------------------------- | ------------------------- | --------------------------------- |
| session:{attempt_id}      | Hash     | remaining_seconds, current_q_idx, risk_score, status, last_heartbeat | Thời gian thi + 5 phút    | Nguồn sự thật cho trạng thái thi  |
| answers:{attempt_id}      | Hash     | {question_ref_id -> JSON đáp án}                                     | Thời gian thi + 2 giờ     | Buffer trước khi flush PostgreSQL |
| q_order:{attempt_id}      | List     | Danh sách UUID câu hỏi đã xáo trộn                                   | Thời gian thi + 2 giờ     | Thứ tự riêng cho mỗi lượt thi     |
| adaptive:{attempt_id}     | Hash     | theta, se, answered_ids\[\], next_q_pool\[\]                         | Thời gian thi + 1 giờ     | Trạng thái IRT adaptive           |
| user:online:{user_id}     | String   | attempt_id (heartbeat check)                                         | 30 giây (tự động gia hạn) | Biết học sinh đang thi không      |
| exam:concurrent:{exam_id} | Set      | Tập hợp attempt_id đang thi                                          | Hết giờ thi + 30 phút     | Đếm người đang thi                |

## **4.2 Nhóm 2: Cache Nóng**

| **Key Pattern**           | **Kiểu**      | **Nội Dung**                            | **TTL** | **Khi Nào Vô Hiệu Hóa**   |
| ------------------------- | ------------- | --------------------------------------- | ------- | ------------------------- |
| exam:config:{exam_id}     | String (JSON) | Toàn bộ cấu hình bài thi                | 30 phút | Khi giáo viên sửa bài thi |
| exam:q_ids:{exam_id}      | List          | Danh sách question_ref_id trong bài thi | 30 phút | Khi thêm / bỏ câu hỏi     |
| question:{q_id}:{version} | String (JSON) | Nội dung câu hỏi từ MongoDB             | 2 giờ   | Khi câu hỏi được cập nhật |
| user:profile:{user_id}    | Hash          | Tên, email, role, org_id                | 15 phút | Khi cập nhật thông tin    |
| org:settings:{org_id}     | String (JSON) | Cấu hình tổ chức                        | 1 giờ   | Khi admin chỉnh sửa       |
| cert:verify:{hash}        | String (JSON) | Thông tin chứng chỉ                     | 24 giờ  | Khi chứng chỉ bị thu hồi  |

## **4.3 Nhóm 3: Giới Hạn Luồng & Bảo Mật**

| **Key Pattern**               | **Kiểu**    | **Nội Dung**                  | **TTL**                   | **Ngưỡng Xử Lý**                 |
| ----------------------------- | ----------- | ----------------------------- | ------------------------- | -------------------------------- |
| rate:login:{ip}               | String      | Số lần đăng nhập thất bại     | 15 phút sliding           | 10 lần -> khóa 15 phút           |
| rate:api:{user_id}:{endpoint} | String      | Số request đến endpoint       | 1 phút sliding            | Tùy endpoint (60-200/phút)       |
| rate:ai_gen:{org_id}          | String      | Số câu hỏi AI đã sinh hôm nay | Hết ngày (midnight)       | Theo gói dịch vụ (50-500/ngày)   |
| token:blacklist:{jti}         | String      | "revoked"                     | Bằng TTL của access token | Access token bị đăng xuất sớm    |
| otp:{user_id}:{purpose}       | String      | Mã OTP đã gửi                 | 10 phút                   | Xác thực email / đổi mật khẩu    |
| lock:exam_start:{attempt_id}  | String (NX) | Distributed lock              | 10 giây                   | Chống race condition bắt đầu thi |

## **4.4 Nhóm 4: Pub/Sub & Stream**

| **Channel / Stream**     | **Loại**              | **Mục Đích**                                            | **Subscriber**          |
| ------------------------ | --------------------- | ------------------------------------------------------- | ----------------------- |
| ws:exam:{attempt_id}     | Pub/Sub               | Đẩy tín hiệu đến WebSocket: cảnh báo, đồng hồ, dừng thi | Exam Service WS handler |
| cheat:alert:{attempt_id} | Pub/Sub               | Cảnh báo gian lận cấp tốc                               | Exam Service, Giám thị  |
| exam:events              | Stream (Redis Stream) | Sự kiện bài thi trước khi vào Kafka                     | Kafka bridge worker     |
| notification:{user_id}   | Pub/Sub               | Thông báo real-time cho người dùng                      | Notification Service    |

## **4.5 Nhóm 5: Xếp Hạng & Thống Kê Real-Time**

| **Key Pattern**       | **Kiểu**   | **Nội Dung**                                     | **TTL**                 |
| --------------------- | ---------- | ------------------------------------------------ | ----------------------- |
| leaderboard:{exam_id} | Sorted Set | user_id -> score (ZADD score user_id)            | 7 ngày sau kết thúc thi |
| exam:stats:{exam_id}  | Hash       | attempt_count, avg_score, pass_count, fail_count | Cập nhật real-time      |
| active_exams:{org_id} | Set        | Tập hợp exam_id đang mở                          | Sync với DB mỗi 5 phút  |

| **Lưu Ý Quan Trọng: Redis Persistence**                                                                                                                                                                                                                                                                                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Các key thuộc Nhóm 1 (Session Bài Thi) bắt buộc sử dụng AOF (Append-Only File) với fsync=always để đảm bảo không mất đáp án khi Redis gặp sự cố. Các key cache (Nhóm 2) dùng RDB backup mỗi 5 phút là đủ. Dùng 2 Redis instance tách biệt nếu cần tối ưu chi phí I/O: 1 instance cho session (fsync=always) và 1 instance cho cache (fsync=everysec). |

**V. CLICKHOUSE -- ANALYTICS OLAP**

ClickHouse là kho dữ liệu phân tích (OLAP). Dữ liệu từ PostgreSQL được đồng bộ vào đây qua Kafka (CDC với Debezium). ClickHouse không được phép ghi trực tiếp từ ứng dụng -- chỉ đọc qua Kafka consumer.

## **5.1 Bảng: exam_facts**

Bảng sự kiện chính cho phân tích kết quả thi. Mỗi bản ghi là một lượt thi đã hoàn thành.

| CREATE TABLE exam_facts (                                      |
| -------------------------------------------------------------- |
| \-- Khóa & định danh                                           |
| attempt_id UUID,                                               |
| exam_id UUID,                                                  |
| user_id UUID,                                                  |
| org_id UUID,                                                   |
| subject_code LowCardinality(String),                           |
|                                                                |
| \-- Thời gian (phân vùng theo tháng)                           |
| started_at DateTime64(3, 'Asia/Ho_Chi_Minh'),                  |
| submitted_at DateTime64(3, 'Asia/Ho_Chi_Minh'),                |
| date Date MATERIALIZED toDate(started_at),                     |
| month Date MATERIALIZED toStartOfMonth(started_at),            |
|                                                                |
| \-- Kết quả                                                    |
| raw_score Float32,                                             |
| max_score Float32,                                             |
| percentage_score Float32,                                      |
| passed UInt8, -- 0 hoặc 1                                      |
| attempt_number UInt8,                                          |
| time_spent_sec UInt32,                                         |
|                                                                |
| \-- Chống gian lận                                             |
| risk_score UInt16,                                             |
| flagged UInt8,                                                 |
| cheat_events_count UInt16,                                     |
|                                                                |
| \-- Thông tin thiết bị                                         |
| country_code LowCardinality(FixedString(3)),                   |
| device_type LowCardinality(String), -- desktop\|mobile\|tablet |
|                                                                |
| \-- Adaptive IRT                                               |
| final_theta Float32,                                           |
| final_se Float32                                               |
| )                                                              |
| ENGINE = MergeTree()                                           |
| PARTITION BY toYYYYMM(started_at)                              |
| ORDER BY (org_id, exam_id, started_at)                         |
| SETTINGS index_granularity = 8192;                             |

## **5.2 Bảng: answer_analytics**

Phân tích chi tiết từng câu hỏi trong mỗi lượt thi. Sử dụng để tính toán tỷ lệ đúng, thời gian trung bình, và tinh chỉnh IRT.

| CREATE TABLE answer_analytics (                                      |
| -------------------------------------------------------------------- |
| attempt_id UUID,                                                     |
| question_ref_id String,                                              |
| exam_id UUID,                                                        |
| org_id UUID,                                                         |
| subject_code LowCardinality(String),                                 |
| date Date,                                                           |
|                                                                      |
| is_correct UInt8,                                                    |
| points_earned Float32,                                               |
| max_points Float32,                                                  |
| time_spent_sec UInt16,                                               |
| answer_position UInt8, -- Vị trí trong bài (1-based)                 |
| was_skipped UInt8,                                                   |
| was_changed UInt8, -- Học sinh có đổi đáp án không                   |
|                                                                      |
| \-- IRT thời điểm trả lời                                            |
| theta_at_answer Float32 -- Ước tính theta tại thời điểm chọn câu này |
| )                                                                    |
| ENGINE = MergeTree()                                                 |
| PARTITION BY toYYYYMM(date)                                          |
| ORDER BY (org_id, question_ref_id, date)                             |
| SETTINGS index_granularity = 8192;                                   |

## **5.3 Bảng: cheat_analytics**

Phân tích xu hướng gian lận theo thời gian, theo loại sự kiện, và theo tổ chức.

| CREATE TABLE cheat_analytics (             |
| ------------------------------------------ |
| attempt_id UUID,                           |
| user_id UUID,                              |
| exam_id UUID,                              |
| org_id UUID,                               |
| event_type LowCardinality(String),         |
| event_layer UInt8,                         |
| severity LowCardinality(String),           |
| risk_delta Int16,                          |
| occurred_at DateTime64(3),                 |
| date Date MATERIALIZED toDate(occurred_at) |
| )                                          |
| ENGINE = MergeTree()                       |
| PARTITION BY toYYYYMM(date)                |
| ORDER BY (org_id, exam_id, occurred_at);   |

## **5.4 View Vật Chất (Materialized Views)**

ClickHouse tự động cập nhật các view này khi có dữ liệu mới vào -- dashboard giáo viên tải tức thì không cần query thô.

| \-- View: Thống kê bài thi theo ngày                    |
| ------------------------------------------------------- |
| CREATE MATERIALIZED VIEW mv_exam_daily_stats            |
| ENGINE = AggregatingMergeTree()                         |
| PARTITION BY toYYYYMM(date)                             |
| ORDER BY (org_id, exam_id, date)                        |
| AS SELECT                                               |
| org_id, exam_id, date,                                  |
| count() AS total_attempts,                              |
| countIf(passed = 1) AS passed_count,                    |
| avg(percentage_score) AS avg_score,                     |
| avg(time_spent_sec) AS avg_time_sec,                    |
| countIf(flagged = 1) AS flagged_count,                  |
| quantile(0.5)(percentage_score) AS median_score,        |
| quantile(0.9)(percentage_score) AS p90_score            |
| FROM exam_facts GROUP BY org_id, exam_id, date;         |
|                                                         |
| \-- View: Thống kê câu hỏi (cập nhật IRT)               |
| CREATE MATERIALIZED VIEW mv_question_stats              |
| ENGINE = AggregatingMergeTree()                         |
| ORDER BY (org_id, question_ref_id)                      |
| AS SELECT                                               |
| org_id, question_ref_id,                                |
| count() AS total_responses,                             |
| avg(is_correct) AS correct_rate,                        |
| avg(time_spent_sec) AS avg_time_sec,                    |
| stddevPop(theta_at_answer) AS theta_spread,             |
| corr(theta_at_answer, is_correct) AS point_biserial     |
| FROM answer_analytics GROUP BY org_id, question_ref_id; |

**VI. ELASTICSEARCH -- TÌM KIẾM & VECTOR**

Elasticsearch 8 được dùng cho hai mục đích: (1) tìm kiếm văn bản đầy đủ (full-text search) trên ngân hàng câu hỏi; (2) tìm kiếm vector (KNN) để tìm câu hỏi tương tự ngữ nghĩa và loại trừ trùng lặp.

## **6.1 Index: question_search**

| PUT /question_search                                           |
| -------------------------------------------------------------- |
| {                                                              |
| "settings": {                                                  |
| "number_of_shards": 5,                                         |
| "number_of_replicas": 1,                                       |
| "analysis": {                                                  |
| "analyzer": {                                                  |
| "vi_analyzer": {                                               |
| "type": "custom",                                              |
| "tokenizer": "standard",                                       |
| "filter": \["lowercase", "stop", "vi_stemmer"\]                |
| }                                                              |
| }                                                              |
| }                                                              |
| },                                                             |
| "mappings": {                                                  |
| "properties": {                                                |
| "question_id": { "type": "keyword" },                          |
| "org_id": { "type": "keyword" },                               |
| "status": { "type": "keyword" },                               |
| "type": { "type": "keyword" },                                 |
| "subject_code": { "type": "keyword" },                         |
| "tags": { "type": "keyword" },                                 |
| "bloom_level": { "type": "keyword" },                          |
|                                                                |
| // Full-text search                                            |
| "content_text": { "type": "text", "analyzer": "vi_analyzer" }, |
| "topic": { "type": "text", "analyzer": "vi_analyzer" },        |
|                                                                |
| // So sánh / lọc                                               |
| "difficulty_irt": { "type": "float" },                         |
| "correct_rate": { "type": "float" },                           |
| "times_used": { "type": "integer" },                           |
|                                                                |
| // Vector semantic search (KNN)                                |
| "embedding": {                                                 |
| "type": "dense_vector",                                        |
| "dims": 1536,                                                  |
| "index": true,                                                 |
| "similarity": "cosine"                                         |
| },                                                             |
|                                                                |
| "updated_at": { "type": "date" }                               |
| }                                                              |
| }                                                              |
| }                                                              |

## **6.2 Các Tính Năng Tìm Kiếm**

| **Tính Năng**          | **Query Elasticsearch**                                 | **Mô Tả**                                                |
| ---------------------- | ------------------------------------------------------- | -------------------------------------------------------- |
| Tìm kiếm văn bản       | multi_match query trên content_text + topic             | Tìm câu hỏi theo từ khóa, hỗ trợ phân tích tiếng Việt    |
| Tìm kiếm ngữ nghĩa     | knn query trên embedding field                          | Tìm câu hỏi có ý nghĩa tương tự (dù dùng từ khác)        |
| Loại trừ trùng lặp     | knn + score_threshold: 0.92                             | Nếu score >= 0.92 = trùng lặp, cảnh báo khi nhập câu mới |
| Tìm kiếm có lọc        | bool + filter (status, org_id, bloom_level, difficulty) | Lọc theo tiêu chí: môn học, độ khó, trạng thái           |
| Gợi ý câu hỏi          | knn từ content câu hỏi hiện tại                         | Gợi ý câu hỏi tương tự cho giáo viên khi soạn đề         |
| Kiểm tra chất lượng AI | knn so sánh với câu hỏi có sẵn                          | Phát hiện AI sinh câu hỏi trùng với ngân hàng            |

**VII. QUAN HỆ DỮ LIỆU & ERD**

## **7.1 ERD PostgreSQL -- Sơ Đồ Tổng Thể**

| organizations 1────&lt; user_organizations &gt;────1 users |
| ---------------------------------------------------------- |
| \| \|                                                      |
| \| 1────< oauth_providers \|                               |
| \| 1────< refresh_tokens \|                                |
| \| \|                                                      |
| 1────< subjects \|                                         |
| \| \| \|                                                   |
| 1────< exams ────────────────────── created_by             |
| \|                                                         |
| 1────< exam_sections                                       |
| \| \|                                                      |
| 1────< exam_questions (ref: MongoDB question_id)           |
| \|                                                         |
| 1────&lt; exam_enrollments &gt;────1 users                 |
| \|                                                         |
| 1────&lt; exam_attempts &gt;────1 users                    |
| \|                                                         |
| 1────< attempt_answers                                     |
| \|────< cheat_events                                       |
| \|────< proctoring_sessions                                |
| \|────< attempt_feedback                                   |
| \|                                                         |
| 1────0 certificates                                        |
|                                                            |
| grading_rubrics >────1 exams                               |

## **7.2 Bảng Quan Hệ Chính**

| **Từ Bảng**         | **Đến Bảng**  | **Kiểu Quan Hệ** | **Khóa Ngoại**                 | **Hành Động Khi Xóa**                 |
| ------------------- | ------------- | ---------------- | ------------------------------ | ------------------------------------- |
| user_organizations  | users         | N:1              | user_id -> users.id            | CASCADE (xóa thành viên khi xóa user) |
| user_organizations  | organizations | N:1              | org_id -> organizations.id     | CASCADE                               |
| exams               | organizations | N:1              | org_id -> organizations.id     | RESTRICT (không xóa org có bài thi)   |
| exams               | users         | N:1 (creator)    | created_by -> users.id         | RESTRICT                              |
| exam_sections       | exams         | N:1              | exam_id -> exams.id            | CASCADE                               |
| exam_questions      | exams         | N:1              | exam_id -> exams.id            | CASCADE                               |
| exam_questions      | exam_sections | N:1 (opt)        | section_id -> exam_sections.id | SET NULL                              |
| exam_enrollments    | exams         | N:1              | exam_id -> exams.id            | CASCADE                               |
| exam_attempts       | exams         | N:1              | exam_id -> exams.id            | RESTRICT                              |
| exam_attempts       | users         | N:1              | user_id -> users.id            | RESTRICT                              |
| attempt_answers     | exam_attempts | N:1              | attempt_id -> exam_attempts.id | CASCADE                               |
| cheat_events        | exam_attempts | N:1              | attempt_id -> exam_attempts.id | CASCADE                               |
| certificates        | exam_attempts | 1:1 (opt)        | attempt_id -> exam_attempts.id | RESTRICT                              |
| proctoring_sessions | exam_attempts | 1:1              | attempt_id -> exam_attempts.id | CASCADE                               |

**VIII. CHIẾN LƯỢC HIỆU NĂNG & MỞ RỘNG**

## **8.1 Index Chiến Lược PostgreSQL**

| \-- ── Exam Service (Hot Path) ──────────────────────────────                      |
| ---------------------------------------------------------------------------------- |
| \-- Tìm bài thi đang mở cho người dùng                                             |
| CREATE INDEX idx_exams_open ON exams(org_id, status, starts_at, ends_at)           |
| WHERE status IN ('published','active');                                            |
|                                                                                    |
| \-- Kiểm tra lượt thi đang hoạt động (check trước khi bắt đầu)                     |
| CREATE INDEX idx_attempt_active ON exam_attempts(user_id, exam_id)                 |
| WHERE status = 'in_progress';                                                      |
|                                                                                    |
| \-- Lấy đáp án nhanh khi nộp bài cuối                                              |
| CREATE INDEX idx_answers_attempt ON attempt_answers(attempt_id, exam_question_id); |
|                                                                                    |
| \-- ── Auth Service (Hot Path) ──────────────────────────────                      |
| CREATE INDEX idx_user_email ON users(email) WHERE is_active = true;                |
| CREATE INDEX idx_token_hash ON refresh_tokens(token_hash) WHERE revoked = false;   |
|                                                                                    |
| \-- ── Cheating Detection ───────────────────────────────────                      |
| CREATE INDEX idx_cheat_recent ON cheat_events(attempt_id, server_timestamp DESC)   |
| WHERE reviewed_by IS NULL;                                                         |
|                                                                                    |
| \-- ── Analytics (Instructor Dashboard) ────────────────────                       |
| CREATE INDEX idx_attempts_exam_perf                                                |
| ON exam_attempts(exam_id, status, raw_score, percentage_score)                     |
| WHERE status IN ('submitted','graded');                                            |
|                                                                                    |
| \-- ── Partial Index tiết kiệm bộ nhớ ──────────────────────                       |
| CREATE INDEX idx_active_users ON users(email, org_id)                              |
| WHERE is_active = true AND deleted_at IS NULL;                                     |

## **8.2 Phân Mảnh Dữ Liệu (Partitioning)**

| **Bảng**        | **Chiến Lược Phân Mảnh**                       | **Lý Do**                                             | **Lịch Nuốt Cũ**                    |
| --------------- | ---------------------------------------------- | ----------------------------------------------------- | ----------------------------------- |
| attempt_answers | RANGE phân mảnh theo tháng (answered_at)       | Bảng lớn nhất -- tránh sequential scan trên toàn bảng | Nuốt partition > 12 tháng sau 2 năm |
| cheat_events    | RANGE phân mảnh theo tháng (server_timestamp)  | Dữ liệu cũ ít truy cập; cần giữ để kiểm toán          | Nuốt sau 24 tháng                   |
| exam_attempts   | LIST phân mảnh theo status (in_progress, done) | Truy cập in_progress thường xuyên nhất; done ít hơn   | Không nuốt, chỉ partition list      |

## **8.3 CDC Pipeline: PostgreSQL -> Kafka -> ClickHouse**

| // Luồng dữ liệu từ PostgreSQL sang ClickHouse qua Debezium             |
| ----------------------------------------------------------------------- |
|                                                                         |
| PostgreSQL                                                              |
| \|-- Debezium Connector (đọc WAL log)                                   |
| \| Cấu hình: slot_name = debezium_quiz, publication = quiz_pub          |
| \| Theo dõi: exam_attempts, attempt_answers, cheat_events, certificates |
| \|                                                                      |
| v                                                                       |
| Kafka Topics:                                                           |
| quiz.public.exam_attempts (mỗi insert/update)                           |
| quiz.public.attempt_answers (mỗi insert)                                |
| quiz.public.cheat_events (mỗi insert)                                   |
| quiz.public.certificates (mỗi insert/update)                            |
| \|                                                                      |
| v                                                                       |
| ClickHouse Kafka Engine Tables (intermediate)                           |
| \-> Materialized Views transform & insert vào bảng chính                |
| \-> Chạy tổng hợp 5 phút một lần cho mv_exam_daily_stats                |

## **8.4 Chiến Lược Backup & Phục Hồi**

| **Cơ Sở Dữ Liệu** | **Backup Chiến Lược**                                                                             | **RPO**  | **RTO**   |
| ----------------- | ------------------------------------------------------------------------------------------------- | -------- | --------- |
| PostgreSQL        | Continuous WAL archiving + daily base backup (pg_basebackup) lên S3; RDS automated backup 35 ngày | < 1 phút | < 15 phút |
| MongoDB           | Mongodump daily + oplog replication (Point-in-time)                                               | < 5 phút | < 30 phút |
| Redis             | AOF (fsync=always) cho session data; RDB snapshot mỗi 5 phút cho cache                            | < 1 giây | < 5 phút  |
| ClickHouse        | Native backup lên S3 mỗi 6 giờ; dữ liệu có thể tái tạo từ Kafka                                   | < 6 giờ  | < 2 giờ   |
| Elasticsearch     | Snapshot API lên S3 mỗi 6 giờ; có thể index lại từ MongoDB                                        | < 6 giờ  | < 1 giờ   |

**IX. BẢO MẬT & TUÂN THỦ DỮ LIỆU**

## **9.1 Mã Hóa Dữ Liệu**

| **Loại Dữ Liệu**             | **Vị Trí**                      | **Phương Pháp Mã Hóa**                     | **Quản Lý Khóa**                        |
| ---------------------------- | ------------------------------- | ------------------------------------------ | --------------------------------------- |
| Mật khẩu người dùng          | PostgreSQL password_hash        | Argon2id (memory=65MB, iter=3, para=4)     | Không giải mã được -- chỉ kiểm tra hash |
| TOTP secret (MFA)            | PostgreSQL mfa_secret           | AES-256-GCM                                | AWS KMS / HashiCorp Vault               |
| Access token OAuth           | PostgreSQL access_token_enc     | AES-256-GCM                                | AWS KMS                                 |
| Dữ liệu nhạy cảm trong JSONB | PostgreSQL event_data, settings | PG native encryption + AES ở tầng ứng dụng | Vault Transit Engine                    |
| Video giám thị               | S3                              | AES-256 server-side encryption (SSE-S3)    | AWS S3 KMS key                          |
| Kết nối CSDL                 | Tất cả lớp mạng                 | TLS 1.3 bắt buộc                           | Chứng chỉ tự động gia hạn               |
| Dữ liệu Redis nhạy cảm       | Redis session hash              | Mã hóa ở tầng ứng dụng trước khi ghi       | Vault Dynamic Secrets                   |

## **9.2 Phân Quyền Truy Cập Cơ Sở Dữ Liệu**

| \-- PostgreSQL: Tạo role riêng cho mỗi service                                      |
| ----------------------------------------------------------------------------------- |
| CREATE ROLE svc_auth LOGIN PASSWORD '' CONNECTION LIMIT 50;                         |
| CREATE ROLE svc_exam LOGIN PASSWORD '' CONNECTION LIMIT 200;                        |
| CREATE ROLE svc_cheat LOGIN PASSWORD '' CONNECTION LIMIT 100;                       |
| CREATE ROLE svc_analytics LOGIN PASSWORD '' CONNECTION LIMIT 20;                    |
| CREATE ROLE svc_readonly LOGIN PASSWORD '' CONNECTION LIMIT 50;                     |
|                                                                                     |
| \-- Auth Service: chỉ truy cập bảng auth                                            |
| GRANT SELECT, INSERT, UPDATE ON users, refresh_tokens, oauth_providers TO svc_auth; |
| GRANT SELECT ON organizations, user_organizations TO svc_auth;                      |
|                                                                                     |
| \-- Exam Service: truy cập bảng thi nhưng KHÔNG xem dữ liệu riêng tư khác           |
| GRANT SELECT ON exams, exam_sections, exam_questions, exam_enrollments TO svc_exam; |
| GRANT SELECT, INSERT, UPDATE ON exam_attempts, attempt_answers TO svc_exam;         |
| GRANT SELECT, INSERT ON cheat_events TO svc_exam;                                   |
|                                                                                     |
| \-- Analytics Service: chỉ đọc, không ghi                                           |
| GRANT SELECT ON ALL TABLES IN SCHEMA public TO svc_readonly;                        |
|                                                                                     |
| \-- Row Level Security (RLS) -- mỗi tổ chức chỉ xem dữ liệu của mình                |
| ALTER TABLE exams ENABLE ROW LEVEL SECURITY;                                        |
| CREATE POLICY org_isolation ON exams                                                |
| USING (org_id = current_setting('app.current_org_id')::UUID);                       |

## **9.3 Audit Log & Tuân Thủ**

| **Yêu Cầu**                           | **Cách Thực Hiện**                                                        | **Lưu Trữ**                           |
| ------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------- |
| Ghi lại mọi thay đổi dữ liệu nhạy cảm | pg_audit extension -- ghi log SELECT/INSERT/UPDATE/DELETE trên bảng chính | PostgreSQL audit log -> S3 (90 ngày)  |
| Không xóa dữ liệu thi kết quả         | soft_delete (deleted_at) + RLS ngăn cấp phép DROP                         | Giữ vĩnh viễn trừ khi có lệnh pháp lý |
| Bảo mật cấp chứng chỉ                 | verification_hash SHA-256; endpoint public xác minh                       | PostgreSQL + cache Redis              |
| Log truy cập dữ liệu                  | Istio access log mỗi request giữa service                                 | ELK stack (30 ngày)                   |
| Phát hiện bất thường                  | Prometheus alert: query chạy > 5s; số kết nối tăng đột biến               | PagerDuty oncall                      |

**X. MIGRATION & BẢO TRÌ CƠ SỞ DỮ LIỆU**

## **10.1 Quy Trình Migration**

Sử dụng Flyway (PostgreSQL) và Mongock (MongoDB) để quản lý phiên bản schema. Mọi thay đổi schema đều phải có migration file riêng, được review và test trước khi merge.

| \-- Quy ước đặt tên file migration:                                                        |
| ------------------------------------------------------------------------------------------ |
| \-- V{version}\_\_{mo_ta_ngan.sql}                                                         |
| \-- V001\_\_create_users_table.sql                                                         |
| \-- V002\_\_add_mfa_to_users.sql                                                           |
| \-- V003\_\_create_exam_tables.sql                                                         |
|                                                                                            |
| \-- Nguyên tắc migration an toàn (Zero-downtime):                                          |
| \-- 1. Thêm cột mới: bắt đầu là NULLABLE, app viết cả hai cột cũ và mới                    |
| \-- 2. Backfill dữ liệu nền trong batch (không lock bảng)                                  |
| \-- 3. Thêm ràng buộc NOT NULL sau khi backfill xong                                       |
| \-- 4. Xóa cột cũ sau khi không còn code dùng nó                                           |
|                                                                                            |
| \-- Ví dụ: thêm cột geo_country vào exam_attempts                                          |
| \-- Migration V015: ALTER TABLE exam_attempts ADD COLUMN geo_country VARCHAR(3) NULL;      |
| \-- Deploy app v1.5 (viết geo_country khi có)                                              |
| \-- Migration V016: UPDATE exam_attempts SET geo_country = 'VN' WHERE geo_country IS NULL; |
| \-- Migration V017: ALTER TABLE exam_attempts ALTER COLUMN geo_country SET DEFAULT 'VN';   |

## **10.2 Bảo Trì Định Kỳ**

| **Công Việc**                               | **Tần Suất**                              | **Công Cụ**                        | **Thời Gian Bảo Trì**        |
| ------------------------------------------- | ----------------------------------------- | ---------------------------------- | ---------------------------- |
| VACUUM ANALYZE (PostgreSQL)                 | Tự động (autovacuum) + thủ công hàng tuần | pg_cron + autovacuum               | Ngoài giờ đỉnh điểm (2-4 AM) |
| Xóa token hết hạn                           | Hàng ngày                                 | pg_cron schedule                   | 2 AM hàng ngày               |
| Xóa session Redis hết hạn                   | Tự động (Redis TTL)                       | Redis TTL                          | Không cần thủ công           |
| Nén và nuốt partition cũ                    | Hàng tháng                                | pg_partman + script tùy chỉnh      | Cuối tháng                   |
| Rebuild index Elasticsearch                 | Hàng tuần (nếu có nhiều delete)           | Index alias + reindex API          | Cuối tuần                    |
| Tinh chỉnh IRT (calibration)                | Hàng ngày lúc thấp điểm                   | Spark batch job (2 AM)             | < 30 phút                    |
| Đồng bộ embedding mới (AI)                  | Khi có câu hỏi mới                        | Kafka trigger -> embedding worker  | < 5 phút/câu                 |
| Kiểm tra toàn vẹn dữ liệu (integrity check) | Hàng tuần                                 | Script kiểm tra FK, orphan records | Cuối tuần                    |

## **10.3 Chiến Lược Khi Scale Lớn Hơn**

- Vượt 50 triệu lượt thi: Phân mảnh attempt_answers theo cả THÁNG và ORG_ID; xem xét chuyển sang Citus (PostgreSQL phân tán)
- Vượt 500 GB ngân hàng câu hỏi MongoDB: Phân mảnh (sharding) theo org_id + subject_code; dùng zone sharding để đặt dữ liệu gần người dùng
- Vượt 1 triệu CCU (Concurrent Users): Tách Redis thành 2 cụm riêng -- 1 cho session (write-heavy) và 1 cho cache (read-heavy)
- Multi-region: Dùng PostgreSQL active-passive multi-region với Patroni; Redis Cluster trên mỗi region; Elasticsearch cross-cluster replication
- CQRS: Tách riêng model ghi (PostgreSQL) và model đọc (ClickHouse read replica + Redis cache) cho các trường hợp query phức tạp

_Tài liệu này mô tả thiết kế cơ sở dữ liệu phiên bản 1.0._

**Hệ Thống Thi Trực Tuyến Thông Minh | Database Design v1.0 | Tháng 4/2026**

_Schema sẽ được cập nhật kèm theo migration file khi có thay đổi thiết kế._
