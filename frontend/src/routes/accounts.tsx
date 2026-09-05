import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/accounts')({ component: AccountsScreen })

function AccountsScreen() {
  return (
    <>
      <h1 className="h3">Accounts</h1>
      <p className="text-body-secondary">Every account, its balance and what it can spend.</p>
    </>
  )
}
