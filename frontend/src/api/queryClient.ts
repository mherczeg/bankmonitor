import { QueryClient } from '@tanstack/react-query'
import { shouldRetryQuery } from './retry'

/**
 * The single owner of server state. Every screen reads through it, and a
 * server-sent event does nothing to it but invalidate a key.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryQuery,

      // Declared rather than inherited: it is the designed compensation for the screens
      // outside the live-update scope. See docs/design-decisions/38-accounts-list-screen.md.
      refetchOnWindowFocus: true,

      // No `staleTime` on purpose — any non-zero value silently defeats the line above,
      // since a focus only refetches what is already stale.
    },
  },
})
