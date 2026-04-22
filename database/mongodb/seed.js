// =============================================================================
// SMART QUIZ SYSTEM - MongoDB SEED DATA (Dữ liệu mẫu ngân hàng câu hỏi)
// =============================================================================
// Chạy SAU schema.js:
//   mongosh "mongodb://localhost:27017/smartquiz" seed.js
// =============================================================================

db = db.getSiblingDB('smartquiz');

// Xoá dữ liệu cũ (nếu có) để seed lại sạch
db.questions.deleteMany({});
db.question_versions.deleteMany({});
db.question_reports.deleteMany({});
db.question_tags.deleteMany({});

const NOW = new Date();
const ORG_HUST = '11111111-1111-1111-1111-111111111111';
const USER_GV_NGUYEN = 'a0000000-0000-0000-0000-000000000002';
const USER_GV_TRAN   = 'a0000000-0000-0000-0000-000000000003';

// =============================================================================
// 1. QUESTIONS - 7 câu hỏi mẫu (đủ 7 loại phổ biến)
// =============================================================================
db.questions.insertMany([

    // --- 1. MULTIPLE CHOICE SINGLE -----------------------------------------
    {
        question_id:  '11111111-1111-1111-1111-111111111111',
        org_id:       ORG_HUST,
        subject_code: 'CS101',
        created_by:   USER_GV_NGUYEN,
        reviewed_by:  USER_GV_TRAN,
        status:       'active',
        ai_generated: false,
        ai_quality_score: 92,
        ai_quality_flags: [],
        version:      1,
        type:         'multiple_choice_single',
        content: {
            text: 'Thuật toán sắp xếp nào có độ phức tạp O(n log n) trong mọi trường hợp?',
            rich_text: '<p>Thuật toán sắp xếp nào có độ phức tạp <b>O(n log n)</b> trong mọi trường hợp?</p>',
            math_latex: null,
            code_snippet: null,
            media: []
        },
        options: [
            { id: 'opt_a', text: 'Bubble Sort',    is_correct: false, explanation: 'O(n²) trường hợp xấu nhất' },
            { id: 'opt_b', text: 'Merge Sort',     is_correct: true,  explanation: 'Luôn O(n log n)' },
            { id: 'opt_c', text: 'Quick Sort',     is_correct: false, explanation: 'O(n²) trường hợp xấu nhất' },
            { id: 'opt_d', text: 'Insertion Sort', is_correct: false, explanation: 'O(n²) trung bình' }
        ],
        explanation: 'Merge Sort dùng chia để trị, luôn đạt O(n log n) bất kể input.',
        hint: 'Nghĩ đến thuật toán chia để trị...',
        reference_links: ['https://en.wikipedia.org/wiki/Merge_sort'],
        metadata: {
            topic: 'Thuật toán sắp xếp',
            subtopic: 'Phân tích độ phức tạp',
            tags: ['thuat_toan', 'sap_xep', 'do_phuc_tap', 'CS'],
            bloom_level: 'analysis',
            language: 'vi',
            estimated_time_seconds: 90
        },
        irt: {
            difficulty_assigned: 3,
            b: 0.82, a: 1.24, c: 0.05,
            calibrated: true,
            calibrated_at: NOW,
            responses_count: 1247
        },
        stats: { times_used: 1247, correct_count: 791, correct_rate: 0.634, avg_time_seconds: 78, skip_rate: 0.02 },
        embedding_id: null,
        embedding_model: 'text-embedding-3-large',
        embedding_updated_at: null,
        created_at: NOW, updated_at: NOW, reviewed_at: NOW
    },

    // --- 2. MULTIPLE CHOICE MULTI ------------------------------------------
    {
        question_id:  '22222222-2222-2222-2222-222222222222',
        org_id:       ORG_HUST,
        subject_code: 'CS101',
        created_by:   USER_GV_NGUYEN,
        status:       'active',
        ai_generated: false,
        version:      1,
        type:         'multiple_choice_multi',
        content: {
            text: 'Những cấu trúc dữ liệu nào sau đây là cấu trúc tuyến tính? (Chọn tất cả đáp án đúng)',
            media: []
        },
        options: [
            { id: 'opt_a', text: 'Mảng (Array)',            is_correct: true  },
            { id: 'opt_b', text: 'Cây (Tree)',              is_correct: false },
            { id: 'opt_c', text: 'Danh sách liên kết',       is_correct: true  },
            { id: 'opt_d', text: 'Đồ thị (Graph)',           is_correct: false },
            { id: 'opt_e', text: 'Hàng đợi (Queue)',         is_correct: true  }
        ],
        explanation: 'Cấu trúc tuyến tính: phần tử được sắp xếp theo thứ tự.',
        metadata: {
            topic: 'Cấu trúc dữ liệu',
            tags: ['data_structure', 'CS'],
            bloom_level: 'comprehension',
            language: 'vi',
            estimated_time_seconds: 60
        },
        irt: { difficulty_assigned: 2, b: -0.15, a: 1.05, c: 0.1, calibrated: true, responses_count: 892 },
        stats: { times_used: 892, correct_count: 601, correct_rate: 0.674, avg_time_seconds: 52, skip_rate: 0.01 },
        created_at: NOW, updated_at: NOW
    },

    // --- 3. FILL BLANK -----------------------------------------------------
    {
        question_id:  '33333333-3333-3333-3333-333333333333',
        org_id:       ORG_HUST,
        subject_code: 'CS101',
        created_by:   USER_GV_NGUYEN,
        status:       'active',
        version:      1,
        type:         'fill_blank',
        content: {
            text: 'Thuật toán sắp xếp ổn định luôn có độ phức tạp O(n log n) và chia để trị là ______.',
            media: []
        },
        grading_config: {
            accepted_answers: ['Merge Sort', 'MergeSort', 'merge sort'],
            use_regex: false,
            case_sensitive: false
        },
        explanation: 'Merge Sort là thuật toán sắp xếp ổn định, chia để trị, O(n log n).',
        metadata: {
            topic: 'Thuật toán sắp xếp',
            tags: ['thuat_toan', 'sap_xep'],
            bloom_level: 'knowledge',
            language: 'vi',
            estimated_time_seconds: 30
        },
        irt: { difficulty_assigned: 2, b: 0.2, a: 1.1, c: 0, calibrated: true, responses_count: 540 },
        stats: { times_used: 540, correct_count: 412, correct_rate: 0.763, avg_time_seconds: 25, skip_rate: 0.05 },
        created_at: NOW, updated_at: NOW
    },

    // --- 4. ORDERING -------------------------------------------------------
    {
        question_id:  '44444444-4444-4444-4444-444444444444',
        org_id:       ORG_HUST,
        subject_code: 'CS201',
        created_by:   USER_GV_TRAN,
        status:       'active',
        version:      1,
        type:         'ordering',
        content: {
            text: 'Sắp xếp các bước dưới đây theo đúng thứ tự của thuật toán BFS trên đồ thị:',
            media: []
        },
        items: [
            { id: 'item_1', text: 'Lấy đỉnh đầu tiên khỏi hàng đợi' },
            { id: 'item_2', text: 'Duyệt tất cả láng giềng và thêm vào hàng đợi' },
            { id: 'item_3', text: 'Đưa đỉnh xuất phát vào hàng đợi và đánh dấu đã thăm' }
        ],
        correct_order: ['item_3', 'item_1', 'item_2'],
        explanation: 'BFS: khởi tạo -> pop -> duyệt láng giềng -> lặp lại.',
        metadata: {
            topic: 'Đồ thị',
            tags: ['graph', 'BFS', 'CS'],
            bloom_level: 'application',
            language: 'vi',
            estimated_time_seconds: 120
        },
        irt: { difficulty_assigned: 3, b: 0.5, a: 1.3, c: 0, calibrated: false, responses_count: 0 },
        stats: { times_used: 0, correct_count: 0, correct_rate: 0, avg_time_seconds: 0, skip_rate: 0 },
        created_at: NOW, updated_at: NOW
    },

    // --- 5. CODE EXECUTION -------------------------------------------------
    {
        question_id:  '55555555-5555-5555-5555-555555555555',
        org_id:       ORG_HUST,
        subject_code: 'CS101',
        created_by:   USER_GV_NGUYEN,
        status:       'active',
        version:      1,
        type:         'code_execution',
        content: {
            text: 'Viết hàm Python `sum_of_list(nums)` trả về tổng các phần tử trong list. Ví dụ sum_of_list([1,2,3]) = 6.',
            code_snippet: 'def sum_of_list(nums):\n    # Your code here\n    pass',
            media: []
        },
        grading_config: {
            language: 'python',
            time_limit_ms: 2000,
            memory_limit_mb: 128,
            test_cases: [
                { input: '[1,2,3]',   expected: '6',  hidden: false, points: 3 },
                { input: '[]',         expected: '0',  hidden: false, points: 2 },
                { input: '[-1,-2,-3]', expected: '-6', hidden: true,  points: 3 },
                { input: '[10000]*1000', expected: '10000000', hidden: true, points: 2 }
            ]
        },
        explanation: 'Dùng `sum(nums)` hoặc vòng lặp tích lũy.',
        metadata: {
            topic: 'Lập trình Python',
            tags: ['python', 'coding', 'list'],
            bloom_level: 'application',
            language: 'vi',
            estimated_time_seconds: 300
        },
        irt: { difficulty_assigned: 2, b: 0, a: 1, c: 0, calibrated: false, responses_count: 0 },
        stats: { times_used: 0, correct_count: 0, correct_rate: 0, avg_time_seconds: 0, skip_rate: 0 },
        created_at: NOW, updated_at: NOW
    },

    // --- 6. TRUE/FALSE -----------------------------------------------------
    {
        question_id:  '66666666-6666-6666-6666-666666666666',
        org_id:       ORG_HUST,
        subject_code: 'CS201',
        created_by:   USER_GV_TRAN,
        status:       'active',
        version:      1,
        type:         'true_false',
        content: {
            text: 'Cây nhị phân tìm kiếm (BST) cân bằng có độ phức tạp tìm kiếm trung bình là O(log n).',
            media: []
        },
        options: [
            { id: 'true',  text: 'Đúng', is_correct: true  },
            { id: 'false', text: 'Sai',  is_correct: false }
        ],
        explanation: 'BST cân bằng duy trì chiều cao ~log n, nên các phép toán đều O(log n).',
        metadata: {
            topic: 'Cây nhị phân',
            tags: ['tree', 'BST', 'CS'],
            bloom_level: 'knowledge',
            language: 'vi',
            estimated_time_seconds: 20
        },
        irt: { difficulty_assigned: 1, b: -0.8, a: 1, c: 0.25, calibrated: true, responses_count: 420 },
        stats: { times_used: 420, correct_count: 356, correct_rate: 0.848, avg_time_seconds: 18, skip_rate: 0.005 },
        created_at: NOW, updated_at: NOW
    },

    // --- 7. ESSAY ----------------------------------------------------------
    {
        question_id:  '77777777-7777-7777-7777-777777777777',
        org_id:       ORG_HUST,
        subject_code: 'CS201',
        created_by:   USER_GV_TRAN,
        status:       'active',
        version:      1,
        type:         'essay',
        content: {
            text: 'So sánh thuật toán Dijkstra và Bellman-Ford. Khi nào dùng thuật toán nào?',
            media: []
        },
        grading_config: {
            min_words: 150,
            max_words: 500,
            rubric_id: null,
            dimensions: [
                { name: 'content_accuracy', max_points: 3 },
                { name: 'example_given',    max_points: 1 },
                { name: 'writing_quality',  max_points: 1 }
            ]
        },
        metadata: {
            topic: 'Đồ thị có trọng số',
            tags: ['graph', 'shortest_path', 'CS'],
            bloom_level: 'analysis',
            language: 'vi',
            estimated_time_seconds: 600
        },
        irt: { difficulty_assigned: 4, b: 1.2, a: 1.1, c: 0, calibrated: false, responses_count: 0 },
        stats: { times_used: 0, correct_count: 0, correct_rate: 0, avg_time_seconds: 0, skip_rate: 0 },
        created_at: NOW, updated_at: NOW
    }
]);

