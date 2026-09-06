import type { Page } from '@playwright/test'

/**
 * The event stream a browser spec drives by hand, installed in place of the browser's own
 * `EventSource` before a line of app code runs.
 *
 * **Why a fake source rather than a scripted response.** The route mocker fulfils a
 * request with a complete string or buffer — there is no streaming body — so a mock
 * cannot push an event *after* the page has rendered, which is the whole of what a
 * live-update spec asserts. With the fake, the sequence is the spec's to order and no step
 * waits on a timer: assert the page reads pending, re-route the endpoint so **truth
 * changes**, dispatch a message, assert the page reads settled.
 *
 * The fake is a plain object with the members `EventSource` has and nothing else — no
 * network, no reconnect timer, no `Last-Event-ID`. What it does keep is the *shape* of a
 * real connection, because the app's behaviour is written against it: a source opens
 * asynchronously after construction rather than in its constructor, a closed source
 * delivers nothing, and a dropped one goes back to `CONNECTING` and is reopened. Reopening
 * is the one place the fake departs from the browser, which reconnects on a timer of its
 * own; here it is a call the spec makes, which is what keeps a reconnection test free of
 * waiting.
 */

/** One source the app has constructed, as the page keeps it. */
type OpenedSource = {
  readonly url: string
  readonly readyState: number
  message: (frame: string) => void
  drop: () => void
  reopen: () => void
}

declare global {
  interface Window {
    /** Every source the app has constructed on this page, oldest first. */
    __eventSources__: OpenedSource[]
  }
}

/** Puts the fake in place for every navigation this page makes, before any app code runs. */
export const useFakeEventSource = async (page: Page): Promise<void> => {
  await page.addInitScript(fakeEventSource)
}

/** Where a subscription has got to, in the three states `EventSource` has. */
export type StreamState = 'connecting' | 'open' | 'closed'

/** The subscription the app is holding, as a spec drives it. */
export type BrowserEventStream = {
  /** The URL the app subscribed to. */
  readonly url: string

  /** Delivers one frame on the default `message` event, which is where this API's events travel. */
  message: (frame: string) => Promise<void>

  /** Drops the connection the way a proxy timing out does: an `error`, then `connecting`. */
  drop: () => Promise<void>

  /** Brings it back up, which a real source does on its own timer and this one does on demand. */
  reopen: () => Promise<void>

  /**
   * Where the subscription has got to. `closed` is the app having called `close`, which is
   * how a spec asserts that leaving a screen tears its subscription down.
   */
  state: () => Promise<StreamState>
}

/**
 * Waits for the app to hold an open subscription, and binds to that one.
 *
 * **Ask for it after the page has rendered, not before the first assertion.** React's
 * StrictMode mounts an effect, tears it down and mounts it again, so a subscribing
 * component briefly leaves a closed source behind a live one; binding to the newest *open*
 * source is what makes that invisible, and it is only reliable once the remount has
 * happened.
 */
export const eventStream = async (page: Page): Promise<BrowserEventStream> => {
  // Counting from one, because `waitForFunction` resolves on a truthy value and the
  // newest open source is index 0 whenever the app holds exactly one.
  const openSources = await page.waitForFunction(() => {
    const sources = window.__eventSources__ ?? []

    for (let counted = sources.length; counted > 0; counted -= 1) {
      if (sources[counted - 1].readyState === 1) return counted
    }

    return 0
  })

  const index = (await openSources.jsonValue()) - 1
  const url = await page.evaluate((at) => window.__eventSources__[at].url, index)

  return {
    url,
    message: (frame) =>
      page.evaluate(({ at, delivered }) => window.__eventSources__[at].message(delivered), { at: index, delivered: frame }),
    drop: () => page.evaluate((at) => window.__eventSources__[at].drop(), index),
    reopen: () => page.evaluate((at) => window.__eventSources__[at].reopen(), index),
    state: async () => STATES[await page.evaluate((at) => window.__eventSources__[at].readyState, index)],
  }
}

/** `EventSource`'s three `readyState` numbers, in the order the constants give them. */
const STATES: readonly StreamState[] = ['connecting', 'open', 'closed']

/**
 * Runs in the page, before app code, as its own source text — so it may close over nothing
 * in this file.
 */
const fakeEventSource = (): void => {
  type Listener = (event: Event) => void

  const CONNECTING = 0
  const OPEN = 1
  const CLOSED = 2

  class FakeEventSource {
    static readonly CONNECTING = CONNECTING
    static readonly OPEN = OPEN
    static readonly CLOSED = CLOSED

    readonly CONNECTING = CONNECTING
    readonly OPEN = OPEN
    readonly CLOSED = CLOSED

    readonly url: string
    readonly withCredentials = false

    readyState: number = CONNECTING

    onopen: Listener | null = null
    onmessage: Listener | null = null
    onerror: Listener | null = null

    private readonly listeners = new Map<string, Set<Listener>>()

    constructor(url: string | URL) {
      this.url = new URL(url, window.location.href).href
      window.__eventSources__.push(this)
      queueMicrotask(() => this.reopen())
    }

    reopen(): void {
      if (this.readyState === CLOSED) return

      this.readyState = OPEN
      this.deliver(new Event('open'))
    }

    message(frame: string): void {
      this.refuseWhenClosed('deliver a message on')
      this.deliver(new MessageEvent('message', { data: frame }))
    }

    drop(): void {
      this.refuseWhenClosed('drop')
      this.readyState = CONNECTING
      this.deliver(new Event('error'))
    }

    close(): void {
      this.readyState = CLOSED
    }

    addEventListener(type: string, listener: Listener): void {
      const listeners = this.listeners.get(type) ?? new Set<Listener>()

      listeners.add(listener)
      this.listeners.set(type, listeners)
    }

    removeEventListener(type: string, listener: Listener): void {
      this.listeners.get(type)?.delete(listener)
    }

    dispatchEvent(event: Event): boolean {
      this.deliver(event)

      return true
    }

    private deliver(event: Event): void {
      if (this.readyState === CLOSED) return

      const handler =
        event.type === 'open' ? this.onopen : event.type === 'message' ? this.onmessage : this.onerror

      handler?.call(this, event)

      for (const listener of this.listeners.get(event.type) ?? []) listener.call(this, event)
    }

    /**
     * A closed source silently ignoring a dispatch would spend the spec's timeout on an
     * assertion that could never come true; saying so names the mistake instead.
     */
    private refuseWhenClosed(attempted: string): void {
      if (this.readyState === CLOSED) {
        throw new Error(`Cannot ${attempted} a subscription the app has already closed: ${this.url}`)
      }
    }
  }

  window.__eventSources__ = []
  window.EventSource = FakeEventSource as unknown as typeof EventSource
}
