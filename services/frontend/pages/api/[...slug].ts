import type { NextApiRequest, NextApiResponse } from 'next';

const GATEWAY_URL = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';

/**
 * Proxy /api/conversation/[id] and /api/conversation/[id]/rename → gateway.
 * DELETE /api/conversation/[id]: delete one conversation.
 * PATCH  /api/conversation/[id]/rename: rename a conversation.
 * GET    /api/history/[id]: get full message history.
 */
export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  const { slug } = req.query;
  const segments = Array.isArray(slug) ? slug : [slug as string];
  const authHeader = req.headers['authorization'] || '';

  // Route: /api/history/[id]  (GET)
  if (segments[0] === 'history' && segments.length === 2) {
    const id = segments[1];
    const response = await fetch(`${GATEWAY_URL}/api/history/${id}`, {
      method: 'GET',
      headers: { ...(authHeader ? { 'Authorization': authHeader as string } : {}) },
    });
    const data = await response.json().catch(() => ({}));
    return res.status(response.status).json(data);
  }

  // Route: /api/conversation/[id]/rename  (PATCH)
  if (segments[0] === 'conversation' && segments.length === 3 && segments[2] === 'rename') {
    const id = segments[1];
    const response = await fetch(`${GATEWAY_URL}/api/conversation/${id}/rename`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader ? { 'Authorization': authHeader as string } : {}),
      },
      body: JSON.stringify(req.body),
    });
    const data = await response.json().catch(() => ({}));
    return res.status(response.status).json(data);
  }

  // Route: /api/conversation/[id]  (DELETE)
  if (segments[0] === 'conversation' && segments.length === 2) {
    const id = segments[1];
    const response = await fetch(`${GATEWAY_URL}/api/conversation/${id}`, {
      method: 'DELETE',
      headers: { ...(authHeader ? { 'Authorization': authHeader as string } : {}) },
    });
    if (response.status === 204) return res.status(204).end();
    const data = await response.json().catch(() => ({}));
    return res.status(response.status).json(data);
  }

  return res.status(404).json({ error: 'Not found' });
}