// =============================================================================
// 2. QUESTION_VERSIONS - Lịch sử chỉnh sửa mẫu
// =============================================================================
db.question_versions.insertMany([
    {
        question_id: '11111111-1111-1111-1111-111111111111',
        version: 1,
        content_snapshot: {
            text: 'Thuật toán sắp xếp nào có độ phức tạp O(n log n)?',
            options: [
                { id: 'opt_a', text: 'Bubble Sort',  is_correct: false },
                { id: 'opt_b', text: 'Merge Sort',   is_correct: true  }
            ]
        },
        changed_by: USER_GV_NGUYEN,
        change_reason: 'Tạo câu hỏi ban đầu',
        created_at: new Date(NOW.getTime() - 7 * 24 * 60 * 60 * 1000)
    }
]);

// =============================================================================
// 3. QUESTION_REPORTS - Báo cáo mẫu
// =============================================================================
db.question_reports.insertMany([
    {
        question_id: '22222222-2222-2222-2222-222222222222',
        reported_by: 'a0000000-0000-0000-0000-000000000004',
        attempt_id:  'f0000000-0000-0000-0000-000000000001',
        report_type: 'ambiguous',
        description: 'Đáp án Queue nên được coi là tuyến tính nhưng đề chưa rõ.',
        status: 'under_review',
        created_at: NOW
    }
]);

