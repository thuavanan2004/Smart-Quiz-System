// =============================================================================
// SMART QUIZ SYSTEM - MongoDB 7 SCHEMA
// =============================================================================
// File tổng hợp collections, JSON schema validator, và indexes cho MongoDB
// Chạy: mongosh "mongodb://localhost:27017/smartquiz" schema.js
// =============================================================================

// Chuyển sang DB smartquiz
db = db.getSiblingDB('smartquiz');

// =============================================================================
// 1. COLLECTION: questions  (ngân hàng câu hỏi chính)
// =============================================================================
db.createCollection('questions', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['question_id', 'type', 'content', 'status', 'created_by', 'created_at'],
            properties: {
                question_id:  { bsonType: 'string', description: 'UUID v4 đồng nhất với PostgreSQL' },
                org_id:       { bsonType: ['string', 'null'] },
                subject_code: { bsonType: 'string' },
                created_by:   { bsonType: 'string' },
                reviewed_by:  { bsonType: ['string', 'null'] },
                status: {
                    enum: ['draft', 'review', 'active', 'deprecated'],
                    description: 'Vòng đời câu hỏi'
                },
                ai_generated:      { bsonType: 'bool' },
                ai_quality_score:  { bsonType: ['int', 'null'], minimum: 0, maximum: 100 },
                ai_quality_flags:  { bsonType: 'array', items: { bsonType: 'string' } },
                version:           { bsonType: 'int', minimum: 1 },
                type: {
                    enum: [
                        'multiple_choice_single', 'multiple_choice_multi', 'true_false',
                        'fill_blank', 'matching', 'ordering', 'short_answer',
                        'essay', 'code_execution', 'drag_drop', 'hotspot'
                    ]
                },
                content: {
                    bsonType: 'object',
                    required: ['text'],
                    properties: {
                        text:         { bsonType: 'string' },
                        rich_text:    { bsonType: ['string', 'null'] },
                        math_latex:   { bsonType: ['string', 'null'] },
                        code_snippet: { bsonType: ['string', 'null'] },
                        media:        { bsonType: 'array' }
                    }
                },
                options:        { bsonType: ['array', 'null'] },
                pairs:          { bsonType: ['array', 'null'] },
                items:          { bsonType: ['array', 'null'] },
                zones:          { bsonType: ['array', 'null'] },
                hotspots:       { bsonType: ['array', 'null'] },
                correct_order:  { bsonType: ['array', 'null'] },
                grading_config: { bsonType: ['object', 'null'] },
                explanation:    { bsonType: ['string', 'null'] },
                hint:           { bsonType: ['string', 'null'] },
                reference_links:{ bsonType: 'array' },
                metadata: {
                    bsonType: 'object',
                    properties: {
                        topic:      { bsonType: 'string' },
                        subtopic:   { bsonType: 'string' },
                        tags:       { bsonType: 'array', items: { bsonType: 'string' } },
                        bloom_level:{
                            enum: ['knowledge', 'comprehension', 'application',
                                   'analysis', 'synthesis', 'evaluation']
                        },
                        language:              { bsonType: 'string' },
                        estimated_time_seconds:{ bsonType: 'int' }
                    }
                },
                irt: {
                    bsonType: 'object',
                    properties: {
                        difficulty_assigned: { bsonType: 'int', minimum: 1, maximum: 5 },
                        b:              { bsonType: 'double' },
                        a:              { bsonType: 'double' },
                        c:              { bsonType: 'double' },
                        calibrated:     { bsonType: 'bool' },
                        calibrated_at:  { bsonType: ['date', 'null'] },
                        responses_count:{ bsonType: 'int' }
                    }
                },
                stats: {
                    bsonType: 'object',
                    properties: {
                        times_used:     { bsonType: 'int' },
                        correct_count:  { bsonType: 'int' },
                        correct_rate:   { bsonType: 'double' },
                        avg_time_seconds:{ bsonType: 'int' },
                        skip_rate:      { bsonType: 'double' }
                    }
                },
                // Vector embedding KHÔNG lưu ở Mongo để tránh duplicate storage với Elasticsearch.
                // ES `question_search.embedding` là source of truth cho vector search (kNN/HNSW).
                // Mongo chỉ giữ pointer + metadata để trace trạng thái embed.
                embedding_id:           { bsonType: ['string', 'null'], description: '_id của doc trong ES question_search' },
                embedding_model:        { bsonType: ['string', 'null'] },
                embedding_updated_at:   { bsonType: ['date', 'null'] },
                created_at:             { bsonType: 'date' },
                updated_at:             { bsonType: 'date' },
                reviewed_at:            { bsonType: ['date', 'null'] }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});

