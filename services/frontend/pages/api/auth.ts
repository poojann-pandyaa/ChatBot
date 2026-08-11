import type { NextApiRequest, NextApiResponse } from 'next';

const GATEWAY_URL = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';

/**
 * Proxy login requests to the app-gateway.
 * POST /api/auth/login → forwards body to gateway, returns token.
 */
export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  try {
    const response = await fetch(`${GATEWAY_URL}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req.body),
    });

    const data = await response.json();
    return res.status(response.status).json(data);
  } catch (error) {
    console.error('Login proxy error:', error);
    return res.status(500).json({ error: 'Failed to reach authentication service' });
  }
}
