import { anAccount, aProblem, expect, refocus, setVisibility, test } from '../harness/test'

/**
 * The Accounts screen in a real browser: the list, an empty service, a refusal an operator
 * can act on and one they cannot, and the refetch that stands in for the events this screen
 * never gets.
 */

const ACCOUNTS = '/accounts'

const GET_ACCOUNTS = 'GET /api/accounts'

test('every Account is listed with its three figures in the form its own Currency is written in', async ({
  api,
  page,
}) => {
  // The HUF Account has zero decimal places, so a screen that divided by a hundred whatever
  // the Currency reads correctly on the EUR row and is visibly wrong on this one.
  api.accounts([
    anAccount({ id: 1, currency: 'EUR', balanceMinorUnits: 100_50, reservedAmountMinorUnits: 25_00 }),
    anAccount({ id: 2, currency: 'HUF', balanceMinorUnits: 1_000_000, reservedAmountMinorUnits: 0 }),
  ])

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('account-1')).toBeVisible()
  await expect(page.getByTestId('currency-1')).toHaveText('EUR')
  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
  await expect(page.getByTestId('reserved-1')).toHaveText('25.00')
  await expect(page.getByTestId('available-1')).toHaveText('75.50')

  await expect(page.getByTestId('account-2')).toBeVisible()
  await expect(page.getByTestId('currency-2')).toHaveText('HUF')
  await expect(page.getByTestId('balance-2')).toHaveText('1000000')
  await expect(page.getByTestId('reserved-2')).toHaveText('0')
  await expect(page.getByTestId('available-2')).toHaveText('1000000')

  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(1)
})

/**
 * The Accounts arrive out of ID order on purpose: against an ascending fixture, a screen
 * that sorted by ID would render exactly what one keeping the endpoint's order renders, and
 * that order is the listing's contract.
 */
test('the rows are in the order the endpoint sent them, not an order the screen chose', async ({
  api,
  page,
}) => {
  api.accounts([anAccount({ id: 2 }), anAccount({ id: 1 })])

  await page.goto(ACCOUNTS)

  await expect(page.locator('tbody tr > th')).toHaveText(['2', '1'])
})

/**
 * An Account the backend could never produce is the only one that tells the two
 * implementations apart: where the Available Balance is derived, a screen showing the
 * server's figure and one subtracting client-side render the same thing.
 */
test('the Available Balance is the figure the service sent, not a subtraction the screen did', async ({
  api,
  page,
}) => {
  api.accounts([
    anAccount({
      id: 1,
      balanceMinorUnits: 100_50,
      reservedAmountMinorUnits: 25_00,
      availableBalanceMinorUnits: 10_00,
    }),
  ])

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('available-1')).toHaveText('10.00')
  await expect(page.getByTestId('available-1')).not.toHaveText('75.50')
})

test('a service holding no Accounts says so instead of showing an empty table', async ({
  api,
  page,
}) => {
  api.accounts([])

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('accounts-empty')).toBeVisible()
  await expect(page.getByTestId('accounts-table')).not.toBeAttached()
})

/**
 * There is no `goto` between the two halves on purpose — the visibility override is
 * installed into the page and a navigation would drop it, leaving the second half asserting
 * against a page that never went away.
 */
test('a tab that went away and came back reloads the list, and one that stayed away does not', async ({
  api,
  page,
}) => {
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 100_50 })])

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(1)

  // Truth changes while nobody is looking, which is the case this screen has no event for.
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 75_50 })])

  await setVisibility(page, 'hidden')

  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(1)

  await setVisibility(page, 'visible')

  await expect(page.getByTestId('balance-1')).toHaveText('75.50')
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(2)

  // A second round trip, because the focus manager does not dedupe: an operator who leaves
  // and returns twice gets two fresh answers rather than one.
  api.accounts([anAccount({ id: 1, balanceMinorUnits: 50_25 })])

  await refocus(page)

  await expect(page.getByTestId('balance-1')).toHaveText('50.25')
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(3)
})

test('a refusal is shown in words an operator can act on, and asking again clears it', async ({
  api,
  page,
}) => {
  api.refuses('GET', '/api/accounts', aProblem('urn:problem:client-error', { status: 400 }))

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('accounts-error-title')).toHaveText('The service refused the request')

  // The problem module's wording rather than the document's own `detail`, which is written
  // for whoever reads the response and names nothing an operator can do.
  await expect(page.getByTestId('accounts-error-body')).toHaveText(
    'It did not say more precisely why, so there is no advice to give beyond one more attempt. If it comes back the same way, it needs reporting.',
  )
  await expect(page.getByTestId('accounts-error-body')).not.toHaveText(
    'The request was understood and could not be acted on.',
  )

  // One request and no more: a `4xx` is an answer already, so the query client did not
  // quietly spend the operator's two retries before showing them anything.
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(1)

  api.accounts([anAccount({ id: 1, balanceMinorUnits: 100_50 })])

  await page.getByTestId('accounts-retry').click()

  await expect(page.getByTestId('accounts-table')).toBeVisible()
  await expect(page.getByTestId('balance-1')).toHaveText('100.50')
  expect(api.timesAsked('GET', '/api/accounts'), GET_ACCOUNTS).toBe(2)
})

test('a refusal that asking again could never clear offers no button that would', async ({
  api,
  page,
}) => {
  api.refuses('GET', '/api/accounts', aProblem('urn:problem:not-found', { status: 404 }))

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('accounts-error-title')).toHaveText('That is not there')
  await expect(page.getByTestId('accounts-retry')).not.toBeAttached()
})
