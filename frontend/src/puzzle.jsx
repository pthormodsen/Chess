import React, { useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import { Chess } from 'chess.js'
import { PieceIcon } from './pieces.jsx'
import { ChessBoard, FILES, squareName } from './board.jsx'
import { piecesFromFen, uciSquares } from './review.jsx'

const STATS_KEY = 'chess.puzzles'
const THEME_KEY = 'chess.puzzleTheme'
const START_RATING = 1200
const RECENT_LIMIT = 150
const HISTORY_LIMIT = 30
const SETUP_DELAY_MS = 650
const REPLY_DELAY_MS = 450
const WRONG_FLASH_MS = 650
const SOLUTION_STEP_MS = 750

function readStats() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(STATS_KEY) ?? '{}')
    return {
      rating: Number.isFinite(stored.rating) ? stored.rating : START_RATING,
      played: Number.isFinite(stored.played) ? stored.played : 0,
      solved: Number.isFinite(stored.solved) ? stored.solved : 0,
      streak: Number.isFinite(stored.streak) ? stored.streak : 0,
      bestStreak: Number.isFinite(stored.bestStreak) ? stored.bestStreak : 0,
      history: Array.isArray(stored.history) ? stored.history.slice(-HISTORY_LIMIT) : [],
      recent: Array.isArray(stored.recent) ? stored.recent.slice(-RECENT_LIMIT) : []
    }
  } catch (ignored) {
    return { rating: START_RATING, played: 0, solved: 0, streak: 0, bestStreak: 0, history: [], recent: [] }
  }
}

function writeStats(stats) {
  try {
    window.localStorage.setItem(STATS_KEY, JSON.stringify(stats))
  } catch (ignored) {
    // Storage blocked: the rating still works for this page view.
  }
}

