import requests
import json
import numpy as np

res1 = requests.post("http://localhost:8005/embed", json={"text": "socket programming"}).json()["embedding"]
res2 = requests.post("http://localhost:8005/embed", json={"text": "write a code for longest prefix matching"}).json()["embedding"]

v1 = np.array(res1)
v2 = np.array(res2)
cosine_dist = 1.0 - np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))
print("Cosine Distance:", cosine_dist)
