import React, { useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import { Chess } from 'chess.js'
import { PieceIcon } from './pieces.jsx'
import { ChessBoard, FILES, squareName } from './board.jsx'
import { piecesFromFen, uciSquares } from './review.jsx'
import { moveSound, playSound, setSoundEnabled, soundEnabled } from './sounds.js'

const STATS_KEY = 'chess.puzzles'
const THEME_KEY = 'chess.puzzleTheme'
const AUTO_NEXT_KEY = 'chess.puzzleAutoNext'
const START_RATING = 1200
const RECENT_LIMIT = 150
const HISTORY_LIMIT = 30
const SETUP_DELAY_MS = 500
const REPLY_DELAY_MS = 350
const WRONG_FLASH_MS = 700
const SOLUTION_STEP_MS = 650
const AUTO_NEXT_MS = 1100

// Themes offered as quick chips; the full list is one tap further.
const FEATURED_THEMES = ['mateIn1', 'mateIn2', 'fork', 'pin', 'skewer', 'hangingPiece', 'discoveredAttack', 'endgame', 'sacrifice', 'deflection']

function readJson(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key)
    return raw == null ? fallback : JSON.parse(raw)
  } catch (ignored) {
    return fallback
  }
}

function writeJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch (ignored) {
    // Storage blocked: it still works for this page view.
  }
}

function readStats() {
  const stored = readJson(STATS_KEY, {}) ?? {}
  const number = (value, fallback) => (Number.isFinite(value) ? value : fallback)
  return {
    rating: number(stored.rating, START_RATING),
    played: number(stored.played, 0),
    solved: number(stored.solved, 0),
    streak: number(stored.streak, 0),
    bestStreak: number(stored.bestStreak, 0),
    history: Array.isArray(stored.history) ? stored.history.slice(-HISTORY_LIMIT) : [],
    recent: Array.isArray(stored.recent) ? stored.recent.slice(-RECENT_LIMIT) : []
  }
}

// Elo against the puzzle's rating; a bigger K while the rating is still settling.
function ratedResult(stats, puzzleRating, win) {
  const k = stats.played < 20 ? 40 : 20
  const expected = 1 / (1 + 10 ** ((puzzleRating - stats.rating) / 400))
  const delta = Math.round(k * ((win ? 1 : 0) - expected))
  return { rating: Math.max(100, stats.rating + delta), delta }
}

