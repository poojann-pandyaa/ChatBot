import { Message } from '@/types/chat';
import { IconCheck, IconCopy, IconEdit, IconUser, IconRobot, IconChevronDown, IconChevronRight, IconCpu, IconBolt, IconRefresh, IconMessage, IconArrowsShuffle, IconRoute } from '@tabler/icons-react';
import { useTranslation } from 'next-i18next';
import { FC, memo, useEffect, useRef, useState } from 'react';
import rehypeMathjax from 'rehype-mathjax';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { CodeBlock } from '../Markdown/CodeBlock';
import { MemoizedReactMarkdown } from '../Markdown/MemoizedReactMarkdown';

// --- Helper: format path_taken into display label ---
const formatPath = (path: string): string => {
  const map: Record<string, string> = {
    cache_hit: 'Cache Hit',
    simple_rag: 'Simple RAG',
    multi_step_rag: 'Multi-Step RAG',
    retry_rag: 'Retry RAG',
    unknown: 'Unknown',
  };
  return map[path] || path;
};

// --- Helper: quality score color class ---
const qualityColorClass = (score: number | null): string => {
  if (score === null) return 'quality-mid';
  if (score >= 0.3) return 'quality-good';
  if (score >= 0.15) return 'quality-mid';
  return 'quality-low';
};

// --- Helper: quality score label ---
const qualityLabel = (score: number | null): string => {
  if (score === null) return 'N/A';
  if (score >= 0.3) return 'High';
  if (score >= 0.15) return 'Medium';
  return 'Low';
};

interface Props {
  message: Message;
  messageIndex: number;
  onEditMessage: (message: Message, messageIndex: number) => void;
}