// =============================================================================
// 4. QUESTION_TAGS - Master tags
// =============================================================================
db.question_tags.insertMany([
    { tag: 'thuat_toan',     display_name: 'Thuật toán',            category: 'CS',   usage_count: 2 },
    { tag: 'sap_xep',        display_name: 'Sắp xếp',               category: 'CS',   usage_count: 2 },
    { tag: 'do_phuc_tap',    display_name: 'Độ phức tạp',           category: 'CS',   usage_count: 1 },
    { tag: 'CS',             display_name: 'Khoa học máy tính',     category: 'CS',   usage_count: 6 },
    { tag: 'data_structure', display_name: 'Cấu trúc dữ liệu',      category: 'CS',   usage_count: 1 },
    { tag: 'graph',          display_name: 'Đồ thị',                category: 'CS',   usage_count: 2 },
    { tag: 'BFS',            display_name: 'Breadth-First Search',  category: 'CS',   usage_count: 1 },
    { tag: 'BST',            display_name: 'Cây nhị phân tìm kiếm', category: 'CS',   usage_count: 1 },
    { tag: 'tree',           display_name: 'Cây',                   category: 'CS',   usage_count: 1 },
    { tag: 'python',         display_name: 'Python',                category: 'Code', usage_count: 1 },
    { tag: 'coding',         display_name: 'Lập trình',             category: 'Code', usage_count: 1 }
]);

