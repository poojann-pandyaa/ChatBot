import requests

classify_url = "http://localhost:8001/classify"
res = requests.post(classify_url, json={"query": "What is the difference between VC++ and C++?"}).json()
print("Classification:", res)
