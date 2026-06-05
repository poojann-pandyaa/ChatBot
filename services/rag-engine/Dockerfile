FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the Hugging Face models during docker build to speed up container startup times
RUN python -c "from transformers import AutoTokenizer, AutoModel; AutoTokenizer.from_pretrained('BAAI/bge-base-en-v1.5'); AutoModel.from_pretrained('BAAI/bge-base-en-v1.5')"
RUN python -c "from transformers import pipeline; pipeline('text2text-generation', model='google/flan-t5-base')"
RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')"

COPY . .

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
