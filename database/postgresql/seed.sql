-- =============================================================================
-- seed.sql — dữ liệu mẫu SmartQuizSystem (DATN scope)
-- =============================================================================
-- Chạy sau schema.sql:
--   psql -U postgres -d smartquiz -f database/postgresql/schema.sql
--   psql -U postgres -d smartquiz -f database/postgresql/seed.sql
--
-- Dữ liệu: 1 admin + 2 teacher + 5 student + 5 câu hỏi + 1 exam + 5 assignment.
-- Đủ để smoke test end-to-end: login → start attempt → submit → grading.
-- Password: "Password123!" — BCrypt cost 12.
-- Seed production-grade (organizations, plan_tier, argon2id...) đã archive.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- Users
-- BCrypt hash của "Password123!" (cost 12). Dùng chung cho demo.
-- -----------------------------------------------------------------------------
INSERT INTO auth.users (id, email, password_hash, full_name, role) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'admin@smartquiz.vn',    '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Quản trị hệ thống',  'ADMIN'),
    ('a0000000-0000-0000-0000-000000000002', 'teacher1@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Nguyễn Văn An',      'TEACHER'),
    ('a0000000-0000-0000-0000-000000000003', 'teacher2@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Trần Thị Bình',      'TEACHER'),
    ('a0000000-0000-0000-0000-000000000004', 'student1@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Lê Minh Cường',      'STUDENT'),
    ('a0000000-0000-0000-0000-000000000005', 'student2@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Phạm Thu Dung',      'STUDENT'),
    ('a0000000-0000-0000-0000-000000000006', 'student3@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Vũ Hoàng Em',        'STUDENT'),
    ('a0000000-0000-0000-0000-000000000007', 'student4@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Đặng Quốc Phong',    'STUDENT'),
    ('a0000000-0000-0000-0000-000000000008', 'student5@smartquiz.vn', '$2a$12$WTH1vCiY/7Aq2V9n8J/K7OJ9kG6x5mYbO5jL0bN6J3q3c9sW2q4fm', 'Hoàng Mỹ Hạnh',      'STUDENT');


-- -----------------------------------------------------------------------------
-- Questions (4 MCQ + 1 Essay; chưa có embedding — AI service sẽ populate khi demo)
-- -----------------------------------------------------------------------------
INSERT INTO core.questions (id, type, difficulty, content, metadata, created_by) VALUES
    ('b0000000-0000-0000-0000-000000000001', 'MCQ_SINGLE', 'EASY',
     '{"stem":"Thuật toán nào có độ phức tạp trung bình O(n log n)?","options":["Bubble sort","Quick sort","Selection sort","Insertion sort"],"correct_answer":[1],"explanation":"Quick sort trung bình O(n log n), worst case O(n²)."}',
     '{"topic":"sorting","tags":["algorithm","complexity"],"bloom_level":"remember"}',
     'a0000000-0000-0000-0000-000000000002'),

    ('b0000000-0000-0000-0000-000000000002', 'MCQ_SINGLE', 'MEDIUM',
     '{"stem":"Cây nhị phân tìm kiếm (BST) có tính chất nào?","options":["Trái < gốc < phải","Trái > gốc","Mọi node có 2 con","Luôn cân bằng"],"correct_answer":[0],"explanation":"BST định nghĩa trái luôn nhỏ hơn gốc, phải luôn lớn hơn gốc."}',
     '{"topic":"data-structure","tags":["tree","bst"],"bloom_level":"understand"}',
     'a0000000-0000-0000-0000-000000000002'),

    ('b0000000-0000-0000-0000-000000000003', 'MCQ_MULTI', 'MEDIUM',
     '{"stem":"Các đặc điểm của transaction ACID là gì? (chọn nhiều)","options":["Atomicity","Consistency","Integrity","Isolation","Durability"],"correct_answer":[0,1,3,4],"explanation":"ACID = Atomicity + Consistency + Isolation + Durability. Integrity không nằm trong acronym."}',
     '{"topic":"database","tags":["acid","transaction"],"bloom_level":"remember"}',
     'a0000000-0000-0000-0000-000000000003'),

    ('b0000000-0000-0000-0000-000000000004', 'TRUE_FALSE', 'EASY',
     '{"stem":"HTTP là stateless protocol.","options":["Đúng","Sai"],"correct_answer":[0],"explanation":"Mỗi request HTTP là độc lập; state lưu qua cookie/session/token."}',
     '{"topic":"network","tags":["http"],"bloom_level":"remember"}',
     'a0000000-0000-0000-0000-000000000002'),

    ('b0000000-0000-0000-0000-000000000005', 'SHORT_ANSWER', 'EASY',
     '{"stem":"Viết tắt của \"International Organization for Standardization\" là gì?","correct_answer":"ISO","accepted_variants":["iso","I.S.O.","Iso"],"explanation":"ISO là viết tắt tiêu chuẩn quốc tế của tổ chức."}',
     '{"topic":"general","tags":["acronym"],"bloom_level":"remember"}',
     'a0000000-0000-0000-0000-000000000002'),

    ('b0000000-0000-0000-0000-000000000006', 'ESSAY', 'HARD',
     '{"stem":"Trình bày nguyên lý transactional outbox pattern và tại sao nó giải quyết vấn đề dual-write.","rubric":"2đ mô tả dual-write problem; 3đ nguyên lý outbox (ghi cùng TX, relayer publish); 3đ idempotent consumer (dedupe event_id); 2đ trade-off (latency, storage)."}',
     '{"topic":"distributed-system","tags":["outbox","messaging"],"bloom_level":"analyze"}',
     'a0000000-0000-0000-0000-000000000003');


