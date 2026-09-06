import { StrictMode, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'

import { invalidationsFor } from '../../src/api/events'
import { problemToMessage } from '../../src/api/problem'
import { queryClient } from '../../src/api/queryClient'
import { queryKeys } from '../../src/api/queryKeys'
import type { Account } from '../../src/api/types'
import { formatAmount } from '../../src/money'

/**
 * The page the smoke spec drives, and the smallest thing that can prove the harness works:
 * a query filed under a real key, a subscription wired the way the design says every
 * subscription is wired, and one figure on screen that changes only when the API's answer
 * changes.
 *
 * **It exists because no screen does yet.** The harness is ticket 37 and the screens are
 * 38 to 43, so proving the machinery before them needs a subject. The wiring below is
 * quoted verbatim from the event module's own documentation — the four lines ticket 42
 * will put on the Transfer page — so what this proves is not "the harness drives the page
 * I wrote for it" but "those four lines survive a real browser".
 *
 * It cannot reach production: `vite build` bundles the one `index.html` at the project
 * root, and this page is neither that file nor under `src/`. It should be deleted once the
 * Transfer page carries the same sequence for real.
 */

/** Ticket 30's endpoint. Nothing requests it here — the harness replaces `EventSource`. */
const TRANSFER_EVENTS = '/api/events/stream'

function Balances() {
  const accounts = useQuery({ queryKey: queryKeys.accounts(), queryFn: fetchAccounts })

  useEffect(() => {
    const source = new EventSource(TRANSFER_EVENTS)

    source.onmessage = (message) => {
      for (const key of invalidationsFor(message.data)) queryClient.invalidateQueries({ queryKey: key })
    }

    return () => source.close()
  }, [])

  if (accounts.isPending) return <p>Loading…</p>

  if (accounts.isError) return <p data-testid="failure">{problemToMessage(accounts.error).title}</p>

  return (
    <ul>
      {accounts.data.map((account) => (
        <li key={account.id}>
          <span data-testid={`balance-${account.id}`}>
            {formatAmount(account.balanceMinorUnits, account.currency)}
          </span>
        </li>
      ))}
    </ul>
  )
}

/**
 * The failure is thrown as the parsed body rather than as an `Error`, which is what both
 * modules downstream of it are written for: `problemToMessage` reads a URN off whatever it
 * is handed, and the retry rule reads the `status` a problem document carries.
 */
const fetchAccounts = async (): Promise<Account[]> => {
  const response = await fetch('/api/accounts')
  const body: unknown = await response.json()

  if (!response.ok) throw body

  return body as Account[]
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <Balances />
    </QueryClientProvider>
  </StrictMode>,
)
