import { Chat } from '@/components/Chat/Chat';
import { Chatbar } from '@/components/Chatbar/Chatbar';
import { Navbar } from '@/components/Mobile/Navbar';
import { Promptbar } from '@/components/Promptbar/Promptbar';
import { ChatBody, Conversation, Message } from '@/types/chat';
import { KeyValuePair } from '@/types/data';
import { ErrorMessage } from '@/types/error';
import { LatestExportFormat, SupportedExportFormats } from '@/types/export';
import { Folder, FolderType } from '@/types/folder';
import {
  OpenAIModel,
  OpenAIModelID,
  OpenAIModels,
  fallbackModelID,
} from '@/types/openai';
import { Prompt } from '@/types/prompt';
import { getEndpoint } from '@/utils/app/api';
import {
  cleanConversationHistory,
  cleanSelectedConversation,
} from '@/utils/app/clean';
import { DEFAULT_SYSTEM_PROMPT } from '@/utils/app/const';
import {
  saveConversation,
  saveConversations,
  updateConversation,
} from '@/utils/app/conversation';
import { saveFolders } from '@/utils/app/folders';
import { exportData, importData } from '@/utils/app/importExport';
import { savePrompts } from '@/utils/app/prompts';
import { IconArrowBarLeft, IconArrowBarRight } from '@tabler/icons-react';
import { GetServerSideProps } from 'next';
import { useTranslation } from 'next-i18next';
import { serverSideTranslations } from 'next-i18next/serverSideTranslations';
import Head from 'next/head';
import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { v4 as uuidv4 } from 'uuid';

interface HomeProps {
  defaultModelId: OpenAIModelID;
}

