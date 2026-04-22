-- =============================================================================
-- SMART QUIZ SYSTEM - PostgreSQL SEED DATA (Dữ liệu mẫu)
-- =============================================================================
-- Chạy sau schema.sql:
--   psql -U postgres -d smartquiz -f schema.sql
--   psql -U postgres -d smartquiz -f seed.sql
-- =============================================================================

BEGIN;

-- =============================================================================
-- 1. ORGANIZATIONS
-- =============================================================================
INSERT INTO organizations (id, name, slug, plan_tier, max_users, max_exams, ai_enabled, proctoring_enabled, settings) VALUES
('11111111-1111-1111-1111-111111111111', 'Đại Học Bách Khoa Hà Nội', 'dai-hoc-bach-khoa-hn', 'enterprise', 10000, 1000, true,  true,
    '{"logo":"https://cdn.example.com/hust.png","primary_color":"#B71C1C","academic_year":"2025-2026"}'::jsonb),
('22222222-2222-2222-2222-222222222222', 'Trung Tâm Anh Ngữ Global', 'global-english',         'pro',        500,   100,  true,  false,
    '{"logo":"https://cdn.example.com/global.png","primary_color":"#1565C0"}'::jsonb),
('33333333-3333-3333-3333-333333333333', 'Demo Free Tier',            'demo-free',              'free',       100,   50,   false, false,
    '{}'::jsonb);

