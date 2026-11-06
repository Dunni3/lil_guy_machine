# The run database's ingest service. Build context: server/.
FROM python:3.12-slim
WORKDIR /srv
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app app
COPY sql sql
COPY schema schema
COPY gen_models.sh .
COPY tests tests
COPY pytest.ini .
RUN useradd --system --uid 10001 lilguy
USER lilguy
EXPOSE 8787
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787", "--proxy-headers", "--log-level", "info"]
