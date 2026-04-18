# SmartQuiz AI Service

Python 3.12 + FastAPI. Quản lý bằng `uv`.

## Chạy local

```bash
cd services/ai
uv sync --extra dev
uv run uvicorn app.main:app --reload --port 8201
```

Healthcheck:

```bash
curl localhost:8201/health/live
curl localhost:8201/metrics
```

## Test

```bash
uv run pytest
uv run ruff check .
uv run black --check .
uv run mypy app
```
