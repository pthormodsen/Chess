import { describe, expect, it } from 'vitest'
import { ratedResult, rememberRecent, themeLabel } from './puzzle.jsx'

describe('puzzle rating', () => {
  it('gains for a win and loses for a miss, more while the rating settles', () => {
    const fresh = { rating: 1200, played: 0 }
    const settled = { rating: 1200, played: 50 }
    expect(ratedResult(fresh, 1200, true)).toEqual({ rating: 1220, delta: 20 })
    expect(ratedResult(settled, 1200, true)).toEqual({ rating: 1210, delta: 10 })
    expect(ratedResult(settled, 1200, false).delta).toBe(-10)
  })

  it('rewards beating a harder puzzle more and never drops below 100', () => {
    const stats = { rating: 1200, played: 50 }
    expect(ratedResult(stats, 1600, true).delta).toBeGreaterThan(ratedResult(stats, 800, true).delta)
    expect(ratedResult({ rating: 105, played: 50 }, 100, false).rating).toBe(100)
  })
})

describe('puzzle helpers', () => {
  it('labels themes', () => {
    expect(themeLabel('mateIn2')).toBe('Mate in 2')
    expect(themeLabel('hangingPiece')).toBe('Hanging piece')
  })

  it('keeps recent ids unique, newest last, bounded', () => {
    expect(rememberRecent({ recent: ['a', 'b', 'c'] }, 'a')).toEqual(['b', 'c', 'a'])
    const many = Array.from({ length: 200 }, (_, i) => `p${i}`)
    const kept = rememberRecent({ recent: many }, 'new')
    expect(kept).toHaveLength(150)
    expect(kept.at(-1)).toBe('new')
  })
})
