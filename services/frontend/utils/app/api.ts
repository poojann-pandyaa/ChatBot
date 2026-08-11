// All chat requests go to the local Next.js API route which proxies to app-gateway.
// The plugin system has been removed — this project does not use external AI plugins.
export const getEndpoint = (): string => {
  return 'api/chat';
};
