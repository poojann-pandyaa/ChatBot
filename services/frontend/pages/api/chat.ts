import { ChatBody, Message } from '@/types/chat';
import { NextRequest } from 'next/server';

export const config = {
  runtime: 'edge',
};

const handler = async (req: NextRequest): Promise<Response> => {
  try {
    const { messages, conversationId } = (await req.json()) as ChatBody;

    if (!messages || messages.length === 0) {
      return new Response('No messages found', { status: 400 });
    }

    const lastMessage = messages[messages.length - 1].content;
    const gatewayUrl = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';
    const activeSessionId = conversationId || 'default-session';

    // Forward the JWT Authorization header from the browser request to the gateway
    const authHeader = req.headers.get('authorization') || '';

    console.log(`Forwarding chat request to gateway: ${gatewayUrl}/api/chat for session: ${activeSessionId}`);

    const gatewayRes = await fetch(`${gatewayUrl}/api/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader ? { 'Authorization': authHeader } : {}),
      },
      body: JSON.stringify({
        prompt: lastMessage,
        conversation_id: activeSessionId,
        debug: true,
        stream: true,
      }),
    });

    if (!gatewayRes.ok) {
      const errorText = await gatewayRes.text();
      // Forward 401/403/429 status codes directly so the frontend can react
      const status = [401, 403, 429].includes(gatewayRes.status) ? gatewayRes.status : 500;
      return new Response(`Gateway error: ${errorText}`, { status });
    }

    return new Response(gatewayRes.body);
  } catch (error) {
    console.error('Error in Next.js chat API route:', error);
    return new Response('Error communicating with backend gateway', { status: 500 });
  }
};

export default handler;
