// Per-browser settings and the session id sent as X-Chess-Session.

export const FLIP_KEY = 'chess.boardFlip'

// The player's "flip board" choice per view, remembered across reloads (per browser).
export function readFlipPrefs() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(FLIP_KEY) ?? '{}')
    return { live: stored.live === true, review: stored.review === true }
  } catch (ignored) {
    return { live: false, review: false }
  }
}

export function writeFlipPrefs(prefs) {
  try {
    window.localStorage.setItem(FLIP_KEY, JSON.stringify(prefs))
  } catch (ignored) {
    // Storage blocked: the flip still applies for this page view.
  }
}

export const SESSION_KEY = 'chess.sessionId'
let memorySessionId = null

export function generateSessionId() {
  return window.crypto?.randomUUID?.() ?? `session-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function getSessionId() {
  try {
    const existing = window.localStorage.getItem(SESSION_KEY)
    if (existing) return existing
    const generated = generateSessionId()
    window.localStorage.setItem(SESSION_KEY, generated)
    return generated
  } catch (ignored) {
    // Storage can be blocked (privacy modes); keep a per-tab session instead of failing to load.
    memorySessionId = memorySessionId ?? generateSessionId()
    return memorySessionId
  }
}
