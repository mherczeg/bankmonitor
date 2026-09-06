import { anAccount, aProblem, aTransfer, expect, test } from '../harness/test'

/**
 * Requesting a Transfer in a real browser: the round trip that lands on the Transfer's own
 * page, the two rules that never leave the browser, and the Idempotency Key an operator
 * can neither see nor influence.
 *
 * The last of those is why this spec reads headers. Whether two attempts went out under
 * one key is a fact about the wire, and a screen that got it wrong would look exactly like
 * one that got it right.
 */

const NEW_TRANSFER = '/transfers/new'

const POST_TRANSFERS = 'POST /api/transfers'

const A_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/** Two Accounts in one Currency to move money between, and a third in another. */
const EUR_SOURCE = anAccount({ id: 1, currency: 'EUR', balanceMinorUnits: 500_00 })

const EUR_DESTINATION = anAccount({ id: 2, currency: 'EUR', balanceMinorUnits: 0 })

const HUF_ACCOUNT = anAccount({ id: 3, currency: 'HUF', balanceMinorUnits: 250_000 })

const THREE_ACCOUNTS = [EUR_SOURCE, EUR_DESTINATION, HUF_ACCOUNT]

const keySent = (headers: Readonly<Record<string, string>>): string | undefined =>
  headers['x-idempotency-key']

test('a filled-in form requests the Transfer and lands on its own page', async ({ api, page }) => {
  api.accounts(THREE_ACCOUNTS)
  api.requestsTransfer(aTransfer({ id: 7, fromAccountId: 1, toAccountId: 2 }))

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('100.50')
  await page.getByTestId('new-transfer-submit').click()

  // The pending state now lives in the URL, which is the frontend half of the
  // asynchronous lifecycle: a refresh here shows where the Transfer got to.
  await expect(page).toHaveURL(/\/transfers\/7$/)
  await expect(page.getByRole('heading', { name: 'Transfer 7' })).toBeVisible()

  expect(api.bodySent('POST', '/api/transfers'), POST_TRANSFERS).toEqual({
    fromAccountId: 1,
    toAccountId: 2,
    amountMinorUnits: 10_050,
  })
})

/**
 * The key is minted by `idempotency.ts` and is version 4 because that is all the backend
 * accepts — a well-formed UUID of any other version is a `400`, and nothing on the screen
 * would say so.
 */
test('the request carries a version 4 Idempotency Key under the header the backend reads', async ({
  api,
  page,
}) => {
  api.accounts(THREE_ACCOUNTS)
  api.requestsTransfer(aTransfer({ id: 7 }))

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('25.00')
  await page.getByTestId('new-transfer-submit').click()

  await expect(page).toHaveURL(/\/transfers\/7$/)

  expect(keySent(api.headersSent('POST', '/api/transfers')), POST_TRANSFERS).toMatch(A_UUID_V4)
})

/**
 * The amount's rule follows the *source* Account, and this is the whole of why the schema
 * is built over the accounts list: the same untouched string is an amount out of the EUR
 * Account and fillér that do not exist out of the HUF one.
 */
test('switching the source Account re-judges the amount already typed, and re-denominates it', async ({
  api,
  page,
}) => {
  api.accounts(THREE_ACCOUNTS)
  api.requestsTransfer(aTransfer({ id: 7 }))

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('100.50')

  await expect(page.getByTestId('new-transfer-denomination')).toHaveText('EUR')
  await expect(page.getByTestId('new-transfer-amount-error')).not.toBeAttached()

  // Nothing is retyped. The source moves, and the string that was an amount stops being one.
  await page.getByTestId('new-transfer-source').selectOption('3')

  await expect(page.getByTestId('new-transfer-denomination')).toHaveText('HUF')
  await expect(page.getByTestId('new-transfer-amount-error')).toHaveText(
    'HUF is written without decimal places, so its amounts are whole numbers.',
  )

  await page.getByTestId('new-transfer-source').selectOption('1')

  await expect(page.getByTestId('new-transfer-amount-error')).not.toBeAttached()

  await page.getByTestId('new-transfer-submit').click()

  await expect(page).toHaveURL(/\/transfers\/7$/)
  expect(api.bodySent('POST', '/api/transfers'), POST_TRANSFERS).toEqual({
    fromAccountId: 1,
    toAccountId: 2,
    amountMinorUnits: 10_050,
  })
})

/**
 * The refusal lands on the destination because that is the side an operator changes: the
 * money is already leaving the Account they chose first.
 */
test('a Transfer to the Account it leaves is refused without a request being sent', async ({
  api,
  page,
}) => {
  api.accounts(THREE_ACCOUNTS)

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('1')
  await page.getByTestId('new-transfer-amount').fill('25.00')
  await page.getByTestId('new-transfer-submit').click()

  await expect(page.getByTestId('new-transfer-destination-error')).toHaveText(
    'A Transfer moves money between two different Accounts.',
  )
  await expect(page).toHaveURL(new RegExp(`${NEW_TRANSFER}$`))
  expect(api.timesAsked('POST', '/api/transfers'), POST_TRANSFERS).toBe(0)
})

