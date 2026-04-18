**TÀI LIỆU THIẾT KẾ HỆ THỐNG**

**Hệ Thống Thi Trực Tuyến Thông Minh**

Tích Hợp AI & Chống Gian Lận

Thiết kế từ đầu (Greenfield) | Quy mô 100.000+ người dùng

Kiến trúc: Microservices + Event-Driven

Tháng 4 năm 2026 | Phiên bản 1.0

| **Quy mô mục tiêu**<br><br>**100.000 người dùng** | **Kiến trúc**<br><br>**Microservices + EDA** | **Loại tài liệu**<br><br>**System Design** |
| ------------------------------------------------- | -------------------------------------------- | ------------------------------------------ |

# **Tóm Tắt Điều Hành**

Tài liệu này trình bày thiết kế hệ thống hoàn chỉnh cho Hệ Thống Thi Trực Tuyến Thông Minh (Online Smart Quiz System), được xây dựng từ đầu (greenfield) với khả năng phục vụ tới 100.000 người dùng đồng thời. Hệ thống tích hợp sinh câu hỏi bằng AI, kiểm tra thích nghi theo mô hình IRT (Item Response Theory), và cơ chế chống gian lận đa tầng trong kiến trúc microservices hiện đại.

Nguyên tắc thiết kế cốt lõi: mở rộng ngang (horizontal scaling), cô lập lỗi theo từng service, AI chạy độc lập trên cơ sở hạ tầng GPU, và áp dụng nhất quán mạnh (strong consistency) đúng nơi toàn vẹn bài thi cần thiết - còn lại chấp nhận eventual consistency để tối ưu hiệu năng.

**I. BÀI TOÁN (PROBLEM STATEMENT)**

## **1.1 Mô Tả Bài Toán**

Hệ thống thi trực tuyến cần cung cấp trải nghiệm kiểm tra an toàn, tin cậy và thông minh trên quy mô lớn. Kiến trúc monolith truyền thống không thể mở rộng độc lập các session thi, cô lập chi phí tính toán AI, hay đảm bảo khả năng chịu lỗi (fault tolerance) cần thiết cho một nền tảng kiểm tra quan trọng.

**Các trường hợp sử dụng chính:**

- Học sinh làm bài thi có thời hạn với độ khó thích nghi từ bất kỳ thiết bị nào trên toàn thế giới
- Giáo viên tạo và quản lý ngân hàng câu hỏi đa dạng với sự hỗ trợ của AI
- AI tự động sinh câu hỏi phù hợp về mặt ngữ nghĩa, chính xác theo phân loại Bloom
- Hệ thống điều chỉnh độ khó câu hỏi theo thời gian thực dựa trên năng lực học sinh (mô hình IRT)
- Tầng chống gian lận phát hiện và xử lý hành vi vi phạm học thuật theo thời gian thực
- Phân tích dữ liệu cung cấp thông tin chi tiết về quá trình học tập cho học sinh, giáo viên và quản trị viên

## **1.2 Các Thách Thức**

### **Thách Thức Về Quy Mô (Scale)**

| **Thách Thức**                  | **Quy Mô**                    | **Mức Độ Ảnh Hưởng**                       |
| ------------------------------- | ----------------------------- | ------------------------------------------ |
| Phiên thi đồng thời             | 100.000 session cùng lúc      | Cao -- nghẽn cơ sở dữ liệu & tính toán     |
| Đợt tăng traffic đỉnh điểm      | 10x bình thường trong giờ thi | Cao -- bắt buộc có auto-scaling            |
| Bão ghi đáp án đồng loạt        | Hàng triệu write/giờ          | Cao -- vấn đề write amplification          |
| Lưu trữ video giám thị          | TB-scale mỗi chu kỳ thi       | Trung bình -- cần CDN + object storage     |
| Thu nhận analytics theo TG thực | 500.000+ sự kiện/phút         | Cao -- cần xử lý luồng (stream processing) |

### **Thách Thức Về AI & Chống Gian Lận**

- Độ trễ LLM (5-45 giây) phải tách biệt khỏi UX bài thi -- cần pipeline bất đồng bộ (async)
- Tính toán IRT thích nghi phải hoàn thành trong 80ms giữa các câu hỏi
- Tỉ lệ false positive trong phát hiện gian lận phải < 0,5% -- cảnh báo sai gây hại cho học sinh
- Video giám thị cần GPU tính toán độc lập, không ảnh hưởng đường hot path của bài thi
- Sinh câu hỏi AI cần có người kiểm duyệt tránh câu hỏi sai sự thật đi vào ngân hàng

**II. THIẾT KẾ CẤP CAO (HIGH-LEVEL DESIGN)**

## **2.1 Tổng Quan Kiến Trúc**

Hệ thống sử dụng kiến trúc Microservices Lai + Hướng Sự Kiện (Hybrid Microservices + Event-Driven Architecture). Mỗi bounded context sở hữu dữ liệu của mình và giao tiếp qua REST/gRPC. Các luồng xuyên service có throughput cao sử dụng Apache Kafka để tách kết (decoupling), đảm bảo độ bền (durability), và khả năng phát lại sự kiện để kiểm toán.

### **Lý Do Chọn Microservices + EDA Thay Vì Modular Monolith?**

| **Tiêu Chí**         | **Modular Monolith**                    | **Microservices + EDA (Đã Chọn)**                  |
| -------------------- | --------------------------------------- | -------------------------------------------------- |
| Cô lập AI/GPU        | Không thể -- tất cả code chung runtime  | Native -- Python AI Service chạy trên GPU node     |
| Mở rộng bài thi      | Phải scale cả ứng dụng                  | Chỉ scale Exam Service lúc đỉnh điểm               |
| Cô lập lỗi           | Một bug có thể sập toàn bộ kỳ thi       | Exam Service độc lập với lỗi của AI Service        |
| Tự chủ nhóm          | Không có -- codebase chung              | CI/CD và quyền sở hữu theo từng service            |
| Ảnh hưởng analytics  | Query nặng làm chậm yêu cầu thi         | ClickHouse và Kafka hoàn toàn tách biệt            |
| Độ phức tạp vận hành | Thấp                                    | Trung bình -- được giảm nhẹ qua Kubernetes + Istio |

## **2.2 Sơ Đồ Kiến Trúc Hệ Thống**

