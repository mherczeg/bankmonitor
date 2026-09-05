import { QueryClient } from '@tanstack/react-query'
import { shouldRetryQuery } from './retry'

/**
 * The single owner of server state. Every screen reads through it, and a
 * server-sent event does nothing to it but invalidate a key.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: shouldRetryQuery },
  },
})
