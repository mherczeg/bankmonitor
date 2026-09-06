import { anAccount, aProblem, expect, test } from '../harness/test'

/**
 * Opening an Account in a real browser: the round trip that puts a row in the list, the
 * rule that never leaves the browser, and the refusal that comes back naming a field.
 *
 * The three sit together because they are the three places the same amount can be got
 * wrong — before it is sent, on the wire, and after the service has looked at it.
 */

const ACCOUNTS = '/accounts'

const POST_ACCOUNTS = 'POST /api/accounts'

test('a filled-in form opens the Account, and the list it joins shows it', async ({ api, page }) => {
  api.accounts([])
  api.opensAccount(anAccount({ id: 7, currency: 'EUR', balanceMinorUnits: 100_50 }))

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('accounts-empty')).toBeVisible()

  await page.getByTestId('new-account-currency').selectOption('EUR')
  await page.getByTestId('new-account-opening-balance').fill('100.50')

  // The list the refetch will find, scripted before the press so the answer is in place
  // by the time the invalidation asks for it.
  api.accounts([anAccount({ id: 7, currency: 'EUR', balanceMinorUnits: 100_50 })])

  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-created')).toHaveText('Account 7 is open.')
  await expect(page.getByTestId('balance-7')).toHaveText('100.50')
  await expect(page.getByTestId('accounts-empty')).not.toBeAttached()

  // The row came from the endpoint rather than from anything the form knew, which is the
  // whole of why the query is invalidated instead of written into.
  expect(api.timesAsked('GET', '/api/accounts')).toBe(2)

  // The form is empty again, ready for the next Account rather than holding the last one.
  await expect(page.getByTestId('new-account-opening-balance')).toHaveValue('')
})

/**
 * The conversion is the one thing no rendering can prove: a screen showing `100.50`
 * correctly says nothing about whether `100.50` or `10050` left the browser, and only the
 * second is what the backend's `long` counts.
 */
test('the amount leaves the browser as a count of Minor Units, not as what was typed', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.opensAccount(anAccount({ id: 7, currency: 'EUR', balanceMinorUnits: 100_50 }))

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-currency').selectOption('EUR')
  await page.getByTestId('new-account-opening-balance').fill('100.50')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-created')).toBeVisible()

  expect(api.bodySent('POST', '/api/accounts'), POST_ACCOUNTS).toEqual({
    currency: 'EUR',
    openingBalanceMinorUnits: 10_050,
  })
})

test('the forint sends the whole number it is written as, with no fillér invented for it', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.opensAccount(anAccount({ id: 8, currency: 'HUF', balanceMinorUnits: 10_050 }))

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-currency').selectOption('HUF')
  await page.getByTestId('new-account-opening-balance').fill('10050')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-created')).toBeVisible()

  expect(api.bodySent('POST', '/api/accounts'), POST_ACCOUNTS).toEqual({
    currency: 'HUF',
    openingBalanceMinorUnits: 10_050,
  })
})

test('the Currencies on offer are the three this service quotes and no others', async ({ api, page }) => {
  api.accounts([])

  await page.goto(ACCOUNTS)

  await expect(page.getByTestId('new-account-currency').locator('option')).toHaveText(['EUR', 'USD', 'HUF'])
})

/**
 * The same string, judged against two Currencies. It is refused in HUF and accepted in
 * EUR without the field being retyped, which is what makes the rule the *chosen*
 * Currency's rather than the form's.
 */
test('more decimals than the chosen Currency has is refused before anything is sent', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.opensAccount(anAccount({ id: 9, currency: 'EUR', balanceMinorUnits: 100_50 }))

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-currency').selectOption('HUF')
  await page.getByTestId('new-account-opening-balance').fill('100.50')

  await expect(page.getByTestId('new-account-opening-balance-error')).toHaveText(
    'HUF is written without decimal places, so its amounts are whole numbers.',
  )

  await page.getByTestId('new-account-submit').click()

  // Nothing left the browser: the rule is the form's to enforce, not a round trip's.
  await expect(page.getByTestId('new-account-created')).not.toBeAttached()
  expect(api.timesAsked('POST', '/api/accounts'), POST_ACCOUNTS).toBe(0)

  // The Currency changes and the same string becomes an amount, with no retyping.
  await page.getByTestId('new-account-currency').selectOption('EUR')

  await expect(page.getByTestId('new-account-opening-balance-error')).not.toBeAttached()

  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-created')).toBeVisible()
  expect(api.bodySent('POST', '/api/accounts'), POST_ACCOUNTS).toEqual({
    currency: 'EUR',
    openingBalanceMinorUnits: 10_050,
  })
})