| +------------------------------------------------------------------+      |
| ------------------------------------------------------------------------- |
| \| TẦNG CLIENT \|                                                         |
| \| Ứng Dụng Web (React) \| Mobile (React Native) \| Admin Panel \|        |
| +------------------------------+-----------------------------------+      |
| \| HTTPS / WebSocket (WSS)                                                |
| +------------------------------v-----------------------------------+      |
| \| API GATEWAY (Kong / AWS API Gateway) \|                                |
| \| Giới Hạn Luồng \| Xác Thực JWT \| Cân Bằng Tải \| Kết Thúc SSL \|      |
| +-+--------+--------+--------+--------+--------+--------+---------+       |
| \| \| \| \| \| \| \|                                                      |
| Auth Exam Question AI Cheating Notification Analytics                     |
| Svc Svc Svc Svc Svc Svc Svc                                               |
| \| \| \| \| \| \| \|                                                      |
| +-+--------+--------+--------+--------+--------+--------+---------+       |
| \| MESSAGE BUS (Apache Kafka) \|                                          |
| \| exam.events \| answer.submitted \| cheat.detected \| analytics \|      |
| +-------+----------+---------+----------+-------------------------------+ |
| \| \| \| \|                                                               |
| PostgreSQL MongoDB Redis ClickHouse                                       |
| (Auth/Thi) (Câu Hỏi) (Session) (Phân Tích)                                |

## **2.3 Công Nghệ Sử Dụng**

| **Tầng**                   | **Công Nghệ**                 | **Lý Do Lựa Chọn**                                                   |
| -------------------------- | ----------------------------- | -------------------------------------------------------------------- |
| API Gateway                | Kong (tự quản lý)             | Hệ sinh thái plugin phong phú, giới hạn luồng, xác thực JWT có sẵn   |
| Auth / Exam / Question / Notification | Java 21 + Spring Boot 3 | Virtual Threads (Project Loom) cho 100k+ WebSocket đồng thời; Spring Security/Spring Data/Spring Kafka trưởng thành; type-safe; hệ sinh thái enterprise |
| Analytics / Chống Gian Lận | Java 21 + Spring Boot 3       | JIT tối ưu luồng sự kiện cao tần; Kafka Streams/Flink SDK native Java; dùng chung DevOps toolchain với các service khác |
| AI Service                 | Python 3.12 (FastAPI)         | Hệ sinh thái ML tốt nhất: LangChain, HuggingFace, PyTorch; hỗ trợ GPU native; tích hợp qua REST/gRPC với các service Java |
| CSDL Chính (Auth, Thi)     | PostgreSQL 16                 | ACID cho người dùng / bài thi; query planner mạnh                    |
| CSDL Câu Hỏi               | MongoDB                       | Schema linh hoạt cho 8+ loại câu hỏi; tìm kiếm văn bản native        |
| Cache / Session            | Redis Cluster                 | Độ trễ dưới ms; pub/sub; quản lý session với TTL                     |
| Message Bus                | Apache Kafka                  | 1 triệu+ tin/giây; log bền vững; phát lại sự kiện để kiểm toán       |
| Analytics OLAP             | ClickHouse                    | 100x nhanh hơn PostgreSQL trên 100 triệu+ hàng dữ liệu               |
| Video / Media              | S3 (AWS S3 / MinIO)           | Lưu trữ đối tượng hiệu quả chi phí, tích hợp CDN                     |
| Điều Phối Container        | Kubernetes (EKS/GKE)          | Auto-scaling, rolling deploy, cô lập tài nguyên                      |
| Service Mesh               | Istio                         | mTLS giữa các service; điều khiển luồng; quan sát                    |
| Quan Sát Hệ Thống          | Prometheus + Grafana + Jaeger | Metrics, dashboard, distributed tracing                              |

**Ghi chú về ngăn xếp Java/Python:**

- **Java service**: Java 21 (LTS) + Spring Boot 3 + Gradle (build). Thư viện chính: Spring Web MVC (REST), Spring WebSocket, Spring Security + OAuth2 Resource Server, Spring Data JPA (PostgreSQL), Spring Data MongoDB, Lettuce (Redis), Spring Kafka, Micrometer (metrics), OpenTelemetry Java Agent (tracing). Triển khai bằng container OCI nhỏ qua Jib hoặc GraalVM native image cho service chịu tải cao nhất.
- **Python AI service**: Python 3.12 + FastAPI + Uvicorn/Gunicorn + Pydantic v2. Thư viện ML: LangChain, HuggingFace Transformers, PyTorch 2, sentence-transformers (embedding). Worker xử lý bất đồng bộ qua aiokafka / Celery. GPU inference qua NVIDIA CUDA + Triton Inference Server khi cần batching.
- **Giao tiếp liên dịch vụ**: REST/JSON cho luồng đồng bộ; gRPC (proto shared) cho latency-critical; Apache Kafka cho event-driven và AI job queue.

**III. THIẾT KẾ CHI TIẾT (DETAILED DESIGN)**

## **3.1 Phân Tích Các Service**

### **Auth Service -- Dịch Vụ Xác Thực**

Trách nhiệm: Quản lý danh tính, cấp JWT, OAuth2/SSO, MFA, phân quyền RBAC theo 4 vai trò: `student`, `instructor`, `admin`, `proctor` (giám thị chuyên trách, chỉ truy cập dữ liệu giám sát).

| **Quyết Định Thiết Kế** | **Lựa Chọn**                                           | **Lý Do**                                          |
| ----------------------- | ------------------------------------------------------ | -------------------------------------------------- |
| Chiến lược token        | JWT Access (15 phút) + Refresh Token (7 ngày)          | Token ngắn hạn giới hạn phạm vi khi bị lộ          |
| Hàm băm mật khẩu        | Argon2id (chiến thắng Password Hashing Competition)    | Memory-hard; chống tấn công brute-force bằng GPU   |
| Lưu refresh token       | Redis (thu hồi tức thì) + PostgreSQL (lưu trữ lâu dài) | Thu hồi token ngay lập tức không qua round-trip DB |
| Xác thực 2 yếu tố (MFA) | TOTP (RFC 6238) -- ứng dụng Authenticator              | Chuẩn công nghiệp; không phụ thuộc SMS             |
| Giới hạn đăng nhập      | 10 lần thất bại / 15 phút / IP (sliding window)        | Chống brute-force mà không khóa người dùng hợp lệ  |
| Nhà cung cấp OAuth      | Google, Microsoft, GitHub SSO                          | Giảm ma sát; tận dụng bảo mật cơ sở hạ tầng IdP    |

### **Exam Service -- Dịch Vụ Bài Thi**

Trách nhiệm: Vòng đời bài thi, điều phối session, quản lý đồng hồ, nộp bài, chấm điểm tự động.