// "mateIn2" → "Mate in 2", "hangingPiece" → "Hanging piece".
export function themeLabel(theme) {
  const words = theme.replace(/([A-Z]|\d+)/g, ' $1').trim().toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function errorMessage(err) {
  if (err.response?.data?.error) return err.response.data.error
  if (err.message === 'Network Error') return 'Cannot reach the chess server.'
  return err.message
}

function splitUci(uci) {
  return { from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci.length > 4 ? uci[4] : undefined }
}

function squareOf(name) {
  return { col: FILES.indexOf(name[0]), row: 8 - Number(name[1]) }
}

function colorName(color) {
  return color === 'white' ? 'White' : 'Black'
}

function rememberRecent(stats, id) {
  return [...stats.recent.filter(item => item !== id), id].slice(-RECENT_LIMIT)
}

let animationCounter = 0

/**
 * Tactics trainer. The server picks a puzzle near your rating; the browser plays it with chess.js:
 * the opponent's first move, then your moves are checked against the solution (any mate also counts),
 * with the opponent's replies in between. One result per puzzle goes into your rating (localStorage):
 * a mistake, a hint or showing the solution counts as a miss.
 *
 * Flow: the next puzzle is fetched while you solve this one, a clean solve moves on by itself (auto-next),
 * feedback is shown on the board, and the keyboard drives everything (Enter next, H hint, R retry, ←/→ replay).
 */
export default function PuzzleTrainer() {
  const [stats, setStats] = useState(readStats)
  const [theme, setTheme] = useState(() => readJson(THEME_KEY, '') ?? '')
  const [themes, setThemes] = useState([])
  const [showThemes, setShowThemes] = useState(false)
  const [autoNext, setAutoNext] = useState(() => readJson(AUTO_NEXT_KEY, true) !== false)
  const [sound, setSound] = useState(soundEnabled)
  const [puzzle, setPuzzle] = useState(null)
  // Every position of the line so far: { fen, uci, san } (index 0 = the puzzle's FEN, no move).
  const [positions, setPositions] = useState([])
  // Replay index while browsing a finished puzzle with ←/→; null = the latest position.
  const [view, setView] = useState(null)
  const [step, setStep] = useState(0)
  // loading | idle | setup | solving | busy (reply/flash in progress) | solved | revealed
  const [phase, setPhase] = useState('loading')
  const [message, setMessage] = useState(null) // { tone: 'right'|'wrong'|'info', text }
  const [badge, setBadge] = useState(null)
  const [hint, setHint] = useState(null) // { square, arrow }
  const [animation, setAnimation] = useState(null)
  const [result, setResult] = useState(null) // { win, delta } once rated
  const [autoPending, setAutoPending] = useState(false)
  const [error, setError] = useState(null)
  const [flipOverride, setFlipOverride] = useState(false)

  const chessRef = useRef(null)
  const timersRef = useRef([])
  const autoTimerRef = useRef(null)
  const loadRef = useRef(0)
  const ratedRef = useRef(false)
  const prefetchRef = useRef(null) // { theme, promise }
  const statsRef = useRef(stats)
  statsRef.current = stats

  const solverColor = puzzle?.color ?? 'white'
  const flipped = (solverColor === 'black') !== flipOverride
  const shownIndex = view ?? positions.length - 1
  const shown = positions[shownIndex] ?? null
  const fen = shown?.fen ?? null
  const pieces = useMemo(() => piecesFromFen(fen), [fen])
  const turnColor = fen?.split(' ')[1] === 'b' ? 'black' : 'white'
  const playing = phase === 'solving' && view == null
  const done = phase === 'solved' || phase === 'revealed'

  useEffect(() => {
    axios.get('/api/puzzles/themes').then(response => setThemes(response.data)).catch(() => {})
    loadPuzzle()
    return () => {
      clearTimers()
      cancelAutoNext()
    }
  }, [])

  // Keyboard: the latest handlers are read through a ref, so the listener is registered once.
  const keysRef = useRef(null)
  keysRef.current = { done, playing, next: () => loadPuzzle(), hint: showHint, retry, flip: () => setFlipOverride(v => !v), browse, phase }
  useEffect(() => {
    function onKey(event) {
      const tag = event.target?.tagName?.toLowerCase()
      if (tag === 'input' || tag === 'select' || tag === 'textarea' || event.altKey || event.metaKey || event.ctrlKey) return
      const keys = keysRef.current
      const key = event.key
      if ((key === 'Enter' || key === ' ' || key === 'n' || key === 'N') && (keys.done || keys.phase === 'idle')) {
        event.preventDefault()
        keys.next()
      } else if ((key === 'h' || key === 'H') && keys.playing) {
        event.preventDefault()
        keys.hint()
      } else if ((key === 'r' || key === 'R') && keys.done) {
        event.preventDefault()
        keys.retry()
      } else if (key === 'f' || key === 'F') {
        event.preventDefault()
        keys.flip()
      } else if ((key === 'ArrowLeft' || key === 'ArrowRight') && keys.done) {
        event.preventDefault()
        keys.browse(key === 'ArrowRight' ? 1 : -1)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  function clearTimers() {
    timersRef.current.forEach(timer => window.clearTimeout(timer))
    timersRef.current = []
  }

  function cancelAutoNext() {
    window.clearTimeout(autoTimerRef.current)
    autoTimerRef.current = null
    setAutoPending(false)
  }

  function later(fn, ms) {
    const load = loadRef.current
    const timer = window.setTimeout(() => {
      if (load === loadRef.current) fn()
    }, ms)
    timersRef.current.push(timer)
  }

  function fetchPuzzle(forTheme, excludeIds) {
    const current = statsRef.current
    return axios.get('/api/puzzles/next', {
      params: {
        rating: current.rating,
        theme: forTheme || undefined,
        exclude: excludeIds.slice(-RECENT_LIMIT).join(',') || undefined
      }
    }).then(response => response.data)
  }

  // Fetches the following puzzle in the background, so "Next" is instant.
  function prefetch(forTheme, currentId) {
    const exclude = [...statsRef.current.recent, currentId]
    const promise = fetchPuzzle(forTheme, exclude)
    promise.catch(() => {})
    prefetchRef.current = { theme: forTheme, promise }
  }

  async function loadPuzzle(nextTheme = theme) {
    clearTimers()
    cancelAutoNext()
    const load = ++loadRef.current
    ratedRef.current = false
    setMessage(null)
    setResult(null)
    setBadge(null)
    setHint(null)
    setView(null)
    setFlipOverride(false)
    const ready = prefetchRef.current?.theme === nextTheme ? prefetchRef.current.promise : null
    prefetchRef.current = null
    if (!ready) setPhase('loading')
    try {
      let next = ready ? await ready.catch(() => null) : null
      if (!next || statsRef.current.recent.includes(next.id)) {
        setPhase('loading')
        next = await fetchPuzzle(nextTheme, statsRef.current.recent)
      }
      if (load !== loadRef.current) return
      startPuzzle(next)
      prefetch(nextTheme, next.id)
      setError(null)
    } catch (err) {
      if (load !== loadRef.current) return
      setError(errorMessage(err))
      setPhase('idle')
    }
  }

  function startPuzzle(next) {
    const chess = new Chess(next.fen)
    chessRef.current = chess
    setPuzzle(next)
    setPositions([{ fen: chess.fen(), uci: null, san: null }])
    setStep(0)
    setAnimation(null)
    setPhase('setup')
    // The opponent's move that sets the puzzle up, after a short pause so you can see it happen.
    later(() => {
      playUci(next.moves[0], true)
      setStep(1)
      setPhase('solving')
    }, SETUP_DELAY_MS)
  }

  function pushPosition(chess, uci, san) {
    setPositions(list => [...list, { fen: chess.fen(), uci, san }])
  }

  function animate(uci) {
    const move = uciSquares(uci)
    setAnimation(move ? { ...move, id: ++animationCounter } : null)
  }

  // Plays a move from the line (opponent replies, solution playback) with animation and sound.
  function playUci(uci, withSound) {
    const chess = chessRef.current
    const move = chess.move(splitUci(uci))
    pushPosition(chess, uci, move.san)
    animate(uci)
    if (withSound) playSound(moveSound(move), sound)
    return move
  }

  function settle(win) {
    if (ratedRef.current || !puzzle) return
    ratedRef.current = true
    const prev = statsRef.current
    const { rating, delta } = ratedResult(prev, puzzle.rating, win)
    const streak = win ? prev.streak + 1 : 0
    const next = {
      rating,
      played: prev.played + 1,
      solved: prev.solved + (win ? 1 : 0),
      streak,
      bestStreak: Math.max(prev.bestStreak, streak),
      history: [...prev.history, { id: puzzle.id, rating: puzzle.rating, win, delta }].slice(-HISTORY_LIMIT),
      recent: rememberRecent(prev, puzzle.id)
    }
    statsRef.current = next
    setStats(next)
    writeJson(STATS_KEY, next)
    setResult({ win, delta })
  }

  function targetsFrom(origin) {
    const chess = chessRef.current
    if (!chess || !playing) return []
    const seen = new Set()
    return chess.moves({ square: squareName(origin.col, origin.row), verbose: true })
      .filter(move => !seen.has(move.to) && seen.add(move.to))
      .map(move => ({ ...squareOf(move.to), capture: move.flags.includes('c') || move.flags.includes('e'), promotion: Boolean(move.promotion) }))
  }

  function handleMove(origin, target, promotion, { dragged } = {}) {
    const chess = chessRef.current
    if (!chess || !playing || !puzzle) return
    const from = squareName(origin.col, origin.row)
    const to = squareName(target.col, target.row)
    const uci = `${from}${to}${promotion ?? ''}`
    const expected = puzzle.moves[step]
    let played
    try {
      played = chess.move({ from, to, promotion: promotion ?? undefined })
    } catch (ignored) {
      return
    }
    pushPosition(chess, uci, played.san)
    setAnimation(dragged ? null : { ...uciSquares(uci), id: ++animationCounter })
    setHint(null)

    // The solution's move, or any other move that mates on the spot.
    const correct = uci === expected || chess.isCheckmate()
    if (!correct) {
      setPhase('busy')
      setBadge({ ...target, tone: 'wrong' })
      setMessage({ tone: 'wrong', text: `${played.san} isn't it. Try again.` })
      playSound('wrong', sound)
      settle(false)
      later(() => {
        chess.undo()
        setPositions(list => list.slice(0, -1))
        setAnimation(null)
        setBadge(null)
        setPhase('solving')
      }, WRONG_FLASH_MS)
      return
    }

    setBadge({ ...target, tone: 'right' })
    const nextStep = step + 1
    if (chess.isCheckmate() || nextStep >= puzzle.moves.length) {
      finishSolved()
      return
    }
    playSound(moveSound(played), sound)
    setPhase('busy')
    setMessage({ tone: 'right', text: `${played.san} — keep going` })
    later(() => {
      playUci(puzzle.moves[nextStep], true)
      setStep(nextStep + 1)
      setBadge(null)
      setPhase('solving')
    }, REPLY_DELAY_MS)
  }

  function finishSolved() {
    const clean = !ratedRef.current
    setPhase('solved')
    settle(true)
    playSound('solved', sound)
    setMessage({ tone: 'right', text: clean ? 'Solved!' : 'Solved, but it counted as a miss.' })
    if (clean && autoNext) {
      setAutoPending(true)
      autoTimerRef.current = window.setTimeout(() => {
        autoTimerRef.current = null
        setAutoPending(false)
        loadPuzzle()
      }, AUTO_NEXT_MS)
    }
  }

  function showHint() {
    if (!playing || !puzzle) return
    const move = uciSquares(puzzle.moves[step])
    if (!move) return
    // First press: the piece to move. Second press: the whole move.
    setHint(current => (current ? { ...current, arrow: move } : { square: { col: move.fromCol, row: move.fromRow }, arrow: null }))
    settle(false)
    setMessage({ tone: 'info', text: 'Hint used: this one counts as a miss.' })
  }

  function showSolution() {
    if (!puzzle || !['solving', 'busy'].includes(phase)) return
    settle(false)
    // Cancels a pending reply or wrong-move flash, so it can't touch the position while the solution plays.
    clearTimers()
    loadRef.current += 1
    const chess = chessRef.current
    while (chess.history().length > step) chess.undo()
    setPositions(list => list.slice(0, step + 1))
    setPhase('revealed')
    setBadge(null)
    setHint(null)
    setView(null)
    setMessage({ tone: 'info', text: 'Solution' })
    puzzle.moves.slice(step).forEach((uci, index) => later(() => playUci(uci, true), SOLUTION_STEP_MS * (index + 1)))
  }

  function retry() {
    if (!puzzle) return
    clearTimers()
    cancelAutoNext()
    loadRef.current += 1
    // Retrying never changes the rating again: the first attempt counted.
    ratedRef.current = true
    setResult(null)
    setMessage(null)
    setBadge(null)
    setHint(null)
    setView(null)
    const chess = new Chess(puzzle.fen)
    chessRef.current = chess
    const first = chess.move(splitUci(puzzle.moves[0]))
    setPositions([{ fen: puzzle.fen, uci: null, san: null }, { fen: chess.fen(), uci: puzzle.moves[0], san: first.san }])
    setAnimation(null)
    setStep(1)
    setPhase('solving')
  }

  // Replays a finished puzzle one move at a time (stops auto-next: you're looking at it).
  function browse(delta) {
    if (!done || positions.length < 2) return
    cancelAutoNext()
    const current = view ?? positions.length - 1
    const next = Math.max(0, Math.min(positions.length - 1, current + delta))
    if (next === current) return
    setBadge(null)
    setView(next === positions.length - 1 ? null : next)
    if (delta > 0 && positions[next].uci) {
      animate(positions[next].uci)
      playSound('move', sound)
    } else {
      setAnimation(null)
    }
  }

  function skip() {
    if (puzzle && !ratedRef.current) {
      const next = { ...statsRef.current, recent: rememberRecent(statsRef.current, puzzle.id) }
      statsRef.current = next
      setStats(next)
      writeJson(STATS_KEY, next)
    }
    loadPuzzle()
  }

  function changeTheme(next) {
    setTheme(next)
    setShowThemes(false)
    writeJson(THEME_KEY, next)
    loadPuzzle(next)
  }

  function toggleAutoNext() {
    const next = !autoNext
    setAutoNext(next)
    writeJson(AUTO_NEXT_KEY, next)
    if (!next) cancelAutoNext()
  }

  function toggleSound() {
    const next = !sound
    setSound(next)
    setSoundEnabled(next)
  }

  const lastUci = shown?.uci
  const lastMove = lastUci ? uciSquares(lastUci) : null
  const headline = phase === 'loading' ? 'Loading…'
    : phase === 'idle' ? 'No puzzle loaded'
    : phase === 'setup' ? `${colorName(turnColor)} to move…`
    : phase === 'solved' ? (result?.win === false ? 'Solved' : 'Solved!')
    : phase === 'revealed' ? 'Solution'
    : `${colorName(solverColor)} to move`
  const subline = message?.text && message.text !== headline ? message
    : phase === 'solving' || phase === 'busy' ? { tone: 'muted', text: 'Find the best move.' }
    : null
  const accuracy = stats.played ? Math.round((stats.solved / stats.played) * 100) : null
  const topSide = flipped ? 'white' : 'black'
  const bottomSide = flipped ? 'black' : 'white'
  const marks = hint?.square ? { [`${hint.square.col},${hint.square.row}`]: 'hint-square' } : {}
  const solutionLine = done ? positions.slice(1) : []
  const visibleThemes = showThemes
    ? themes
    : FEATURED_THEMES.map(name => themes.find(item => item.name === name)).filter(Boolean)

  const bar = side => (
    <div className={`player-bar ${turnColor === side && !done ? 'active' : ''}`}>
      <div className="player-identity">
        <span className={`player-avatar ${side}`} aria-hidden="true">
          <PieceIcon name="King" color={side} decorative />
        </span>
        <div className="player-text">
          <div className="player-name">{side === solverColor ? 'You' : 'Opponent'}</div>
          {side === solverColor && stats.streak > 1 && <div className="puzzle-streak-mini">🔥 {stats.streak} in a row</div>}
        </div>
      </div>
      {side === solverColor && (
        <div className="puzzle-rating-chip" title="Your puzzle rating">
          {result && (
            <span key={`${puzzle?.id}-${result.delta}`} className={`rating-delta ${result.delta >= 0 ? 'up' : 'down'}`}>
              {result.delta >= 0 ? `+${result.delta}` : result.delta}
            </span>
          )}
          <span className="rating-value">{stats.rating}</span>
        </div>
      )}
    </div>
  )

  return (
    <section className="game-layout is-live is-puzzle">
      <div className="play-area">
        {bar(topSide)}
        <ChessBoard
          pieces={pieces}
          flipped={flipped}
          lastMove={lastMove}
          canMove={piece => playing && piece.color === solverColor && piece.color === turnColor}
          targetsFrom={targetsFrom}
          onMove={handleMove}
          marks={marks}
          badge={view == null ? badge : null}
          animation={animation}
          arrows={hint?.arrow ? [{ move: hint.arrow, tone: 'best-suggestion', label: 'Hint' }] : []}
          resetKey={`${puzzle?.id}-${fen}`}
        />
        {bar(bottomSide)}
      </div>

      <aside className="side-panel puzzle-panel">
        <header className={`puzzle-status ${message?.tone ?? ''}`}>
          <span className={`turn-dot ${turnColor}`} aria-hidden="true" />
          <div className="puzzle-status-text">
            <h1 aria-live="polite">{headline}</h1>
            {subline && <p className={subline.tone}>{subline.text}</p>}
          </div>
        </header>

        {error && <div className="notice error" role="alert">{error}</div>}

        <footer className="puzzle-actions">
          {done || phase === 'idle' ? (
            <button className={`primary-action ${autoPending ? 'auto-next' : ''}`} type="button" onClick={() => loadPuzzle()}>
              <span>Next puzzle</span>
              <kbd>Enter</kbd>
            </button>
          ) : (
            <button className="primary-action secondary-tone" type="button" onClick={showHint} disabled={!playing}>
              <span>{hint ? 'Show move' : 'Hint'}</span>
              <kbd>H</kbd>
            </button>
          )}
          <div className="quiet-actions">
            {done ? (
              <button type="button" className="quiet-action" onClick={retry} title="Retry (R)">↺ Retry</button>
            ) : (
              <button type="button" className="quiet-action" onClick={showSolution} disabled={!['solving', 'busy'].includes(phase)}>Solution</button>
            )}
            {!done && <button type="button" className="quiet-action" onClick={skip} disabled={phase === 'loading'}>Skip</button>}
            <button type="button" className="quiet-action" onClick={() => setFlipOverride(v => !v)} title="Flip board (F)">⇅ Flip</button>
          </div>
        </footer>

        <div className="puzzle-scroll">
          {done && solutionLine.length > 0 && (
            <ol className="puzzle-line" aria-label="Moves">
              {solutionLine.map((item, index) => (
                <li key={index}>
                  <button
                    type="button"
                    className={[index === 0 ? 'opponent' : '', index + 1 === shownIndex ? 'current' : ''].filter(Boolean).join(' ')}
                    onClick={() => { cancelAutoNext(); setBadge(null); setAnimation(null); setView(index + 1 === positions.length - 1 ? null : index + 1) }}
                  >
                    {item.san}
                  </button>
                </li>
              ))}
              <li className="puzzle-line-hint">← → to replay</li>
            </ol>
          )}

          <div className="puzzle-stats" aria-label="Your puzzle stats">
            <span><strong>{stats.rating}</strong> rating</span>
            <span><strong>{stats.streak}</strong> streak</span>
            <span><strong>{accuracy == null ? '–' : `${accuracy}%`}</strong> solved</span>
            <span><strong>{stats.bestStreak}</strong> best</span>
          </div>

          {stats.history.length > 0 && (
            <ol className="puzzle-history" aria-label="Recent puzzles">
              {stats.history.map((item, index) => (
                <li key={`${item.id}-${index}`} className={item.win ? 'win' : 'loss'} title={`${item.rating} · ${item.win ? 'solved' : 'missed'} (${item.delta >= 0 ? '+' : ''}${item.delta ?? 0})`} />
              ))}
            </ol>
          )}

          <div className="puzzle-themes-picker">
            <div className="section-head compact">
              <h2>Theme</h2>
              {themes.length > FEATURED_THEMES.length && (
                <button type="button" className="link-button" onClick={() => setShowThemes(v => !v)}>
                  {showThemes ? 'Fewer' : 'All themes'}
                </button>
              )}
            </div>
            <div className="theme-chips">
              <button type="button" className={theme === '' ? 'active' : ''} onClick={() => changeTheme('')}>Mixed</button>
              {visibleThemes.map(item => (
                <button key={item.name} type="button" className={theme === item.name ? 'active' : ''} onClick={() => changeTheme(item.name)}>
                  {themeLabel(item.name)}
                </button>
              ))}
              {theme && !visibleThemes.some(item => item.name === theme) && (
                <button type="button" className="active">{themeLabel(theme)}</button>
              )}
            </div>
          </div>

          {puzzle && done && (
            <div className="puzzle-info">
              <span>Puzzle rated <strong>{puzzle.rating}</strong></span>
              <div className="puzzle-tags">
                {puzzle.themes.map(item => <span key={item}>{themeLabel(item)}</span>)}
              </div>
              <a href={`https://lichess.org/training/${puzzle.id}`} target="_blank" rel="noreferrer">Open on Lichess ↗</a>
            </div>
          )}

          <div className="puzzle-toggles">
            <label><input type="checkbox" checked={autoNext} onChange={toggleAutoNext} /> Next puzzle automatically after a clean solve</label>
            <label><input type="checkbox" checked={sound} onChange={toggleSound} /> Sound</label>
          </div>
        </div>
      </aside>
    </section>
  )
}
