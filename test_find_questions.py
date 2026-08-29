import requests

es_url = "http://localhost:9200/stackexchange_chunks/_search"
def search(term):
    print(f"\n--- Searching for {term} ---")
    q_payload = {
        "query": {
            "match": {
                "chunk_text": term
            }
        },
        "size": 5,
        "_source": ["chunk_text"]
    }
    es_res = requests.post(es_url, json=q_payload).json()
    if "hits" in es_res and "hits" in es_res["hits"]:
        for hit in es_res["hits"]["hits"]:
            text = hit["_source"]["chunk_text"]
            q = [line for line in text.split("\n") if line.startswith("Q: ")]
            if q: print(q[0][:150])
            
search("how does garbage collection")
search("what is a virtual machine")
search("how to parse xml")
search("design pattern")