- Máy trạng thái bài thi: draft -> published -> scheduled -> active -> completed -> archived (lượt thi có chu kỳ riêng: in_progress -> submitted -> graded; có thể rẽ nhánh suspended/expired/cancelled)
- Session lưu trên Redis với TTL = thời gian thi; WebSocket đồng bộ đồng hồ để chống thay đổi phía client
- Nộp đáp án idempotent -- gọi lặp lại trả về kết quả đã cache (dedup bằng submission_id UUID)
- Câu hỏi xáo trộn phía server khi bắt đầu session và lưu vào Redis -- client không bao giờ thấy bộ câu hỏi đầy đủ
- Fan-out chấm điểm: câu hỏi tự động chấm xong ngay; essay/trả lời ngắn được đưa lên hàng Kafka cho AI Service

| Máy Trạng Thái Bài Thi (exam) & Lượt Thi (attempt):                                            |
| ---------------------------------------------------------------------------------------------- |
|                                                                                                |
| Exam:  draft --\[publish\]--> published --\[schedule\]--> scheduled --\[start_time\]--> active |
| active --\[end_time / all_submitted\]--> completed --\[archive\]--> archived                   |
|                                                                                                |
| Attempt:                                                                                       |
| in_progress --\[submit\]--> submitted --\[grade\]--> graded                                    |
| in_progress --\[timeout\]--> expired                                                           |
| in_progress --\[cancel\]--> cancelled                                                          |
| in_progress --\[risk >= 60\]--> suspended (chờ người kiểm tra xem xét)                         |

### **Question Service -- Dịch Vụ Câu Hỏi**

Trách nhiệm: CRUD ngân hàng câu hỏi, quản lý phiên bản, tinh chỉnh độ khó, phân loại chủ đề.

| **Loại Câu Hỏi (type)**         | **Phương Pháp Chấm Điểm Tự Động**                                             |
| ------------------------------- | ----------------------------------------------------------------------------- |
| multiple_choice_single          | So sánh option_id duy nhất với đáp án đúng                                    |
| multiple_choice_multi           | So sánh tập hợp option_id (cần chọn đúng tất cả option is_correct=true)       |
| true_false                      | So sánh boolean                                                               |
| fill_blank                      | Khớp regex trên mảng accepted_answers (cấu hình case_sensitive, use_regex)    |
| short_answer                    | NLP + tương tự ngữ nghĩa; đánh dấu kiểm tra thủ công nếu độ tin cậy < 0,75    |
| essay                           | AI chấm theo rubric (bất đồng bộ) + bắt buộc giáo viên xem lại                |
| code_execution                  | Thực thi trong sandbox (gVisor) đối chiếu input/output với test case          |
| matching                        | So sánh cặp pair (left_id <-> right_id) đã chọn                               |
| ordering                        | So sánh mảng thứ tự với correct_order                                         |
| drag_drop                       | So sánh ánh xạ item_id -> zone_id với correct_zone                            |
| hotspot                         | Kiểm tra tọa độ click nằm trong vùng hotspot is_correct                       |

**Tinh Chỉnh Độ Khó Theo IRT:**

- Mỗi câu hỏi có tham số IRT: b (độ khó), a (độ phân biệt), c (khả năng đoán)
- Tham số khởi tạo từ đánh giá của giáo viên, sau đó tinh chỉnh sau 30+ lượt trả lời bằng thuật toán EM
- Tinh chỉnh chạy bất đồng bộ trong Analytics Service -- không ảnh hưởng độ trễ bài thi

### **AI Service -- Dịch Vụ Trí Tuệ Nhân Tạo**

Trách nhiệm: Sinh câu hỏi, chấm essay, tìm kiếm ngữ nghĩa, kiểm tra chất lượng.

| **Tính Năng**                  | **Cách Tiếp Cận**                                                    | **Độ Trễ**                 |
| ------------------------------ | -------------------------------------------------------------------- | -------------------------- |
| Sinh câu hỏi                   | LLM (GPT-4o) với structured output + chuỗi kiểm tra chất lượng       | Bất đồng bộ (5-30 giây OK) |
| Sinh lựa chọn sai (distractor) | Mô hình fine-tuned + lọc tương tự ngữ nghĩa cho đáp án sai hợp lý    | Bất đồng bộ                |
| Chấm essay bằng AI             | LLM nhận biết rubric với ước tính độ tin cậy                         | Bất đồng bộ (< 60 giây)    |
| Tìm kiếm ngữ nghĩa             | text-embedding-3 + pgvector / Elasticsearch KNN (1536 chiều)         | Đồng bộ (< 200ms)          |
| Loại trừ trùng lặp             | Cosine similarity trên embedding; ngưỡng 0,92 cảnh báo trùng lặp     | Bất đồng bộ khi nhập liệu  |
| Kiểm tra chất lượng            | LLM kiểm tra riêng chấm điểm chính xác, hai nghĩa, phù hợp (0-100)   | Bất đồng bộ                |

| **Bảo Mật AI: Kiểm Soát Người-Trong-Vòng-Lặp (Human-in-the-Loop)**                                                                                                                                                                                                                                       |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Toàn bộ câu hỏi do AI tạo ra đều vào trạng thái draft và cần giáo viên xem lại trước khi vào ngân hàng sống. Một LLM kiểm tra riêng biệt chấm điểm chất lượng (0-100) đánh dấu các vấn đề tiềm ẩn: sai sự thật, câu hỏi hàm hồ, nhiều đáp án đúng, nội dung không phù hợp.                                |

### **Cheating Detection Service -- Dịch Vụ Chống Gian Lận**

Trách nhiệm: Phân tích hành vi đa tầng, tương quan sự kiện, tính toán điểm rủi ro, tạo cảnh báo.

| **Tầng Phát Hiện**              | **Tín Hiệu Thu Thập**                                     | **Trọng Số Rủi Ro** |
| ------------------------------- | --------------------------------------------------------- | ------------------- |
| L1 -- Phía Client               | Chuyển tab, mất focus, thoát toàn màn hình, copy/paste    | +5 mỗi sự kiện      |
| L2 -- Trình duyệt               | Phát hiện DevTools, tắt chuột phải, chặn phím tắt         | +10 đến +15         |
| L3 -- Mạng                      | Đổi vị trí địa lý giữa kỳ thi, VPN, nhiều IP              | +25                 |
| L4 -- Phân tích hành vi         | Bất thường tốc độ gõ phím, phân bố thời gian trả lời      | +6 đến +10          |
| L5 -- Video giám thị (tùy chọn) | Không có mặt / nhiều mặt / điện thoại / nhìn ra ngoài     | +20 đến +35         |
| L6 -- Thống kê (sau thi)        | Tương đồng đáp án với bạn cùng lớp, đáp án sai trùng nhau | Chỉ báo cáo         |

