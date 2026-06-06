class ReasoningTrace:
    def __init__(self, query):
        self.query = query
        self.history = []
        self.classification = {}
        self.retrieved_per_subquery = {}
        self.reranked_final = []
        self.generation_prompt = ""
        self.final_answer = ""
        
        # Router Agent tracking
        self.router_decisions = {
            "original_query": query,
            "is_followup": False,
            "query_rewritten": False,
            "rewritten_query": None,
            "cache_hit": False,
            "retrieval_retried": False,
            "retry_reason": None,
            "refined_query": None,
            "quality_score": None,
            "path_taken": "unknown"  # "cache_hit" | "simple_rag" | "multi_step_rag" | "retry_rag"
        }
        
    def to_dict(self):
        return {
            "query": self.query,
            "history": self.history,
            "classification": self.classification,
            "retrieved_per_subquery": self.retrieved_per_subquery,
            "reranked_final_configs": [r['metadata'] for r in self.reranked_final],
            "generation_prompt": self.generation_prompt,
            "final_answer": self.final_answer,
            "router_decisions": self.router_decisions
        }
