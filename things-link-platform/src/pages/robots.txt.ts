export const prerender = true

export function GET() {
  const site = import.meta.env.SITE
  const body = site
    ? `User-agent: *\nAllow: /\nSitemap: ${new URL('/sitemap.xml', site)}\n`
    : 'User-agent: *\nDisallow: /\n'

  return new Response(body, {
    headers: { 'Content-Type': 'text/plain; charset=utf-8' }
  })
}