| Điểm Rủi Ro = Tổng(trọng_số_sự_kiện x hệ_số_tần_suất x hệ_số_suy_giảm_thời_gian) |
| -------------------------------------------------------------------------------- |
|                                                                                  |
| Ngưỡng Xử Lý (cập nhật exam_attempts.status):                                    |
| 0 - 29 : low      --> Không hành động                                            |
| 30 - 59 : medium  --> Cảnh báo học sinh + flagged_for_review = true              |
| 60 - 79 : high    --> status = 'suspended' (tạm dừng, kiểm tra thủ công)         |
| 80+     : critical--> status = 'cancelled' + leo thang xử lý ngay lập tức        |

### **Analytics Service -- Dịch Vụ Phân Tích**

Trách nhiệm: Phân tích theo thời gian thực và theo lô cho học sinh, giáo viên và quản trị viên.

- Nhóm consumer Kafka theo dõi: exam.events, answer.submitted, cheat.detected
- Xử lý luồng (stream) qua Apache Flink cho dashboard thời gian thực (tổng hợp dưới 1 giây)
- Xử lý theo lô (batch) qua Apache Spark cho báo cáo tháng / tuần (đường cong học, phân tích nhóm)
- ClickHouse làm OLAP store -- quét 100+ triệu hàng trong < 2 giây nhờ lưu trữ theo cột
- View vật chất tổng hợp trước 5 phút một lần cho dashboard giáo viên tải zero-latency

## **3.2 Thiết Kế Cơ Sở Dữ Liệu**

### **PostgreSQL -- Schema Xác Thực & Bài Thi**

> **⚠️ SUPERSEDED:** Phần `user_role` ENUM dưới đây đã được refactor sang RBAC động (bảng `roles` + `permissions` + `role_permissions`). Xem `database/postgresql/schema.sql` (mục 2a/2b) và `docs/auth-service-design.md` (mục 3.3) là nguồn sự thật hiện tại. Snippet dưới giữ để tham khảo thiết kế ban đầu.

| \-- BẢNG NGƯỜI DÙNG (không gắn trực tiếp với org; dùng user_organizations cho N:N)              |
| ----------------------------------------------------------------------------------------------- |
| CREATE TYPE user_role AS ENUM ('student','instructor','admin','proctor');                       |
| CREATE TABLE users (                                                                            |
| id UUID PRIMARY KEY DEFAULT gen_random_uuid(),                                                  |
| email VARCHAR(320) UNIQUE NOT NULL,                                                             |
| password_hash VARCHAR(255), -- Argon2id, NULL nếu chỉ đăng nhập OAuth                           |
| mfa_secret VARCHAR(64), -- TOTP (mã hóa AES-256-GCM lúc lưu)                                    |
| mfa_enabled BOOLEAN DEFAULT false,                                                              |
| email_verified BOOLEAN DEFAULT false,                                                           |
| is_active BOOLEAN DEFAULT true,                                                                 |
| created_at TIMESTAMPTZ DEFAULT NOW()                                                            |
| );                                                                                              |
|                                                                                                 |
| \-- QUAN HỆ NGƯỜI DÙNG - TỔ CHỨC (multi-tenant, vai trò gắn theo từng org)                      |
| CREATE TABLE user_organizations (                                                               |
| user_id UUID REFERENCES users(id) ON DELETE CASCADE,                                            |
| org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,                                     |
| role user_role NOT NULL,                                                                        |
| joined_at TIMESTAMPTZ DEFAULT NOW(),                                                            |
| is_active BOOLEAN DEFAULT true,                                                                 |
| PRIMARY KEY (user_id, org_id)                                                                   |
| );                                                                                              |
|                                                                                                 |
| \-- BẢNG BÀI THI                                                                                |
| CREATE TYPE exam_status AS ENUM ('draft','published','scheduled','active','completed','archived'); |
| CREATE TABLE exams (                                                                            |
| id UUID PRIMARY KEY DEFAULT gen_random_uuid(),                                                  |
| org_id UUID NOT NULL REFERENCES organizations(id),                                              |
| subject_id UUID REFERENCES subjects(id),                                                        |
| title VARCHAR(500) NOT NULL,                                                                    |
| created_by UUID NOT NULL REFERENCES users(id),                                                  |
| status exam_status DEFAULT 'draft',                                                             |
| exam_type VARCHAR(30) DEFAULT 'standard', -- standard\|adaptive\|practice\|survey               |
| duration_minutes INT NOT NULL,                                                                  |
| total_points NUMERIC(8,2) NOT NULL,                                                             |
| proctoring_level SMALLINT DEFAULT 0, -- 0:none 1:basic 2:video                                  |
| starts_at TIMESTAMPTZ,                                                                          |
| ends_at TIMESTAMPTZ                                                                             |
| );                                                                                              |
|                                                                                                 |
| \-- BẢNG LƯỢT THI                                                                               |
| CREATE TYPE attempt_status AS ENUM ('in_progress','submitted','graded','suspended','expired','cancelled'); |
| CREATE TABLE exam_attempts (                                                                    |
| id UUID PRIMARY KEY DEFAULT gen_random_uuid(),                                                  |
| exam_id UUID NOT NULL REFERENCES exams(id),                                                     |
| user_id UUID NOT NULL REFERENCES users(id),                                                     |
| status attempt_status DEFAULT 'in_progress',                                                    |
| started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),                                                  |
| submitted_at TIMESTAMPTZ,                                                                       |
| raw_score NUMERIC(8,2),                                                                         |
| percentage_score NUMERIC(5,2),                                                                  |
| risk_score SMALLINT DEFAULT 0,                                                                  |
| flagged_for_review BOOLEAN DEFAULT false,                                                       |
| ip_address INET                                                                                 |
| );                                                                                              |
|                                                                                                 |
| CREATE INDEX idx_attempt_exam_user ON exam_attempts(exam_id, user_id);                          |
| CREATE INDEX idx_active_attempts ON exam_attempts(status) WHERE status = 'in_progress';         |

### **MongoDB -- Schema Câu Hỏi**