// =============================================================================
// 5. QUESTION_IMPORTS - Audit job import mẫu
// =============================================================================
db.question_imports.deleteMany({});
db.question_imports.insertMany([
    {
        job_id: '88888888-0001-0000-0000-000000000001',
        org_id: ORG_HUST,
        imported_by: USER_GV_NGUYEN,
        format: 'xlsx',
        file_s3_key: 'imports/2026-04/hust-cs101-batch-001.xlsx',
        status: 'completed',
        total: 50,
        success_count: 48,
        failed_count: 2,
        errors: [
            { row: 12, error: 'Missing correct_option for multiple_choice_single' },
            { row: 37, error: 'Option count < 2' }
        ],
        created_at: new Date(NOW.getTime() - 2 * 24 * 60 * 60 * 1000),
        completed_at: new Date(NOW.getTime() - 2 * 24 * 60 * 60 * 1000 + 3 * 60 * 1000)
    },
    {
        job_id: '88888888-0001-0000-0000-000000000002',
        org_id: ORG_HUST,
        imported_by: USER_GV_TRAN,
        format: 'gift',
        file_s3_key: 'imports/2026-04/moodle-migration-batch.gift',
        status: 'running',
        total: 200,
        success_count: 87,
        failed_count: 0,
        errors: [],
        created_at: new Date(NOW.getTime() - 5 * 60 * 1000),
        completed_at: null
    }
]);

