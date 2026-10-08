import { getCollection } from 'astro:content'
import { docsPath } from '@/config'

export const prerender = true

function escapeXml(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
}

export async function GET() {
  const site = import.meta.env.SITE
  const docs = (await getCollection('docs')).sort((a, b) =>
    a.data.order - b.data.order || a.id.localeCompare(b.id, 'en')
  )
  const paths = ['/', ...docs.map((doc) => docsPath(doc.id))]
  const urls = site
    ? paths.map((path) => `  <url><loc>${escapeXml(new URL(path, site).toString())}</loc></url>`).join('\n')
    : ''
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`

  return new Response(body, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' }
  })
}