function readTheme() {
  try {
    return window.localStorage.getItem(THEME_KEY) ?? ''
  } catch (ignored) {
    return ''
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

// The solver's line in SAN, starting after the opponent's first move: "Qxg6+ Bg7 Re8#".
function solutionSan(puzzle) {
  try {
    const chess = new Chess(puzzle.fen)
    return puzzle.moves.map(uci => chess.move(splitUci(uci)).san).slice(1).join(' ')
  } catch (ignored) {
    return ''
  }
}

function colorName(color) {
  return color === 'white' ? 'White' : 'Black'
}

/**
 * Tactics trainer. The server picks a puzzle near your rating; the browser plays it with chess.js:
 * the opponent's first move, then your moves are checked against the solution (any mate also counts),
 * with the opponent's replies in between. One result per puzzle goes into your rating (localStorage):
 * a mistake, a hint or showing the solution counts as a miss.
 */
export default function PuzzleTrainer() {
  const [stats, setStats] = useState(readStats)
  const [theme, setTheme] = useState(readTheme)
  const [themes, setThemes] = useState([])
  const [puzzle, setPuzzle] = useState(null)
  const [fen, setFen] = useState(null)
  const [step, setStep] = useState(0)
  // loading | setup | solving | busy (reply/flash in progress) | solved | revealed
  const [phase, setPhase] = useState('loading')
  const [feedback, setFeedback] = useState(null) // { tone: 'right'|'wrong'|'info', text }
  const [lastMove, setLastMove] = useState(null)
  const [marks, setMarks] = useState({})
  const [hintArrow, setHintArrow] = useState(null)
  const [result, setResult] = useState(null) // { win, delta, rating } once rated
  const [error, setError] = useState(null)
  const [flipOverride, setFlipOverride] = useState(false)

  const chessRef = useRef(null)
  const timersRef = useRef([])
  const loadRef = useRef(0)
  const ratedRef = useRef(false)

  const solverColor = puzzle?.color ?? 'white'
  const flipped = (solverColor === 'black') !== flipOverride
  const pieces = useMemo(() => piecesFromFen(fen), [fen])
  const turnColor = fen?.split(' ')[1] === 'b' ? 'black' : 'white'
  const playing = phase === 'solving'

  useEffect(() => {
    axios.get('/api/puzzles/themes').then(response => setThemes(response.data)).catch(() => {})
    loadPuzzle()
    return clearTimers
  }, [])

  function clearTimers() {
    timersRef.current.forEach(timer => window.clearTimeout(timer))
    timersRef.current = []
  }

  function later(fn, ms) {
    const load = loadRef.current
    const timer = window.setTimeout(() => {
      if (load === loadRef.current) fn()
    }, ms)
    timersRef.current.push(timer)
  }

  async function loadPuzzle(nextTheme = theme) {
    clearTimers()
    const load = ++loadRef.current
    setPhase('loading')
    setFeedback(null)
    setResult(null)
    setMarks({})
    setHintArrow(null)
    setFlipOverride(false)
    ratedRef.current = false
    try {
      const response = await axios.get('/api/puzzles/next', {
        params: { rating: stats.rating, theme: nextTheme || undefined, exclude: stats.recent.slice(-RECENT_LIMIT).join(',') || undefined }
      })
      if (load !== loadRef.current) return
      startPuzzle(response.data)
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
    setFen(chess.fen())
    setLastMove(null)
    setStep(0)
    setPhase('setup')
    // The opponent's move that sets the puzzle up, shown after a short pause so you can see it happen.
    later(() => {
      playUci(next.moves[0])
      setStep(1)
      setPhase('solving')
    }, SETUP_DELAY_MS)
  }

  function playUci(uci) {
    const chess = chessRef.current
    chess.move(splitUci(uci))
    setFen(chess.fen())
    setLastMove(uciSquares(uci))
  }

  function settle(win) {
    if (ratedRef.current || !puzzle) return
    ratedRef.current = true
    setStats(prev => {
      const { rating, delta } = ratedResult(prev, puzzle.rating, win)
      const streak = win ? prev.streak + 1 : 0
      const next = {
        rating,
        played: prev.played + 1,
        solved: prev.solved + (win ? 1 : 0),
        streak,
        bestStreak: Math.max(prev.bestStreak, streak),
        history: [...prev.history, { id: puzzle.id, rating: puzzle.rating, win, delta }].slice(-HISTORY_LIMIT),
        recent: [...prev.recent.filter(id => id !== puzzle.id), puzzle.id].slice(-RECENT_LIMIT)
      }
      writeStats(next)
      setResult({ win, delta, rating })
      return next
    })
  }

  function targetsFrom(origin) {
    const chess = chessRef.current
    if (!chess || !playing) return []
    const seen = new Set()
    return chess.moves({ square: squareName(origin.col, origin.row), verbose: true })
      .filter(move => !seen.has(move.to) && seen.add(move.to))
      .map(move => ({ ...squareOf(move.to), capture: move.flags.includes('c') || move.flags.includes('e'), promotion: Boolean(move.promotion) }))
  }

  function handleMove(origin, target, promotion) {
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
    setFen(chess.fen())
    setLastMove(uciSquares(uci))
    setMarks({})
    setHintArrow(null)

    // The solution's move, or any other move that mates on the spot.
    const correct = uci === expected || chess.isCheckmate()
    if (!correct) {
      setPhase('busy')
      setMarks({ [`${target.col},${target.row}`]: 'wrong-square', [`${origin.col},${origin.row}`]: 'wrong-square' })
      setFeedback({ tone: 'wrong', text: `${played.san} is not the move. Try again.` })
      settle(false)
      later(() => {
        chess.undo()
        setFen(chess.fen())
        setLastMove(uciSquares(puzzle.moves[step - 1]))
        setMarks({})
        setPhase('solving')
      }, WRONG_FLASH_MS)
      return
    }

    const nextStep = step + 1
    if (chess.isCheckmate() || nextStep >= puzzle.moves.length) {
      finishSolved(target)
      return
    }
    setPhase('busy')
    setMarks({ [`${target.col},${target.row}`]: 'right-square' })
    setFeedback({ tone: 'right', text: `${played.san} is right. Keep going.` })
    later(() => {
      playUci(puzzle.moves[nextStep])
      setStep(nextStep + 1)
      setMarks({})
      setPhase('solving')
    }, REPLY_DELAY_MS)
  }

  function finishSolved(target) {
    setPhase('solved')
    setMarks(target ? { [`${target.col},${target.row}`]: 'right-square' } : {})
    const clean = !ratedRef.current
    setFeedback({ tone: 'right', text: clean ? 'Solved!' : 'Solved, after a miss.' })
    settle(true)
  }

  function showHint() {
    if (!playing || !puzzle) return
    const move = uciSquares(puzzle.moves[step])
    if (!move) return
    // First press: the piece to move. Second press: the whole move.
    if (marks[`${move.fromCol},${move.fromRow}`] === 'hint-square') {
      setHintArrow(move)
    } else {
      setMarks({ [`${move.fromCol},${move.fromRow}`]: 'hint-square' })
    }
    settle(false)
    setFeedback({ tone: 'info', text: 'Hint used. This puzzle counts as a miss.' })
  }

  function showSolution() {
    if (!puzzle || phase === 'loading' || phase === 'setup' || phase === 'solved' || phase === 'revealed') return
    settle(false)
    // Cancels a pending reply or wrong-move flash, so it can't touch the position while the solution plays.
    clearTimers()
    loadRef.current += 1
    setPhase('revealed')
    setMarks({})
    setHintArrow(null)
    setFeedback({ tone: 'info', text: `Solution: ${solutionSan(puzzle)}` })
    const chess = chessRef.current
    // A wrong move still on the board (mid-flash) is taken back first.
    while (chess.history().length > step) chess.undo()
    setFen(chess.fen())
    const remaining = puzzle.moves.slice(step)
    remaining.forEach((uci, index) => later(() => playUci(uci), SOLUTION_STEP_MS * (index + 1)))
  }

  function retry() {
    if (!puzzle) return
    clearTimers()
    loadRef.current += 1
    setResult(null)
    setFeedback(null)
    setMarks({})
    setHintArrow(null)
    // Retrying never changes the rating again: the first attempt counted.
    ratedRef.current = true
    const chess = new Chess(puzzle.fen)
    chessRef.current = chess
    chess.move(splitUci(puzzle.moves[0]))
    setFen(chess.fen())
    setLastMove(uciSquares(puzzle.moves[0]))
    setStep(1)
    setPhase('solving')
  }

  // Skipping isn't rated, but the puzzle still goes into the recent list so it doesn't come straight back.
  function skip() {
    if (puzzle && !ratedRef.current) {
      setStats(prev => {
        const next = { ...prev, recent: [...prev.recent.filter(id => id !== puzzle.id), puzzle.id].slice(-RECENT_LIMIT) }
        writeStats(next)
        return next
      })
    }
    loadPuzzle()
  }

  function changeTheme(next) {
    setTheme(next)
    try { window.localStorage.setItem(THEME_KEY, next) } catch (ignored) {}
    loadPuzzle(next)
  }

  const done = phase === 'solved' || phase === 'revealed'
  const headline = phase === 'loading' ? 'Loading puzzle…'
    : phase === 'idle' ? 'No puzzle loaded'
    : phase === 'setup' ? `${colorName(turnColor)} moves…`
    : phase === 'solved' ? (result?.win === false ? 'Solved' : 'Solved!')
    : phase === 'revealed' ? 'Solution'
    : `Find the best move for ${colorName(solverColor)}`
  const accuracy = stats.played ? Math.round((stats.solved / stats.played) * 100) : null
  const topSide = flipped ? 'white' : 'black'
  const bottomSide = flipped ? 'black' : 'white'
  const bar = side => (
    <div className={`player-bar ${turnColor === side && !done ? 'active' : ''}`}>
      <div className="player-identity">
        <span className={`player-avatar ${side}`} aria-hidden="true">
          <PieceIcon name="King" color={side} decorative />
        </span>
        <div className="player-text">
          <div className="player-name">{side === solverColor ? 'You' : 'Opponent'}</div>
        </div>
      </div>
      {side === solverColor && (
        <div className="clock puzzle-rating-chip" title="Your puzzle rating">{stats.rating}</div>
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
          arrows={hintArrow ? [{ move: hintArrow, tone: 'best-suggestion', label: 'Hint' }] : []}
          resetKey={`${puzzle?.id}-${fen}`}
        />
        {bar(bottomSide)}
      </div>

      <aside className="side-panel puzzle-panel">
        <header className="panel-status">
          <div className="status-text">
            <span className="status-kicker">Puzzles</span>
            <h1 aria-live="polite">{headline}</h1>
          </div>
          <div className="status-meta">
            <span className={`turn-dot ${turnColor}`} title={`${turnColor} to move`} />
          </div>
        </header>

        {error && <div className="notice error" role="alert">{error}</div>}
        {feedback && <div className={`puzzle-feedback ${feedback.tone}`} role="status">{feedback.text}</div>}

        <div className="puzzle-scroll">
          <div className="puzzle-stats">
            <div className="puzzle-stat main">
              <span>Rating</span>
              <strong>{stats.rating}</strong>
              {result && (
                <em className={result.delta >= 0 ? 'up' : 'down'}>{result.delta >= 0 ? `+${result.delta}` : result.delta}</em>
              )}
            </div>
            <div className="puzzle-stat">
              <span>Streak</span>
              <strong>{stats.streak}</strong>
            </div>
            <div className="puzzle-stat">
              <span>Solved</span>
              <strong>{accuracy == null ? '–' : `${accuracy}%`}</strong>
            </div>
          </div>

          {stats.history.length > 0 && (
            <ol className="puzzle-history" aria-label="Recent puzzles">
              {stats.history.map((item, index) => (
                <li key={`${item.id}-${index}`} className={item.win ? 'win' : 'loss'} title={`${item.rating} · ${item.win ? 'solved' : 'missed'}`}>
                  {item.win ? '✓' : '✕'}
                </li>
              ))}
            </ol>
          )}

          <label className="field">
            <span className="field-label">Theme</span>
            <select value={theme} onChange={event => changeTheme(event.target.value)}>
              <option value="">All themes</option>
              {themes.map(item => (
                <option key={item.name} value={item.name}>{themeLabel(item.name)} ({item.count})</option>
              ))}
            </select>
          </label>

          {puzzle && done && (
            <div className="puzzle-info">
              <div className="section-head compact">
                <h2>Puzzle {puzzle.rating}</h2>
                <a href={`https://lichess.org/training/${puzzle.id}`} target="_blank" rel="noreferrer">Lichess ↗</a>
              </div>
              <div className="puzzle-themes">
                {puzzle.themes.map(item => <span key={item}>{themeLabel(item)}</span>)}
              </div>
            </div>
          )}
        </div>

        <footer className="panel-actions">
          {done || phase === 'idle' ? (
            <button className="primary-action" type="button" onClick={() => loadPuzzle()}>Next puzzle</button>
          ) : (
            <button className="primary-action" type="button" onClick={showHint} disabled={!playing}>Hint</button>
          )}
          <div className="quiet-actions">
            {done ? (
              <button type="button" className="quiet-action" onClick={retry}>Retry</button>
            ) : (
              <button type="button" className="quiet-action" onClick={showSolution} disabled={!puzzle || phase === 'loading' || phase === 'setup'}>
                Solution
              </button>
            )}
            {!done && (
              <button type="button" className="quiet-action" onClick={skip} disabled={phase === 'loading'}>Skip</button>
            )}
            <button type="button" className="quiet-action" onClick={() => setFlipOverride(value => !value)} aria-label="Flip board" title="Flip board">⇅ Flip</button>
          </div>
        </footer>
      </aside>
    </section>
  )
}