export const ChatMessage: FC<Props> = memo(
  ({ message, messageIndex, onEditMessage }) => {
    const { t } = useTranslation('chat');
    const [isEditing, setIsEditing] = useState<boolean>(false);
    const [isTyping, setIsTyping] = useState<boolean>(false);
    const [messageContent, setMessageContent] = useState(message.content);
    const [messagedCopied, setMessageCopied] = useState(false);
    const [showTrace, setShowTrace] = useState<boolean>(false);

    const textareaRef = useRef<HTMLTextAreaElement>(null);

    const toggleEditing = () => {
      setIsEditing(!isEditing);
    };

    const handleInputChange = (
      event: React.ChangeEvent<HTMLTextAreaElement>,
    ) => {
      setMessageContent(event.target.value);
      if (textareaRef.current) {
        textareaRef.current.style.height = 'inherit';
        textareaRef.current.style.height = `${textareaRef.current.scrollHeight}px`;
      }
    };

    const handleEditMessage = () => {
      if (message.content != messageContent) {
        onEditMessage({ ...message, content: messageContent }, messageIndex);
      }
      setIsEditing(false);
    };

    const handlePressEnter = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !isTyping && !e.shiftKey) {
        e.preventDefault();
        handleEditMessage();
      }
    };

    const copyOnClick = () => {
      if (!navigator.clipboard) return;

      navigator.clipboard.writeText(message.content).then(() => {
        setMessageCopied(true);
        setTimeout(() => {
          setMessageCopied(false);
        }, 2000);
      });
    };

    useEffect(() => {
      if (textareaRef.current) {
        textareaRef.current.style.height = 'inherit';
        textareaRef.current.style.height = `${textareaRef.current.scrollHeight}px`;
      }
    }, [isEditing]);

    const rd = message.trace?.router_decisions;

    return (
      <div
        className={`group px-4 py-4 md:py-6 flex w-full animate-fade-in-up ${
          message.role === 'assistant' ? 'justify-start' : 'justify-end'
        }`}
        style={{ overflowWrap: 'anywhere' }}
      >
        <div
          className={`relative flex gap-4 p-5 text-base shadow-md backdrop-blur-md w-full md:max-w-2xl lg:max-w-3xl xl:max-w-4xl ${
            message.role === 'assistant'
              ? 'bg-white/10 text-gray-800 dark:text-gray-100 border border-white/10 rounded-3xl rounded-tl-sm'
              : 'bg-gradient-to-r from-blue-600 to-indigo-600 text-white rounded-3xl rounded-tr-sm'
          }`}
        >
          {message.role === 'assistant' && (
            <div className="min-w-[40px] mt-1 font-bold">
              <IconRobot size={30} className="text-gray-200" />
            </div>
          )}

          <div className="prose mt-[-2px] w-full dark:prose-invert">
            {message.role === 'user' ? (
              <div className="flex w-full">
                {isEditing ? (
                  <div className="flex w-full flex-col">
                    <textarea
                      ref={textareaRef}
                      className="w-full resize-none whitespace-pre-wrap border-none dark:bg-[#343541]"
                      value={messageContent}
                      onChange={handleInputChange}
                      onKeyDown={handlePressEnter}
                      onCompositionStart={() => setIsTyping(true)}
                      onCompositionEnd={() => setIsTyping(false)}
                      style={{
                        fontFamily: 'inherit',
                        fontSize: 'inherit',
                        lineHeight: 'inherit',
                        padding: '0',
                        margin: '0',
                        overflow: 'hidden',
                      }}
                    />

                    <div className="mt-10 flex justify-center space-x-4">
                      <button
                        className="h-[40px] rounded-md bg-blue-500 px-4 py-1 text-sm font-medium text-white enabled:hover:bg-blue-600 disabled:opacity-50"
                        onClick={handleEditMessage}
                        disabled={messageContent.trim().length <= 0}
                      >
                        {t('Save & Submit')}
                      </button>
                      <button
                        className="h-[40px] rounded-md border border-neutral-300 px-4 py-1 text-sm font-medium text-neutral-700 hover:bg-neutral-100 dark:border-neutral-700 dark:text-neutral-300 dark:hover:bg-neutral-800"
                        onClick={() => {
                          setMessageContent(message.content);
                          setIsEditing(false);
                        }}
                      >
                        {t('Cancel')}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="prose whitespace-pre-wrap dark:prose-invert">
                    {message.content}
                  </div>
                )}

                {(window.innerWidth < 640 || !isEditing) && (
                  <button
                    className={`absolute text-white/50 hover:text-white/80 transition-colors focus:translate-x-0 opacity-0 group-hover:opacity-100 ${
                      window.innerWidth < 640
                        ? 'left-3 bottom-1'
                        : '-left-10 top-[26px]'
                    }
                    `}
                    onClick={toggleEditing}
                  >
                    <IconEdit size={20} />
                  </button>
                )}
              </div>
            ) : (
              <>
                <div
                  className={`absolute ${
                    window.innerWidth < 640
                      ? 'right-3 bottom-1'
                      : 'right-0 top-[26px] m-0'
                  }`}
                >
                  {messagedCopied ? (
                    <IconCheck
                      size={20}
                      className="text-green-500 dark:text-green-400"
                    />
                  ) : (
                    <button
                      className="text-gray-500 hover:text-gray-700 opacity-0 group-hover:opacity-100 transition-opacity dark:text-gray-400 dark:hover:text-gray-300"
                      onClick={copyOnClick}
                    >
                      <IconCopy size={20} />
                    </button>
                  )}
                </div>

                {/* ═══════════ Router Decision Badges ═══════════ */}
                {rd && (
                  <div className="flex flex-wrap items-center gap-1.5 mb-3 animate-fade-in">
                    {/* Path pill — always shown */}
                    <span
                      className="router-badge inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wide bg-indigo-500/15 text-indigo-300 border border-indigo-500/20 cursor-default"
                      title={rd.quality_score !== null ? `Quality Score: ${rd.quality_score.toFixed(3)}` : 'Quality Score: N/A'}
                    >
                      <IconRoute size={12} />
                      {formatPath(rd.path_taken)}
                    </span>

                    {/* Cache hit */}
                    {rd.cache_hit && (
                      <span
                        className="router-badge inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wide bg-emerald-500/15 text-emerald-300 border border-emerald-500/20 cursor-default animate-slide-in-left"
                        title="Response served from semantic cache"
                      >
                        <IconBolt size={12} />
                        Cached
                      </span>
                    )}

                    {/* Follow-up detected */}
                    {rd.is_followup && (
                      <span
                        className="router-badge inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wide bg-cyan-500/15 text-cyan-300 border border-cyan-500/20 cursor-default animate-slide-in-left"
                        title="This query was detected as a conversational follow-up"
                      >
                        <IconMessage size={12} />
                        Follow-up
                      </span>
                    )}

                    {/* Query rewritten */}
                    {rd.query_rewritten && (
                      <span
                        className="router-badge inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wide bg-violet-500/15 text-violet-300 border border-violet-500/20 cursor-default animate-slide-in-left"
                        title={`Original: "${rd.original_query}" → Rewritten: "${rd.rewritten_query}"`}
                      >
                        <IconArrowsShuffle size={12} />
                        Rewritten
                      </span>
                    )}

                    {/* Retrieval retried */}
                    {rd.retrieval_retried && (
                      <span
                        className="router-badge inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wide bg-amber-500/15 text-amber-300 border border-amber-500/20 cursor-default animate-slide-in-left"
                        title={`Reason: ${rd.retry_reason || 'low_relevance'}${rd.refined_query ? `, Refined to: "${rd.refined_query}"` : ''}`}
                      >
                        <IconRefresh size={12} />
                        Retried
                      </span>
                    )}
                  </div>
                )}

                {/* ═══════════ Message Content ═══════════ */}
                <MemoizedReactMarkdown
                  className="prose dark:prose-invert"
                  remarkPlugins={[remarkGfm, remarkMath]}
                  rehypePlugins={[rehypeMathjax]}
                  components={{
                    code({ node, inline, className, children, ...props }) {
                      const match = /language-(\w+)/.exec(className || '');

                      return !inline && match ? (
                        <CodeBlock
                          key={Math.random()}
                          language={match[1]}
                          value={String(children).replace(/\n$/, '')}
                          {...props}
                        />
                      ) : (
                        <code className={className} {...props}>
                          {children}
                        </code>
                      );
                    },
                    table({ children }) {
                      return (
                        <table className="border-collapse border border-black py-1 px-3 dark:border-white">
                          {children}
                        </table>
                      );
                    },
                    th({ children }) {
                      return (
                        <th className="break-words border border-black bg-gray-500 py-1 px-3 text-white dark:border-white">
                          {children}
                        </th>
                      );
                    },
                    td({ children }) {
                      return (
                        <td className="break-words border border-black py-1 px-3 dark:border-white">
                          {children}
                        </td>
                      );
                    },
                  }}
                >
                  {message.content}
                </MemoizedReactMarkdown>

                {/* ═══════════ Collapsible Trace Panel ═══════════ */}
                {message.trace && (
                  <div className="mt-4 border border-white/10 dark:border-neutral-800 rounded-xl overflow-hidden bg-neutral-950/20 dark:bg-black/20">
                    <button
                      onClick={() => setShowTrace(!showTrace)}
                      className="flex items-center justify-between w-full px-4 py-3 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-neutral-800/30 transition-colors"
                    >
                      <div className="flex items-center gap-2">
                        <IconCpu size={18} className="text-blue-400 animate-pulse" />
                        <span>RAG Execution Trace ({message.trace.reasoning_type})</span>
                      </div>
                      {showTrace ? (
                        <IconChevronDown size={18} className="text-gray-400" />
                      ) : (
                        <IconChevronRight size={18} className="text-gray-400" />
                      )}
                    </button>

                    {showTrace && (
                      <div className="px-4 pb-4 pt-2 border-t border-white/5 dark:border-neutral-900 text-xs space-y-4 animate-fade-in">

                        {/* ── Router Decisions Section ── */}
                        {rd && (
                          <div className="space-y-3 p-3 rounded-lg bg-white/5 dark:bg-neutral-900/40 border border-white/5">
                            <span className="text-[10px] font-bold tracking-wider text-gray-500 uppercase flex items-center gap-1.5">
                              <IconRoute size={13} className="text-indigo-400" />
                              Router Decisions
                            </span>

                            {/* Path + Quality row */}
                            <div className="flex items-center justify-between">
                              <div className="flex items-center gap-2">
                                <span className="text-[10px] text-gray-500">Path:</span>
                                <span className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase border ${
                                  rd.path_taken === 'cache_hit'
                                    ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                                    : rd.path_taken === 'simple_rag'
                                    ? 'bg-blue-500/10 text-blue-400 border-blue-500/20'
                                    : rd.path_taken === 'multi_step_rag'
                                    ? 'bg-violet-500/10 text-violet-400 border-violet-500/20'
                                    : 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                                }`}>
                                  {formatPath(rd.path_taken)}
                                </span>
                              </div>
                              {rd.quality_score !== null && (
                                <span className="text-[10px] font-mono text-gray-400">
                                  Quality: {rd.quality_score.toFixed(3)} ({qualityLabel(rd.quality_score)})
                                </span>
                              )}
                            </div>

                            {/* Quality score bar */}
                            {rd.quality_score !== null && (
                              <div className="quality-bar">
                                <div
                                  className={`quality-bar-fill ${qualityColorClass(rd.quality_score)}`}
                                  style={{ width: `${Math.min(rd.quality_score * 100, 100)}%` }}
                                />
                              </div>
                            )}

                            {/* Follow-up + Rewrite flow */}
                            <div className="space-y-1.5 text-gray-400">
                              <div className="flex items-center gap-2">
                                <span className="text-[10px] text-gray-500 w-[70px]">Follow-up:</span>
                                <span className={`text-[10px] font-medium ${rd.is_followup ? 'text-cyan-400' : 'text-gray-500'}`}>
                                  {rd.is_followup ? 'Yes' : 'No'}
                                </span>
                              </div>

                              {rd.query_rewritten && (
                                <>
                                  <div className="flex items-start gap-2">
                                    <span className="text-[10px] text-gray-500 w-[70px] shrink-0">Original:</span>
                                    <span className="text-[10px] text-gray-300 font-mono bg-black/20 px-1.5 py-0.5 rounded break-all">
                                      {rd.original_query}
                                    </span>
                                  </div>
                                  <div className="flex items-center justify-center text-[10px] text-violet-400">
                                    ↓ rewritten
                                  </div>
                                  <div className="flex items-start gap-2">
                                    <span className="text-[10px] text-gray-500 w-[70px] shrink-0">Rewritten:</span>
                                    <span className="text-[10px] text-violet-300 font-mono bg-violet-500/10 px-1.5 py-0.5 rounded break-all">
                                      {rd.rewritten_query}
                                    </span>
                                  </div>
                                </>
                              )}

                              {rd.retrieval_retried && (
                                <>
                                  <div className="flex items-center gap-2 mt-1">
                                    <span className="text-[10px] text-gray-500 w-[70px]">Retry:</span>
                                    <span className="text-[10px] font-medium text-amber-400">
                                      Yes — {rd.retry_reason || 'low_relevance'}
                                    </span>
                                  </div>
                                  {rd.refined_query && (
                                    <div className="flex items-start gap-2">
                                      <span className="text-[10px] text-gray-500 w-[70px] shrink-0">Refined:</span>
                                      <span className="text-[10px] text-amber-300 font-mono bg-amber-500/10 px-1.5 py-0.5 rounded break-all">
                                        {rd.refined_query}
                                      </span>
                                    </div>
                                  )}
                                </>
                              )}

                              <div className="flex items-center gap-2">
                                <span className="text-[10px] text-gray-500 w-[70px]">Cache:</span>
                                <span className={`text-[10px] font-medium ${rd.cache_hit ? 'text-emerald-400' : 'text-gray-500'}`}>
                                  {rd.cache_hit ? 'Hit ⚡' : 'Miss'}
                                </span>
                              </div>
                            </div>
                          </div>
                        )}

                        {/* ── Classification Info ── */}
                        <div className="space-y-1">
                          <span className="text-[10px] font-bold tracking-wider text-gray-500 uppercase">Reasoning Classification</span>
                          <div className="flex items-center gap-2">
                            <span
                              className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase border ${
                                message.trace.reasoning_type === 'commonsense'
                                  ? 'bg-green-500/10 text-green-400 border-green-500/20'
                                  : message.trace.reasoning_type === 'adaptive'
                                  ? 'bg-blue-500/10 text-blue-400 border-blue-500/20'
                                  : 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                              }`}
                            >
                              {message.trace.reasoning_type}
                            </span>
                            <span className="text-gray-400 dark:text-gray-500">
                              Intent-based query execution routing
                            </span>
                          </div>
                        </div>

                        {/* ── Sub-questions ── */}
                        {message.trace.sub_questions && message.trace.sub_questions.length > 0 && (
                          <div className="space-y-1.5">
                            <span className="text-[10px] font-bold tracking-wider text-gray-500 uppercase">Generated Sub-Questions</span>
                            <ul className="list-disc pl-4 space-y-1 text-gray-600 dark:text-gray-400">
                              {message.trace.sub_questions.map((q, idx) => (
                                <li key={idx}>{q}</li>
                              ))}
                            </ul>
                          </div>
                        )}

                        {/* ── Retrieved Sources ── */}
                        {message.trace.sources && message.trace.sources.length > 0 ? (
                          <div className="space-y-2">
                            <span className="text-[10px] font-bold tracking-wider text-gray-500 uppercase">Reranked Sources ({message.trace.sources.length})</span>
                            <div className="space-y-2 max-h-60 overflow-y-auto pr-1 custom-scrollbar trace-scroll">
                              {message.trace.sources.map((src, idx) => (
                                <div
                                  key={idx}
                                  className="p-2.5 rounded-lg border border-white/5 bg-white/5 dark:bg-neutral-900/50 hover:bg-white/10 dark:hover:bg-neutral-900 transition-colors space-y-1.5"
                                >
                                  <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-2 text-[10px] text-gray-500 font-medium">
                                      <span className="text-blue-400 font-semibold">{src.domain || 'StackOverflow'}</span>
                                      <span>•</span>
                                      <span>ID: {src.question_id || src.chunk_id}</span>
                                      {src.is_accepted && (
                                        <>
                                          <span>•</span>
                                          <span className="text-green-400 bg-green-400/10 px-1.5 py-0.2 rounded text-[9px]">Accepted Answer</span>
                                        </>
                                      )}
                                    </div>
                                    <span className="text-[10px] font-mono text-gray-400 bg-neutral-800 px-1.5 py-0.5 rounded">
                                      Score: {src.score.toFixed(3)}
                                    </span>
                                  </div>
                                  <p className="text-gray-700 dark:text-gray-300 leading-relaxed font-sans select-all whitespace-pre-wrap max-h-24 overflow-y-auto text-[11px] bg-black/10 p-1.5 rounded">
                                    {src.chunk_text}
                                  </p>
                                </div>
                              ))}
                            </div>
                          </div>
                        ) : (
                          <div className="text-gray-500 italic">No external sources retrieved.</div>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    );
  },
);
ChatMessage.displayName = 'ChatMessage';