/**
 * Ticket 39 moved this rule out of `parseAmount`, which now reads zero as the amount it
 * is so that an Account can be opened with nothing in it. This form is where it landed.
 */
test('an amount of zero is refused without a request being sent', async ({ api, page }) => {
  api.accounts(THREE_ACCOUNTS)

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('0')
  await page.getByTestId('new-transfer-submit').click()

  await expect(page.getByTestId('new-transfer-amount-error')).toHaveText(
    'A Transfer has to move more than nothing.',
  )
  expect(api.timesAsked('POST', '/api/transfers'), POST_TRANSFERS).toBe(0)
})

test('pressing submit on an untouched form asks for all three at once and sends nothing', async ({
  api,
  page,
}) => {
  api.accounts(THREE_ACCOUNTS)

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-submit').click()

  await expect(page.getByTestId('new-transfer-source-error')).toHaveText(
    'Choose the Account the money leaves.',
  )
  await expect(page.getByTestId('new-transfer-destination-error')).toHaveText(
    'Choose the Account the money arrives at.',
  )
  await expect(page.getByTestId('new-transfer-amount-error')).toHaveText('Enter the amount to transfer.')
  expect(api.timesAsked('POST', '/api/transfers'), POST_TRANSFERS).toBe(0)
})

/**
 * The one refusal of the four that advises trying again: an Available Balance moves on its
 * own as other Transfers settle, so the same request unchanged can go through later. The
 * wording is `problem.ts`'s and never the document's own `detail`.
 */
test('an insufficient Available Balance is reported readably, with a retry offered', async ({
  api,
  page,
}) => {
  api.accounts(THREE_ACCOUNTS)
  api.refuses('POST', '/api/transfers', aProblem('urn:problem:insufficient-funds'))

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('900.00')
  await page.getByTestId('new-transfer-submit').click()

  await expect(page.getByTestId('new-transfer-error-title')).toHaveText(
    'The source Account does not have the funds',
  )
  await expect(page.getByTestId('new-transfer-error-body')).not.toHaveText(
    'The request was understood and could not be acted on.',
  )
  await expect(page.getByTestId('new-transfer-retry')).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${NEW_TRANSFER}$`))
})

/**
 * The browser-observable half of the Idempotency Key rules, and the reason this spec reads
 * headers at all.
 *
 * A retry is the same intent, so it goes out under the key its first attempt used — that
 * is what lets the backend tell it apart from a second Transfer. A corrected amount is a
 * different intent, so it does not, or the operator would be resubmitting under a key
 * already spent on the amount that was refused and would meet a refusal that can never
 * clear.
 */
test('a retry keeps the key and a corrected amount takes a new one', async ({ api, page }) => {
  api.accounts(THREE_ACCOUNTS)
  api.refuses('POST', '/api/transfers', aProblem('urn:problem:insufficient-funds'))

  await page.goto(NEW_TRANSFER)

  await page.getByTestId('new-transfer-source').selectOption('1')
  await page.getByTestId('new-transfer-destination').selectOption('2')
  await page.getByTestId('new-transfer-amount').fill('900.00')
  await page.getByTestId('new-transfer-submit').click()

  await expect(page.getByTestId('new-transfer-retry')).toBeVisible()

  const firstAttempt = keySent(api.headersSent('POST', '/api/transfers'))

  expect(firstAttempt, POST_TRANSFERS).toMatch(A_UUID_V4)

  await page.getByTestId('new-transfer-retry').click()

  await expect.poll(() => api.timesAsked('POST', '/api/transfers'), POST_TRANSFERS).toBe(2)

  expect(keySent(api.headersSent('POST', '/api/transfers')), POST_TRANSFERS).toBe(firstAttempt)

  // A failure alone never resets the key; correcting the payload does, because that is a
  // different thing to have meant.
  await page.getByTestId('new-transfer-amount').fill('9.00')

  await expect(page.getByTestId('new-transfer-error')).not.toBeAttached()

  await page.getByTestId('new-transfer-submit').click()

  await expect.poll(() => api.timesAsked('POST', '/api/transfers'), POST_TRANSFERS).toBe(3)

  expect(keySent(api.headersSent('POST', '/api/transfers')), POST_TRANSFERS).not.toBe(firstAttempt)
})

/** There is nothing to choose between with one Account, and no form worth drawing over it. */
test('one Account is not enough to draw the form over', async ({ api, page }) => {
  api.accounts([EUR_SOURCE])

  await page.goto(NEW_TRANSFER)

  await expect(page.getByTestId('new-transfer-too-few-accounts')).toBeVisible()
  await expect(page.getByTestId('new-transfer-form')).not.toBeAttached()
})
