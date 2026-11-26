// Small synthesized sound effects (Web Audio): no audio files to ship or load.
const SOUND_KEY = 'chess.sound'
let context = null

export function soundEnabled() {
  try {
    return window.localStorage.getItem(SOUND_KEY) !== 'off'
  } catch (ignored) {
    return true
  }
}

export function setSoundEnabled(on) {
  try {
    window.localStorage.setItem(SOUND_KEY, on ? 'on' : 'off')
  } catch (ignored) {
    // Storage blocked: the choice lasts for this page view.
  }
}

function audio() {
  if (!context) {
    const Context = window.AudioContext ?? window.webkitAudioContext
    if (!Context) return null
    context = new Context()
  }
  if (context.state === 'suspended') context.resume().catch(() => {})
  return context
}

function tone(ctx, { freq, start = 0, duration = 0.08, type = 'sine', gain = 0.08, slideTo }) {
  const t0 = ctx.currentTime + start
  const osc = ctx.createOscillator()
  const amp = ctx.createGain()
  osc.type = type
  osc.frequency.setValueAtTime(freq, t0)
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t0 + duration)
  amp.gain.setValueAtTime(0.0001, t0)
  amp.gain.exponentialRampToValueAtTime(gain, t0 + 0.005)
  amp.gain.exponentialRampToValueAtTime(0.0001, t0 + duration)
  osc.connect(amp).connect(ctx.destination)
  osc.start(t0)
  osc.stop(t0 + duration + 0.02)
}

/** kind: move | capture | check | right | wrong | solved */
export function playSound(kind, enabled = soundEnabled()) {
  if (!enabled) return
  const ctx = audio()
  if (!ctx) return
  try {
    switch (kind) {
      case 'move':
        tone(ctx, { freq: 420, slideTo: 260, duration: 0.06, type: 'triangle', gain: 0.12 })
        break
      case 'capture':
        tone(ctx, { freq: 300, slideTo: 140, duration: 0.09, type: 'square', gain: 0.06 })
        tone(ctx, { freq: 520, slideTo: 300, duration: 0.05, type: 'triangle', gain: 0.08 })
        break
      case 'check':
        tone(ctx, { freq: 640, duration: 0.07, type: 'triangle', gain: 0.09 })
        tone(ctx, { freq: 480, start: 0.07, duration: 0.08, type: 'triangle', gain: 0.08 })
        break
      case 'right':
        tone(ctx, { freq: 660, duration: 0.09, gain: 0.07 })
        tone(ctx, { freq: 990, start: 0.08, duration: 0.12, gain: 0.07 })
        break
      case 'solved':
        tone(ctx, { freq: 660, duration: 0.09, gain: 0.07 })
        tone(ctx, { freq: 880, start: 0.08, duration: 0.09, gain: 0.07 })
        tone(ctx, { freq: 1320, start: 0.16, duration: 0.18, gain: 0.07 })
        break
      case 'wrong':
        tone(ctx, { freq: 220, slideTo: 150, duration: 0.22, type: 'sawtooth', gain: 0.045 })
        break
      default:
        break
    }
  } catch (ignored) {
    // Audio is a nicety; never let it break a move.
  }
}

/** The sound for a chess.js move result. */
export function moveSound(move) {
  if (!move) return 'move'
  if (move.san?.includes('+') || move.san?.includes('#')) return 'check'
  return move.flags?.includes('c') || move.flags?.includes('e') ? 'capture' : 'move'
}
