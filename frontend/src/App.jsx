import React, { useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import { PieceIcon, pieceFromSymbol } from './pieces.jsx'
import { BoardArrow } from './board.jsx'
import { playSound } from './sounds.js'
import PuzzleTrainer from './puzzle.jsx'
import {
  AnalysisProgress, ClassIcon, CoachCard, EvalBar, EvalGraph, ExplorePanel, ReviewMoveList, ReviewNav, ReviewSummary,
  classMeta, evalAtPly, exploreEvaluation, findKeyMoment, piecesFromFen, uciSquares
} from './review.jsx'

const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']

function squareName(col, row) {
  return `${files[col]}${8 - row}`
}

function sameSquare(a, b) {
  return a && b && a.col === b.col && a.row === b.row
}

function splitCaptures(value) {
  if (!value || value === '-') return []
  return value.split(' ').filter(Boolean)
}

function formatApiError(err) {
  if (err.response?.status === 404) {
    return 'Chess backend was reached, but /api/game was not found. You are probably talking to the wrong server process.'
  }
  if (err.response?.data?.error) return err.response.data.error
  if (err.message === 'Network Error') {
    return 'Cannot reach the Chess backend. Start Spring Boot on port 8081, then reload this page.'
  }
  return err.message
}

// Pieces after a move, for showing an explored move at once while Stockfish judges it.
// Handles castling (the rook moves too), en passant and promotion, so the board is right immediately.
function withMoveApplied(pieces, move) {
  const list = pieces ?? []
  const mover = list.find(piece => piece.col === move.fromCol && piece.row === move.fromRow)
  if (!mover) return list
  const castling = mover.name === 'King' && Math.abs(move.toCol - move.fromCol) === 2
  const rookFrom = castling ? (move.toCol > move.fromCol ? 7 : 0) : null
  const rookTo = castling ? (move.toCol > move.fromCol ? 5 : 3) : null
  const enPassant = mover.name === 'Pawn' && move.fromCol !== move.toCol
    && !list.some(piece => piece.col === move.toCol && piece.row === move.toRow)
  const promotes = mover.name === 'Pawn' && (move.toRow === 0 || move.toRow === 7)
  return list
    .filter(piece => !(piece.col === move.toCol && piece.row === move.toRow))
    .filter(piece => !(enPassant && piece.col === move.toCol && piece.row === move.fromRow))
    .map(piece => {
      if (piece === mover) {
        const name = promotes ? PROMOTION_NAMES[move.promotion ?? 'q'] : piece.name
        return { ...piece, name, col: move.toCol, row: move.toRow }
      }
      if (castling && piece.name === 'Rook' && piece.color === mover.color && piece.row === move.fromRow && piece.col === rookFrom) {
        return { ...piece, col: rookTo }
      }
      return piece
    })
}

let slideCounter = 0

/** SAN of the last move in displayMoves entries like "12. Nf3 Nc6" or "1... e5". */
function lastSan(displayMoves) {
  const entry = displayMoves?.[displayMoves.length - 1]
  return entry ? entry.trim().split(/\s+/).pop() : null
}

function sameMove(a, b) {
  return a && b && a.fromCol === b.fromCol && a.fromRow === b.fromRow && a.toCol === b.toCol && a.toRow === b.toRow
}

const FLIP_KEY = 'chess.boardFlip'

// The player's "flip board" choice per view, remembered across reloads (per browser).
function readFlipPrefs() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(FLIP_KEY) ?? '{}')
    return { live: stored.live === true, review: stored.review === true }
  } catch (ignored) {
    return { live: false, review: false }
  }
}

function writeFlipPrefs(prefs) {
  try {
    window.localStorage.setItem(FLIP_KEY, JSON.stringify(prefs))
  } catch (ignored) {
    // Storage blocked: the flip still applies for this page view.
  }
}

const SESSION_KEY = 'chess.sessionId'
let memorySessionId = null

