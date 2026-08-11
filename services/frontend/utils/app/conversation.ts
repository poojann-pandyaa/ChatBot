import { Conversation } from '@/types/chat';

export const updateConversation = (
  updatedConversation: Conversation,
  allConversations: Conversation[],
) => {
  const updatedConversations = allConversations.map((c) => {
    if (c.id === updatedConversation.id) {
      return updatedConversation;
    }
    return c;
  });

  saveConversation(updatedConversation);
  saveConversations(updatedConversations);

  return {
    single: updatedConversation,
    all: updatedConversations,
  };
};

export const saveConversation = (conversation: Conversation) => {
  const toSave = { ...conversation, messages: [] };
  localStorage.setItem('selectedConversation', JSON.stringify(toSave));
};

// Conversation list is now owned by Postgres (GET /api/conversations).
// This function is kept for call-site compatibility but no longer persists
// to localStorage — the backend is the authoritative source.
export const saveConversations = (_conversations: Conversation[]) => {
  // no-op: list is fetched from backend on page load
};