-- -----------------------------------------------------------------------------
-- Exam + questions + assignment
-- -----------------------------------------------------------------------------
INSERT INTO core.exams (id, title, description, duration_min, total_points, status, open_at, close_at, created_by, published_at) VALUES
    ('c0000000-0000-0000-0000-000000000001',
     'Bài kiểm tra giữa kỳ — Nhập môn Công nghệ Phần mềm',
     'Kiểm tra kiến thức cơ bản: thuật toán, cấu trúc dữ liệu, CSDL, mạng. 60 phút.',
     60, 10, 'PUBLISHED',
     now() - interval '1 hour', now() + interval '7 days',
     'a0000000-0000-0000-0000-000000000002',
     now() - interval '30 minutes');

-- Snapshot câu hỏi vào exam (pinned content)
INSERT INTO core.exam_questions (exam_id, position, question_id, question_version, points, snapshot)
SELECT 'c0000000-0000-0000-0000-000000000001', pos, qid, 1, pts, content
FROM (VALUES
    (1, 'b0000000-0000-0000-0000-000000000001'::uuid, 2.0),
    (2, 'b0000000-0000-0000-0000-000000000002'::uuid, 2.0),
    (3, 'b0000000-0000-0000-0000-000000000003'::uuid, 2.0),
    (4, 'b0000000-0000-0000-0000-000000000004'::uuid, 1.0),
    (5, 'b0000000-0000-0000-0000-000000000005'::uuid, 1.0),
    (6, 'b0000000-0000-0000-0000-000000000006'::uuid, 2.0)
) AS t(pos, qid, pts)
JOIN core.questions q ON q.id = t.qid;

-- Assign cho 5 student
INSERT INTO core.exam_assignments (exam_id, student_id) VALUES
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004'),
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000005'),
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000006'),
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000007'),
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000008');


COMMIT;

-- Smoke check — dùng để verify sau khi chạy seed:
--   SELECT count(*) FROM auth.users;                    -- 8
--   SELECT count(*) FROM core.questions;                -- 6
--   SELECT count(*) FROM core.exam_questions;           -- 6
--   SELECT count(*) FROM core.exam_assignments;         -- 5
