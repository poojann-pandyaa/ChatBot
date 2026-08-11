FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt
RUN pip install --no-cache-dir "accelerate>=0.27.2"

# ── Bake HuggingFace models into the image at build time ────────────────────
# Docker layer-caches this step: models are only re-downloaded if the model
# IDs below change. All three model IDs must exactly match those loaded in
# app.py (_load_models function) so the offline env vars enforce no network
# calls at runtime.
RUN python3 -c "\
from transformers import AutoModel, AutoTokenizer, AutoModelForSeq2SeqLM; \
from sentence_transformers import CrossEncoder; \
print('Downloading google/flan-t5-base...'); \
AutoTokenizer.from_pretrained('google/flan-t5-base'); \
AutoModelForSeq2SeqLM.from_pretrained('google/flan-t5-base'); \
print('Downloading BAAI/bge-base-en-v1.5...'); \
AutoTokenizer.from_pretrained('BAAI/bge-base-en-v1.5'); \
AutoModel.from_pretrained('BAAI/bge-base-en-v1.5'); \
print('Downloading cross-encoder/ms-marco-MiniLM-L-6-v2...'); \
CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2'); \
print('All models downloaded successfully.') \
"

COPY . .

EXPOSE 8000

ENV PYTHONUNBUFFERED=1
# Force offline mode — models are baked in; any attempt to reach HuggingFace
# Hub at runtime means a model ID mismatch and should fail loudly.
ENV HF_HUB_OFFLINE=1
ENV TRANSFORMERS_OFFLINE=1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
