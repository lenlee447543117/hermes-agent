FROM python:3.10-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc libpq-dev && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ARG ZHIPU_API_KEY
ENV ZHIPU_API_KEY=${ZHIPU_API_KEY}
ENV GLM_MODEL=glm-4.7
ENV GLM_MAX_TOKENS=4096

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