| // collection: questions                                                                             |
| ---------------------------------------------------------------------------------------------------- |
| {                                                                                                    |
| "\_id": ObjectId,                                                                                    |
| "question_id": "UUID",                                                                               |
| "org_id": "UUID",                                                                                    |
| "subject_code": "CS201",                                                                             |
| "type": "multiple_choice_single", // xem bảng 11 loại ở phần 3.1                                     |
| "status": "draft \| review \| active \| deprecated",                                                 |
| "content": {                                                                                         |
| "text": "Thuật toán sắp xếp nào có độ phức tạp xấu nhất O(n log n)?",                                |
| "rich_text": "&lt;p&gt;Nội dung HTML...&lt;/p&gt;",                                                  |
| "media": \[{ "type": "image", "url": "s3://...", "alt": "..." }\]                                    |
| },                                                                                                   |
| "options": \[                                                                                        |
| { "id": "opt_a", "text": "Bubble Sort", "is_correct": false },                                       |
| { "id": "opt_b", "text": "Merge Sort", "is_correct": true },                                         |
| { "id": "opt_c", "text": "Quick Sort", "is_correct": false }                                         |
| \],                                                                                                  |
| "explanation": "Merge Sort đảm bảo O(n log n) trong mọi trường hợp...",                              |
| "metadata": {                                                                                        |
| "topic": "Cấu Trúc Dữ Liệu",                                                                         |
| "tags": \["algorithm", "complexity", "sorting"\],                                                    |
| "bloom_level": "analysis", // knowledge\|comprehension\|application\|analysis\|synthesis\|evaluation |
| "difficulty_assigned": 3                                                                             |
| },                                                                                                   |
| "irt": {                                                                                             |
| "b": 0.82, // độ khó                                                                                 |
| "a": 1.24, // độ phân biệt                                                                           |
| "c": 0.05, // đoán ngẫu nhiên                                                                        |
| "calibrated": true                                                                                   |
| },                                                                                                   |
| "stats": { "times_used": 1247, "correct_rate": 0.634 },                                              |
| "ai_generated": false,                                                                               |
| "ai_quality_score": null,                                                                            |
| "ai_quality_flags": \[\],                                                                            |
| "embedding": \[0.023, -0.412, ...\], // Vector 1536 chiều                                            |
| "version": 2                                                                                         |
| }                                                                                                    |

### **Redis -- Các Mẫu Key Chính**

| **Mẫu Key**                 | **Kiểu Dữ Liệu** | **Giá Trị Lưu**                          | **TTL**                |
| --------------------------- | ---------------- | ---------------------------------------- | ---------------------- |
| session:{attempt_id}        | Hash             | remaining_sec, current_q_idx, risk_score | Thời gian thi + 5 phút |
| answers:{attempt_id}        | Hash             | question_id -> JSON đáp án               | Thời gian thi + 1 giờ  |
| user:online:{user_id}       | String           | attempt_id (heartbeat)                   | 30 giây                |
| exam:concurrent:{exam_id}   | Set              | Tập hợp attempt_id đang thi              | Hết giờ thi + 30 phút  |
| rate:login:{ip}             | String           | Số lần thất bại                          | 15 phút sliding        |
| token:blacklist:{jti}       | String           | "revoked"                                | Hết hạn token          |
| leaderboard:{exam_id}       | Sorted Set       | user_id -> điểm số                       | 7 ngày                 |

## **3.3 Thiết Kế API**

### **Auth Service**

| **Method** | **Endpoint**       | **Mô Tả**                                         | **Xác Thực** |
| ---------- | ------------------ | ------------------------------------------------- | ------------ |
| POST       | /auth/register     | Đăng ký tài khoản mới                             | Công khai    |
| POST       | /auth/login        | Đăng nhập + TOTP; trả về access + refresh token   | Công khai    |
| POST       | /auth/refresh      | Đổi refresh token lấy access token mới            | Công khai    |
| POST       | /auth/logout       | Thu hồi refresh token                             | JWT          |
| POST       | /auth/mfa/setup    | Tạo mã QR và secret TOTP                          | JWT          |
| GET        | /auth/me           | Trả về thông tin người dùng hiện tại              | JWT          |

### **Exam Service**

| **Method** | **Endpoint**                 | **Mô Tả**                                            | **Quyền**     |
| ---------- | ---------------------------- | ---------------------------------------------------- | ------------- |
| POST       | /exams                       | Tạo bài thi mới với cấu hình                         | instructor    |
| GET        | /exams/{id}                  | Lấy thông tin bài thi (câu hỏi ẩn với học sinh)      | authenticated |
| POST       | /exams/{id}/start            | Bắt đầu lượt thi; trả về session + câu hỏi đầu       | student       |
| POST       | /attempts/{id}/answers       | Nộp đáp án cho một câu hỏi (idempotent)              | student       |
| POST       | /attempts/{id}/submit        | Nộp bài thi cuối cùng                                | student       |
| GET        | /attempts/{id}/result        | Lấy điểm + phản hồi (chỉ sau khi thi xong)           | student       |
| GET        | /exams/{id}/analytics        | Thống kê tổng hợp cho giáo viên                      | instructor    |
| WS         | /attempts/{id}/session       | WebSocket: nhịp tim, đồng bộ giờ, sự kiện gian lận   | student       |

### **AI Service -- Sinh Câu Hỏi**

| POST /ai/questions/generate                                                                         |
| --------------------------------------------------------------------------------------------------- |
| Request: {                                                                                          |
| "topic": "Chuẩn hóa cơ sở dữ liệu",                                                                 |
| "question_type": "multiple_choice_single",                                                          |
| "difficulty": 3,                                                                                    |
| "count": 5,                                                                                         |
| "context": "Khóa học CSDL cấp đại học",                                                             |
| "bloom_level": "analysis" // knowledge\|comprehension\|application\|analysis\|synthesis\|evaluation |
| }                                                                                                   |
| Response: { "job_id": "UUID", "status": "pending", "eta_seconds": 15 }                              |
|                                                                                                     |
| GET /ai/questions/generate/{job_id}                                                                 |
| Response (completed): {                                                                             |
| "status": "completed",                                                                              |
| "questions": \[{                                                                                    |
| "draft_id": "UUID",                                                                                 |
| "content": { "text": "...", "options": \[...\] },                                                   |
| "ai_quality_score": 87,                                                                             |
| "ai_quality_flags": \[\],                                                                           |
| "explanation": "..."                                                                                |
| }\]                                                                                                 |
| }                                                                                                   |

## **3.4 Các Luồng Xử Lý Chính**

### **Luồng 1 -- Học Sinh Làm Bài Thi**

| Student   API Gateway    Exam Service    Redis          Kafka                      |
| ---------------------------------------------------------------------------------- |
| \|           \|              \|            \|             \|                         |
| +--POST /exams/{id}/start-->+--route+auth-->+            \|             \|          |
| \|           \|              +--create session-->\|       \|             \|          |
| \|           \|              +--load questions-->\|(cache miss->Mongo)\| \|          |
| \|<--{session, Q1}----------+<-----------------+         \|             \|          |
| \|           \|              \|                 \|         \|             \|          |
| +--WS connect-->+<--WebSocket->+--heartbeat-->\|         \|             \|          |
| \|           \|              \|     (every 10s) \|         \|             \|          |
| +--POST /attempts/{id}/answers-->+------------>+--store answer-->\|     \|          |
| \|<--{saved:true}-----------+                 \|         +--publish------>\|        |
| \|           \|              \|                 \|         \|       answer.submitted |
| +--POST /attempts/{id}/submit-->+------------->+--finalize-->\|          \|          |
| \|           \|              \|                 \|         +--publish----->\|         |
| \|           \|              \|                 \|         \|       exam.completed   |
| \|<--{score, grade}---------+<----------------+          \|             \|          |

