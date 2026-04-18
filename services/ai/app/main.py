from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

app = FastAPI(
    title="SmartQuiz AI Service",
    version="0.1.0",
    description="Question generation, essay grading, semantic search.",
)

Instrumentator().instrument(app).expose(app, endpoint="/metrics")


@app.get("/health/live")
async def health_live() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready")
async def health_ready() -> dict[str, str]:
    return {"status": "ok"}
