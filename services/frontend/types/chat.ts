import { OpenAIModel } from './openai';

export interface RouterDecisions {
  original_query: string;
  is_followup: boolean;
  query_rewritten: boolean;
  rewritten_query: string | null;
  cache_hit: boolean;
  retrieval_retried: boolean;
  retry_reason: string | null;
  refined_query: string | null;
  quality_score: number | null;
  path_taken: string; // "cache_hit" | "simple_rag" | "multi_step_rag" | "retry_rag"
}

export interface RAGTrace {
  reasoning_type: string;
  sub_questions: string[];
  sources: {
    chunk_id: number;
    score: number;
    question_id: string;
    is_accepted: boolean;
    domain: string;
    chunk_text: string;
  }[];
  router_decisions?: RouterDecisions;
}

export interface Message {
  role: Role;
  content: string;
  trace?: RAGTrace;
}

export type Role = 'assistant' | 'user';

export interface ChatBody {
  model: OpenAIModel;
  messages: Message[];
  prompt: string;
  conversationId?: string;
}

export interface Conversation {
  id: string;
  name: string;
  messages: Message[];
  model: OpenAIModel;
  prompt: string;
  folderId: string | null;
}