### **Luồng 2 -- Kiểm Tra Thích Nghi Theo IRT**

Sau mỗi câu trả lời, hệ thống cập nhật ước tính năng lực (theta) của học sinh và chọn câu hỏi tiếp theo tối đa hóa Fisher Information tại mức năng lực hiện tại.

| Thuật Toán Lựa Chọn Câu Hỏi Thích Nghi (IRT):                           |
| ----------------------------------------------------------------------- |
|                                                                         |
| 1\. Khởi tạo: theta_0 = 0.0 (trung bình quần thể)                       |
|                                                                         |
| 2\. Sau mỗi câu trả lời, cập nhật theta qua Maximum Likelihood:         |
| P(đúng \| theta, a, b) = 1 / (1 + exp(-a \* (theta - b)))               |
| Cập nhật theta bằng phương pháp Newton-Raphson                          |
|                                                                         |
| 3\. Chọn câu hỏi tiếp theo q\* tối đa hóa Fisher Information:           |
| I(theta) = a^2 \* P(theta) \* (1 - P(theta))                            |
| Ràng buộc: không quá 40% câu hỏi từ cùng chủ đề;                        |
| chủ đề bắt buộc phải xuất hiện nếu được cấu hình                        |
|                                                                         |
| 4\. Điều kiện dừng (khi thỏa mãn điều kiện đầu tiên):                   |
| \- Sai số chuẩn SE(theta) < 0,30 (ước tính đủ tin cậy), HOẶC            |
| \- Đạt số câu hỏi tối đa, HOẶC                                          |
| \- Hết giờ thi                                                          |
|                                                                         |
| 5\. Điểm cuối: Chuyển theta sang điểm T (T-score) hoặc thứ hạng phân vị |

### **Luồng 3 -- Phát Hiện Gian Lận**

| Trình Duyệt   WS Server   Chống Gian Lận   Redis/Kafka         |
| -------------------------------------------------------------- |
| \|              \|              \|             \|                |
| +--tab_switch----->+--chuyển sự kiện-->+     \|                  |
| \|              \|              +--risk += 5  \|                  |
| \|<--{cảnh_báo:...}-+<--đẩy cảnh báo-----+    \|                  |
| \|              \|              \|             \|                |
| +--ip_change_detected->+----------------->+   \|                  |
| \|              \|              +--risk += 25 \|                  |
| \|              \|              +--tổng = 62 --->+--publish cheat.alert |
| \|              \|              \|             \|                |
| \| Exam Service<------consume Kafka topic-------+               |
| \|              \| attempt.status = 'suspended' nếu risk >= 60 \| |
| \|<--{attempt_suspended}-+-----------------------------------+   |

**IV. CÁC KHÍA CẠNH THIẾT KẾ HỆ THỐNG**

## **4.1 Khả Năng Mở Rộng (Scalability)**

| **Vấn Đề**                | **Chiến Lược**                                                      | **Kết Quả Mong Đợi**                      |
| ------------------------- | ------------------------------------------------------------------- | ----------------------------------------- |
| 100.000 session đồng thời | Exam Service stateless (Spring Boot + Virtual Threads) + Redis cluster; 20 pod x 5.000 WS = 100.000 | Mở rộng ngang tuyến tính                  |
| Bão ghi đáp án đỉnh điểm  | Write-through Redis -> Kafka buffer -> flush PostgreSQL bất đồng bộ | Độ trễ < 50ms tại 500.000 write/phút      |
| Cô lập AI                 | GPU node pool riêng + hàng đợi Kafka bất đồng bộ                    | Độ trễ AI không bao giờ ảnh hưởng bài thi |
| Đọc câu hỏi               | MongoDB read replicas + Redis LRU cache (1 triệu câu hỏi)           | Lấy câu hỏi dưới 10ms                     |
| Video giám thị            | Client trích xuất 1 fps -> S3 -> suy luận GPU bất đồng bộ           | GPU tách rời khỏi hot path bài thi        |
| Analytics                 | ClickHouse với view vật chất tổng hợp trước (làm mới 5 phút)        | Dashboard < 1 giây trên 100+ triệu hàng   |

**Chính Sách Tự Động Mở Rộng (Kubernetes HPA):**

- Exam Service: Scale khi CPU > 70% HOẶC > 5.000 WebSocket/pod (JVM Virtual Threads cho phép mật độ cao hơn Node.js truyền thống)
- AI Service: Scale khi GPU > 60%; thu nhỏ sau 10 phút nhàn rỗi (GPU đắt tiền)
- Cheating Service: Scale khi CPU > 80%; xử lý 10.000 sự kiện/giây/pod
- PostgreSQL: RDS Multi-AZ với read replica; Redis: ElastiCache cluster mode

## **4.2 Tính Sẵn Sàng Cao (Availability) -- Mục Tiêu SLA: 99,95%**

| **Thành Phần** | **Chiến Lược HA**                                                       | **RTO / RPO**                     |
| -------------- | ----------------------------------------------------------------------- | --------------------------------- |
| API Gateway    | Multi-AZ active-active; kiểm tra sức khỏe mỗi 5 giây                    | RTO < 30 giây (DNS failover)      |
| Exam Service   | k8s pod chống áp lực qua 3 AZ; tối thiểu 3 bản sao                      | RTO < 10 giây (khởi động lại pod) |
| PostgreSQL     | RDS Multi-AZ với bản sao đồng bộ; tự động chuyển đổi                    | RTO < 60 giây, RPO = 0            |
| Redis          | ElastiCache cluster; 3 shard x 2 bản sao                                | RTO < 30 giây, RPO < 1 giây       |
| Kafka          | 3 broker; replication factor 3; min.insync.replicas=2                   | RTO < 60 giây, không mất dữ liệu  |
| AI Service     | Dựa trên hàng đợi -- yêu cầu được xử lý lại khi pod lỗi (Kafka bảo đảm) | RTO < 5 phút (xử lý lại hàng đợi) |

| **Mẫu Circuit Breaker (Cầu Chì Điện Tử)**                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Tất cả các cuộc gọi HTTP giữa service đều được bao với circuit breaker. Nếu AI Service lỗi, kỳ thi vẫn tiếp tục mà không có tính năng AI. Nếu Analytics Service lỗi, sự kiện được đệm vào Kafka không ảnh hưởng đến bài thi. Khả năng suy giảm có kiểm soát đảm bảo bài thi luôn tiếp tục ngay cả khi một phần hệ thống gặp sự cố.          |

