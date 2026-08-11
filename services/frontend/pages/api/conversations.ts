import type { NextApiRequest, NextApiResponse } from 'next';

const GATEWAY_URL = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';

/**
 * Proxy /api/conversations → gateway.
 * GET: list all conversations for the authenticated user.
 * DELETE: clear all conversations.
 */
export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  const authHeader = req.headers['authorization'] || '';

  try {
    const response = await fetch(`${GATEWAY_URL}/api/conversations`, {
      method: req.method,
      headers: {
        'Content-Type': 'application/json',
        ...(authHeader ? { 'Authorization': authHeader as string } : {}),
      },
    });

    if (response.status === 204) {
      return res.status(204).end();
    }

    const data = await response.json().catch(() => null);
    return res.status(response.status).json(data ?? {});
  } catch (error) {
    console.error('Conversations proxy error:', error);
    return res.status(500).json({ error: 'Failed to reach backend' });
  }
}
