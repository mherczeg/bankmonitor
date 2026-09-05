import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/transfers/new')({ component: NewTransferScreen })

function NewTransferScreen() {
  return (
    <>
      <h1 className="h3">New transfer</h1>
      <p className="text-body-secondary">
        Submitting navigates to the transfer, so pending state lives in the URL.
      </p>
    </>
  )
}