/**
 * A refusal the browser could not have anticipated — the client's rules passed, the
 * service's did not. What matters is where the sentence lands: `openingBalanceMinorUnits`
 * is the member the wire carries, and the operator can only correct the field beside it.
 */
test('a field the service refused is shown against that field, in the words it used', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.refuses(
    'POST',
    '/api/accounts',
    aProblem('urn:problem:validation-failed', {
      status: 422,
      errors: [{ field: 'openingBalanceMinorUnits', message: 'must be greater than or equal to 0' }],
    }),
  )

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-currency').selectOption('EUR')
  await page.getByTestId('new-account-opening-balance').fill('100.50')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-opening-balance-error')).toHaveText(
    'must be greater than or equal to 0',
  )
  await expect(page.getByTestId('new-account-opening-balance')).toHaveClass(/is-invalid/)

  // The heading above is the problem module's wording for the URN, never the document's
  // own `detail`, which is written for whoever reads the response.
  await expect(page.getByTestId('new-account-error-title')).toHaveText('Some of what was entered was refused')
  await expect(page.getByTestId('new-account-error-body')).not.toHaveText(
    'The request was understood and could not be acted on.',
  )

  // Nothing was opened, so nothing claims one was, and the list is untouched.
  await expect(page.getByTestId('new-account-created')).not.toBeAttached()
  await expect(page.getByTestId('accounts-empty')).toBeVisible()
})

/**
 * A verdict is about the payload that produced it. Editing the field voids it, or the
 * operator is left reading a refusal of an amount that is no longer in the box.
 */
test('correcting the field clears what the service said about the amount it refused', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.refuses(
    'POST',
    '/api/accounts',
    aProblem('urn:problem:validation-failed', {
      status: 422,
      errors: [{ field: 'openingBalanceMinorUnits', message: 'must be greater than or equal to 0' }],
    }),
  )

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-opening-balance').fill('100.50')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-error')).toBeVisible()

  await page.getByTestId('new-account-opening-balance').fill('200.50')

  await expect(page.getByTestId('new-account-opening-balance-error')).not.toBeAttached()
  await expect(page.getByTestId('new-account-error')).not.toBeAttached()
})

/**
 * A refusal naming a member this form has no field for. Dropping it would leave an
 * operator with a heading and no reason; it is shown with the refusal instead.
 */
test('a refusal naming something the form cannot show against a field still says what it was', async ({
  api,
  page,
}) => {
  api.accounts([])
  api.refuses(
    'POST',
    '/api/accounts',
    aProblem('urn:problem:validation-failed', {
      status: 422,
      errors: [
        { field: null, message: 'the request was refused as a whole' },
        { field: 'somethingElse', message: 'must be smaller' },
      ],
    }),
  )

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-opening-balance').fill('100.50')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-error-unattached')).toHaveText(
    /the request was refused as a whole/,
  )
  await expect(page.getByTestId('new-account-error-unattached')).toHaveText(
    /somethingElse: must be smaller/,
  )
})

test('an empty amount is asked for rather than sent as one', async ({ api, page }) => {
  api.accounts([])

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-opening-balance-error')).toHaveText(
    'Enter the amount this Account opens with.',
  )
  expect(api.timesAsked('POST', '/api/accounts'), POST_ACCOUNTS).toBe(0)
})

/** Ticket 09 allows an Account with nothing in it, so the form must be able to ask for one. */
test('an Account can be opened with nothing in it', async ({ api, page }) => {
  api.accounts([])
  api.opensAccount(anAccount({ id: 10, currency: 'USD', balanceMinorUnits: 0 }))

  await page.goto(ACCOUNTS)

  await page.getByTestId('new-account-currency').selectOption('USD')
  await page.getByTestId('new-account-opening-balance').fill('0')
  await page.getByTestId('new-account-submit').click()

  await expect(page.getByTestId('new-account-created')).toBeVisible()
  expect(api.bodySent('POST', '/api/accounts'), POST_ACCOUNTS).toEqual({
    currency: 'USD',
    openingBalanceMinorUnits: 0,
  })
})
