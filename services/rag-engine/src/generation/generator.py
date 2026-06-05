import os
import httpx
import asyncio

class FinalGenerator:
    def __init__(self, ollama_url: str = None, model_name: str = None):
        self.ollama_url = ollama_url or os.getenv("OLLAMA_URL", "http://localhost:11434")
        self.model_name = model_name or os.getenv("OLLAMA_MODEL", "gemma2:2b")
        # Initialize an async HTTP client
        self.client = httpx.AsyncClient(base_url=self.ollama_url, timeout=120.0)
        print(f"Ollama Generator initialized. URL: {self.ollama_url}, Model: {self.model_name}")

    async def _invoke(self, prompt: str) -> str:
        try:
            response = await self.client.post("/api/generate", json={
                "model": self.model_name,
                "prompt": prompt,
                "options": {
                    "temperature": 0.7,
                    "top_p": 0.9,
                    "repeat_penalty": 1.2
                },
                "stream": False,
            })
            response.raise_for_status()
            return response.json()["response"].strip()
        except Exception as e:
            print(f"Ollama invocation failed: {e}")
            # Mock generator fallback for integration/development tests if Ollama is unavailable
            return f"[MOCK GENERATION] Response to prompt: {prompt[:100]}..."

    def build_prompt(
        self,
        query: str,
        retrieved_chunks: list,
        reasoning_type: str,
        sub_questions: list,
    ) -> str:
        context_parts = []
        for i, cand in enumerate(retrieved_chunks[:3]):
            meta = cand["metadata"]
            score = meta.get("score", 0)
            is_acc = meta.get("is_accepted", False)
            chunk_text = meta.get("chunk_text", "")[:1200]
            domain = meta.get("domain", "unknown")
            context_parts.append(
                f"[Source {i+1} | Score: {score} | Accepted: {is_acc} | Domain: {domain}]\n{chunk_text}"
            )

        context = "\n\n".join(context_parts)

        cot_instructions = {
            "commonsense": (
                "Answer the question thoroughly and helpfully based on the sources above. "
                "Explain the concept, include code examples if the sources contain them, "
                "and cite which source(s) you used. Write at least 3-4 sentences."
            ),
            "adaptive": (
                "The question has multiple parts. "
                "First address each sub-question separately using the sources, "
                "then synthesise everything into a single unified answer. "
                "Be thorough -- include examples and code where relevant."
            ),
            "strategic": (
                "This is a complex comparative or architectural question. "
                "Step 1 - identify the main categories or dimensions relevant to the query. "
                "Step 2 - discuss each dimension in depth using evidence from the sources. "
                "Step 3 - write a final synthesised recommendation with reasoning."
            ),
        }
        cot_instruction = cot_instructions.get(reasoning_type, cot_instructions["commonsense"])

        sub_q_block = ""
        if sub_questions and sub_questions != [query]:
            sub_q_block = "Sub-questions to address:\n" + "\n".join(
                f"  {idx+1}. {sq}" for idx, sq in enumerate(sub_questions)
            ) + "\n\n"

        # Gemma-2 IT chat template
        prompt = (
            "<start_of_turn>user\n"
            "You are a senior software engineer answering Stack Overflow questions. "
            "Use the retrieved evidence below as your primary source, but you may elaborate "
            "on concepts, explain reasoning, and provide structure to make the answer clear.\n\n"
            "RULES:\n"
            "1. Base your answer on the Retrieved Evidence. Do not invent facts that contradict the sources.\n"
            "2. If the sources do not contain enough information to answer, say exactly: "
            "\"The retrieved sources do not contain enough information to answer this question.\" and stop.\n"
            "3. You MAY explain, expand, and structure information from the sources -- "
            "do not copy-paste raw source text verbatim.\n"
            "4. Include code blocks using markdown (```language) if the sources contain code "
            "or if a code example would make the answer significantly clearer.\n\n"
            f"Retrieved Evidence:\n{context}\n\n"
            f"{sub_q_block}"
            f"Instruction: {cot_instruction}\n\n"
            f"Question: {query}\n"
            "<end_of_turn>\n"
            "<start_of_turn>model\n"
        )
        return prompt

    def _score_response(self, response: str) -> float:
        tokens = response.split()
        if not tokens:
            return 0.0
        return len(set(tokens)) / len(tokens)

    async def generate_with_consistency(self, prompt: str, n: int = 3) -> str:
        print(f"Applying self-consistency decoding (n={n}) ...")
        # Call Ollama concurrently
        tasks = [self._invoke(prompt) for _ in range(n)]
        responses = await asyncio.gather(*tasks)
        scored = [(self._score_response(r), r) for r in responses]
        return max(scored, key=lambda x: x[0])[1]

    async def generate(self, trace):
        r_type = trace.classification.get("reasoning_type", "commonsense")
        sq = trace.classification.get("sub_questions", [])

        prompt = self.build_prompt(trace.query, trace.reranked_final, r_type, sq)
        trace.generation_prompt = prompt

        if r_type == "strategic":
            answer = await self.generate_with_consistency(prompt, n=3)
        else:
            answer = await self._invoke(prompt)

        trace.final_answer = answer
        return trace

    async def close(self):
        await self.client.aclose()
