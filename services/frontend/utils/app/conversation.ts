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

export const saveConversations = (conversations: Conversation[]) => {
  const toSave = conversations.map(c => ({ ...c, messages: [] }));
  localStorage.setItem('conversationHistory', JSON.stringify(toSave));
};
