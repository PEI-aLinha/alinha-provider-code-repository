/** Leaves the app for an outside page (GitHub). Its own module so tests can replace it: jsdom's location is read-only. */
export function leaveFor(url: string): void {
  window.location.assign(url)
}