// =============================================================================
// 6. AI_PROMPTS - Prompt registry cho AI Service
// =============================================================================
db.ai_prompts.deleteMany({});
db.ai_prompts.insertMany([
    {
        name: 'generate_mc_single',
        version: 'v3.1',
        template: `System:
You are an expert educational question writer for {{language}}-speaking students.
Generate rigorous multiple-choice questions that test higher-order thinking.

Topic: {{topic}}
Difficulty: {{difficulty}}/5
Bloom level: {{bloom_level}}
Subject: {{subject_code}}
Knowledge context:
{{#each context_chunks}}- {{this.text}}
{{/each}}

Rules:
1. Exactly 4 options, exactly 1 correct.
2. All distractors must be plausible misconceptions.
3. Include explanation for each option.
4. Output strictly matching the JSON schema.

User: Generate {{count}} questions.`,
        variables: ['language', 'topic', 'difficulty', 'bloom_level', 'subject_code', 'context_chunks', 'count'],
        model: 'gpt-4o',
        temperature: 0.7,
        max_tokens: 2000,
        response_format: {
            type: 'json_schema',
            schema: {
                type: 'object',
                properties: {
                    questions: {
                        type: 'array',
                        items: {
                            type: 'object',
                            required: ['text', 'options', 'explanation'],
                            properties: {
                                text:        { type: 'string' },
                                options:     { type: 'array', minItems: 4, maxItems: 4 },
                                explanation: { type: 'string' }
                            }
                        }
                    }
                }
            }
        },
        active: true,
        traffic_weight: 1.0,
        evals: {
            golden_set_size: 100,
            pass_rate: 0.92,
            last_evaluated: new Date(NOW.getTime() - 7 * 24 * 60 * 60 * 1000)
        },
        created_by: 'a0000000-0000-0000-0000-000000000001',
        created_at: new Date(NOW.getTime() - 30 * 24 * 60 * 60 * 1000)
    },
    {
        name: 'grade_essay',
        version: 'v2.0',
        template: `You are an experienced teacher grading a student essay.
Rubric dimensions: {{dimensions_json}}
Sample correct answer (reference only): {{sample_answer}}
Student answer: <user_answer>{{student_text}}</user_answer>

Grade step by step. For each dimension: give score, reasoning, and confidence.
Output JSON: {per_dimension: [...], total_points, overall_feedback, confidence}.`,
        variables: ['dimensions_json', 'sample_answer', 'student_text'],
        model: 'gpt-4o',
        temperature: 0.3,
        max_tokens: 1500,
        response_format: null,
        active: true,
        traffic_weight: 1.0,
        evals: {
            golden_set_size: 50,
            pass_rate: 0.88,
            last_evaluated: new Date(NOW.getTime() - 3 * 24 * 60 * 60 * 1000)
        },
        created_by: 'a0000000-0000-0000-0000-000000000001',
        created_at: new Date(NOW.getTime() - 20 * 24 * 60 * 60 * 1000)
    },
    {
        name: 'quality_check_question',
        version: 'v1.0',
        template: `You are an independent LLM judge evaluating question quality.
Question: {{question_json}}

Rate 0-100 on: factual_accuracy, ambiguity, difficulty_appropriateness, option_plausibility.
Return: {score: 0-100, flags: ["factual_error","ambiguous",...], rationale: "..."}.`,
        variables: ['question_json'],
        model: 'gpt-4o',
        temperature: 0.0,
        max_tokens: 500,
        response_format: null,
        active: true,
        traffic_weight: 1.0,
        evals: { golden_set_size: 30, pass_rate: 0.95, last_evaluated: null },
        created_by: 'a0000000-0000-0000-0000-000000000001',
        created_at: new Date(NOW.getTime() - 45 * 24 * 60 * 60 * 1000)
    },
    {
        name: 'generate_mc_single',
        version: 'v3.2-experimental',
        template: `[v3.2 experimental — test chain-of-thought trước khi output]\n(same as v3.1 but instruct "think step by step first")`,
        variables: ['language', 'topic', 'difficulty', 'bloom_level', 'subject_code', 'context_chunks', 'count'],
        model: 'gpt-4o',
        temperature: 0.7,
        max_tokens: 3000,
        response_format: null,
        active: false,                    // đang shadow test, chưa live
        traffic_weight: 0.0,
        evals: { golden_set_size: 100, pass_rate: 0.91, last_evaluated: new Date(NOW.getTime() - 1 * 24 * 60 * 60 * 1000) },
        created_by: 'a0000000-0000-0000-0000-000000000001',
        created_at: new Date(NOW.getTime() - 2 * 24 * 60 * 60 * 1000)
    }
]);

// =============================================================================
// VERIFY
// =============================================================================
print('>>> Số lượng documents:');
print('questions:         ' + db.questions.countDocuments());
print('question_versions: ' + db.question_versions.countDocuments());
print('question_reports:  ' + db.question_reports.countDocuments());
print('question_tags:     ' + db.question_tags.countDocuments());
print('question_imports:  ' + db.question_imports.countDocuments());
print('ai_prompts:        ' + db.ai_prompts.countDocuments());
