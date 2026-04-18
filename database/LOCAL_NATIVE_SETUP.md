# Setup Database Native trên Windows (Không Dùng Docker)

Hướng dẫn cài & seed từng DB bằng **hệ quản trị riêng (native installer)** — không cần Docker Desktop.

## Tổng quan khả năng chạy native

| DB | Native Windows? | Ghi chú |
| -- | --------------- | ------- |
| **PostgreSQL 16** | ✅ Có installer chính thức | Dễ nhất, khuyến nghị |
| **MongoDB 7**     | ✅ Có MSI chính thức + chạy được như Service | Tốt |
| **Elasticsearch 8** | ✅ Có Windows ZIP chính thức | Cần Java 17 (đã bundle sẵn) |
| **Redis 7**       | ⚠️ Không có build chính thức cho Windows | Dùng **Memurai** (drop-in) hoặc WSL |
| **ClickHouse**    | ❌ Không hỗ trợ Windows native | Bắt buộc **WSL2** hoặc Docker |

> **Khuyến nghị thực tế:** Chạy native 3 DB **PostgreSQL + MongoDB + Elasticsearch** trên Windows. Riêng **Redis và ClickHouse** dùng WSL2 hoặc Docker — sẽ đỡ đau đầu hơn nhiều.

---

## 1. PostgreSQL 16 (Native)

### Cài đặt

1. Tải installer: https://www.postgresql.org/download/windows/ → chọn **PostgreSQL 16.x**
2. Chạy installer, chọn các component:
   - [x] PostgreSQL Server
   - [x] pgAdmin 4
   - [x] Command Line Tools
   - [ ] Stack Builder (không cần)
3. Đặt password cho superuser `postgres` (ghi nhớ lại!)
4. Giữ mặc định: port `5432`, locale `Default`
5. Sau khi cài xong, PostgreSQL chạy tự động như **Windows Service**.

### Thêm `psql` vào PATH (nếu installer chưa thêm)

```
Control Panel → System → Advanced system settings → Environment Variables
→ Path → Edit → Add:  C:\Program Files\PostgreSQL\16\bin
```

Test:
```cmd
psql --version
```

### Tạo database + chạy schema + seed

```cmd
cd D:\SmartQuizSystem\database\postgresql

:: Tạo DB
psql -U postgres -c "CREATE DATABASE smartquiz;"

:: Chạy schema
psql -U postgres -d smartquiz -f schema.sql

:: Chạy seed
psql -U postgres -d smartquiz -f seed.sql

:: Verify
psql -U postgres -d smartquiz -c "SELECT email FROM users;"
```

> Nếu gặp lỗi UTF-8 khi chạy trên Windows terminal, thêm: `chcp 65001` trước các lệnh psql.

### Quản lý service
```cmd
:: Services.msc → tìm "postgresql-x64-16"
:: Hoặc command line:
net start postgresql-x64-16
net stop  postgresql-x64-16
```

---

## 2. MongoDB 7 (Native)

### Cài đặt

1. Tải MSI: https://www.mongodb.com/try/download/community → chọn **MongoDB 7.0, Windows x64, msi**
2. Chạy installer: **Complete** setup
   - [x] Install MongoDB as a Service (recommended)
   - [x] Run service as Network Service user
3. Tải thêm **mongosh** (shell): https://www.mongodb.com/try/download/shell → MSI
4. Sau khi cài, MongoDB chạy tự động ở port `27017`.

### Thêm `mongosh` vào PATH (nếu cần)
```
Path → Add:  C:\Program Files\MongoDB\Server\7.0\bin
              C:\Users\<YourUser>\AppData\Local\Programs\mongosh
```

Test:
```cmd
mongosh --version
```

### Chạy schema + seed

```cmd
cd D:\SmartQuizSystem\database\mongodb

:: Không auth (mặc định sau MSI install)
mongosh "mongodb://localhost:27017/smartquiz" schema.js
mongosh "mongodb://localhost:27017/smartquiz" seed.js

:: Verify
mongosh "mongodb://localhost:27017/smartquiz" --eval "db.questions.countDocuments()"
```

### Quản lý service
```cmd
net start MongoDB
net stop  MongoDB
```

