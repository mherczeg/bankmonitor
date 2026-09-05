import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/transfers/')({ component: TransactionsScreen })

function TransactionsScreen() {
  return (
    <>
      <h1 className="h3">Transactions</h1>
      <p className="text-body-secondary">Every transfer, in every state.</p>
    </>
  )
}
