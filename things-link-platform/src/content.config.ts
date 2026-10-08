import { defineCollection } from 'astro:content'
import { glob } from 'astro/loaders'
import { z } from 'astro/zod'

/**
 * 文档 Collection。
 *
 * 所有 Markdown 文档放在 src/content/docs/ 目录下，
 * 使用 [...slug].astro 渲染。
 */
const docs = defineCollection({
  loader: glob({ pattern: '**/[^_]*.md', base: './src/content/docs' }),
  schema: z.object({
    title: z.string(),
    description: z.string().optional(),
    /** 排序权重，越小越靠前 */
    order: z.number().default(0)
  })
})

export const collections = { docs }