### Bật auth (nếu muốn production-like)
Sửa `C:\Program Files\MongoDB\Server\7.0\bin\mongod.cfg`:
```yaml
security:
  authorization: enabled
```
Sau đó restart service và tạo user root trước khi bật auth.

---

## 3. Elasticsearch 8 (Native)

### Cài đặt

1. Tải ZIP: https://www.elastic.co/downloads/elasticsearch → **Windows x86_64**
2. Giải nén vào `D:\tools\elasticsearch-8.13.0\`
3. Sửa `config\elasticsearch.yml` — thêm cuối file:
   ```yaml
   cluster.name: smartquiz-local
   discovery.type: single-node
   xpack.security.enabled: false
   xpack.security.http.ssl.enabled: false
   ```
4. (Tuỳ chọn) Giảm RAM nếu máy yếu — sửa `config\jvm.options.d\heap.options`:
   ```
   -Xms1g
   -Xmx1g
   ```

### Khởi động

```cmd
cd D:\tools\elasticsearch-8.13.0
bin\elasticsearch.bat
```

> Mở sẵn PowerShell riêng, ES sẽ chiếm terminal. Đợi ~30s, test bằng browser: http://localhost:9200

### Cài như Windows Service (tuỳ chọn)
```cmd
cd D:\tools\elasticsearch-8.13.0
bin\elasticsearch-service.bat install
bin\elasticsearch-service.bat start

:: Quản lý
bin\elasticsearch-service.bat stop
bin\elasticsearch-service.bat remove
```

### Tạo index + seed

Dùng **PowerShell** (có sẵn trên Windows, không cần curl):

```powershell
cd D:\SmartQuizSystem\database\elasticsearch

:: Tạo index từ schema.json
Invoke-RestMethod -Uri "http://localhost:9200/question_search" `
    -Method PUT `
    -ContentType "application/json" `
    -InFile ".\schema.json"

:: Seed bulk từ seed.ndjson
Invoke-WebRequest -Uri "http://localhost:9200/_bulk" `
    -Method POST `
    -ContentType "application/x-ndjson" `
    -InFile ".\seed.ndjson"

:: Verify
Invoke-RestMethod -Uri "http://localhost:9200/question_search/_count"
```

Nếu đã cài `curl` (có sẵn Windows 10/11):
```cmd
curl -X PUT "http://localhost:9200/question_search" -H "Content-Type: application/json" --data-binary "@schema.json"
curl -X POST "http://localhost:9200/_bulk" -H "Content-Type: application/x-ndjson" --data-binary "@seed.ndjson"
```

### Cài Kibana (tuỳ chọn - UI)
- Tải: https://www.elastic.co/downloads/kibana → Windows ZIP (cùng version với ES)
- Giải nén → `bin\kibana.bat` → mở http://localhost:5601

---

## 4. Redis 7 (Không có native chính thức — dùng Memurai)

Redis Labs không phát hành build Windows. Có 3 lựa chọn:

### Cách A — Memurai (Khuyến nghị, drop-in Redis)

Memurai là fork Redis-compatible, chạy native Windows như service.

1. Tải: https://www.memurai.com/get-memurai → **Developer Edition (Free)**
2. Chạy installer (chấp nhận mặc định, port `6379`)
3. Cài xong, chạy tự động như service `Memurai`.

```cmd
:: Test
memurai-cli ping
:: → PONG

:: Hoặc dùng redis-cli vẫn được
redis-cli ping
```

### Seed dữ liệu
```cmd
cd D:\SmartQuizSystem\database\redis
memurai-cli -h localhost -p 6379 < seed.redis

:: Verify
memurai-cli DBSIZE
memurai-cli HGETALL session:f0000000-0000-0000-0000-000000000003
```

### Cách B — WSL2 Ubuntu
```bash
# Mở Ubuntu terminal trong WSL
sudo apt update && sudo apt install -y redis-server
sudo service redis-server start