## **4.3 Mô Hình Nhất Quán (Consistency)**

| **Miền Dữ Liệu**      | **Mô Hình**                              | **Lý Do**                                          |
| --------------------- | ---------------------------------------- | -------------------------------------------------- |
| Xác thực người dùng   | Mạnh (PostgreSQL ACID)                   | Liên quan bảo mật; đọc dữ liệu cũ là nguy hiểm     |
| Đáp án bài thi        | Mạnh (Redis AOF + flush DB bất đồng bộ)  | Đáp án không được phép mất; Redis fsync=always     |
| Đồng hồ session       | Mạnh (shard Redis đơn cho mỗi session)   | Gian lận đồng hồ là vector tấn công trực tiếp      |
| Đọc ngân hàng câu hỏi | Cuối cùng (replica + cache TTL 5 phút)   | Câu hỏi cũ chấp nhận được; cải thiện độ trễ        |
| Dashboard phân tích   | Cuối cùng (view vật chất; độ trễ 5 phút) | Độ trễ 5 phút chấp nhận được cho báo cáo giáo viên |
| Điểm rủi ro gian lận  | Mạnh (Redis + đánh giá ngay lập tức)     | Điểm cũ = lỗ hổng bảo mật                          |

## **4.4 Mục Tiêu Hiệu Năng**

| **Thao Tác**                   | **P50**   | **P99**   | **Vấn Đề Nghẽn Cổ**                                  |
| ------------------------------ | --------- | --------- | ---------------------------------------------------- |
| Đăng nhập / Cấp Token          | < 80ms    | < 200ms   | Argon2id cố tình là chậm (200ms = cài đặt bảo mật)   |
| Bắt Đầu Session Thi            | < 100ms   | < 300ms   | Chọn câu hỏi + ghi session Redis                     |
| Nộp Đáp Án                     | < 50ms    | < 150ms   | Ghi Redis (flush PostgreSQL bất đồng bộ)             |
| Câu Hỏi Tiếp Theo (Thích Nghi) | < 80ms    | < 200ms   | Tính toán IRT trên tập ứng viên câu hỏi              |
| Xử Lý Sự Kiện Gian Lận         | < 100ms   | < 500ms   | Kafka consume + cập nhật risk score trong Redis      |
| Sinh Câu Hỏi AI                | < 15 giây | < 45 giây | Suy luận LLM (bất đồng bộ -- không ảnh hưởng UX)     |
| Tải Dashboard Phân Tích        | < 500ms   | < 2 giây  | Truy vấn ClickHouse (view vật chất giúp ích nhiều)   |

**V. ĐÁNH ĐỔI & LÝ DO KIẾN TRÚC (TRADE-OFFS)**

## **5.1 Lý Do Chọn Kiến Trúc Này**

| **Quyết Định**      | **Thay Thế Đã Xem Xét**     | **Tại Sao Chọn Này**                                                                        | **Đánh Đổi Chấp Nhận**                                                  |
| ------------------- | --------------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Microservices       | Modular Monolith            | AI/GPU cần Python runtime + scale riêng; cô lập lỗi bắt buộc cho toàn vẹn bài thi           | Độ phức tạp vận hành cao hơn; giảm nhẹ bằng Kubernetes + Istio          |
| Kafka (EDA)         | HTTP trực tiếp giữa service | Tách biệt đột ghi; phát lại sự kiện để kiểm toán; analytics độc lập với đường thi           | Eventual consistency ở một số luồng; giảm nhẹ bằng distributed tracing  |
| Redis cho session   | JWT thuần túy (stateless)   | JWT không thu hồi được giữa kỳ thi; Redis cung cấp kiểm soát phía server để bảo vệ toàn vẹn | Phụ thuộc Redis; giảm nhẹ bằng HA cluster                               |
| MongoDB cho câu hỏi | PostgreSQL JSONB            | Schema thật sự dị nhất trên 8+ loại câu hỏi; tìm kiếm văn bản + vector index native         | Không có JOIN SQL xuyên service; giải quyết bằng API composition        |
| ClickHouse          | PostgreSQL read replica     | Postgres gặp khó khăn ở 100+ triệu hàng phân tích; ClickHouse nhanh hơn 100 lần             | Thêm một kho dữ liệu để vận hành; cần ETL pipeline                      |
| Python cho AI       | Java + gọi API LLM ngoài    | Hệ sinh thái ML (LangChain, PyTorch, HuggingFace) không thể thay thế; hỗ trợ thư viện GPU native (CUDA)   | Runtime khác với các service Java; tách deploy riêng và giao tiếp qua REST/gRPC + Kafka   |
| Java 21 cho service | Node.js + Go hỗn hợp        | Một runtime duy nhất cho tất cả service backend; Virtual Threads đạt non-blocking I/O không cần async code; Spring Boot chuẩn hóa observability/config/security | Footprint bộ nhớ JVM lớn hơn Go; giảm nhẹ bằng GraalVM native image cho service chịu tải cao |

## **5.2 Nếu Cần Mở Rộng Lên 1 Triệu+ Người Dùng**

- PostgreSQL -> CockroachDB hoặc Vitess: SQL phân tán cho mở rộng ghi ngang theo chiều ngang
- MongoDB -> Cluster phân mảnh: Phân mảnh theo org_id + subject_id; thêm Pinecone / Weaviate là vector DB chuyên dụng
- Redis -> Cluster lớn hơn: 1 triệu WebSocket đồng thời cần cơ sở hạ tầng chuyên biệt với consistent hashing
- Kafka -> Tăng partition: Tăng số partition theo tỷ lệ; giới thiệu Kafka Streams cho suy luận ML trên luồng đáp án
- Đa vùng (Multi-region): Triển khai active-active; GeoDNS routing; nhân bản session dựa trên CRDT tránh xung đột
- Video giám thị -> Biên suy luận (Edge inference): WebRTC + NVIDIA Jetson tại CDN PoP để giảm băng thông đường truyền và độ trễ suy luận

**VI. CẢI TIẾN TƯƠNG LAI (FUTURE IMPROVEMENTS)**

## **6.1 Nâng Cao Khả Năng AI**

