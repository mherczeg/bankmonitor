import { aProblem, anAccount, eventStream, expect, test } from '../harness/test'

/**
 * The one spec that proves the harness itself: a real browser, a scripted network and a
 * dispatched event driving a re-render, with nothing waiting on a clock.
 *
 * Later screens carry their own specs against their own pages. This one drives
 * [the subject](./subject.tsx), because the machinery landed before the screens did.
 */

const SUBJECT = '/e2e/smoke/index.html'

const ACCOUNTS = 'GET /api/accounts'

const settled = (transferId: number) => JSON.stringify({ type: 'TRANSFER_SETTLED', transferId })

test('a dispatched event re-renders the page from the API answer that changed under it', async ({
  api,
  page,
}) => {
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 100_50, reservedAmountMinorUnits: 25_00 })])

  await page.goto(SUBJECT)

  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
  expect(api.timesAsked('GET', '/api/accounts'), ACCOUNTS).toBe(1)

  // Truth changes before the event and not with it, exactly as it does in the backend: the
  // Transfer settled, so the reservation is spent and the balance is down by it.
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 75_50 })])

  const stream = await eventStream(page)
  await stream.message(settled(7))

  await expect(page.getByTestId('balance-1')).toHaveText('75.50')

  // The re-render came from a refetch the event asked for, and not from a page that was
  // refetching anyway — which is the difference between this spec proving the design and
  // proving nothing.
  expect(api.timesAsked('GET', '/api/accounts'), ACCOUNTS).toBe(2)
})

test('a subscription that dropped and came back still delivers', async ({ api, page }) => {
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 100_50 })])

  await page.goto(SUBJECT)
  await expect(page.getByTestId('balance-1')).toHaveText('100.50')

  const stream = await eventStream(page)

  await stream.drop()
  expect(await stream.state()).toBe('connecting')

  await stream.reopen()
  expect(await stream.state()).toBe('open')

  api.accounts([anAccount({ id: 1, balanceMinorUnits: 75_50 })])
  await stream.message(settled(7))

  await expect(page.getByTestId('balance-1')).toHaveText('75.50')
})

test('a scripted refusal reaches the screen as the problem document it was given', async ({
  api,
  page,
}) => {
  api.refuses('GET', '/api/accounts', aProblem('urn:problem:not-found', { status: 404 }))

  await page.goto(SUBJECT)

  await expect(page.getByTestId('failure')).toHaveText('That is not there')
})