// Indexes cho questions
db.questions.createIndex({ question_id: 1 }, { unique: true, name: 'uniq_question_id' });
db.questions.createIndex({ org_id: 1, status: 1, subject_code: 1 }, { name: 'org_status_subject' });
db.questions.createIndex({ 'metadata.tags': 1 },              { name: 'tags' });
db.questions.createIndex({ 'irt.b': 1, 'irt.a': 1 },          { name: 'irt_difficulty' });
db.questions.createIndex({ status: 1, ai_generated: 1, ai_quality_score: -1 }, { name: 'ai_quality' });
db.questions.createIndex({ type: 1, status: 1 },              { name: 'type_status' });
// Generate theo độ khó IRT + Bloom level (hot path cho AI question-picker)
db.questions.createIndex(
    { org_id: 1, 'metadata.bloom_level': 1, 'irt.b': 1 },
    { name: 'org_bloom_irt' }
);

// Shard key cho questions khi scale (hashed để balance write, compound để query theo org).
// Chạy ở cluster sharded: cần enableSharding trước.
//   sh.enableSharding('smartquiz');
//   sh.shardCollection('smartquiz.questions', { org_id: 'hashed', subject_code: 1, question_id: 1 });

// Full-text index
db.questions.createIndex(
    { 'content.text': 'text', 'metadata.tags': 'text', 'metadata.topic': 'text' },
    {
        weights: { 'content.text': 10, 'metadata.tags': 5, 'metadata.topic': 3 },
        default_language: 'none',  // Không dùng stemmer mặc định để hỗ trợ đa ngôn ngữ
        name: 'question_text_search'
    }
);

// =============================================================================
// 2. COLLECTION: question_versions (lịch sử chỉnh sửa)
// =============================================================================
db.createCollection('question_versions', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['question_id', 'version', 'content_snapshot', 'changed_by', 'created_at'],
            properties: {
                question_id:      { bsonType: 'string' },
                version:          { bsonType: 'int', minimum: 1 },
                content_snapshot: { bsonType: 'object' },
                changed_by:       { bsonType: 'string' },
                change_reason:    { bsonType: 'string' },
                created_at:       { bsonType: 'date' }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});

db.question_versions.createIndex({ question_id: 1, version: -1 }, { unique: true, name: 'uniq_qid_version' });
db.question_versions.createIndex({ created_at: -1 });

// =============================================================================
// 3. COLLECTION: question_reports (báo cáo câu hỏi)
// =============================================================================
db.createCollection('question_reports', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['question_id', 'reported_by', 'report_type', 'status', 'created_at'],
            properties: {
                question_id:  { bsonType: 'string' },
                reported_by:  { bsonType: 'string' },
                attempt_id:   { bsonType: ['string', 'null'] },
                report_type:  { enum: ['wrong_answer', 'ambiguous', 'offensive', 'typo', 'other'] },
                description:  { bsonType: 'string' },
                status:       { enum: ['pending', 'under_review', 'resolved', 'dismissed'] },
                resolved_by:  { bsonType: ['string', 'null'] },
                resolution:   { bsonType: ['string', 'null'] },
                created_at:   { bsonType: 'date' },
                resolved_at:  { bsonType: ['date', 'null'] }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});

