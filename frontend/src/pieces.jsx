import React from 'react'

// Chess pieces drawn as inline SVG (45×45 grid) so they look identical on every platform.
// Unicode glyphs depend on system fonts, and U+265F (♟) is also an emoji, which phones render in colour.

const BASE = 'M10.5 39.5h24v-2.6c0-.9-.7-1.6-1.6-1.6H12.1c-.9 0-1.6.7-1.6 1.6z'

const SHAPES = {
  Pawn: {
    body: [
      'M22.5 8.5a4.6 4.6 0 0 0-3.7 7.3 6.4 6.4 0 0 0-.9 10.1c-2.9 1.6-5.6 4.8-6.3 9.4h21.8c-.7-4.6-3.4-7.8-6.3-9.4a6.4 6.4 0 0 0-.9-10.1 4.6 4.6 0 0 0-3.7-7.3z'
    ],
    detail: []
  },
  Rook: {
    body: [
      'M13.5 35.3l1.8-3.8h14.4l1.8 3.8z',
      'M16.2 31.5l1-12.5h10.6l1 12.5z',
      'M13.8 19V10h3.9v3h3v-3h3.6v3h3v-3h3.9v9z'
    ],
    detail: ['M15.3 19h14.4', 'M16.5 28h12']
  },
  Knight: {
    body: [
      'M14.2 35.3c-.4-4.3 1.5-7.4 4.7-9.8 1.9-1.4 2.8-2.9 2.6-4.6-1.2 1-2.6 1.5-4.1 1.9-1.6.4-2.7 1.4-3.6 2.5-1.1 1.3-3.2 1.1-3.9-.4-.6-1.3-.2-2.5.7-3.6l4.4-5.6c.8-1 1.3-2.2 1.5-3.4.2-1 .9-1.7 1.7-2.1l-.4-3.2c1.7.4 3 1.4 3.9 2.6l1.6-2.2c.9 1 1.4 2.2 1.5 3.4 5.4 2.2 8.5 7.6 8.5 15.1 0 4-.6 7.7-1.6 9.9z'
    ],
    detail: ['M17.6 16.6h.01', 'M12.6 22.8c.6-.2 1.1-.6 1.4-1.1']
  },
  Bishop: {
    body: [
      'M14 35.3c1.2-2.4 3.4-4 6.2-4.6h4.6c2.8.6 5 2.2 6.2 4.6z',
      'M17.6 30.7l-.8-2.6h11.4l-.8 2.6z',
      'M22.5 11.2c-4.6 3.5-7.1 7.4-7.1 10.9 0 3 1.9 5 3.9 6h6.4c2-1 3.9-3 3.9-6 0-3.5-2.5-7.4-7.1-10.9z',
      'M22.5 6.4a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8z'
    ],
    detail: ['M25.6 15.6l-4.7 4.7', 'M17.6 28.1h9.8']
  },
  Queen: {
    body: [
      'M13 35.3l1.6-3.8h15.8l1.6 3.8z',
      'M14.6 31.5L11.4 15.6l5.6 8.6.4-11.5 4 10.6 1.1-12.1 1.1 12.1 4-10.6.4 11.5 5.6-8.6-3.2 15.9z',
      'M11.4 11.4a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z',
      'M17.4 8.5a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z',
      'M22.5 7a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z',
      'M27.6 8.5a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z',
      'M33.6 11.4a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z'
    ],
    detail: ['M15.4 27.6h14.2']
  },
  King: {
    body: [
      'M13 35.3l1.4-3.8h16.2l1.4 3.8z',
      'M14.4 31.5c-3.9-4.6-5.1-9.6-2.8-12.6 2.6-3.4 7.6-2.4 10.9 2.6 3.3-5 8.3-6 10.9-2.6 2.3 3-.1 8-2.8 12.6z',
      'M22.5 13.8c-1.9 0-3 1.4-3 2.9 0 1.9 3 5 3 5s3-3.1 3-5c0-1.5-1.1-2.9-3-2.9z'
    ],
    detail: ['M14.8 27.4h15.4'],
    cross: 'M22.5 5.2v8.4M19 8.6h7'
  }
}

const PALETTE = {
  white: { fill: '#fbfaf6', stroke: '#2a2926', detail: '#2a2926' },
  black: { fill: '#34322e', stroke: '#141311', detail: '#d9d5cb' }
}

const SYMBOL_TO_PIECE = {
  '♔': ['King', 'white'], '♕': ['Queen', 'white'], '♖': ['Rook', 'white'],
  '♗': ['Bishop', 'white'], '♘': ['Knight', 'white'], '♙': ['Pawn', 'white'],
  '♚': ['King', 'black'], '♛': ['Queen', 'black'], '♜': ['Rook', 'black'],
  '♝': ['Bishop', 'black'], '♞': ['Knight', 'black'], '♟': ['Pawn', 'black']
}

/** Maps a server-provided Unicode piece symbol (e.g. in captured-piece lists) to { name, color }. */
export function pieceFromSymbol(symbol) {
  const match = SYMBOL_TO_PIECE[symbol?.replace(/[︎️]/g, '')]
  return match ? { name: match[0], color: match[1] } : null
}

export function PieceIcon({ name, color, className = '', title, decorative = false }) {
  const shape = SHAPES[name]
  if (!shape) return null
  const palette = PALETTE[color === 'black' ? 'black' : 'white']
  const label = title ?? `${color} ${name.toLowerCase()}`

  return (
    <svg
      className={`piece-svg ${className}`}
      viewBox="0 0 45 45"
      focusable="false"
      {...(decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': label })}
    >
      <g fill={palette.fill} stroke={palette.stroke} strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round">
        <path d={BASE} />
        {shape.body.map((d, index) => <path key={index} d={d} />)}
      </g>
      {shape.cross && (
        <path d={shape.cross} fill="none" stroke={palette.stroke} strokeWidth="2.6" strokeLinecap="round" />
      )}
      {shape.cross && (
        <path d={shape.cross} fill="none" stroke={palette.fill} strokeWidth="1" strokeLinecap="round" />
      )}
      {shape.detail.length > 0 && (
        <g fill="none" stroke={palette.detail} strokeWidth={name === 'Knight' ? 2.2 : 1.2} strokeLinecap="round" strokeLinejoin="round">
          {shape.detail.map((d, index) => <path key={index} d={d} />)}
        </g>
      )}
    </svg>
  )
}
