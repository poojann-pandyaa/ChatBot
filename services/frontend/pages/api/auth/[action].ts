import type { NextApiRequest, NextApiResponse } from 'next';

const GATEWAY_URL = process.env.APP_GATEWAY_URL || 'http://app-gateway:8080';

export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const { action } = req.query;
  const validActions = ['login', 'register', 'refresh', 'logout'];

  if (!action || typeof action !== 'string' || !validActions.includes(action)) {
    return res.status(404).json({ error: 'Not found' });
  }

  try {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    
    // Pass the refresh_token cookie if it exists
    if (req.headers.cookie) {
      headers['Cookie'] = req.headers.cookie;
    }

    const response = await fetch(`${GATEWAY_URL}/api/auth/${action}`, {
      method: 'POST',
      headers,
      body: req.body && Object.keys(req.body).length > 0 ? JSON.stringify(req.body) : undefined,
    });

    // Pass back any Set-Cookie headers from the gateway (HttpOnly refresh token)
    const setCookie = response.headers.get('set-cookie');
    if (setCookie) {
      res.setHeader('Set-Cookie', setCookie);
    }

    if (response.status === 204) {
      return res.status(204).end();
    }

    const data = await response.json();
    return res.status(response.status).json(data);
  } catch (error) {
    console.error(`Auth proxy error for ${action}:`, error);
    return res.status(500).json({ error: 'Failed to reach authentication service' });
  }
}
