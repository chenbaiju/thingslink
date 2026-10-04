
export const prerender = true

function escapeXml(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
}

export async function GET() {
  const site = import.meta.env.SITE
  const paths = ['/']
  const urls = site
    ? paths.map((path) => `  <url><loc>${escapeXml(new URL(path, site).toString())}</loc></url>`).join('\n')
    : ''
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`

  return new Response(body, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' }
  })
}