# Seed (từ Windows path mount trong WSL)
cd /mnt/d/SmartQuizSystem/database/redis
redis-cli < seed.redis
```

### Cách C — Microsoft's old Redis port (KHÔNG khuyến nghị)
https://github.com/microsoftarchive/redis — đã ngừng maintain từ 2016, chỉ hỗ trợ Redis 3.x.

---

## 5. ClickHouse (Không có native Windows — phải WSL2)

ClickHouse **không hỗ trợ Windows native**. 2 lựa chọn:

### Cách A — WSL2 Ubuntu (Khuyến nghị native-like)

```bash
# Trong terminal WSL Ubuntu
sudo apt-get install -y apt-transport-https ca-certificates dirmngr
GNUPGHOME=$(mktemp -d)
sudo GNUPGHOME="$GNUPGHOME" gpg --no-default-keyring --keyring /usr/share/keyrings/clickhouse-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 8919F6BD2B48D754
sudo rm -rf "$GNUPGHOME"

echo "deb [signed-by=/usr/share/keyrings/clickhouse-keyring.gpg] https://packages.clickhouse.com/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/clickhouse.list

sudo apt-get update
sudo apt-get install -y clickhouse-server clickhouse-client

# (Khi installer hỏi password default user, có thể để trống cho local dev)

# Start
sudo service clickhouse-server start

# Chạy schema + seed
cd /mnt/d/SmartQuizSystem/database/clickhouse
clickhouse-client --multiquery < schema.sql
clickhouse-client --multiquery < seed.sql

# Verify
clickhouse-client --query "SELECT count() FROM smartquiz_analytics.exam_facts"
```

WSL forward port tự động → Windows app kết nối qua `localhost:8123` / `localhost:9000`.

### Cách B — Chỉ container ClickHouse (không compose)
```cmd
docker run -d --name clickhouse ^
  -p 8123:8123 -p 9000:9000 ^
  --ulimit nofile=262144:262144 ^
  -v D:\SmartQuizSystem\database\clickhouse:/init:ro ^
  clickhouse/clickhouse-server:23.8

docker exec -i clickhouse clickhouse-client --multiquery < D:\SmartQuizSystem\database\clickhouse\schema.sql
docker exec -i clickhouse clickhouse-client --multiquery < D:\SmartQuizSystem\database\clickhouse\seed.sql
```

---

## Kiểm tra toàn bộ hệ thống

Sau khi cài xong, chạy script test kết nối (PowerShell):

```powershell
Write-Host "PostgreSQL:" (psql -U postgres -d smartquiz -t -c "SELECT COUNT(*) FROM users;")
Write-Host "MongoDB:" (mongosh "mongodb://localhost:27017/smartquiz" --quiet --eval "db.questions.countDocuments()")
Write-Host "Redis/Memurai:" (memurai-cli DBSIZE)
Write-Host "Elasticsearch:" (Invoke-RestMethod "http://localhost:9200/question_search/_count").count
Write-Host "ClickHouse: (nếu chạy WSL)" -ForegroundColor Yellow
wsl clickhouse-client --query "SELECT count() FROM smartquiz_analytics.exam_facts"
```

---

## Chuỗi kết nối `.env` khi chạy native

```env
POSTGRES_URL=postgresql://postgres:YOUR_PG_PASSWORD@localhost:5432/smartquiz
MONGODB_URL=mongodb://localhost:27017/smartquiz
REDIS_URL=redis://localhost:6379/0
CLICKHOUSE_URL=http://default@localhost:8123/smartquiz_analytics
ELASTICSEARCH_URL=http://localhost:9200
```

> Thay `YOUR_PG_PASSWORD` bằng mật khẩu bạn đã đặt lúc cài PostgreSQL.

---

## So sánh: Docker vs Native

| Tiêu chí | Docker Compose | Native |
| -------- | -------------- | ------ |
| Thời gian setup ban đầu | ~5 phút (1 lệnh) | ~30-60 phút (cài từng DB) |
| RAM chiếm dụng | Cao (Docker Desktop + VM) | Thấp (chỉ process cần) |
| Dễ reset môi trường | ✅ `docker compose down -v` | ❌ Phải drop/recreate từng DB |
| Giống production | ✅ (container-based) | ❌ Có thể khác version/config |
| Hỗ trợ đầy đủ 5 DB | ✅ | ⚠️ Redis + CH phải workaround |
| Debug trực tiếp | Phải `docker exec` | ✅ Gọi CLI trực tiếp |

**Gợi ý:**
- Dev hàng ngày trên máy cá nhân → **Native** (PG + Mongo + ES) + **Memurai** cho Redis + **WSL** cho ClickHouse.
- Demo / test integration full-stack → **Docker Compose** cho gọn.