export default function Home({
  defaultModelId,
}: HomeProps) {
  const { t } = useTranslation('chat');

  // AUTH & BUDGET STATE ────────────────────────────────────────────────────────
  const [authToken, _setAuthToken] = useState<string | null>(null);
  const tokenRef = useRef<string | null>(null);

  const setAuthToken = (token: string | null) => {
    tokenRef.current = token;
    _setAuthToken(token);
  };

  const [authUserId, setAuthUserId] = useState<string>('');
  const [loginUserId, setLoginUserId] = useState<string>('');
  const [loginPassword, setLoginPassword] = useState<string>('');
  const [isRegisterMode, setIsRegisterMode] = useState<boolean>(false);
  const [loginError, setLoginError] = useState<string>('');
  const [loginLoading, setLoginLoading] = useState<boolean>(false);

  const [tokenBudgetLimit, setTokenBudgetLimit] = useState<number | null>(null);
  const [tokenBudgetRemaining, setTokenBudgetRemaining] = useState<number | null>(null);
  const [tokenBudgetReset, setTokenBudgetReset] = useState<number | null>(null);
  const [isRestoringSession, setIsRestoringSession] = useState<boolean>(true);

  const isLoggedIn = !!authToken;

  const fetchWithAuth = async (url: string, options: RequestInit = {}): Promise<Response> => {
    let currentToken = tokenRef.current;
    const headers = { ...options.headers, ...(currentToken ? { 'Authorization': `Bearer ${currentToken}` } : {}) };
    let res = await fetch(url, { ...options, headers });
    
    if (res.status === 401) {
      try {
        const refreshRes = await fetch('/api/auth/refresh', { method: 'POST' });
        if (refreshRes.ok) {
          const data = await refreshRes.json();
          setAuthToken(data.token);
          setAuthUserId(data.user_id);
          currentToken = data.token;
          const retryHeaders = { ...options.headers, 'Authorization': `Bearer ${currentToken}` };
          res = await fetch(url, { ...options, headers: retryHeaders });
        } else {
          await handleLogout();
        }
      } catch (e) {
        await handleLogout();
      }
    }
    
    // Extract Token Budget headers
    const limit = res.headers.get('X-Token-Budget-Limit');
    const remaining = res.headers.get('X-Token-Budget-Remaining');
    const reset = res.headers.get('X-Token-Budget-Reset');
    if (limit) setTokenBudgetLimit(parseInt(limit, 10));
    if (remaining) setTokenBudgetRemaining(parseInt(remaining, 10));
    if (reset) setTokenBudgetReset(parseInt(reset, 10));

    return res;
  };

  const handleAuthSubmit = async () => {
    if (!loginUserId.trim() || !loginPassword.trim()) {
      setLoginError('Please enter both username and password.');
      return;
    }
    setLoginLoading(true);
    setLoginError('');
    try {
      const endpoint = isRegisterMode ? '/api/auth/register' : '/api/auth/login';
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: loginUserId.trim(), password: loginPassword.trim() }),
      });
      const data = await res.json();
      if (res.ok && data.token) {
        setAuthToken(data.token);
        setAuthUserId(data.user_id);
      } else {
        setLoginError(data.error || 'Authentication failed.');
      }
    } catch {
      setLoginError('Cannot reach the authentication service.');
    } finally {
      setLoginLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) {}
    setAuthToken(null);
    setAuthUserId('');
    setConversations([]);
    setSelectedConversation(undefined);
    setTokenBudgetLimit(null);
    setTokenBudgetRemaining(null);
    setTokenBudgetReset(null);
  };

  // STATE ----------------------------------------------

  const [loading, setLoading] = useState<boolean>(false);
  const [lightMode, setLightMode] = useState<'dark' | 'light'>('dark');
  const [messageIsStreaming, setMessageIsStreaming] = useState<boolean>(false);

  const [folders, setFolders] = useState<Folder[]>([]);

  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversation, setSelectedConversation] =
    useState<Conversation>();
  const [currentMessage, setCurrentMessage] = useState<Message>();

  const [showSidebar, setShowSidebar] = useState<boolean>(true);

  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [showPromptbar, setShowPromptbar] = useState<boolean>(true);

  // REFS ----------------------------------------------

  const stopConversationRef = useRef<boolean>(false);

  // FETCH RESPONSE ----------------------------------------------

  const handleSend = async (
    message: Message,
    deleteCount = 0,
  ) => {
    if (selectedConversation) {
      let updatedConversation: Conversation;

      if (deleteCount) {
        const updatedMessages = [...selectedConversation.messages];
        for (let i = 0; i < deleteCount; i++) {
          updatedMessages.pop();
        }

        updatedConversation = {
          ...selectedConversation,
          messages: [...updatedMessages, message],
        };
      } else {
        updatedConversation = {
          ...selectedConversation,
          messages: [...selectedConversation.messages, message],
        };
      }

      setSelectedConversation(updatedConversation);
      setLoading(true);
      setMessageIsStreaming(true);

      const chatBody: ChatBody = {
        model: updatedConversation.model,
        messages: updatedConversation.messages,
        prompt: updatedConversation.prompt,
        conversationId: updatedConversation.id,
      };

      const endpoint = getEndpoint();
      let body = JSON.stringify(chatBody);

      const controller = new AbortController();
      const response = await fetchWithAuth(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        signal: controller.signal,
        body,
      });

      if (!response.ok) {
        setLoading(false);
        setMessageIsStreaming(false);
        if (response.status === 429) {
          try {
            const errData = await response.json();
            toast.error(errData.message || 'Token budget exhausted. Please wait for refill.');
          } catch {
            toast.error('Token budget exhausted. Please wait for refill.');
          }
        } else if (response.status !== 401) {
          // 401 is fully handled by fetchWithAuth (refresh + logout), so skip it here
          toast.error(response.statusText);
        }
        return;
      }

      const data = response.body;

      if (!data) {
        setLoading(false);
        setMessageIsStreaming(false);
        return;
      }

      if (updatedConversation.messages.length === 1) {
        const { content } = message;
          const customName =
            content.length > 30 ? content.substring(0, 30) + '...' : content;

          updatedConversation = {
            ...updatedConversation,
            name: customName,
          };
        }

        setLoading(false);

        try {
          const reader = data.getReader();
          const decoder = new TextDecoder();
          let done = false;
          let streamBuffer = '';
          let assistantContent = '';
          let assistantTrace: any = undefined;
          let isFirst = true;

          while (!done) {
            if (stopConversationRef.current === true) {
              controller.abort();
              done = true;
              break;
            }
            const { value, done: doneReading } = await reader.read();
            done = doneReading;
            const chunkValue = decoder.decode(value || new Uint8Array(), { stream: !doneReading });

            streamBuffer += chunkValue;
            const lines = streamBuffer.split('\n');
            streamBuffer = lines.pop() || '';

            for (const line of lines) {
              if (!line.trim()) continue;
              try {
                const parsed = JSON.parse(line);
                if (parsed.type === 'trace') {
                  assistantTrace = parsed.data;
                } else if (parsed.type === 'token') {
                  assistantContent += parsed.data;
                } else if (parsed.type === 'error') {
                  assistantContent += `\n[Error: ${parsed.data}]`;
                }
              } catch (err) {
                console.error('Failed to parse line from stream:', line, err);
              }
            }

            if (isFirst) {
              isFirst = false;
              const updatedMessages: Message[] = [
                ...updatedConversation.messages,
                { role: 'assistant', content: assistantContent, trace: assistantTrace },
              ];

              updatedConversation = {
                ...updatedConversation,
                messages: updatedMessages,
              };

              setSelectedConversation(updatedConversation);
            } else {
              const updatedMessages: Message[] = updatedConversation.messages.map(
                (message, index) => {
                  if (index === updatedConversation.messages.length - 1) {
                    return {
                      ...message,
                      content: assistantContent,
                      trace: assistantTrace || message.trace,
                    };
                  }

                  return message;
                },
              );

              updatedConversation = {
                ...updatedConversation,
                messages: updatedMessages,
              };

              setSelectedConversation(updatedConversation);
            }
          }

          if (streamBuffer.trim()) {
            try {
              const parsed = JSON.parse(streamBuffer.trim());
              if (parsed.type === 'trace') {
                assistantTrace = parsed.data;
              } else if (parsed.type === 'token') {
                assistantContent += parsed.data;
              } else if (parsed.type === 'error') {
                assistantContent += `\n[Error: ${parsed.data}]`;
              }
              
              const updatedMessages: Message[] = updatedConversation.messages.map(
                (message, index) => {
                  if (index === updatedConversation.messages.length - 1) {
                    return {
                      ...message,
                      content: assistantContent,
                      trace: assistantTrace || message.trace,
                    };
                  }

                  return message;
                },
              );

              updatedConversation = {
                ...updatedConversation,
                messages: updatedMessages,
              };

              setSelectedConversation(updatedConversation);
            } catch (err) {
              console.error('Failed to parse leftover line from stream:', streamBuffer, err);
            }
          }

          saveConversation(updatedConversation);

          const updatedConversations: Conversation[] = conversations.map(
            (conversation) => {
              if (conversation.id === selectedConversation.id) {
                return updatedConversation;
              }

              return conversation;
            },
          );

          if (updatedConversations.length === 0) {
            updatedConversations.push(updatedConversation);
          }

          setConversations(updatedConversations);
          saveConversations(updatedConversations);
        } catch (err) {
          console.error('Error handling streaming response:', err);
        } finally {
          setMessageIsStreaming(false);
        }
    }
  };

  const handleLightMode = (mode: 'dark' | 'light') => {
    setLightMode(mode);
    localStorage.setItem('theme', mode);
  };

  const handleToggleChatbar = () => {
    setShowSidebar(!showSidebar);
    localStorage.setItem('showChatbar', JSON.stringify(!showSidebar));
  };

  const handleTogglePromptbar = () => {
    setShowPromptbar(!showPromptbar);
    localStorage.setItem('showPromptbar', JSON.stringify(!showPromptbar));
  };

  const handleExportData = () => {
    exportData();
  };

  const handleImportConversations = (data: SupportedExportFormats) => {
    const { history, folders, prompts }: LatestExportFormat = importData(data);

    setConversations(history);
    setSelectedConversation(history[history.length - 1]);
    setFolders(folders);
    setPrompts(prompts);
  };

  const handleSelectConversation = async (conversation: Conversation) => {
    try {
      const res = await fetchWithAuth(`/api/history/${conversation.id}`);
      if (res.status === 401) { await handleLogout(); return; }
      if (res.ok) {
        const data = await res.json();
        if (data && data.messages) {
          conversation = { ...conversation, messages: data.messages };
        }
      }
    } catch (error) {
      console.error("Failed to fetch history for conversation", error);
    }
    setSelectedConversation(conversation);
    saveConversation(conversation);
  };

  // FOLDER OPERATIONS  --------------------------------------------

  const handleCreateFolder = (name: string, type: FolderType) => {
    const newFolder: Folder = {
      id: uuidv4(),
      name,
      type,
    };

    const updatedFolders = [...folders, newFolder];

    setFolders(updatedFolders);
    saveFolders(updatedFolders);
  };

  const handleDeleteFolder = (folderId: string) => {
    const updatedFolders = folders.filter((f) => f.id !== folderId);
    setFolders(updatedFolders);
    saveFolders(updatedFolders);

    const updatedConversations: Conversation[] = conversations.map((c) => {
      if (c.folderId === folderId) {
        return {
          ...c,
          folderId: null,
        };
      }

      return c;
    });
    setConversations(updatedConversations);
    saveConversations(updatedConversations);

    const updatedPrompts: Prompt[] = prompts.map((p) => {
      if (p.folderId === folderId) {
        return {
          ...p,
          folderId: null,
        };
      }

      return p;
    });
    setPrompts(updatedPrompts);
    savePrompts(updatedPrompts);
  };

  const handleUpdateFolder = (folderId: string, name: string) => {
    const updatedFolders = folders.map((f) => {
      if (f.id === folderId) {
        return {
          ...f,
          name,
        };
      }

      return f;
    });

    setFolders(updatedFolders);
    saveFolders(updatedFolders);
  };

  // CONVERSATION OPERATIONS  --------------------------------------------

  const handleNewConversation = () => {
    const lastConversation = conversations[conversations.length - 1];

    const newConversation: Conversation = {
      id: uuidv4(),
      name: `${t('New Conversation')}`,
      messages: [],
      model: lastConversation?.model || {
        id: OpenAIModels[defaultModelId].id,
        name: OpenAIModels[defaultModelId].name,
        maxLength: OpenAIModels[defaultModelId].maxLength,
        tokenLimit: OpenAIModels[defaultModelId].tokenLimit,
      },
      prompt: DEFAULT_SYSTEM_PROMPT,
      folderId: null,
    };

    const updatedConversations = [...conversations, newConversation];

    setSelectedConversation(newConversation);
    setConversations(updatedConversations);

    saveConversation(newConversation);
    saveConversations(updatedConversations);

    setLoading(false);
  };

  const handleDeleteConversation = (conversation: Conversation) => {
    // Optimistically update local state
    const updatedConversations = conversations.filter(
      (c) => c.id !== conversation.id,
    );
    setConversations(updatedConversations);
    saveConversations(updatedConversations);

    if (updatedConversations.length > 0) {
      setSelectedConversation(
        updatedConversations[updatedConversations.length - 1],
      );
      saveConversation(updatedConversations[updatedConversations.length - 1]);
    } else {
      setSelectedConversation({
        id: uuidv4(),
        name: 'New conversation',
        messages: [],
        model: OpenAIModels[defaultModelId],
        prompt: DEFAULT_SYSTEM_PROMPT,
        folderId: null,
      });
      localStorage.removeItem('selectedConversation');
    }

    // Persist deletion to backend
    fetchWithAuth(`/api/conversation/${conversation.id}`, {
      method: 'DELETE',
    }).catch(err => console.error('Failed to delete conversation on backend', err));
  };

  const handleUpdateConversation = (
    conversation: Conversation,
    data: KeyValuePair,
  ) => {
    const updatedConversation = {
      ...conversation,
      [data.key]: data.value,
    };

    const { single, all } = updateConversation(
      updatedConversation,
      conversations,
    );

    setSelectedConversation(single);
    setConversations(all);

    // If the user renamed the conversation, persist to backend
    if (data.key === 'name') {
      fetchWithAuth(`/api/conversation/${conversation.id}/rename`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: data.value }),
      }).catch(err => console.error('Failed to rename conversation on backend', err));
    }
  };

  const handleClearConversations = () => {
    // Optimistically clear local state
    setConversations([]);
    localStorage.removeItem('conversationHistory');

    setSelectedConversation({
      id: uuidv4(),
      name: 'New conversation',
      messages: [],
      model: OpenAIModels[defaultModelId],
      prompt: DEFAULT_SYSTEM_PROMPT,
      folderId: null,
    });
    localStorage.removeItem('selectedConversation');

    const updatedFolders = folders.filter((f) => f.type !== 'chat');
    setFolders(updatedFolders);
    saveFolders(updatedFolders);

    // Persist clear-all to backend
    fetchWithAuth('/api/conversations', {
      method: 'DELETE',
    }).catch(err => console.error('Failed to clear conversations on backend', err));
  };

  const handleEditMessage = (message: Message, messageIndex: number) => {
    if (selectedConversation) {
      const updatedMessages = selectedConversation.messages
        .map((m, i) => {
          if (i < messageIndex) {
            return m;
          }
        })
        .filter((m) => m) as Message[];

      const updatedConversation = {
        ...selectedConversation,
        messages: updatedMessages,
      };

      const { single, all } = updateConversation(
        updatedConversation,
        conversations,
      );

      setSelectedConversation(single);
      setConversations(all);

      setCurrentMessage(message);
    }
  };

  // PROMPT OPERATIONS --------------------------------------------

  const handleCreatePrompt = () => {
    const newPrompt: Prompt = {
      id: uuidv4(),
      name: `Prompt ${prompts.length + 1}`,
      description: '',
      content: '',
      model: OpenAIModels[defaultModelId],
      folderId: null,
    };

    const updatedPrompts = [...prompts, newPrompt];

    setPrompts(updatedPrompts);
    savePrompts(updatedPrompts);
  };

  const handleUpdatePrompt = (prompt: Prompt) => {
    const updatedPrompts = prompts.map((p) => {
      if (p.id === prompt.id) {
        return prompt;
      }

      return p;
    });

    setPrompts(updatedPrompts);
    savePrompts(updatedPrompts);
  };

  const handleDeletePrompt = (prompt: Prompt) => {
    const updatedPrompts = prompts.filter((p) => p.id !== prompt.id);
    setPrompts(updatedPrompts);
    savePrompts(updatedPrompts);
  };

  // EFFECTS  --------------------------------------------

  useEffect(() => {
    if (currentMessage) {
      handleSend(currentMessage);
      setCurrentMessage(undefined);
    }
  }, [currentMessage]);

  useEffect(() => {
    if (window.innerWidth < 640) {
      setShowSidebar(false);
    }
  }, [selectedConversation]);

  // ON LOAD --------------------------------------------

  useEffect(() => {
    // On load: try to restore session from HttpOnly refresh token cookie via silent refresh
    fetch('/api/auth/refresh', { method: 'POST' })
      .then(res => res.ok ? res.json() : null)
      .then(data => {
        if (data && data.token) {
          setAuthToken(data.token);
          setAuthUserId(data.user_id);
        }
      })
      .catch(() => {})
      .finally(() => setIsRestoringSession(false));

    const theme = localStorage.getItem('theme');
    if (theme) {
      setLightMode(theme as 'dark' | 'light');
    }

    if (window.innerWidth < 640) {
      setShowSidebar(false);
    }

    const showChatbar = localStorage.getItem('showChatbar');
    if (showChatbar) {
      setShowSidebar(showChatbar === 'true');
    }

    const showPromptbar = localStorage.getItem('showPromptbar');
    if (showPromptbar) {
      setShowPromptbar(showPromptbar === 'true');
    }

    const folders = localStorage.getItem('folders');
    if (folders) {
      setFolders(JSON.parse(folders));
    }

    const prompts = localStorage.getItem('prompts');
    if (prompts) {
      setPrompts(JSON.parse(prompts));
    }
  }, []);

  useEffect(() => {
    if (isRestoringSession) return;
    
    if (!isLoggedIn) {
      setConversations([]);
      return;
    }

    // Load conversation list from backend (Postgres is the authority)
    fetchWithAuth('/api/conversations')
      .then(async res => {
        if (res.status === 401) { await handleLogout(); return Promise.reject('Unauthorized'); }
        return res.json();
      })
      .then((list: Array<{id: string; name: string}>) => {
        const mapped: Conversation[] = list.map(c => ({
          id: c.id,
          name: c.name,
          messages: [],
          model: OpenAIModels[defaultModelId],
          prompt: DEFAULT_SYSTEM_PROMPT,
          folderId: null,
        }));
        setConversations(mapped);
        localStorage.removeItem('conversationHistory'); // clean up stale data
        
        // Only try loading selected conversation history AFTER conversations load
        const selectedConversation = localStorage.getItem('selectedConversation');
        if (selectedConversation) {
          try {
            const parsedSelectedConversation: Conversation = JSON.parse(selectedConversation);
            const cleanedSelectedConversation = cleanSelectedConversation(parsedSelectedConversation);
            
            fetchWithAuth(`/api/history/${cleanedSelectedConversation.id}`)
              .then(res => res.ok ? res.json() : null)
              .then(data => {
                  if (data && data.messages) {
                     cleanedSelectedConversation.messages = data.messages;
                  }
                  setSelectedConversation(cleanedSelectedConversation);
              })
              .catch(err => {
                  console.error("Failed to fetch history on load", err);
                  setSelectedConversation(cleanedSelectedConversation);
              });
          } catch (e) {
            console.error("Failed to parse selected conversation", e);
          }
        } else {
          setSelectedConversation({
            id: uuidv4(),
            name: 'New conversation',
            messages: [],
            model: OpenAIModels[defaultModelId],
            prompt: DEFAULT_SYSTEM_PROMPT,
            folderId: null,
          });
        }
      })
      .catch(err => {
        console.error('Failed to load conversation list from backend', err);
        setConversations([]);
      });

  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isRestoringSession, isLoggedIn]);

  return (
    <>
      <Head>
        <title>LLMOps Chat</title>
        <meta name="description" content="AI-powered chat backed by a local RAG pipeline." />
        <meta
          name="viewport"
          content="height=device-height ,width=device-width, initial-scale=1, user-scalable=no"
        />
        <link rel="icon" href="/favicon.ico" />
      </Head>

      {/* ── Login Screen ─────────────────────────────────────────── */}
      {!isLoggedIn && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          height: '100vh', width: '100vw', background: 'linear-gradient(135deg, #0f0c29, #302b63, #24243e)',
        }}>
          {isRestoringSession ? (
            <div style={{ color: 'rgba(255,255,255,0.7)', fontSize: '16px', fontWeight: 500 }}>
              Restoring session...
            </div>
          ) : (
          <div style={{
            background: 'rgba(255,255,255,0.07)', backdropFilter: 'blur(16px)',
            border: '1px solid rgba(255,255,255,0.15)', borderRadius: '16px',
            padding: '40px 48px', width: '100%', maxWidth: '420px', boxShadow: '0 25px 60px rgba(0,0,0,0.5)',
          }}>
            <div style={{ textAlign: 'center', marginBottom: '32px' }}>
              <div style={{ fontSize: '36px', marginBottom: '8px' }}>🤖</div>
              <h1 style={{ color: '#fff', fontSize: '24px', fontWeight: 700, margin: 0 }}>LLMOps Chat</h1>
              <p style={{ color: 'rgba(255,255,255,0.5)', fontSize: '14px', marginTop: '6px' }}>
                {isRegisterMode ? 'Create a new account' : 'Sign in to continue'}
              </p>
            </div>
            <div style={{ marginBottom: '16px' }}>
              <label style={{ color: 'rgba(255,255,255,0.7)', fontSize: '13px', fontWeight: 500, display: 'block', marginBottom: '6px' }}>
                Username
              </label>
              <input
                id="login-user-input"
                type="text"
                value={loginUserId}
                onChange={e => setLoginUserId(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleAuthSubmit()}
                placeholder="Enter your username"
                style={{
                  width: '100%', padding: '10px 14px', borderRadius: '8px', border: '1px solid rgba(255,255,255,0.2)',
                  background: 'rgba(255,255,255,0.08)', color: '#fff', fontSize: '15px', outline: 'none', boxSizing: 'border-box',
                }}
              />
            </div>
            <div style={{ marginBottom: '24px' }}>
              <label style={{ color: 'rgba(255,255,255,0.7)', fontSize: '13px', fontWeight: 500, display: 'block', marginBottom: '6px' }}>
                Password
              </label>
              <input
                id="login-pass-input"
                type="password"
                value={loginPassword}
                onChange={e => setLoginPassword(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleAuthSubmit()}
                placeholder="••••••••"
                style={{
                  width: '100%', padding: '10px 14px', borderRadius: '8px', border: '1px solid rgba(255,255,255,0.2)',
                  background: 'rgba(255,255,255,0.08)', color: '#fff', fontSize: '15px', outline: 'none', boxSizing: 'border-box',
                }}
              />
            </div>
            {loginError && (
              <p style={{ color: '#ff6b6b', fontSize: '13px', marginBottom: '16px', textAlign: 'center' }}>
                {loginError}
              </p>
            )}
            <button
              id="login-submit-btn"
              onClick={handleAuthSubmit}
              disabled={loginLoading}
              style={{
                width: '100%', padding: '12px', borderRadius: '8px', border: 'none',
                background: loginLoading ? 'rgba(255,255,255,0.2)' : '#4338ca', color: '#fff',
                fontSize: '15px', fontWeight: 600, cursor: loginLoading ? 'not-allowed' : 'pointer', transition: 'background 0.2s',
              }}
            >
              {loginLoading ? 'Please wait...' : (isRegisterMode ? 'Register' : 'Sign In')}
            </button>
            <div style={{ marginTop: '16px', textAlign: 'center' }}>
              <button 
                onClick={() => setIsRegisterMode(!isRegisterMode)}
                style={{ background: 'none', border: 'none', color: 'rgba(255,255,255,0.6)', fontSize: '13px', cursor: 'pointer', textDecoration: 'underline' }}
              >
                {isRegisterMode ? 'Already have an account? Sign In' : 'Need an account? Register'}
              </button>
            </div>
          </div>
          )}
        </div>
      )}

      {/* ── Main Chat App ─────────────────────────────────────────── */}
      {isLoggedIn && selectedConversation && (
        <main
          className={`flex h-screen w-screen flex-col text-sm text-white dark:text-white ${lightMode}`}
        >
          <div className="fixed top-0 w-full sm:hidden">
            <Navbar
              selectedConversation={selectedConversation}
              onNewConversation={handleNewConversation}
            />
          </div>

          {/* User badge + Budget + Logout button */}
          <div style={{
            position: 'fixed', top: '8px', right: '12px', zIndex: 100,
            display: 'flex', alignItems: 'center', gap: '10px',
          }}>
            {tokenBudgetRemaining !== null && (
              <span style={{
                color: tokenBudgetRemaining <= 0 ? '#ff6b6b' : 'rgba(255,255,255,0.8)', fontSize: '12px',
                background: tokenBudgetRemaining <= 0 ? 'rgba(255,100,100,0.1)' : 'rgba(255,255,255,0.08)', padding: '4px 10px', borderRadius: '20px',
                border: tokenBudgetRemaining <= 0 ? '1px solid #ff6b6b' : 'none',
              }} title={tokenBudgetReset ? `Resets in ${tokenBudgetReset}s` : ''}>
                🪙 {Math.max(0, tokenBudgetRemaining).toLocaleString()} {tokenBudgetLimit ? `/ ${tokenBudgetLimit.toLocaleString()}` : ''} tokens
              </span>
            )}
            <span style={{
              color: 'rgba(255,255,255,0.6)', fontSize: '12px',
              background: 'rgba(255,255,255,0.08)', padding: '4px 10px', borderRadius: '20px',
            }}>
              👤 {authUserId}
            </span>
            <button
              id="logout-btn"
              onClick={handleLogout}
              style={{
                background: 'rgba(255,80,80,0.15)', border: '1px solid rgba(255,80,80,0.3)',
                color: 'rgba(255,120,120,0.9)', fontSize: '12px', padding: '4px 12px',
                borderRadius: '20px', cursor: 'pointer',
              }}
            >
              Sign out
            </button>
          </div>

          <div className="flex h-full w-full pt-[48px] sm:pt-0">
            {showSidebar ? (
              <div>
                <Chatbar
                  loading={messageIsStreaming}
                  conversations={conversations}
                  lightMode={lightMode}
                  selectedConversation={selectedConversation}
                  folders={folders.filter((folder) => folder.type === 'chat')}
                  onToggleLightMode={handleLightMode}
                  onCreateFolder={(name) => handleCreateFolder(name, 'chat')}
                  onDeleteFolder={handleDeleteFolder}
                  onUpdateFolder={handleUpdateFolder}
                  onNewConversation={handleNewConversation}
                  onSelectConversation={handleSelectConversation}
                  onDeleteConversation={handleDeleteConversation}
                  onUpdateConversation={handleUpdateConversation}
                  onClearConversations={handleClearConversations}
                  onExportConversations={handleExportData}
                  onImportConversations={handleImportConversations}
                />

                <button
                  className="fixed top-5 left-[270px] z-50 h-7 w-7 hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-[270px] sm:h-8 sm:w-8 sm:text-neutral-700"
                  onClick={handleToggleChatbar}
                >
                  <IconArrowBarLeft />
                </button>
                <div
                  onClick={handleToggleChatbar}
                  className="absolute top-0 left-0 z-10 h-full w-full bg-black opacity-70 sm:hidden"
                ></div>
              </div>
            ) : (
              <button
                className="fixed top-2.5 left-4 z-50 h-7 w-7 text-white hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-4 sm:h-8 sm:w-8 sm:text-neutral-700"
                onClick={handleToggleChatbar}
              >
                <IconArrowBarRight />
              </button>
            )}

            <div className="flex flex-1">
              <Chat
                conversation={selectedConversation}
                messageIsStreaming={messageIsStreaming}
                loading={loading}
                onSend={handleSend}
                onUpdateConversation={handleUpdateConversation}
                onEditMessage={handleEditMessage}
                stopConversationRef={stopConversationRef}
              />
            </div>
          </div>
        </main>
      )}
    </>
  );
};

export const getServerSideProps: GetServerSideProps = async ({ locale }) => {
  const defaultModelId = fallbackModelID;

  return {
    props: {
      defaultModelId,
      ...(await serverSideTranslations(locale ?? 'en', [
        'common',
        'chat',
        'sidebar',
        'markdown',
        'promptbar',
      ])),
    },
  };
};
