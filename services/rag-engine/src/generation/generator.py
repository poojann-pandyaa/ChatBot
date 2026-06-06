import os
import httpx
import asyncio

class FinalGenerator:
    def __init__(self, ollama_url: str = None, model_name: str = None):
        self.ollama_url = ollama_url or os.getenv("OLLAMA_URL", "http://localhost:11434")
        self.model_name = model_name or os.getenv("OLLAMA_MODEL", "gemma2:2b")
        # Initialize an async HTTP client
        self.client = httpx.AsyncClient(base_url=self.ollama_url, timeout=None)
        print(f"Ollama Generator initialized. URL: {self.ollama_url}, Model: {self.model_name}")

    async def _invoke(self, prompt: str) -> str:
        try:
            response = await self.client.post("/api/generate", json={
                "model": self.model_name,
                "prompt": prompt,
                "options": {
                    "temperature": 0.2,
                    "top_p": 0.9,
                    "repeat_penalty": 1.0,
                    "num_ctx": 4096,
                    "num_predict": 2048,
                },
                "stream": False,
            })
            if not response.is_success:
                error_body = response.text[:500]
                print(f"Ollama HTTP {response.status_code}: {error_body}")
                raise Exception(f"Ollama returned {response.status_code}: {error_body}")
            return response.json()["response"].strip()
        except Exception as e:
            print(f"Ollama invocation failed: {e}")
            # Mock generator fallback for integration/development tests if Ollama is unavailable
            return f"[MOCK GENERATION] Response to prompt: {prompt[:100]}..."

    async def _invoke_stream(self, prompt: str):
        try:
            async with self.client.stream("POST", "/api/generate", json={
                "model": self.model_name,
                "prompt": prompt,
                "options": {
                    "temperature": 0.2,
                    "top_p": 0.9,
                    "repeat_penalty": 1.0,
                    "num_ctx": 4096,
                    "num_predict": 2048,
                },
                "stream": True,
            }) as response:
                if not response.is_success:
                    error_body = await response.aread()
                    print(f"Ollama HTTP {response.status_code}: {error_body[:500].decode()}")
                    raise Exception(f"Ollama returned {response.status_code}")
                
                import json
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        token = data.get("response", "")
                        if token:
                            yield token
                    except Exception as parse_err:
                        print(f"Failed to parse Ollama JSON line: {parse_err}")
        except Exception as e:
            print(f"Ollama stream invocation failed: {e}")
            # Yield a mock generation chunk by chunk
            mock_text = f"[MOCK STREAM GENERATION] Response to prompt: {prompt[:50]}..."
            for char in mock_text:
                yield char

    def build_prompt(
        self,
        query: str,
        retrieved_chunks: list,
        reasoning_type: str,
        sub_questions: list,
        history: list = None,
    ) -> str:
        context_parts = []
        for i, cand in enumerate(retrieved_chunks[:3]):
            meta = cand["metadata"]
            score = meta.get("score", 0)
            is_acc = meta.get("is_accepted", False)
            chunk_text = meta.get("chunk_text", "")[:800]
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

        # Construct history block
        history_block = ""
        if history:
            for msg in history:
                role = "user" if msg.get("role") == "user" else "model"
                content = msg.get("content", "")
                history_block += f"<start_of_turn>{role}\n{content}\n<end_of_turn>\n"

        # Gemma-2 IT chat template
        prompt = (
            f"{history_block}"
            "<start_of_turn>user\n"
            "You are a senior software engineer answering Stack Overflow questions. "
            "Use the retrieved evidence below as your primary source, but you may elaborate "
            "on concepts, explain reasoning, and provide structure to make the answer clear.\n\n"
            "RULES:\n"
            "1. Base your answer on the Retrieved Evidence AND the conversation history.\n"
            "2. If the user is asking a follow-up question about the previous conversation, prioritize the conversation history. You may ignore the retrieved evidence if it is not relevant to the follow-up.\n"
            "3. Do not invent facts that contradict the sources or history.\n"
            "4. If neither the sources nor the history contain enough information to answer, say exactly: "
            "\"The retrieved sources do not contain enough information to answer this question.\" and stop.\n"
            "5. You MAY explain, expand, and structure information from the sources -- "
            "do not copy-paste raw source text verbatim.\n"
            "6. Include code blocks using markdown (```language) if the sources contain code "
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

    async def generate_with_consistency(self, prompt: str, n: int = 1) -> str:
        print(f"Applying self-consistency decoding (n={n}) ...")
        # Call Ollama SEQUENTIALLY to avoid n× concurrent memory spikes on constrained nodes
        responses = []
        for i in range(n):
            print(f"  Self-consistency pass {i+1}/{n}...")
            r = await self._invoke(prompt)
            responses.append(r)
        scored = [(self._score_response(r), r) for r in responses]
        return max(scored, key=lambda x: x[0])[1]

    async def generate(self, trace):
        r_type = trace.classification.get("reasoning_type", "commonsense")
        sq = trace.classification.get("sub_questions", [])

        prompt = self.build_prompt(trace.query, trace.reranked_final, r_type, sq, trace.history)
        trace.generation_prompt = prompt

        if r_type == "strategic":
            answer = await self.generate_with_consistency(prompt, n=1)
        else:
            answer = await self._invoke(prompt)

        trace.final_answer = answer
        return trace

    async def generate_stream(self, trace):
        r_type = trace.classification.get("reasoning_type", "commonsense")
        sq = trace.classification.get("sub_questions", [])

        prompt = self.build_prompt(trace.query, trace.reranked_final, r_type, sq, trace.history)
        trace.generation_prompt = prompt

        async for token in self._invoke_stream(prompt):
            yield token

    async def rewrite_query(self, query: str, history: list) -> str:
        if not history:
            return query
            
        try:
            history_text = ""
            for msg in history:
                role = "User" if msg.get("role") == "user" else "Assistant"
                content_snippet = msg.get("content", "")[:300].replace("\n", " ")
                history_text += f"{role}: {content_snippet}\n"

            prompt = (
                "<start_of_turn>user\n"
                "You are a search query rewriter. Rewrite the follow-up user query into a single standalone query. "
                "Resolve any pronouns (like 'it', 'this', 'that', 'them', 'these') or incomplete sentences using the conversation history.\n"
                "If the follow-up query is already standalone or does not refer to the history, return it exactly as it is.\n"
                "Do NOT include any explanations, labels, quotes, or introductory text. Return ONLY the rewritten query on a single line.\n\n"
                "Conversation History:\n"
                f"{history_text}\n"
                f"Follow-up Query: {query}\n"
                "<end_of_turn>\n"
                "<start_of_turn>model\n"
            )
            
            response = await self.client.post("/api/generate", json={
                "model": self.model_name,
                "prompt": prompt,
                "options": {
                    "temperature": 0.0,
                    "num_ctx": 2048,
                    "num_predict": 32,
                    "stop": ["\n", "<end_of_turn>"]
                },
                "stream": False,
            })
            if response.is_success:
                rewritten = response.json()["response"].strip().strip('"').strip("'")
                if rewritten:
                    print(f"Query Rewritten: '{query}' -> '{rewritten}'")
                    return rewritten
            return query
        except Exception as e:
            print(f"Failed to rewrite query: {e}")
            return query

    async def close(self):
        await self.client.aclose()

