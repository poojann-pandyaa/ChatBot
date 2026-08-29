from sentence_transformers import CrossEncoder
model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2')
scores = model.predict([
    ("what is the capital of france?", "Paris is the capital of France."),
    ("what is the capital of france?", "London is the capital of the UK."),
])
print(scores)