db.question_reports.createIndex({ question_id: 1, status: 1 });
db.question_reports.createIndex({ status: 1, created_at: -1 });
db.question_reports.createIndex({ reported_by: 1 });
// TTL: xoá report đã resolved sau 90 ngày để tiết kiệm storage
db.question_reports.createIndex(
    { resolved_at: 1 },
    {
        name: 'ttl_resolved_90d',
        expireAfterSeconds: 7776000,
        partialFilterExpression: { status: 'resolved' }
    }
);

// =============================================================================
// 4. COLLECTION: question_tags (master list tag để auto-complete)
// =============================================================================
db.createCollection('question_tags', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['tag', 'usage_count'],
            properties: {
                tag:          { bsonType: 'string' },
                display_name: { bsonType: 'string' },
                category:     { bsonType: 'string' },
                usage_count:  { bsonType: 'int' }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});
db.question_tags.createIndex({ tag: 1 }, { unique: true });
db.question_tags.createIndex({ usage_count: -1 });

// =============================================================================
// 5. COLLECTION: question_imports (audit job import câu hỏi)
//    (từ question-service-design.md mục VI.1)
// =============================================================================
db.createCollection('question_imports', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['job_id', 'org_id', 'imported_by', 'format', 'status', 'created_at'],
            properties: {
                job_id:        { bsonType: 'string' },
                org_id:        { bsonType: 'string' },
                imported_by:   { bsonType: 'string' },
                format:        { enum: ['csv', 'xlsx', 'gift', 'qti', 'json', 'moodlexml'] },
                file_s3_key:   { bsonType: 'string' },
                status:        { enum: ['pending', 'running', 'completed', 'failed'] },
                total:         { bsonType: 'int' },
                success_count: { bsonType: 'int' },
                failed_count:  { bsonType: 'int' },
                errors:        { bsonType: 'array' },
                created_at:    { bsonType: 'date' },
                completed_at:  { bsonType: ['date', 'null'] }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});
db.question_imports.createIndex({ job_id: 1 }, { unique: true });
db.question_imports.createIndex({ imported_by: 1, created_at: -1 });
db.question_imports.createIndex({ org_id: 1, status: 1 });

// =============================================================================
// 6. COLLECTION: ai_prompts (Prompt Registry cho AI Service)
//    (từ ai-service-design.md mục IV.2)
// =============================================================================
db.createCollection('ai_prompts', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['name', 'version', 'template', 'model', 'active', 'created_at'],
            properties: {
                name:            { bsonType: 'string' },   // "generate_mc_single"
                version:         { bsonType: 'string' },   // "v3.1"
                template:        { bsonType: 'string' },   // Jinja2 template
                variables:       { bsonType: 'array', items: { bsonType: 'string' } },
                model:           { bsonType: 'string' },   // "gpt-4o"
                temperature:     { bsonType: 'double' },
                max_tokens:      { bsonType: 'int' },
                response_format: { bsonType: ['object', 'null'] },  // JSON schema
                active:          { bsonType: 'bool' },
                traffic_weight:  { bsonType: 'double' },   // 0.0 - 1.0 cho A/B
                evals: {
                    bsonType: 'object',
                    properties: {
                        golden_set_size: { bsonType: 'int' },
                        pass_rate:       { bsonType: 'double' },
                        last_evaluated:  { bsonType: ['date', 'null'] }
                    }
                },
                created_by:      { bsonType: 'string' },
                created_at:      { bsonType: 'date' }
            }
        }
    },
    validationLevel: 'strict',
    validationAction: 'error'
});
db.ai_prompts.createIndex({ name: 1, version: 1 }, { unique: true });
db.ai_prompts.createIndex({ name: 1, active: 1 });

// =============================================================================
// XÁC NHẬN TẠO THÀNH CÔNG
// =============================================================================
print('>>> Collections đã tạo:');
printjson(db.getCollectionNames());

print('\n>>> Indexes questions:');
printjson(db.questions.getIndexes().map(i => i.name));

print('\n>>> Indexes ai_prompts:');
printjson(db.ai_prompts.getIndexes().map(i => i.name));

print('\n>>> Indexes question_imports:');
printjson(db.question_imports.getIndexes().map(i => i.name));
