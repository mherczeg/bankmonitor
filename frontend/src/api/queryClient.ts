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

    mutations: {
      // Declared for the reason above it is declared: the value happens to be the default,
      // and it is a designed property rather than one to inherit. A mutation here is a
      // `POST` that opens an Account or requests a Transfer, and only the Transfer carries
      // an Idempotency Key — so a silent re-send of the other one opens a second Account
      // nobody asked for. See docs/design-decisions/39-create-account-form.md.
      retry: false,
    },
  },
})
