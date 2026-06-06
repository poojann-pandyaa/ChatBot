class FollowupDetector:
    """Heuristic follow-up detection. No LLM call needed."""
    
    PRONOUN_PATTERNS = [
        "it", "this", "that", "them", "these", "those",
        "its", "the same", "above", "previous", "earlier",
        "in java", "in python", "in c++", "in javascript", "in go", "in rust",
    ]
    
    def is_followup(self, query: str, history: list) -> bool:
        if not history:
            return False
        
        q = query.lower().strip()
        
        # Short queries with history are likely follow-ups
        if len(q.split()) <= 5 and history:
            return True
        
        # Contains pronouns that reference previous context
        for pattern in self.PRONOUN_PATTERNS:
            # Match whole words or boundary checks
            import re
            if re.search(r'\b' + re.escape(pattern) + r'\b', q):
                return True
        
        # Starts with conjunctions suggesting continuation
        if q.startswith(("and ", "but ", "also ", "what about ", "how about ", "why ", "how to ")):
            # "how to" or "why" as short sentences are often follow-ups if history is present
            if len(q.split()) <= 6:
                return True
        
        return False
