import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/transfers/$transferId')({ component: TransferScreen })

function TransferScreen() {
  const { transferId } = Route.useParams()

  return (
    <>
      <h1 className="h3">Transfer {transferId}</h1>
      <p className="text-body-secondary">
        The live one — the only screen that holds an event-stream connection.
      </p>
    </>
  )
}
