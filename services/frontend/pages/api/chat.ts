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
      }),
    });

    if (!gatewayRes.ok) {
      const errorText = await gatewayRes.text();
      return new Response(`Gateway error: ${errorText}`, { status: 500 });
    }

    const data = await gatewayRes.json();
    const answer = data.answer || '';

    // Create a readable stream that outputs the answer character-by-character
    // to simulate standard streaming behavior expected by the Chatbot UI
    const encoder = new TextEncoder();
    const customStream = new ReadableStream({
      async start(controller) {
        // Enqueue the whole answer
        const queue = encoder.encode(answer);
        controller.enqueue(queue);
        controller.close();
      },
    });

    return new Response(customStream);
  } catch (error) {
    console.error('Error in Next.js chat API route:', error);
    return new Response('Error communicating with backend gateway', { status: 500 });
  }
};

export default handler;
