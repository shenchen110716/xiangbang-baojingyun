FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN addgroup --system app && adduser --system --ingroup app app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN mkdir -p /app/uploads && chown -R app:app /app

USER app
EXPOSE 8000

# Run pending Alembic migrations before the app starts, not inside the
# FastAPI startup hook, so multiple workers/replicas never race on DDL.
CMD alembic upgrade head && uvicorn backend.app:app --host 0.0.0.0 --port ${PORT:-8000}