function generateSessionId() {
  return window.crypto?.randomUUID?.() ?? `session-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function getSessionId() {
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

const PIECE_VALUES = { Pawn: 1, Knight: 3, Bishop: 3, Rook: 5, Queen: 9, King: 0 }

// White material minus Black material, from the pieces on the board (so promotions count correctly).
function materialBalance(pieces) {
  return (pieces ?? []).reduce((sum, piece) => {
    const value = PIECE_VALUES[piece.name] ?? 0
    return piece.color === 'white' ? sum + value : sum - value
  }, 0)
}

const PROMOTION_PIECES = ['q', 'r', 'b', 'n']
const PROMOTION_NAMES = { q: 'Queen', r: 'Rook', b: 'Bishop', n: 'Knight' }
const DRAG_THRESHOLD_PX = 6
const POLL_INTERVAL_MS = 1000
const boardIndexes = [0, 1, 2, 3, 4, 5, 6, 7]
const noLegalMoves = { origin: null, moves: [] }

// Top-level view, kept in the URL hash so a reload (or a shared link) opens the same one.
function modeFromHash() {
  return window.location.hash === '#puzzles' ? 'puzzles' : 'play'
}

export default function App() {
  const [mode, setMode] = useState(modeFromHash)
  const modeRef = useRef(mode)
  modeRef.current = mode
  const [game, setGame] = useState(null)
  const [selected, setSelected] = useState(null)
  // Legal targets are stored with the square they were fetched for, so stale responses can't be used for another piece.
  const [legalFor, setLegalFor] = useState(noLegalMoves)
  const [pending, setPending] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [error, setError] = useState(null)
  const [connectionError, setConnectionError] = useState(null)
  const [timeControl, setTimeControl] = useState(10)
  const [humanVsHuman, setHumanVsHuman] = useState(true)
  const [playAs, setPlayAs] = useState('white')
  const [engineElo, setEngineElo] = useState(1200)
  const [copyLabel, setCopyLabel] = useState('Copy PGN')
  const [dragGhost, setDragGhost] = useState(null)
  const [promotion, setPromotion] = useState(null)
  const [pgnText, setPgnText] = useState('')
  const [showImport, setShowImport] = useState(false)
  const [importing, setImporting] = useState(false)
  // null = automatic (open until the first move); true/false once the player toggles it.
  const [settingsOpen, setSettingsOpen] = useState(null)
  const [confirmHome, setConfirmHome] = useState(false)
  // Review: the summary screen comes first; "moves" is the move-by-move review.
  const [reviewStage, setReviewStage] = useState('summary')
  // Best line being played on the board: { plyIndex, index } (index into the frame's bestLinePlies).
  const [linePreview, setLinePreview] = useState(null)
  // Explore: your own moves from a review position. { basePly, baseFen, plies: [explored move], index }
  const [explore, setExplore] = useState(null)
  // The explored move waiting for Stockfish, shown on the board right away: { fromCol, fromRow, toCol, toRow }
  const [explorePending, setExplorePending] = useState(null)
  // Your move, shown on the board while the server confirms it (reverted if it is rejected).
  const [optimisticMove, setOptimisticMove] = useState(null)
  // { fromCol, fromRow, toCol, toRow, id }: the piece on the target square slides in from its origin.
  const [slide, setSlide] = useState(null)
  // Legal moves (UCI) per FEN while exploring; positions repeat a lot when stepping back and forth.
  const legalByFenRef = useRef(new Map())
  const reviewScrollRef = useRef(null)

  const boardRef = useRef(null)
  const dragRef = useRef(null)
  const suppressClickRef = useRef(false)
  // Set when a move is dropped by drag: the piece is already where it lands, so it doesn't slide.
  const draggedRef = useRef(false)
  const shownStepRef = useRef(null)
  const legalRequestRef = useRef(0)
  // latest: id of the newest mutation; epoch: bumped when any mutation starts or ends, so polls that overlap one are dropped.
  const mutationRef = useRef({ latest: 0, epoch: 0, inFlight: 0 })
  // Last requested review ply while navigation is in flight, so fast key repeats keep moving instead of re-sending one ply.
  const navRef = useRef({ target: null, inFlight: 0 })
  const wasReviewRef = useRef(false)

  useEffect(() => {
    axios.defaults.headers.common['X-Chess-Session'] = getSessionId()
    let stopped = false
    let timer = null

    async function poll() {
      // The game isn't on screen in puzzle mode; switching back refreshes it at once.
      if (!document.hidden && modeRef.current === 'play') await refreshGame()
      if (!stopped) timer = window.setTimeout(poll, POLL_INTERVAL_MS)
    }

    poll()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [])

  const reviewFrame = game?.analysisMode ? game?.analysisFrame : null
  const reviewEntries = game?.analysisEntries ?? []
  const previewPlies = linePreview && reviewFrame?.plyIndex === linePreview.plyIndex ? reviewFrame.bestLinePlies ?? [] : []
  const previewPly = previewPlies[linePreview?.index ?? -1] ?? null
  const reviewPly = reviewFrame?.plyIndex ?? -1
  const exploring = Boolean(reviewFrame && explore && explore.basePly === reviewPly)
  const explorePly = exploring && explore.index >= 0 ? explore.plies[explore.index] : null
  // In the move review the board is playable: the side to move in the shown position may try any move.
  const reviewInteractive = Boolean(game?.analysisMode) && reviewStage === 'moves' && !previewPly
  const interactiveFen = reviewInteractive ? (explorePly ? explorePly.fen : reviewFrame?.positionFen ?? null) : null
  const interactiveTurn = interactiveFen ? (interactiveFen.split(' ')[1] === 'w' ? 'white' : 'black') : null
  const positionPieces = previewPly ? piecesFromFen(previewPly.fen) : explorePly ? piecesFromFen(explorePly.fen) : game?.pieces
  const liveOptimistic = optimisticMove && !game?.analysisMode ? optimisticMove : null
  const boardPieces = explorePending ? withMoveApplied(positionPieces, explorePending)
    : liveOptimistic ? withMoveApplied(positionPieces, liveOptimistic)
    : positionPieces

  const piecesBySquare = useMemo(() => {
    const map = new Map()
    for (const piece of boardPieces ?? []) {
      map.set(`${piece.col},${piece.row}`, piece)
    }
    return map
  }, [boardPieces])

  const balance = materialBalance(game?.pieces)
  const whiteCaptures = splitCaptures(game?.capturedByWhite)
  const blackCaptures = splitCaptures(game?.capturedByBlack)
  const lastMove = previewPly ? uciSquares(previewPly.uci)
    : explorePending ? explorePending
    : explorePly ? uciSquares(explorePly.uci)
    : liveOptimistic ? liveOptimistic
    : game?.lastMove
  const disconnected = connectionError && !game
  const engineTurn = Boolean(game?.engineTurn)
  const engineUnavailable = Boolean(game && !game.humanVsHuman && !game.engineEnabled && game.active && !game.analysisMode)
  // Stockfish games: fixed orientation from "Play as". Local games: the side to move sits at the bottom.
  const [flipPrefs, setFlipPrefs] = useState(readFlipPrefs)
  // Live: the side to move in local games, your side vs Stockfish; the flip button inverts that.
  const desiredFlip = (game ? (game.humanVsHuman ? game.turn === 'black' : Boolean(game.enginePlaysWhite)) : false) !== flipPrefs.live
  const [liveFlipped, setLiveFlipped] = useState(false)
  // Review: never depends on whose turn it was. White at the bottom, or your side when you played Stockfish.
  const reviewFlipped = (Boolean(game && !game.humanVsHuman && game.enginePlaysWhite)) !== flipPrefs.review
  const flipped = game?.analysisMode ? reviewFlipped : liveFlipped
  const analysisRunning = analyzing || game?.analysisStatus === 'running'
  const legalMoves = selected && sameSquare(legalFor.origin, selected) ? legalFor.moves : []
  // The move whose classification is shown on the board: the explored move, else the reviewed one.
  const decorate = !previewPly && !explorePending
  const reviewPlayedMove = !decorate ? null : explorePly ? uciSquares(explorePly.uci) : reviewPly >= 0 ? uciSquares(reviewFrame.playedMove) : null
  const reviewBestMove = !decorate ? null : explorePly ? uciSquares(explorePly.bestMove) : reviewPly >= 0 ? uciSquares(reviewFrame.bestMove) : null
  const badgeTag = explorePly
    ? (explorePly.deliveredMate ? 'Best' : explorePly.qualityTag)
    : (reviewFrame?.deliveredMate ? 'Best' : reviewFrame?.qualityTag)
  const reviewMeta = classMeta(badgeTag)
  const showBestArrow = reviewBestMove && !sameMove(reviewBestMove, reviewPlayedMove)
  const nextPreviewPly = previewPlies[(linePreview?.index ?? -1) + 1] ?? null
  const reviewMovesStage = Boolean(game?.analysisMode) && reviewStage === 'moves'
  const plyCount = game?.uciMoves?.length ?? 0

  // Which move the board is showing, as (scope, step): a move slides in and plays a sound only when the
  // step advances by exactly one within the same line (live game, review, best line, your own line).
  const exploreBase = exploring ? explore.index + 1 : 0
  const shownStep = !game ? null
    : !game.analysisMode ? { scope: 'live', n: plyCount + (liveOptimistic ? 1 : 0), move: lastMove, san: lastSan(game.displayMoves) }
    : previewPly ? { scope: `line@${reviewPly}`, n: linePreview.index + 1, move: lastMove, san: previewPly.san }
    : explorePending || explorePly ? { scope: `explore@${reviewPly}`, n: exploreBase + (explorePending ? 1 : 0), move: lastMove, san: explorePending ? null : explorePly.san }
    : { scope: 'review', n: reviewPly, move: reviewPly >= 0 ? uciSquares(reviewFrame?.playedMove) : null, san: reviewFrame?.san }
  const shownPieceCount = boardPieces?.length ?? 0

  useEffect(() => {
    const previous = shownStepRef.current
    shownStepRef.current = shownStep && { ...shownStep, count: shownPieceCount }
    const dragged = draggedRef.current
    draggedRef.current = false
    if (!previous || !shownStep?.move) return
    const fromReviewPosition = previous.scope === 'review' && shownStep.scope.endsWith(`@${previous.n}`) && shownStep.n === 1
    const advanced = (previous.scope === shownStep.scope && shownStep.n === previous.n + 1) || fromReviewPosition
    if (!advanced) {
      if (previous.scope !== shownStep.scope || previous.n !== shownStep.n) setSlide(null)
      return
    }
    setSlide(dragged ? null : { ...shownStep.move, id: ++slideCounter })
    const san = shownStep.san ?? ''
    playSound(/[+#]/.test(san) ? 'check' : shownPieceCount < previous.count || san.includes('x') ? 'capture' : 'move')
  }, [shownStep?.scope, shownStep?.n])

  // Turn the board only after a move has fully landed: never mid-drag, never with the promotion picker open,
  // and never while stepping through a review (orientation stays as it was when review began).
  useEffect(() => {
  if (game?.analysisMode || dragGhost || promotion) return

  if (!game?.humanVsHuman) {
    setLiveFlipped(desiredFlip)
    return
  }

  const timer = window.setTimeout(() => setLiveFlipped(desiredFlip), 250)
  return () => window.clearTimeout(timer)
}, [desiredFlip, game?.humanVsHuman, game?.analysisMode, dragGhost, promotion])

  // Any change of position invalidates the current selection, drag and promotion prompt.
  useEffect(() => {
    clearSelection()
    dragRef.current = null
    setDragGhost(null)
    setPromotion(null)
  }, [plyCount, game?.analysisMode, game?.active, interactiveFen])

  useEffect(() => {
    function handleHash() {
      setMode(modeFromHash())
    }
    window.addEventListener('hashchange', handleHash)
    return () => window.removeEventListener('hashchange', handleHash)
  }, [])

  useEffect(() => {
    if (mode === 'play') refreshGame()
  }, [mode])

  function switchMode(next) {
    if (next === mode) return
    const url = next === 'puzzles' ? '#puzzles' : window.location.pathname + window.location.search
    window.history.pushState(null, '', url)
    setMode(next)
  }

  // Entering review always opens the summary; leaving it clears any line preview.
  useEffect(() => {
    const inReviewNow = Boolean(game?.analysisMode)
    if (inReviewNow && !wasReviewRef.current) setReviewStage('summary')
    if (!inReviewNow) {
      setLinePreview(null)
      setExplore(null)
    }
    wasReviewRef.current = inReviewNow
  }, [game?.analysisMode])

  useEffect(() => {
    setLinePreview(null)
    setExplore(null)
    // Once the server state has caught up with every request, key presses start from the shown ply again.
    if (navRef.current.inFlight === 0) navRef.current.target = null
  }, [reviewPly])

  // Starting your own line brings its panel (line + verdict) into view.
  useEffect(() => {
    if (exploring) reviewScrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }, [exploring])

  useEffect(() => {
    function handleReviewKeys(event) {
      const target = event.target
      const tagName = target?.tagName?.toLowerCase()
      const isTyping = tagName === 'input' || tagName === 'select' || tagName === 'textarea' || target?.isContentEditable
      if (modeRef.current !== 'play' || !game?.analysisMode || pending || isTyping || confirmHome || event.altKey || event.metaKey || event.ctrlKey) return
      if (event.key === 'f' || event.key === 'F') {
        event.preventDefault()
        toggleFlip()
        return
      }

      // While a best line is on the board, the arrows walk through that line instead.
      if (linePreview && previewPlies.length) {
        const keys = { ArrowLeft: -1, ArrowRight: 1 }
        if (event.key === 'Escape') {
          event.preventDefault()
          setLinePreview(null)
        } else if (event.key in keys) {
          event.preventDefault()
          const next = linePreview.index + keys[event.key]
          if (next < 0) setLinePreview(null)
          else setLinePreview({ ...linePreview, index: Math.min(previewPlies.length - 1, next) })
        }
        return
      }

      // While exploring your own line, the arrows walk through it; Escape returns to the review.
      if (exploring) {
        if (event.key === 'Escape') {
          event.preventDefault()
          exitExplore()
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          event.preventDefault()
          const next = explore.index + (event.key === 'ArrowRight' ? 1 : -1)
          setExplore({ ...explore, index: Math.max(-1, Math.min(explore.plies.length - 1, next)) })
        }
        return
      }

      const total = game.analysisFrame?.totalPlies ?? 0
      const current = navRef.current.target ?? reviewPly
      let destination = null
      if (event.key === 'ArrowLeft') destination = current - 1
      else if (event.key === 'ArrowRight') destination = current + 1
      else if (event.key === 'Home') destination = -1
      else if (event.key === 'End') destination = total - 1
      else if (event.key === 'ArrowDown') destination = findKeyMoment(reviewEntries, current, 1)
      else if (event.key === 'ArrowUp') destination = findKeyMoment(reviewEntries, current, -1)
      else return
      event.preventDefault()
      if (destination == null) return
      gotoPly(Math.max(-1, Math.min(total - 1, destination)))
    }

    window.addEventListener('keydown', handleReviewKeys)
    return () => window.removeEventListener('keydown', handleReviewKeys)
  }, [game?.analysisMode, reviewPly, reviewEntries, linePreview, previewPlies, pending, confirmHome, reviewStage, flipPrefs, exploring, explore])

  async function refreshGame() {
    const tracker = mutationRef.current
    const epochAtStart = tracker.epoch
    try {
      const response = await axios.get('/api/game')
      // Drop polls that overlapped a mutation: the server may have answered them before the mutation applied.
      if (epochAtStart === tracker.epoch && tracker.inFlight === 0) {
        applyGameState(response.data)
      }
      setConnectionError(null)
    } catch (err) {
      setConnectionError(formatApiError(err))
    }
  }

  // Runs a state-changing request; `current` is false when a newer mutation started meanwhile.
  async function mutate(request) {
    const tracker = mutationRef.current
    const id = ++tracker.latest
    tracker.epoch += 1
    tracker.inFlight += 1
    try {
      const response = await request()
      return { response, current: id === tracker.latest }
    } finally {
      tracker.inFlight -= 1
      tracker.epoch += 1
    }
  }

  function applyGameState(nextGame) {
    setGame(nextGame)
    if (typeof nextGame.timeMinutes === 'number') setTimeControl(nextGame.timeMinutes)
    if (typeof nextGame.humanVsHuman === 'boolean') setHumanVsHuman(nextGame.humanVsHuman)
    if (typeof nextGame.engineElo === 'number') setEngineElo(nextGame.engineElo)
    if (typeof nextGame.enginePlaysWhite === 'boolean') {
      setPlayAs(nextGame.enginePlaysWhite ? 'black' : 'white')
    }
  }

  function buildSettingsPayload() {
    return {
      minutes: timeControl,
      humanVsHuman,
      enginePlaysWhite: !humanVsHuman && playAs === 'black',
      engineElo
    }
  }

  function canMovePiece(piece) {
    if (reviewInteractive) {
      return Boolean(piece && interactiveFen && !explorePending && !promotion && piece.color === interactiveTurn)
    }
    const waitingForStart = game?.awaitingStart && !game?.humanVsHuman
    return Boolean(piece && game?.active && !pending && !promotion && !game?.analysisMode && !engineTurn && !waitingForStart && piece.color === game?.turn)
  }

  function clearSelection() {
    legalRequestRef.current += 1
    setSelected(null)
    setLegalFor(noLegalMoves)
  }

  async function newGame() {
    setPending(true)
    clearSelection()
    setSettingsOpen(null)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/new', buildSettingsPayload()))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setPending(false)
    }
  }

  // Setup → playing. Stockfish (if it has White) makes its first move only after this.
  async function startGame() {
    setPending(true)
    clearSelection()
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/start', buildSettingsPayload()))
      if (current) applyGameState(response.data)
      setSettingsOpen(null)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      await refreshGame()
    } finally {
      setPending(false)
    }
  }

  async function updateSettings(next) {
    const nextSettings = {
      minutes: next.timeControl ?? timeControl,
      humanVsHuman: next.humanVsHuman ?? humanVsHuman,
      enginePlaysWhite: !(next.humanVsHuman ?? humanVsHuman) && (next.playAs ?? playAs) === 'black',
      engineElo: next.engineElo ?? engineElo
    }

    if (typeof next.timeControl === 'number') setTimeControl(next.timeControl)
    if (typeof next.humanVsHuman === 'boolean') setHumanVsHuman(next.humanVsHuman)
    if (typeof next.playAs === 'string') setPlayAs(next.playAs)
    if (typeof next.engineElo === 'number') setEngineElo(next.engineElo)

    try {
      const { response, current } = await mutate(() => axios.post('/api/game/settings', nextSettings))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      await refreshGame() // roll the optimistic controls back to the server's settings
    }
  }

  async function fetchLegalMoves(origin) {
    if (reviewInteractive) return exploreLegalTargets(origin)
    const response = await axios.get('/api/game/legal', { params: { col: origin.col, row: origin.row } })
    return response.data
  }

  // Legal targets from one square in the shown review/explore position, in the server's {col,row,capture} shape.
  async function exploreLegalTargets(origin) {
    const fen = interactiveFen
    let legal = legalByFenRef.current.get(fen)
    if (!legal) {
      const response = await axios.post('/api/game/explore/legal', { fen })
      legal = response.data.legalMoves
      legalByFenRef.current.set(fen, legal)
    }
    const from = squareName(origin.col, origin.row)
    const mover = piecesBySquare.get(`${origin.col},${origin.row}`)
    const seen = new Set()
    return legal
      .filter(uci => uci.startsWith(from))
      .map(uci => uciSquares(uci))
      .filter(move => move && !seen.has(`${move.toCol},${move.toRow}`) && seen.add(`${move.toCol},${move.toRow}`))
      .map(move => ({
        col: move.toCol,
        row: move.toRow,
        capture: piecesBySquare.has(`${move.toCol},${move.toRow}`) || (mover?.name === 'Pawn' && move.fromCol !== move.toCol)
      }))
  }

  // Plays a move of your own from the shown review position; Stockfish judges it like a game move.
  async function exploreMove(origin, target, promotionPiece) {
    const fen = interactiveFen
    if (!fen || !reviewFrame) return
    const uci = `${squareName(origin.col, origin.row)}${squareName(target.col, target.row)}${promotionPiece ?? ''}`
    const base = exploring ? explore : { basePly: reviewPly, baseFen: reviewFrame.positionFen, plies: [], index: -1 }
    setPromotion(null)
    clearSelection()
    setExplorePending({ fromCol: origin.col, fromRow: origin.row, toCol: target.col, toRow: target.row, promotion: promotionPiece })
    try {
      const response = await axios.post('/api/game/explore', { fen, move: uci })
      const ply = response.data
      legalByFenRef.current.set(ply.fen, ply.legalMoves)
      setExplore(current => {
        const from = current && current.basePly === base.basePly ? current : base
        const plies = [...from.plies.slice(0, from.index + 1), ply]
        return { ...from, plies, index: plies.length - 1 }
      })
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setExplorePending(null)
    }
  }

  function exitExplore() {
    setExplore(null)
    clearSelection()
  }

  async function selectPiece(piece) {
    if (!canMovePiece(piece)) {
      clearSelection()
      return
    }

    const origin = { col: piece.col, row: piece.row }
    const requestId = ++legalRequestRef.current
    setSelected(origin)
    setLegalFor({ origin, moves: [] })
    try {
      const moves = await fetchLegalMoves(origin)
      if (requestId === legalRequestRef.current) setLegalFor({ origin, moves })
    } catch (err) {
      if (requestId === legalRequestRef.current) setError(formatApiError(err))
    }
  }

  // Validates a from→to pair against the legal targets of that exact origin, then asks for a promotion piece if needed.
  async function attemptMove(origin, target) {
    if (sameSquare(origin, target)) return
    const piece = piecesBySquare.get(`${origin.col},${origin.row}`)
    if (!canMovePiece(piece)) return

    let moves = sameSquare(legalFor.origin, origin) ? legalFor.moves : null
    if (!moves || moves.length === 0) {
      try {
        moves = await fetchLegalMoves(origin)
      } catch (err) {
        setError(formatApiError(err))
        return
      }
    }
    if (!moves.some(move => sameSquare(move, target))) return

    if (piece.name === 'Pawn' && (target.row === 0 || target.row === 7)) {
      setPromotion({ origin, target, color: piece.color })
      return
    }
    submitMove(origin, target)
  }

  async function submitMove(origin, target, promotionPiece) {
    if (reviewInteractive) {
      exploreMove(origin, target, promotionPiece)
      return
    }
    const scrollPosition = { left: window.scrollX, top: window.scrollY }
    setPending(true)
    setPromotion(null)
    setOptimisticMove({ fromCol: origin.col, fromRow: origin.row, toCol: target.col, toRow: target.row, promotion: promotionPiece })
    let failed = false
    try {
      const payload = { fromCol: origin.col, fromRow: origin.row, toCol: target.col, toRow: target.row }
      if (promotionPiece) payload.promotion = promotionPiece
      const { response, current } = await mutate(() => axios.post('/api/game/move', payload))
      if (current) applyGameState(response.data.state)
      window.requestAnimationFrame(() => window.scrollTo(scrollPosition.left, scrollPosition.top))
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
      failed = true
    } finally {
      clearSelection()
      setOptimisticMove(null)
      setPending(false)
    }
    if (failed) await refreshGame()
  }

  function handleSquareClick(col, row) {
    if (suppressClickRef.current) return
    draggedRef.current = false
    if (reviewInteractive) {
      if (pending || promotion || explorePending) return
    } else {
      if (pending || promotion || !game?.active || engineTurn || game?.analysisMode) return
      if (game?.awaitingStart && !game?.humanVsHuman) return
    }

    const target = { col, row }
    const piece = piecesBySquare.get(`${col},${row}`)

    if (selected && legalMoves.some(move => sameSquare(move, target))) {
      attemptMove(selected, target)
      return
    }

    if (piece && sameSquare(selected, target)) {
      clearSelection()
      return
    }

    if (piece) {
      selectPiece(piece)
      return
    }

    clearSelection()
  }

  function squareFromPoint(clientX, clientY) {
    const rect = boardRef.current?.getBoundingClientRect()
    if (!rect) return null
    const displayCol = Math.floor(((clientX - rect.left) / rect.width) * 8)
    const displayRow = Math.floor(((clientY - rect.top) / rect.height) * 8)
    if (displayCol < 0 || displayCol > 7 || displayRow < 0 || displayRow > 7) return null
    return {
      col: flipped ? 7 - displayCol : displayCol,
      row: flipped ? 7 - displayRow : displayRow
    }
  }

  // Pointer events (mouse, touch and pen) replace HTML5 drag-and-drop, which mobile browsers don't support.
  function handlePiecePointerDown(event, piece) {
    if (event.button !== 0 || !canMovePiece(piece)) return
    dragRef.current = {
      pointerId: event.pointerId,
      piece,
      origin: { col: piece.col, row: piece.row },
      startX: event.clientX,
      startY: event.clientY,
      active: false
    }
    try { event.currentTarget.setPointerCapture(event.pointerId) } catch (ignored) {}
  }

  function handlePiecePointerMove(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    if (!drag.active) {
      if (Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < DRAG_THRESHOLD_PX) return
      drag.active = true
      if (!sameSquare(selected, drag.origin)) selectPiece(drag.piece)
    }
    const rect = boardRef.current?.getBoundingClientRect()
    if (!rect) return
    setDragGhost({ piece: drag.piece, x: event.clientX - rect.left, y: event.clientY - rect.top })
  }

  function handlePiecePointerUp(event) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    setDragGhost(null)
    if (!drag.active) return // a tap: the click handler selects or moves

    // The click that follows a drag must not re-select or move.
    suppressClickRef.current = true
    window.setTimeout(() => { suppressClickRef.current = false }, 0)
    const target = squareFromPoint(event.clientX, event.clientY)
    if (target) {
      draggedRef.current = true
      attemptMove(drag.origin, target)
    }
  }

  function handlePiecePointerCancel() {
    dragRef.current = null
    setDragGhost(null)
  }

  // Home/brand: open the start/setup state. A game in progress is never discarded without confirmation.
  function goHome() {
    if (mode !== 'play') {
      switchMode('play')
      return
    }
    if (plyCount > 0 || game?.analysisMode || (game && !game.awaitingStart && game.active)) {
      setConfirmHome(true)
      return
    }
    showSetupHome()
  }

  function showSetupHome() {
    setShowImport(false)
    setSettingsOpen(true)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function startOverFromHome() {
    setConfirmHome(false)
    await newGame()
    showSetupHome()
  }

  async function copyPgn() {
    try {
      const response = await axios.get('/api/game/pgn')
      await navigator.clipboard.writeText(response.data.pgn)
      setCopyLabel('Copied')
      window.setTimeout(() => setCopyLabel('Copy PGN'), 1400)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  // The server analyzes in the background; polling picks up analysisStatus "running" → "ready"/"failed".
  async function analyzeGame() {
    setAnalyzing(true)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analyze'))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setAnalyzing(false)
    }
  }

  async function importPgn() {
    if (!pgnText.trim()) {
      setError('Paste PGN text or upload a .pgn file first.')
      return
    }
    setImporting(true)
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/import-pgn', { pgn: pgnText }))
      if (current) applyGameState(response.data)
      clearSelection()
      setShowImport(false)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    } finally {
      setImporting(false)
    }
  }

  async function handlePgnFile(event) {
    const file = event.target.files?.[0]
    if (!file) return
    try {
      setPgnText(await file.text())
      setShowImport(true)
      setError(null)
    } catch (err) {
      setError(`Could not read PGN file: ${err.message}`)
    } finally {
      event.target.value = ''
    }
  }

  // Jumps the review to a ply (-1 = start). Any navigation also moves from the summary into the review.
  async function gotoPly(ply) {
    if (ply == null) return
    setReviewStage('moves')
    const nav = navRef.current
    nav.target = ply
    nav.inFlight += 1
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analysis/goto', { ply }))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      nav.target = null
      setError(formatApiError(err))
    } finally {
      nav.inFlight -= 1
    }
  }

  function toggleFlip() {
    const view = game?.analysisMode ? 'review' : 'live'
    setFlipPrefs(prev => {
      const next = { ...prev, [view]: !prev[view] }
      writeFlipPrefs(next)
      return next
    })
  }

  function playLine(index) {
    if (!reviewFrame?.bestLinePlies?.length) return
    setLinePreview({ plyIndex: reviewFrame.plyIndex, index })
  }

  async function exitAnalysis() {
    try {
      const { response, current } = await mutate(() => axios.post('/api/game/analysis/exit'))
      if (current) applyGameState(response.data)
      setError(null)
    } catch (err) {
      setError(formatApiError(err))
    }
  }

  const topSide = flipped ? 'white' : 'black'
  const bottomSide = flipped ? 'black' : 'white'
  const engineSide = game?.enginePlaysWhite ? 'white' : 'black'
  const playerName = side => {
    if (game?.humanVsHuman ?? true) return side === 'white' ? 'White' : 'Black'
    return side === engineSide ? 'Stockfish' : 'You'
  }
  const sideBar = side => (
    <PlayerBar
      side={side}
      name={playerName(side)}
      detail={!(game?.humanVsHuman ?? true) && side === engineSide ? `${game?.engineElo ?? engineElo}` : null}
      clock={(side === 'white' ? game?.whiteClock : game?.blackClock) ?? '10:00'}
      active={game?.turn === side && game?.active && !game?.awaitingStart}
      clockRunning={game?.turn === side && game?.active && game?.clockStarted && !game?.analysisMode}
      untimed={Boolean(game?.untimed)}
      showClock={humanVsHuman && !game?.untimed}
      thinking={engineTurn && side === engineSide}
      captures={side === 'white' ? whiteCaptures : blackCaptures}
      advantage={side === 'white' ? balance : -balance}
    />
  )
  const headline = disconnected
    ? 'Game server unavailable'
    : game?.analysisMode
      ? 'Game review'
    : engineUnavailable
      ? 'Stockfish unavailable'
      : engineTurn
        ? 'Engine thinking…'
        : game?.status ?? 'Loading board'
  const result = game?.result && game.result !== '*' ? game.result : null
  const inReview = Boolean(game?.analysisMode)
  // Setup is open before the first move and tucked away once the game is under way, unless the player toggles it.
  const showSetup = settingsOpen ?? Boolean(game ? game.awaitingStart : plyCount === 0)
  // Settings configure the next game; once a game is running they are locked until New game.
  const setupLocked = Boolean(game && !game.awaitingStart)
  const setupSummary = humanVsHuman
    ? `${timeControl} min · 2 players`
    : `vs Stockfish · ${playAs === 'white' ? 'White' : 'Black'} · ${engineElo}`
  const analysisNotice = !inReview && game?.analysisStatus === 'failed' ? (game.analysisReport ?? [])[0] : null
  const showProgress = !inReview && game?.analysisStatus === 'running'
  const reviewEval = explorePly
    ? (explorePly.deliveredMate
      ? { pawns: 0, mate: null, win: explorePly.whiteMove ? 100 : 0, result: explorePly.whiteMove ? '1-0' : '0-1' }
      : exploreEvaluation(explorePly))
    : evalAtPly(reviewEntries, reviewPly)
  const reviewMoveLabel = reviewPly >= 0 && reviewFrame
    ? `${reviewFrame.moveNumber}${reviewFrame.whiteMove ? '.' : '...'} ${reviewFrame.san}`
    : 'the starting position'

  return (
    <main className="app-shell">
      <nav className="app-rail" aria-label="Main">
        <button type="button" className="brand" onClick={goHome} title="Home – new game setup">
          <span className="brand-mark" aria-hidden="true">
            <PieceIcon name="Knight" color="white" decorative />
          </span>
          <span className="brand-name">Chess</span>
        </button>
        <div className="rail-links">
          <button type="button" className={`rail-link ${mode === 'play' ? 'active' : ''}`} aria-current={mode === 'play' ? 'page' : undefined} onClick={() => switchMode('play')}>
            <PieceIcon name="Pawn" color="white" decorative />
            <span>Play</span>
          </button>
          <button type="button" className={`rail-link ${mode === 'puzzles' ? 'active' : ''}`} aria-current={mode === 'puzzles' ? 'page' : undefined} onClick={() => switchMode('puzzles')}>
            <PieceIcon name="Knight" color="white" decorative />
            <span>Puzzles</span>
          </button>
        </div>
      </nav>

      {mode === 'puzzles' ? <PuzzleTrainer /> : (
      <section className={`game-layout ${inReview ? 'is-review' : 'is-live'}`}>
        <div className="play-area">
          {sideBar(topSide)}

          {/* Shown over the top player bar (its clock is irrelevant in review), never over the board. */}
          {exploring && !previewPly && (
            <div className="line-banner explore" role="status">
              <span>{explorePending ? 'Thinking…' : `Your line ${explore.index + 1}/${explore.plies.length}`}</span>
              <button type="button" onClick={exitExplore}>Back to review ✕</button>
            </div>
          )}
          {previewPly && (
            <div className="line-banner" role="status">
              <span>Best line {linePreview.index + 1}/{previewPlies.length}</span>
              <button type="button" onClick={() => setLinePreview(null)}>Back to game ✕</button>
            </div>
          )}
          {inReview && reviewEval && (
            <EvalBar
              evaluation={!explorePly && reviewFrame?.deliveredMate
                ? { ...reviewEval, win: reviewFrame.whiteMove ? 100 : 0, result: reviewFrame.whiteMove ? '1-0' : '0-1' }
                : reviewEval}
              flipped={flipped}
            />
          )}
          <div className="board-frame">
            <div className="board" ref={boardRef} role="grid" aria-label="Chess board">
              {showBestArrow && <BoardArrow move={reviewBestMove} flipped={flipped} tone="best-suggestion" label="Best move" />}
              {nextPreviewPly && <BoardArrow move={uciSquares(nextPreviewPly.uci)} flipped={flipped} tone="best-suggestion" label="Next move in line" />}

              {boardIndexes.map(displayRow => boardIndexes.map(displayCol => {
                const row = flipped ? 7 - displayRow : displayRow
                const col = flipped ? 7 - displayCol : displayCol
                const square = { col, row }
                const piece = piecesBySquare.get(`${col},${row}`)
                const dark = (row + col) % 2 === 1
                const selectedHere = sameSquare(selected, square)
                const move = legalMoves.find(item => sameSquare(item, square))
                const lastHere = lastMove && (
                  (lastMove.fromCol === col && lastMove.fromRow === row) ||
                  (lastMove.toCol === col && lastMove.toRow === row)
                )
                const reviewBadgeHere = reviewMeta && reviewPlayedMove && reviewPlayedMove.toCol === col && reviewPlayedMove.toRow === row
                const reviewTintHere = reviewMeta && reviewPlayedMove && (reviewBadgeHere || (reviewPlayedMove.fromCol === col && reviewPlayedMove.fromRow === row))
                const movable = canMovePiece(piece)
                const draggingHere = dragGhost && sameSquare(dragGhost.piece, square)
                const slideHere = slide && slide.toCol === col && slide.toRow === row
                const slideStyle = slideHere ? {
                  '--slide-x': `${((flipped ? 7 - slide.fromCol : slide.fromCol) - displayCol) * 100}%`,
                  '--slide-y': `${((flipped ? 7 - slide.fromRow : slide.fromRow) - displayRow) * 100}%`
                } : undefined

                return (
                  <button
                    key={squareName(col, row)}
                    type="button"
                    className={[
                      'square',
                      dark ? 'dark' : 'light',
                      selectedHere ? 'selected' : '',
                      lastHere && !reviewTintHere ? 'last-move' : '',
                      reviewTintHere ? 'review-square' : '',
                      previewPly && lastHere ? 'line-square' : '',
                      move?.capture ? 'can-capture' : '',
                      move && !move.capture ? 'can-move' : '',
                      slideHere ? 'slide-target' : ''
                    ].filter(Boolean).join(' ')}
                    style={reviewTintHere ? { '--class-color': reviewMeta.color } : undefined}
                    onClick={() => handleSquareClick(col, row)}
                    aria-label={`${squareName(col, row)}${piece ? ` ${piece.color} ${piece.name}` : ''}`}
                  >
                    {displayCol === 0 && <span className="rank-label">{8 - row}</span>}
                    {displayRow === 7 && <span className="file-label">{files[col]}</span>}
                    {reviewBadgeHere && (
                      <span className="review-badge">
                        <ClassIcon tag={badgeTag} size={28} />
                      </span>
                    )}
                    {piece && (
                      <span
                        key={slideHere ? `slide-${slide.id}` : 'piece'}
                        style={slideStyle}
                        className={['piece', piece.color, movable ? 'movable' : '', draggingHere ? 'dragging' : '', slideHere ? 'sliding' : ''].filter(Boolean).join(' ')}
                        onPointerDown={event => handlePiecePointerDown(event, piece)}
                        onPointerMove={handlePiecePointerMove}
                        onPointerUp={handlePiecePointerUp}
                        onPointerCancel={handlePiecePointerCancel}
                      >
                        <PieceIcon name={piece.name} color={piece.color} decorative />
                      </span>
                    )}
                  </button>
                )
              }))}

              {dragGhost && (
                <span
                  className={`piece drag-ghost ${dragGhost.piece.color}`}
                  style={{ left: `${dragGhost.x}px`, top: `${dragGhost.y}px` }}
                  aria-hidden="true"
                >
                  <PieceIcon name={dragGhost.piece.name} color={dragGhost.piece.color} decorative />
                </span>
              )}

              {promotion && (
                <div className="promotion-picker" role="dialog" aria-label="Choose promotion piece">
                  <div className="promotion-options">
                    {PROMOTION_PIECES.map(letter => (
                      <button
                        key={letter}
                        type="button"
                        className={`promotion-option ${promotion.color}`}
                        onClick={() => submitMove(promotion.origin, promotion.target, letter)}
                        aria-label={`Promote to ${PROMOTION_NAMES[letter]}`}
                      >
                        <PieceIcon name={PROMOTION_NAMES[letter]} color={promotion.color} decorative />
                      </button>
                    ))}
                  </div>
                  <button type="button" className="promotion-cancel" onClick={() => { setPromotion(null); clearSelection() }}>
                    Cancel
                  </button>
                </div>
              )}
            </div>
          </div>

          {sideBar(bottomSide)}
        </div>

        <aside className="side-panel">
          <header className="panel-status">
            {!inReview && !result && (
              <span className={`turn-dot ${game?.turn ?? 'white'}`} title={`${game?.turn ?? 'white'} to move`} />
            )}
            <h1 aria-live="polite">{headline}</h1>
            {result && <span className="result-chip">{result}</span>}
          </header>

          {disconnected && (
            <div className="notice warning" role="status">
              <span className="spinner" aria-hidden="true" />
              <span>Can’t reach the game server. Retrying…</span>
            </div>
          )}
          {error && <div className="notice error" role="alert">{error}</div>}
          {connectionError && game && <div className="notice error" role="alert">{connectionError}</div>}
          {analysisNotice && <div className="notice error" role="status">{analysisNotice}</div>}

          {inReview ? (
            <section className={`review-panel stage-${reviewStage}`} aria-label="Game review">
              <div className="review-head">
                <div className="review-tabs" role="tablist" aria-label="Review view">
                  <button type="button" role="tab" aria-selected={reviewStage === 'summary'}
                    className={reviewStage === 'summary' ? 'active' : ''} onClick={() => setReviewStage('summary')}>Summary</button>
                  <button type="button" role="tab" aria-selected={reviewStage === 'moves'}
                    className={reviewStage === 'moves' ? 'active' : ''} onClick={() => setReviewStage('moves')}>Moves</button>
                </div>
                <button type="button" className="icon-action" onClick={toggleFlip} aria-label="Flip board (F)" title="Flip board (F)">⇅</button>
                <button type="button" className="quiet-action" onClick={exitAnalysis}>Exit review</button>
              </div>

              {reviewStage === 'summary' && game.analysisSummary ? (
                <div className="review-scroll">
                  <ReviewSummary
                    summary={game.analysisSummary}
                    entries={reviewEntries}
                    onSelect={gotoPly}
                    onStart={() => gotoPly(reviewPly >= 0 ? reviewPly : 0)}
                  />
                </div>
              ) : (
                <>
                  <div className="review-scroll" ref={reviewScrollRef}>
                    {exploring ? (
                      <ExplorePanel
                        explore={explore}
                        baseLabel={reviewMoveLabel}
                        busy={Boolean(explorePending)}
                        onSelect={index => { clearSelection(); setExplore({ ...explore, index }) }}
                        onExit={exitExplore}
                      />
                    ) : (
                      <>
                        <CoachCard
                          frame={reviewFrame}
                          entries={reviewEntries}
                          linePreview={previewPly ? linePreview : null}
                          onPlayLine={playLine}
                          onStopLine={() => setLinePreview(null)}
                        />
                        {reviewFrame?.positionFen && !previewPly && (
                          <p className="explore-hint">
                            {explorePending ? 'Stockfish is thinking…' : 'Try your own idea: move a piece on the board and Stockfish judges it.'}
                          </p>
                        )}
                      </>
                    )}
                    <EvalGraph entries={reviewEntries} currentPly={reviewPly} onSelect={gotoPly} />
                    <ReviewMoveList entries={reviewEntries} currentPly={reviewPly} onSelect={gotoPly} />
                  </div>
                  <ReviewNav
                    ply={reviewPly}
                    total={reviewFrame?.totalPlies ?? reviewEntries.length}
                    entries={reviewEntries}
                    disabled={!reviewFrame}
                    onGoto={gotoPly}
                  />
                </>
              )}
            </section>
          ) : (
            <>
              <section className={`setup ${showSetup ? 'open' : ''}`}>
                <button
                  type="button"
                  className="setup-toggle"
                  aria-expanded={showSetup}
                  onClick={() => setSettingsOpen(!showSetup)}
                >
                  <span className="setup-label">Game setup</span>
                  <span className="setup-summary">{setupSummary}</span>
                  <span className="chevron" aria-hidden="true">▾</span>
                </button>

                {showSetup && (
                  <div className="setup-body">
                    {setupLocked && <p className="setup-note">Settings are locked during a game. Press New game to change them.</p>}
                    <div className="field">
                      <span className="field-label">Opponent</span>
                      <div className="segmented" role="group" aria-label="Opponent">
                        <button type="button" className={humanVsHuman ? 'active' : ''} aria-pressed={humanVsHuman} onClick={() => updateSettings({ humanVsHuman: true })} disabled={setupLocked}>
                          2 players
                        </button>
                        <button type="button" className={!humanVsHuman ? 'active' : ''} aria-pressed={!humanVsHuman} onClick={() => updateSettings({ humanVsHuman: false })} disabled={setupLocked}>
                          Stockfish
                        </button>
                      </div>
                    </div>

                    {!humanVsHuman && (
                      <div className="field-row">
                        <div className="field">
                          <span className="field-label">Play as</span>
                          <div className="segmented" role="group" aria-label="Choose side">
                            <button type="button" className={playAs === 'white' ? 'active' : ''} aria-pressed={playAs === 'white'} onClick={() => updateSettings({ playAs: 'white' })} disabled={setupLocked}>
                              White
                            </button>
                            <button type="button" className={playAs === 'black' ? 'active' : ''} aria-pressed={playAs === 'black'} onClick={() => updateSettings({ playAs: 'black' })} disabled={setupLocked}>
                              Black
                            </button>
                          </div>
                        </div>
                        <label className="field">
                          <span className="field-label">Strength</span>
                          <select value={engineElo} onChange={event => updateSettings({ engineElo: Number(event.target.value) })} disabled={setupLocked}>
                            {[800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400, 2600, 2800].map(elo => (
                              <option value={elo} key={elo}>Elo {elo}</option>
                            ))}
                          </select>
                        </label>
                      </div>
                    )}

                    {humanVsHuman && (
                      <label className="field">
                        <span className="field-label">Time</span>
                        <select value={timeControl} onChange={event => updateSettings({ timeControl: Number(event.target.value) })} disabled={setupLocked}>
                          <option value={1}>1 min</option>
                          <option value={3}>3 min</option>
                          <option value={5}>5 min</option>
                          <option value={10}>10 min</option>
                          <option value={15}>15 min</option>
                          <option value={30}>30 min</option>
                        </select>
                      </label>
                    )}
                  </div>
                )}
              </section>

              {showProgress && <AnalysisProgress progress={game.analysisProgress} entries={reviewEntries} />}

              <section className={`moves-section ${showProgress ? 'is-hidden' : ''}`} aria-label="Move history">
                <div className="section-head">
                  <h2>Moves</h2>
                  <span>{plyCount} ply</span>
                </div>
                <MoveTable
                  displayMoves={game?.displayMoves ?? []}
                  emptyText={game?.awaitingStart && !game?.humanVsHuman ? 'Press Start game to begin.' : 'Make the first move to start the game.'}
                />
              </section>

              {showImport && (
                <section className="import-drawer" aria-label="Import PGN">
                  <div className="section-head">
                    <h2>Import PGN</h2>
                    <label className="file-picker">
                      Upload file
                      <input type="file" accept=".pgn,.txt" onChange={handlePgnFile} />
                    </label>
                  </div>
                  <textarea
                    value={pgnText}
                    onChange={event => setPgnText(event.target.value)}
                    placeholder='Paste PGN, e.g. 1. e4 c6 2. Qh5 d5'
                    rows={4}
                  />
                  <div className="drawer-actions">
                    <button type="button" className="quiet-action" onClick={() => setShowImport(false)}>Cancel</button>
                    <button type="button" className="secondary-action" onClick={importPgn} disabled={importing}>
                      {importing ? 'Importing…' : 'Import for review'}
                    </button>
                  </div>
                </section>
              )}

              <footer className="panel-actions">
                {game?.awaitingStart ? (
                  <button className="primary-action" type="button" onClick={startGame} disabled={pending}>
                    Start game
                  </button>
                ) : (
                  <button className="primary-action" type="button" onClick={newGame} disabled={pending}>
                    New game
                  </button>
                )}
                <div className="quiet-actions">
                  <button type="button" className="quiet-action" onClick={analyzeGame} disabled={analysisRunning}>
                    {analysisRunning ? 'Analyzing…' : 'Analyze'}
                  </button>
                  <button type="button" className="quiet-action" onClick={copyPgn} title="Copy PGN">
                    {copyLabel === 'Copy PGN' ? 'Copy' : copyLabel}
                  </button>
                  <button type="button" className="quiet-action" onClick={toggleFlip} aria-label="Flip board" title="Flip board">⇅ Flip</button>
                  <button type="button" className={`quiet-action ${showImport ? 'active' : ''}`} onClick={() => setShowImport(value => !value)} title="Import PGN">
                    Import
                  </button>
                </div>
              </footer>
            </>
          )}
        </aside>
      </section>
      )}

      {confirmHome && (
        <ConfirmDialog
          title="Leave this game?"
          onCancel={() => setConfirmHome(false)}
          actions={[
            { label: copyLabel === 'Copy PGN' ? 'Copy PGN first' : copyLabel, onClick: copyPgn, tone: 'quiet' },
            { label: 'Keep playing', onClick: () => setConfirmHome(false), tone: 'secondary', autoFocus: true },
            { label: 'Start new game', onClick: startOverFromHome, tone: 'primary' }
          ]}
        >
          {game?.analysisMode
            ? 'Starting over ends the current review and clears this game from the board.'
            : `Your current game (${plyCount} ${plyCount === 1 ? 'move' : 'moves'}) will be cleared. Copy the PGN first if you want to keep it.`}
        </ConfirmDialog>
      )}
    </main>
  )
}

function ConfirmDialog({ title, children, actions, onCancel }) {
  const dialogRef = useRef(null)

  useEffect(() => {
    const previouslyFocused = document.activeElement
    dialogRef.current?.querySelector('[data-autofocus="true"]')?.focus()
    function handleKey(event) {
      if (event.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', handleKey)
    return () => {
      window.removeEventListener('keydown', handleKey)
      previouslyFocused?.focus?.()
    }
  }, [])

  return (
    <div className="dialog-backdrop" onPointerDown={event => { if (event.target === event.currentTarget) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title" ref={dialogRef}>
        <h2 id="dialog-title">{title}</h2>
        <p>{children}</p>
        <div className="dialog-actions">
          {actions.map(action => (
            <button
              key={action.tone}
              type="button"
              className={`dialog-button ${action.tone}`}
              data-autofocus={action.autoFocus ? 'true' : undefined}
              onClick={action.onClick}
            >
              {action.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

function PlayerBar({ side, name, detail, clock, active, clockRunning, untimed, showClock, thinking, captures, advantage }) {
  return (
    <div className={`player-bar ${active ? 'active' : ''}`}>
      <div className="player-identity">
        <span className={`player-avatar ${side}`} aria-hidden="true">
          <PieceIcon name="King" color={side} decorative />
        </span>
        <div className="player-text">
          <div className="player-name">
            {name}
            {detail && <span className="player-detail">{detail}</span>}
            {thinking && <span className="thinking-dots" aria-label="thinking"><i /><i /><i /></span>}
          </div>
          <div className="captures">
            {captures.map((symbol, index) => {
              const captured = pieceFromSymbol(symbol)
              return captured
                ? <PieceIcon key={`${symbol}-${index}`} name={captured.name} color={captured.color} className={`captured-piece ${captured.color}`} />
                : <span key={`${symbol}-${index}`}>{symbol}</span>
            })}
            {advantage > 0 && (
              <span className="material-advantage" aria-label={`up ${advantage} in material`}>+{advantage}</span>
            )}
          </div>
        </div>
      </div>
      {showClock && (
        <div
          className={`clock ${clockRunning ? 'running' : ''} ${untimed ? 'untimed' : ''}`}
          title={untimed ? 'Imported game – no clock' : undefined}
        >
          {clock}
        </div>
      )}
    </div>
  )
}

// displayMoves entries look like "12. Nf3 Nc6", "12. Nf3" or "1... e5" (when Black moved first).
function parseMoveRows(displayMoves) {
  return displayMoves.map(entry => {
    const match = /^(\d+)\.(\.\.)?\s*(\S+)?(?:\s+(\S+))?/.exec(entry)
    if (!match) return { number: '', white: entry, black: null }
    const [, number, blackFirst, first, second] = match
    return blackFirst ? { number, white: '…', black: first ?? null } : { number, white: first ?? '', black: second ?? null }
  })
}

function MoveTable({ displayMoves, emptyText }) {
  const listRef = useRef(null)
  const rows = parseMoveRows(displayMoves)
  const lastRow = rows.length - 1
  const lastIsBlack = lastRow >= 0 && Boolean(rows[lastRow].black)

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [displayMoves.length, lastIsBlack])

  if (!rows.length) {
    return <div className="moves empty">{emptyText}</div>
  }

  return (
    <ol className="moves" ref={listRef}>
      {rows.map((row, index) => (
        <li className="move-row" key={`${row.number}-${index}`}>
          <span className="move-number">{row.number}.</span>
          <span className={`move-san ${index === lastRow && !lastIsBlack ? 'current' : ''}`}>{row.white}</span>
          <span className={`move-san ${index === lastRow && lastIsBlack ? 'current' : ''}`}>{row.black ?? ''}</span>
        </li>
      ))}
    </ol>
  )
}
