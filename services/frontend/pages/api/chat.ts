import { ChatBody, Message } from '@/types/chat';

export const config = {
  runtime: 'edge',
};

const handler = async (req: Request): Promise<Response> => {
  try {
    const { messages, conversationId } = (await req.json()) as ChatBody;

    if (!messages || messages.length === 0) {
      return new Response('No messages found', { status: 400 });
    }

    const lastMessage = messages[messages.length - 1].content;
    const gatewayUrl = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';
    const activeSessionId = conversationId || 'default-session';

    console.log(`Forwarding chat request to gateway: ${gatewayUrl}/api/chat for session: ${activeSessionId}`);

    const gatewayRes = await fetch(`${gatewayUrl}/api/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        prompt: lastMessage,
        conversation_id: activeSessionId,
        debug: true, // Enable trace retrieval
        stream: true, // Request streaming from gateway
      }),
    });

    if (!gatewayRes.ok) {
      const errorText = await gatewayRes.text();
      return new Response(`Gateway error: ${errorText}`, { status: 500 });
    }

    return new Response(gatewayRes.body);
  } catch (error) {
    console.error('Error in Next.js chat API route:', error);
    return new Response('Error communicating with backend gateway', { status: 500 });
  }
};

export default handler;
