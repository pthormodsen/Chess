export function formatApiError(err) {
  if (err.response?.status === 404) {
    return 'Chess backend was reached, but /api/game was not found. You are probably talking to the wrong server process.'
  }
  if (err.response?.data?.error) return err.response.data.error
  if (err.message === 'Network Error') {
    return 'Cannot reach the Chess backend. Start Spring Boot on port 8081, then reload this page.'
  }
  return err.message
}
