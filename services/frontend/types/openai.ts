// Model descriptor for the local LLMOps RAG pipeline.
// This project does not call OpenAI or any external AI API.
// The actual generation is handled by Ollama (Gemma 2B) running in-cluster.

export interface LLMModel {
  id: string;
  name: string;
  maxLength: number;
  tokenLimit: number;
}

export enum LLMModelID {
  GEMMA_2B = 'gemma2:2b',
}

export const fallbackModelID = LLMModelID.GEMMA_2B;

export const LLMModels: Record<LLMModelID, LLMModel> = {
  [LLMModelID.GEMMA_2B]: {
    id: LLMModelID.GEMMA_2B,
    name: 'Gemma 2B (Local · Ollama)',
    maxLength: 8192,
    tokenLimit: 4096,
  },
};

// ---------------------------------------------------------------------------
// Legacy compatibility aliases — referenced in index.tsx until full refactor.
// These point to the local model, not OpenAI.
// ---------------------------------------------------------------------------
export type OpenAIModel = LLMModel;
export const OpenAIModelID = LLMModelID;
export const OpenAIModels = LLMModels;