-- =============================================================================
-- 2. USERS (password_hash: argon2id cho chuỗi "Password123!" - mock)
-- =============================================================================
INSERT INTO users (id, email, username, full_name, password_hash, email_verified, locale) VALUES
('a0000000-0000-0000-0000-000000000001', 'admin@smartquiz.vn',      'admin',     'System Admin',     '$argon2id$v=19$m=65536,t=3,p=4$mockhashadmin',       true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000002', 'gv.nguyen@hust.edu.vn',   'gv_nguyen', 'Nguyễn Văn An',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashnguyen',      true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000003', 'gv.tran@hust.edu.vn',     'gv_tran',   'Trần Thị Bình',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashtran',        true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000004', 'hs.le@hust.edu.vn',       'hs_le',     'Lê Minh Cường',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashle',          true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000005', 'hs.pham@hust.edu.vn',     'hs_pham',   'Phạm Thu Dung',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashpham',        true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000006', 'hs.hoang@hust.edu.vn',    'hs_hoang',  'Hoàng Đức Hải',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashhoang',       true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000007', 'proctor@hust.edu.vn',     'proctor',   'Giám Thị Quân',    '$argon2id$v=19$m=65536,t=3,p=4$mockhashproctor',     true, 'vi-VN'),
('a0000000-0000-0000-0000-000000000008', 'teacher@global.vn',       'gv_global', 'John Smith',       '$argon2id$v=19$m=65536,t=3,p=4$mockhashglobal',      true, 'en-US');

-- =============================================================================
-- 2b. ROLES (4 system role mặc định)
-- =============================================================================
INSERT INTO roles (id, org_id, code, name, description, is_system) VALUES
('70000000-0000-0000-0000-000000000001', NULL, 'student',    'Học sinh',       'Người làm bài thi',                              true),
('70000000-0000-0000-0000-000000000002', NULL, 'instructor', 'Giáo viên',      'Tạo bài thi, câu hỏi, chấm điểm',                true),
('70000000-0000-0000-0000-000000000003', NULL, 'admin',      'Quản trị org',   'Quản trị tổ chức (user, billing, setting)',      true),
('70000000-0000-0000-0000-000000000004', NULL, 'proctor',    'Giám thị',       'Giám sát bài thi, xử lý alert gian lận',         true);

-- Ví dụ custom role của HUST (Phase 2 feature demo)
INSERT INTO roles (id, org_id, code, name, description, is_system) VALUES
('70000000-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111',
    'custom.grading_assistant', 'Trợ giảng chấm bài',
    'Chấm essay/short_answer nhưng không tạo bài thi', false);

-- =============================================================================
-- 2c. PERMISSIONS (catalog hệ thống, ~60 permissions)
-- =============================================================================
INSERT INTO permissions (id, code, resource, action, scope, description) VALUES
-- ORG + USER management
('71000000-0000-0000-0000-000000000001', 'user.read.org',         'user',      'read',       'org', 'Xem danh sách user trong org'),
('71000000-0000-0000-0000-000000000002', 'user.invite',           'user',      'invite',     'org', 'Mời user mới vào org'),
('71000000-0000-0000-0000-000000000003', 'user.update.role',      'user',      'update_role','org', 'Đổi role của user'),
('71000000-0000-0000-0000-000000000004', 'user.lock',             'user',      'lock',       'org', 'Khoá/unlock user'),
('71000000-0000-0000-0000-000000000005', 'user.delete',           'user',      'delete',     'org', 'Xoá user (soft)'),
('71000000-0000-0000-0000-000000000006', 'user.impersonate',      'user',      'impersonate','platform', 'Đăng nhập as user khác (platform admin)'),
('71000000-0000-0000-0000-000000000007', 'org.settings.update',   'org',       'update',     'org', 'Sửa setting của org'),

-- SUBJECT
('71000000-0000-0000-0000-000000000010', 'subject.read',          'subject',   'read',       'org', 'Xem subject'),
('71000000-0000-0000-0000-000000000011', 'subject.create',        'subject',   'create',     'org', 'Tạo subject'),
('71000000-0000-0000-0000-000000000012', 'subject.update',        'subject',   'update',     'org', 'Sửa subject'),

-- QUESTION
('71000000-0000-0000-0000-000000000020', 'question.read.org',     'question',  'read',       'org', 'Xem ngân hàng câu hỏi org'),
('71000000-0000-0000-0000-000000000021', 'question.create',       'question',  'create',     'own', 'Tạo câu hỏi'),
('71000000-0000-0000-0000-000000000022', 'question.update.own',   'question',  'update',     'own', 'Sửa câu hỏi mình tạo'),
('71000000-0000-0000-0000-000000000023', 'question.update.any',   'question',  'update',     'org', 'Sửa bất kỳ câu hỏi trong org'),
('71000000-0000-0000-0000-000000000024', 'question.approve',      'question',  'approve',    'org', 'Review + approve câu hỏi'),
('71000000-0000-0000-0000-000000000025', 'question.deprecate',    'question',  'deprecate',  'org', 'Loại câu hỏi khỏi active'),
('71000000-0000-0000-0000-000000000026', 'question.import',       'question',  'import',     'org', 'Import hàng loạt'),
('71000000-0000-0000-0000-000000000027', 'question.export',       'question',  'export',     'org', 'Export'),
('71000000-0000-0000-0000-000000000028', 'question.report',       'question',  'report',     'org', 'Báo cáo câu hỏi có vấn đề'),

-- EXAM
('71000000-0000-0000-0000-000000000030', 'exam.read',             'exam',      'read',       'org', 'Xem bài thi'),
('71000000-0000-0000-0000-000000000031', 'exam.create',           'exam',      'create',     'own', 'Tạo bài thi'),
('71000000-0000-0000-0000-000000000032', 'exam.update.own',       'exam',      'update',     'own', 'Sửa bài thi mình tạo'),
('71000000-0000-0000-0000-000000000033', 'exam.update.any',       'exam',      'update',     'org', 'Sửa bất kỳ bài thi'),
('71000000-0000-0000-0000-000000000034', 'exam.publish',          'exam',      'publish',    'own', 'Publish bài thi mình tạo'),
('71000000-0000-0000-0000-000000000035', 'exam.archive',          'exam',      'archive',    'org', 'Archive bài thi'),
('71000000-0000-0000-0000-000000000036', 'exam.delete',           'exam',      'delete',     'own', 'Xoá bài thi mình tạo'),
('71000000-0000-0000-0000-000000000037', 'exam.enroll.students',  'exam',      'enroll',     'own', 'Mời student vào bài thi'),
('71000000-0000-0000-0000-000000000038', 'exam.analytics',        'exam',      'analytics',  'org', 'Xem dashboard exam'),

-- ATTEMPT
('71000000-0000-0000-0000-000000000040', 'attempt.start',         'attempt',   'start',      'own', 'Bắt đầu làm bài (student)'),
('71000000-0000-0000-0000-000000000041', 'attempt.submit',        'attempt',   'submit',     'own', 'Nộp bài'),
('71000000-0000-0000-0000-000000000042', 'attempt.read.own',      'attempt',   'read',       'own', 'Xem lượt thi của mình'),
('71000000-0000-0000-0000-000000000043', 'attempt.read.org',      'attempt',   'read',       'org', 'Xem mọi lượt thi trong org'),
('71000000-0000-0000-0000-000000000044', 'attempt.grade',         'attempt',   'grade',      'org', 'Chấm bài thủ công'),
('71000000-0000-0000-0000-000000000045', 'attempt.regrade',       'attempt',   'regrade',    'org', 'Chấm lại'),
('71000000-0000-0000-0000-000000000046', 'attempt.suspend',       'attempt',   'suspend',    'org', 'Tạm dừng attempt (proctor)'),
('71000000-0000-0000-0000-000000000047', 'attempt.resume',        'attempt',   'resume',     'org', 'Cho tiếp tục attempt'),
('71000000-0000-0000-0000-000000000048', 'attempt.terminate',     'attempt',   'terminate',  'org', 'Kết thúc attempt (cheat confirmed)'),

-- CHEAT / PROCTORING
('71000000-0000-0000-0000-000000000050', 'cheat.review',          'cheat',     'review',     'org', 'Review cheat queue'),
('71000000-0000-0000-0000-000000000051', 'cheat.decide',          'cheat',     'decide',     'org', 'Confirm/dismiss cheat'),
('71000000-0000-0000-0000-000000000052', 'cheat.appeal.submit',   'cheat',     'appeal',     'own', 'Nộp appeal'),
('71000000-0000-0000-0000-000000000053', 'cheat.appeal.resolve',  'cheat',     'appeal_resolve','org','Xử lý appeal'),
('71000000-0000-0000-0000-000000000054', 'cheat.video.view',      'cheat',     'video_view', 'org', 'Xem video giám thị'),
('71000000-0000-0000-0000-000000000055', 'cheat.config.weights',  'cheat',     'config',     'platform','Sửa weight detection (platform)'),

-- CERTIFICATE
('71000000-0000-0000-0000-000000000060', 'certificate.read.own',  'certificate','read',      'own', 'Xem chứng chỉ của mình'),
('71000000-0000-0000-0000-000000000061', 'certificate.read.org',  'certificate','read',      'org', 'Xem mọi chứng chỉ trong org'),
('71000000-0000-0000-0000-000000000062', 'certificate.revoke',    'certificate','revoke',    'org', 'Thu hồi chứng chỉ'),

-- AI Service
('71000000-0000-0000-0000-000000000070', 'ai.generate',           'ai',        'generate',   'org', 'Sinh câu hỏi AI'),
('71000000-0000-0000-0000-000000000071', 'ai.grade.essay',        'ai',        'grade_essay','org', 'Trigger chấm essay AI'),
('71000000-0000-0000-0000-000000000072', 'ai.embed',              'ai',        'embed',      'org', 'Embed text (search)'),
('71000000-0000-0000-0000-000000000073', 'ai.cost.view',          'ai',        'cost_view',  'org', 'Xem chi phí AI của org'),
('71000000-0000-0000-0000-000000000074', 'ai.budget.manage',      'ai',        'budget',     'org', 'Set budget AI của org'),
('71000000-0000-0000-0000-000000000075', 'ai.prompt.manage',      'ai',        'prompt',     'platform', 'Quản lý prompt registry'),

-- ANALYTICS
('71000000-0000-0000-0000-000000000080', 'analytics.self',        'analytics', 'self',       'own', 'Xem progress của mình'),
('71000000-0000-0000-0000-000000000081', 'analytics.exam',        'analytics', 'exam',       'own', 'Analytics của exam mình tạo'),
('71000000-0000-0000-0000-000000000082', 'analytics.org',         'analytics', 'org',        'org', 'Analytics toàn org'),
('71000000-0000-0000-0000-000000000083', 'analytics.export',      'analytics', 'export',     'org', 'Export dữ liệu phân tích'),
('71000000-0000-0000-0000-000000000084', 'analytics.experiment',  'analytics', 'experiment', 'platform','Quản lý A/B experiment');

-- =============================================================================
-- 2d. ROLE_PERMISSIONS (grant mặc định cho 4 system role)
-- =============================================================================

-- Helper: lấy role_id + permission_id theo code
DO $$
DECLARE
    r_student     UUID := '70000000-0000-0000-0000-000000000001';
    r_instructor  UUID := '70000000-0000-0000-0000-000000000002';
    r_admin       UUID := '70000000-0000-0000-0000-000000000003';
    r_proctor     UUID := '70000000-0000-0000-0000-000000000004';
    r_grading     UUID := '70000000-0000-0000-0000-000000000010';
BEGIN
    -- STUDENT: chỉ đủ quyền làm bài + xem kết quả của mình
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r_student, id FROM permissions WHERE code IN (
        'attempt.start', 'attempt.submit', 'attempt.read.own',
        'certificate.read.own', 'cheat.appeal.submit',
        'question.report', 'analytics.self'
    );

    -- INSTRUCTOR: CRUD câu hỏi, CRUD bài thi mình tạo, chấm bài, analytics exam
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r_instructor, id FROM permissions WHERE code IN (
        'user.read.org',
        'subject.read','subject.create','subject.update',
        'question.read.org','question.create','question.update.own','question.approve',
        'question.deprecate','question.import','question.export','question.report',
        'exam.read','exam.create','exam.update.own','exam.publish','exam.delete',
        'exam.enroll.students','exam.analytics',
        'attempt.read.org','attempt.grade','attempt.regrade',
        'cheat.review','cheat.decide','cheat.appeal.resolve','cheat.video.view',
        'certificate.read.org','certificate.revoke',
        'ai.generate','ai.grade.essay','ai.embed','ai.cost.view',
        'analytics.self','analytics.exam','analytics.export'
    );

    -- ADMIN (org): tất cả quyền trong org (trừ platform-scope)
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r_admin, id FROM permissions
    WHERE scope IN ('own','org');

    -- PROCTOR: giám sát + xử lý cheat, không tạo bài thi
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r_proctor, id FROM permissions WHERE code IN (
        'user.read.org',
        'exam.read','attempt.read.org',
        'attempt.suspend','attempt.resume','attempt.terminate',
        'cheat.review','cheat.decide','cheat.video.view',
        'analytics.exam'
    );

    -- Custom: Trợ giảng chấm bài — chỉ quyền grade, không create exam/question
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r_grading, id FROM permissions WHERE code IN (
        'user.read.org',
        'question.read.org',
        'exam.read','attempt.read.org','attempt.grade',
        'ai.grade.essay'
    );
END $$;

-- =============================================================================
-- 3. USER_ORGANIZATIONS (role_id FK, không còn hardcoded)
-- =============================================================================
INSERT INTO user_organizations (user_id, org_id, role_id) VALUES
('a0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000003'), -- admin
('a0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000002'), -- instructor
('a0000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000002'), -- instructor
('a0000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000001'), -- student
('a0000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000001'), -- student
('a0000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000001'), -- student
('a0000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000004'), -- proctor
('a0000000-0000-0000-0000-000000000008', '22222222-2222-2222-2222-222222222222', '70000000-0000-0000-0000-000000000002'); -- instructor

-- =============================================================================
-- 4. SUBJECTS
-- =============================================================================
INSERT INTO subjects (id, org_id, name, code, description) VALUES
('b0000000-0000-0000-0000-000000000001', NULL,                                   'Toán Học',          'MATH101', 'Môn toán đại cương'),
('b0000000-0000-0000-0000-000000000002', NULL,                                   'Vật Lý',            'PHY201',  'Vật lý đại cương'),
('b0000000-0000-0000-0000-000000000003', NULL,                                   'Khoa Học Máy Tính', 'CS101',   'Nhập môn CS & thuật toán'),
('b0000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'Cấu Trúc Dữ Liệu', 'CS201',   'Cấu trúc dữ liệu nâng cao'),
('b0000000-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222222', 'Tiếng Anh B1',      'ENG_B1',  'Chứng chỉ Anh ngữ bậc 3');

-- =============================================================================
-- 5. EXAMS
-- =============================================================================
INSERT INTO exams (id, org_id, subject_id, created_by, title, description, status, exam_type,
                   duration_minutes, max_attempts, passing_score, total_points,
                   shuffle_questions, shuffle_options, proctoring_level, starts_at, ends_at, published_at) VALUES
('c0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'b0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000002',
    'Kiểm Tra Giữa Kỳ - Thuật Toán Sắp Xếp',
    'Bài kiểm tra 60 phút về các thuật toán sắp xếp cơ bản',
    'published', 'standard', 60, 2, 60.00, 100.00,
    true, true, 1,
    '2026-04-20 08:00:00+07', '2026-04-27 23:59:00+07', NOW()),

('c0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'b0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000003',
    'Quiz Cấu Trúc Dữ Liệu - Cây & Đồ Thị',
    'Bài thi nhanh 30 phút về cây và đồ thị',
    'active', 'standard', 30, 1, 70.00, 50.00,
    false, true, 0,
    '2026-04-15 08:00:00+07', '2026-05-15 23:59:00+07', NOW()),

('c0000000-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', 'b0000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000008',
    'English B1 Placement Test',
    'Reading + Grammar 45 minutes',
    'draft', 'adaptive', 45, 3, 65.00, 80.00,
    true, false, 2, NULL, NULL, NULL);

-- =============================================================================
-- 6. EXAM_SECTIONS
-- =============================================================================
INSERT INTO exam_sections (id, exam_id, title, order_index, question_count, points_per_question) VALUES
('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'Phần A: Lý thuyết',       1, 10, 5.00),
('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'Phần B: Phân tích',        2, 5,  10.00),
('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000002', 'Cây nhị phân + Đồ thị',    1, 10, 5.00);

-- =============================================================================
-- 7. EXAM_QUESTIONS (question_ref_id trỏ đến MongoDB questions collection)
-- =============================================================================
INSERT INTO exam_questions (id, exam_id, section_id, question_ref_id, question_version, order_index, points) VALUES
-- Exam 1 / Section A
('e0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 1, 1, 5.00),
('e0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 1, 2, 5.00),
('e0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 1, 3, 5.00),
-- Exam 1 / Section B
('e0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000002', '44444444-4444-4444-4444-444444444444', 1, 1, 10.00),
('e0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000002', '55555555-5555-5555-5555-555555555555', 1, 2, 10.00),
-- Exam 2
('e0000000-0000-0000-0000-000000000006', 'c0000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000003', '66666666-6666-6666-6666-666666666666', 1, 1, 5.00),
('e0000000-0000-0000-0000-000000000007', 'c0000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000003', '77777777-7777-7777-7777-777777777777', 1, 2, 5.00);

-- =============================================================================
-- 8. EXAM_ENROLLMENTS
-- =============================================================================
INSERT INTO exam_enrollments (exam_id, user_id, enrolled_by) VALUES
('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002'),
('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002'),
('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002'),
('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000003'),
('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003');

-- =============================================================================
-- 9. EXAM_ATTEMPTS
-- =============================================================================
INSERT INTO exam_attempts (id, exam_id, user_id, attempt_number, status, started_at, submitted_at, graded_at,
                           expires_at, time_spent_seconds, raw_score, max_score, percentage_score, passed,
                           risk_score, ip_address, geo_country, geo_city) VALUES
-- Lượt đã hoàn thành + đạt
('f0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 1, 'graded',
    '2026-04-15 08:30:00+07', '2026-04-15 09:25:00+07', '2026-04-15 09:30:00+07',
    '2026-04-15 09:30:00+07', 3300, 85.00, 100.00, 85.00, true,
    5, '171.230.12.45', 'VN', 'Hà Nội'),
-- Lượt đã hoàn thành + không đạt
('f0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000005', 1, 'graded',
    '2026-04-15 14:00:00+07', '2026-04-15 14:55:00+07', '2026-04-15 15:00:00+07',
    '2026-04-15 15:00:00+07', 3300, 45.00, 100.00, 45.00, false,
    15, '117.5.201.88', 'VN', 'Hà Nội'),
-- Lượt đang làm
('f0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000006', 1, 'in_progress',
    NOW() - INTERVAL '10 minutes', NULL, NULL,
    NOW() + INTERVAL '20 minutes', 600, NULL, 50.00, NULL, NULL,
    0, '171.230.12.46', 'VN', 'Hà Nội');

-- =============================================================================
-- 10. ATTEMPT_ANSWERS
-- =============================================================================
INSERT INTO attempt_answers (attempt_id, question_ref_id, exam_question_id, answer_data, is_correct, points_earned, time_spent_seconds) VALUES
-- Attempt 1 (đạt)
('f0000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'e0000000-0000-0000-0000-000000000001',
    '{"selected_options":["opt_b"]}'::jsonb, true,  5.00, 45),
('f0000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'e0000000-0000-0000-0000-000000000002',
    '{"selected_options":["opt_a","opt_c"]}'::jsonb, true, 5.00, 60),
('f0000000-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'e0000000-0000-0000-0000-000000000003',
    '{"text":"Merge Sort"}'::jsonb, true, 5.00, 30),
('f0000000-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', 'e0000000-0000-0000-0000-000000000004',
    '{"order":["item_3","item_1","item_2"]}'::jsonb, true, 10.00, 120),
('f0000000-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', 'e0000000-0000-0000-0000-000000000005',
    '{"language":"python","code":"def bubble_sort(arr): ..."}'::jsonb, false, 0.00, 300),
-- Attempt 2 (không đạt)
('f0000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'e0000000-0000-0000-0000-000000000001',
    '{"selected_options":["opt_a"]}'::jsonb, false, 0.00, 50),
('f0000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'e0000000-0000-0000-0000-000000000002',
    '{"selected_options":["opt_b"]}'::jsonb, false, 0.00, 40),
-- Attempt 3 (đang làm, 1 câu đã trả lời)
('f0000000-0000-0000-0000-000000000003', '66666666-6666-6666-6666-666666666666', 'e0000000-0000-0000-0000-000000000006',
    '{"selected_options":["opt_c"]}'::jsonb, NULL, NULL, 120);

-- =============================================================================
-- 11. CHEAT_EVENTS
-- =============================================================================
INSERT INTO cheat_events (attempt_id, user_id, event_type, event_layer, severity, risk_delta, event_data, client_timestamp, question_index, auto_action) VALUES
('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', 'tab_switch',     1, 'low',    3,
    '{"duration_ms":4200,"target_url":"unknown"}'::jsonb, '2026-04-15 14:10:00+07', 1, 'warn'),
('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', 'copy_event',     1, 'medium', 5,
    '{"content_length":45}'::jsonb,                        '2026-04-15 14:12:30+07', 1, 'warn'),
('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', 'devtools_open',  2, 'high',   10,
    '{"detected_at_ms":1250}'::jsonb,                      '2026-04-15 14:20:00+07', 2, 'suspend'),
('f0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 'window_blur',    1, 'low',    2,
    '{"duration_ms":2100}'::jsonb,                         '2026-04-15 08:45:00+07', 3, NULL);

-- =============================================================================
-- 12. GRADING_RUBRICS
-- =============================================================================
INSERT INTO grading_rubrics (exam_id, name, criteria, created_by) VALUES
('c0000000-0000-0000-0000-000000000001', 'Rubric bài code',
    '[{"name":"Correctness","max_points":6,"description":"Kết quả đúng với test case"},
      {"name":"Efficiency","max_points":2,"description":"Độ phức tạp hợp lý"},
      {"name":"Code quality","max_points":2,"description":"Đặt tên, format, comment"}]'::jsonb,
    'a0000000-0000-0000-0000-000000000002');

-- =============================================================================
-- 13. ATTEMPT_FEEDBACK
-- =============================================================================
INSERT INTO attempt_feedback (attempt_id, overall_feedback, strengths, weaknesses, recommendations, ai_generated, visible_to_student, visible_at) VALUES
('f0000000-0000-0000-0000-000000000001',
    'Bài làm tốt, hiểu rõ các thuật toán cơ bản. Câu code cần luyện thêm.',
    ARRAY['Nắm vững độ phức tạp','Viết đáp án rõ ràng'],
    ARRAY['Code chưa xử lý edge case'],
    ARRAY['Luyện thêm LeetCode Easy','Đọc CLRS chương 6'],
    false, true, NOW());

-- =============================================================================
-- 14. CERTIFICATES
-- =============================================================================
INSERT INTO certificates (attempt_id, user_id, exam_id, certificate_number, score, verification_hash) VALUES
('f0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000001',
    'HUST-CS101-2026-0001', 85.00,
    digest('cert-hust-cs101-2026-0001', 'sha256'));

-- =============================================================================
-- 15. PROCTORING_SESSIONS
-- =============================================================================
INSERT INTO proctoring_sessions (attempt_id, status, total_frames_analyzed, face_detected_frames, face_missing_frames, ai_risk_summary, ended_at) VALUES
('f0000000-0000-0000-0000-000000000001', 'ended', 18000, 17850, 150,
    '{"avg_attention":0.92,"suspicious_frames":12,"phone_detected":false}'::jsonb,
    '2026-04-15 09:25:00+07');

-- =============================================================================
-- 16. PASSWORD_HISTORY (Auth mở rộng)
-- =============================================================================
INSERT INTO password_history (user_id, password_hash, changed_at) VALUES
('a0000000-0000-0000-0000-000000000002', '$argon2id$v=19$m=65536,t=3,p=4$oldhashnguyen1', NOW() - INTERVAL '60 days'),
('a0000000-0000-0000-0000-000000000002', '$argon2id$v=19$m=65536,t=3,p=4$oldhashnguyen2', NOW() - INTERVAL '30 days'),
('a0000000-0000-0000-0000-000000000004', '$argon2id$v=19$m=65536,t=3,p=4$oldhashle1',     NOW() - INTERVAL '90 days');

-- =============================================================================
-- 17. MFA_BACKUP_CODES
-- =============================================================================
-- 10 code cho user 'admin' (hash SHA-256 của "abcd-efgh-ijkl-0001" ... "abcd-efgh-ijkl-0010")
INSERT INTO mfa_backup_codes (user_id, code_hash) VALUES
('a0000000-0000-0000-0000-000000000001', digest('abcd-efgh-ijkl-0001', 'sha256')),
('a0000000-0000-0000-0000-000000000001', digest('abcd-efgh-ijkl-0002', 'sha256')),
('a0000000-0000-0000-0000-000000000001', digest('abcd-efgh-ijkl-0003', 'sha256')),
('a0000000-0000-0000-0000-000000000001', digest('abcd-efgh-ijkl-0004', 'sha256')),
('a0000000-0000-0000-0000-000000000001', digest('abcd-efgh-ijkl-0005', 'sha256'));

-- Một code đã used
UPDATE mfa_backup_codes SET used_at = NOW() - INTERVAL '2 days'
WHERE user_id = 'a0000000-0000-0000-0000-000000000001'
  AND code_hash = digest('abcd-efgh-ijkl-0001', 'sha256');

-- =============================================================================
-- 18. EMAIL_VERIFICATION_TOKENS
-- =============================================================================
INSERT INTO email_verification_tokens (token_hash, user_id, purpose, expires_at, used_at) VALUES
-- Token verify email cho user mới (chưa dùng)
(digest('verify-token-active-001', 'sha256'),
    'a0000000-0000-0000-0000-000000000005', 'verify_email',
    NOW() + INTERVAL '20 hours', NULL),
-- Token reset password (đã dùng)
(digest('reset-token-used-001', 'sha256'),
    'a0000000-0000-0000-0000-000000000004', 'reset_password',
    NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours 30 minutes'),
-- Token reset password (active)
(digest('reset-token-active-001', 'sha256'),
    'a0000000-0000-0000-0000-000000000006', 'reset_password',
    NOW() + INTERVAL '45 minutes', NULL);

-- =============================================================================
-- 19. AUDIT_LOG_AUTH
-- =============================================================================
INSERT INTO audit_log_auth (user_id, actor_id, event, ip_address, user_agent, meta, created_at) VALUES
('a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004', 'login_success',
    '171.230.12.45', 'Mozilla/5.0 Chrome/120',
    '{"method":"password","mfa_used":false}'::jsonb, NOW() - INTERVAL '1 day'),
('a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', 'login_failed',
    '117.5.201.88', 'Mozilla/5.0 Firefox/121',
    '{"reason":"wrong_password","consecutive_count":2}'::jsonb, NOW() - INTERVAL '3 hours'),
('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'mfa_enabled',
    '171.230.12.1', 'Mozilla/5.0 Chrome/120', '{}'::jsonb, NOW() - INTERVAL '7 days'),
('a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004', 'password_changed',
    '171.230.12.45', 'Mozilla/5.0 Chrome/120',
    '{"triggered_by":"user"}'::jsonb, NOW() - INTERVAL '2 hours 30 minutes'),
('a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'login_success',
    '171.230.50.10', 'Mozilla/5.0 Safari/17',
    '{"method":"oauth","provider":"google"}'::jsonb, NOW() - INTERVAL '6 hours');

-- =============================================================================
-- 20. AI_BUDGETS (1 row/org)
-- =============================================================================
INSERT INTO ai_budgets (org_id, monthly_limit_usd, current_month_usd, current_month, hard_stop) VALUES
('11111111-1111-1111-1111-111111111111', 500.00, 87.35, date_trunc('month', NOW())::date, true),
('22222222-2222-2222-2222-222222222222', 100.00, 12.50, date_trunc('month', NOW())::date, true),
('33333333-3333-3333-3333-333333333333', 10.00,  2.05,  date_trunc('month', NOW())::date, true);

-- =============================================================================
-- 21. AI_JOBS
-- =============================================================================
INSERT INTO ai_jobs (id, org_id, user_id, job_type, status, input_payload, output_payload,
                    model_used, prompt_version, input_tokens, output_tokens, cost_usd,
                    started_at, completed_at) VALUES
-- Generate questions job (completed)
('aaaa0001-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
    'a0000000-0000-0000-0000-000000000002',
    'generate_q', 'completed',
    '{"topic":"Thuật toán sắp xếp","count":5,"type":"multiple_choice_single","difficulty":3,"bloom_level":"analysis"}'::jsonb,
    '{"questions_generated":5,"question_ids":["aaa1","aaa2","aaa3","aaa4","aaa5"]}'::jsonb,
    'gpt-4o', 'generate_mc_single@v3.1', 1850, 2100, 0.02625,
    NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours' + INTERVAL '18 seconds'),
-- Essay grading job (completed)
('aaaa0002-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
    NULL,
    'grade_essay', 'completed',
    '{"attempt_id":"f0000000-0000-0000-0000-000000000001","question_ref_id":"77777777-7777-7777-7777-777777777777"}'::jsonb,
    '{"total_points":8.5,"confidence":0.82,"needs_manual_review":false}'::jsonb,
    'gpt-4o', 'grade_essay@v2.0', 820, 450, 0.00655,
    NOW() - INTERVAL '1 hour', NOW() - INTERVAL '1 hour' + INTERVAL '45 seconds'),
-- Quality check job (pending)
('aaaa0003-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',
    'a0000000-0000-0000-0000-000000000003',
    'quality_check', 'pending',
    '{"question_id":"aaa1"}'::jsonb,
    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
-- Embedding job (running)
('aaaa0004-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111',
    NULL,
    'embed', 'running',
    '{"texts_count":50,"model":"text-embedding-3-large"}'::jsonb,
    NULL, NULL, NULL, NULL, NULL, NULL,
    NOW() - INTERVAL '2 minutes', NULL),
-- Failed job
('aaaa0005-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222222',
    'a0000000-0000-0000-0000-000000000008',
    'generate_q', 'failed',
    '{"topic":"Reading comprehension","count":10}'::jsonb,
    NULL,
    'gpt-4o-mini', 'generate_mc_single@v3.0', 420, 0, 0.00105,
    NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes' + INTERVAL '5 seconds');

UPDATE ai_jobs SET error_message = 'OpenAI API timeout after 30s'
WHERE id = 'aaaa0005-0000-0000-0000-000000000005';

-- =============================================================================
-- 22. AI_COST_LEDGER (chi tiết từng call)
-- =============================================================================
INSERT INTO ai_cost_ledger (org_id, job_id, feature, model, input_tokens, output_tokens, cost_usd) VALUES
('11111111-1111-1111-1111-111111111111', 'aaaa0001-0000-0000-0000-000000000001', 'generate',
    'gpt-4o',      1850, 2100, 0.02625),
('11111111-1111-1111-1111-111111111111', 'aaaa0002-0000-0000-0000-000000000002', 'grade',
    'gpt-4o',      820,  450,  0.00655),
('11111111-1111-1111-1111-111111111111', NULL, 'embed',
    'text-embedding-3-large', 500, 0, 0.00007),
('11111111-1111-1111-1111-111111111111', NULL, 'embed',
    'text-embedding-3-large', 1200, 0, 0.000156),
('11111111-1111-1111-1111-111111111111', NULL, 'moderation',
    'text-moderation-latest', 85, 0, 0.0),
('22222222-2222-2222-2222-222222222222', 'aaaa0005-0000-0000-0000-000000000005', 'generate',
    'gpt-4o-mini', 420,  0,   0.00105);

-- =============================================================================
-- 23. CHEAT_REVIEW_QUEUE
-- =============================================================================
INSERT INTO cheat_review_queue (attempt_id, triggered_by_event, risk_score_at_trigger, severity,
                                assigned_to, status, decision, decision_reason, reviewed_at)
SELECT
    'f0000000-0000-0000-0000-000000000002',
    id,
    18,
    'high',
    'a0000000-0000-0000-0000-000000000007',    -- proctor
    'resolved',
    'confirmed',
    'Devtools mở + copy content + thay đổi IP → xác nhận gian lận',
    NOW() - INTERVAL '2 days 3 hours'
FROM cheat_events
WHERE attempt_id = 'f0000000-0000-0000-0000-000000000002'
  AND event_type = 'devtools_open'
LIMIT 1;

-- Một review đang pending
INSERT INTO cheat_review_queue (attempt_id, risk_score_at_trigger, severity, status) VALUES
('f0000000-0000-0000-0000-000000000003', 35, 'medium', 'pending');

-- =============================================================================
-- 24. CHEAT_APPEALS
-- =============================================================================
INSERT INTO cheat_appeals (attempt_id, user_id, reason, evidence_s3_keys, status, reviewed_by, decision, decision_reason, resolved_at) VALUES
('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005',
    'Em không gian lận. Em mở devtools để check console lỗi, không xem đáp án. Xin xem xét lại.',
    ARRAY['appeals/evidence/2026-04-15/f0000002-screenshot1.png', 'appeals/evidence/2026-04-15/f0000002-explanation.pdf'],
    'resolved',
    'a0000000-0000-0000-0000-000000000002',    -- instructor chủ bài thi
    'upheld',
    'Log cho thấy có copy content đáp án không chỉ mở devtools. Giữ nguyên quyết định.',
    NOW() - INTERVAL '1 day');

COMMIT;

-- =============================================================================
-- VERIFY DATA
-- =============================================================================
SELECT 'organizations' tbl, COUNT(*) FROM organizations
UNION ALL SELECT 'users',               COUNT(*) FROM users
UNION ALL SELECT 'roles',               COUNT(*) FROM roles
UNION ALL SELECT 'permissions',         COUNT(*) FROM permissions
UNION ALL SELECT 'role_permissions',    COUNT(*) FROM role_permissions
UNION ALL SELECT 'user_organizations',  COUNT(*) FROM user_organizations
UNION ALL SELECT 'subjects',            COUNT(*) FROM subjects
UNION ALL SELECT 'exams',               COUNT(*) FROM exams
UNION ALL SELECT 'exam_sections',       COUNT(*) FROM exam_sections
UNION ALL SELECT 'exam_questions',      COUNT(*) FROM exam_questions
UNION ALL SELECT 'exam_enrollments',    COUNT(*) FROM exam_enrollments
UNION ALL SELECT 'exam_attempts',       COUNT(*) FROM exam_attempts
UNION ALL SELECT 'attempt_answers',     COUNT(*) FROM attempt_answers
UNION ALL SELECT 'cheat_events',        COUNT(*) FROM cheat_events
UNION ALL SELECT 'certificates',        COUNT(*) FROM certificates
UNION ALL SELECT 'password_history',    COUNT(*) FROM password_history
UNION ALL SELECT 'mfa_backup_codes',    COUNT(*) FROM mfa_backup_codes
UNION ALL SELECT 'email_verif_tokens',  COUNT(*) FROM email_verification_tokens
UNION ALL SELECT 'audit_log_auth',      COUNT(*) FROM audit_log_auth
UNION ALL SELECT 'ai_jobs',             COUNT(*) FROM ai_jobs
UNION ALL SELECT 'ai_cost_ledger',      COUNT(*) FROM ai_cost_ledger
UNION ALL SELECT 'ai_budgets',          COUNT(*) FROM ai_budgets
UNION ALL SELECT 'cheat_review_queue',  COUNT(*) FROM cheat_review_queue
UNION ALL SELECT 'cheat_appeals',       COUNT(*) FROM cheat_appeals;