| **Tính Năng**                               | **Hướng Tiếp Cận**                                                     | **Giá Trị Mang Lại**                              | **Độ Phức Tạp** |
| ------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------- | --------------- |
| Lộ trình học tập cá nhân hóa                | Học tăng cường (contextual bandits) sắp xếp trình tự chủ đề tối ưu     | Kết quả học tập tốt hơn, tỷ lệ hoàn thành cao hơn | Cao             |
| Tự động chấm essay theo rubric              | LLM fine-tune theo rubric chuyên ngành với ước lượng độ tin cậy        | Giảm tải giáo viên khi chấm essay quy mô lớn      | Cao             |
| Câu hỏi đa phương tiện                      | Mô hình Vision-Language sinh và chấm câu hỏi có hình ảnh / biểu đồ     | Hỗ trợ STEM cần suy luận trực quan                | Cao             |
| Dự báo kết quả học sinh                     | LSTM / Prophet dự báo kết quả kỳ thi từ lịch sử làm bài kiểm tra       | Can thiệp sớm cho học sinh có nguy cơ             | Trung bình      |
| Cải thiện IRT khi bắt đầu lạnh (cold-start) | XGBoost trên metadata câu hỏi dự báo độ khó trước câu trả lời đầu tiên | Độ khó chính xác ngay từ ngày đầu                 | Trung bình      |

## **6.2 Nâng Cấp Giám Thị Thời Gian Thực**

- Theo dõi mắt nhìn (Gaze tracking): Eye-tracking qua MediaPipe tại 5 fps; phát hiện nhìn ra ngoài màn hình trên 5 giây
- Phân tích âm thanh: Phát hiện giọng nói (VAD) trên thiết bị; đánh dấu các mẫu âm thanh khi làm bài
- Nhận diện liên tục: So khớp khuôn mặt với ảnh đăng ký xuyên suốt kỳ thi, không chỉ lúc bắt đầu
- Động lực học gõ phím (Keystroke dynamics): Sinh trắc học hành vi -- nhịp gõ phím là xác thực bị động liên tục
- Kiểm toán công bằng AI: Phân tích thiên lệch theo nhóm dân số hàng quý để tránh tỉ lệ false positive phân biệt đối xử

## **6.3 Lộ Trình Nền Tảng**

| **Thời Gian** | **Tính Năng**                     | **Tích Hợp Mục Tiêu**                                                |
| ------------- | --------------------------------- | -------------------------------------------------------------------- |
| Q3/2026       | Tích hợp LMS (LTI 1.3)            | Canvas, Moodle, Blackboard, Google Classroom                         |
| Q3/2026       | Chứng chỉ blockchain              | Ethereum / Polygon phát chứng chỉ chống giả mạo                      |
| Q4/2026       | SDK giám thị mobile               | iOS/Android với xác thực phần cứng (SafetyNet / DeviceCheck)         |
| Q4/2026       | Chế độ thi ngoại tuyến            | PWA + SQLite cục bộ; đồng bộ khi có kết nối lại                      |
| Q1/2027       | Học liên kết (Federated Learning) | Huấn luyện mô hình cục bộ; tổng hợp gradient (bảo vệ quyền riêng tư) |

## **6.4 Big Data & Analytics Nâng Cao**

- Data Lake: Toàn bộ sự kiện bài thi -> S3 Parquet -> Apache Iceberg (truy vấn time-travel, tiến hóa schema)
- Phân tích theo nhóm (Cohort): Xác định cụm mẫu học tập; đề xuất nội dung qua collaborative filtering
- Phát hiện DIF (Differential Item Functioning): Xác định câu hỏi có thiên kiến theo nhóm dân số
- Khung A/B testing: Thí nghiệm ngẫu nhiên trên thứ tự câu hỏi, thời gian phản hồi, giao diện bài thi

**PHỤ LỤC -- TÀI LIỆU THAM KHẢO KỸ THUẬT**

## **A. Cấu Hình Port Các Service**

| **Service**          | **Port Nội Bộ** | **Giao Thức**     | **Health Check** |
| -------------------- | --------------- | ----------------- | ---------------- |
| API Gateway          | 80 / 443        | HTTP / HTTPS      | /health          |
| Auth Service         | 3001            | HTTP + gRPC :4001 | /health/live     |
| Exam Service         | 3002            | HTTP + WebSocket  | /health/live     |
| Question Service     | 3003            | HTTP              | /health/live     |
| AI Service           | 3004            | HTTP              | /health/live     |
| Cheating Detection   | 3005            | HTTP              | /health/live     |
| Analytics Service    | 3006            | HTTP              | /health/live     |
| Notification Service | 3007            | HTTP              | /health/live     |
| Media Service        | 3008            | HTTP              | /health/live     |

## **B. Thiết Kế Topic Kafka**

| **Topic**               | **Số Partition** | **Lưu Trữ** | **Các Consumer**                          |
| ----------------------- | ---------------- | ----------- | ----------------------------------------- |
| exam.session.started    | 20               | 7 ngày      | Analytics, Notification                   |
| exam.answer.submitted   | 50               | 7 ngày      | Grading, Analytics, CheatingDetection     |
| exam.session.completed  | 20               | 30 ngày     | Grading, Analytics, Notification          |
| cheat.event.raw         | 20               | 30 ngày     | CheatingDetection, Analytics              |
| cheat.alert.generated   | 10               | 90 ngày     | ExamService(suspend), Notification, Admin |
| ai.generation.requested | 10               | 1 ngày      | AIService (worker)                        |
| ai.generation.completed | 10               | 1 ngày      | QuestionService (draft import)            |
| analytics.user.activity | 30               | 1 ngày      | Analytics (xử lý luồng)                   |

## **C. Stack Quan Sát Hệ Thống (SRE Observability)**

| **Mục Tiêu**      | **Công Cụ**                             | **Chỉ Số Chính Theo Dõi**                                  |
| ----------------- | --------------------------------------- | ---------------------------------------------------------- |
| Thu thập chỉ số   | Prometheus + Alertmanager               | Request rate, error rate, p99 latency (phương pháp RED)    |
| Dashboard         | Grafana                                 | Số session đang thi, phân bố điểm rủi ro, độ trễ Kafka     |
| Truy vết phân tán | Jaeger                                  | Truy vết yêu cầu xuyên tất cả microservice                 |
| Tập trung log     | ELK (Elasticsearch + Logstash + Kibana) | Log JSON có cấu trúc, lịch sử kiểm toán bảo mật            |
| Theo dõi lỗi      | Sentry                                  | Ngoại lệ chưa xử lý, vấn đề hiệu năng, theo dõi phiên bản  |
| Giám sát uptime   | Blackbox Exporter + PagerDuty           | Luồng thi tổng hợp mỗi 1 phút                              |

Phân loại tài liệu: Nội bộ | Tài liệu Thiết kế Hệ Thống

**Hệ Thống Thi Trực Tuyến Thông Minh | Phiên bản 1.0 | Tháng 4/2026**

_Đây là tài liệu thiết kế sống và cần được cập nhật khi các quyết định kiến trúc thay đổi._
