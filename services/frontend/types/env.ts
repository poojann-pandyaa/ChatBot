// Runtime environment variables for the LLMOps Chat frontend.
// All communication goes to the local app-gateway — no external AI APIs.
export interface ProcessEnv {
  APP_GATEWAY_URL: string; // e.g. http://app-gateway:8080
}
