import requests

# Let's search for "difference between VC++ and C++" which we know exists
query = "What is the difference between VC++ and C++?"
es_url = "http://localhost:9200/stackexchange_chunks/_search"
q_payload = {
    "query": {
        "match": {
            "chunk_text": query
        }
    },
    "size": 5
}
es_res = requests.post(es_url, json=q_payload).json()
if "hits" in es_res and "hits" in es_res["hits"]:
    for hit in es_res["hits"]["hits"]:
        print(hit["_source"]["chunk_text"][:100], "(Score:", hit["_score"], ")")
