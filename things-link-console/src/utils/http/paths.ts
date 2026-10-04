import type { paths } from '@/types/api/schema'

/**
 * 把 OpenAPI 契约里的路径变成**编译期可检查的类型**。
 *
 * 【它补的是哪个洞】
 * `openapi-typescript` 只生成类型，请求函数是手写的，于是 URL 一直是个普通字符串：
 *
 * ```ts
 * request.get<ProjectMemberResponse[]>({ url: `/api/v1/projects/${id}/members` })
 * ```
 *
 * 请求体与响应体都有类型约束，**唯独路径和方法没有**。后端把
 * `/members` 改成 `/users`，`schema.d.ts` 会变、`pnpm api:check` 会红，
 * 但上面这行照样编译通过 —— 症状是运行时 404，而 404 有一百种可能的原因，
 * 排查时几乎不会第一时间怀疑到「路径写错了」。
 *
 * 加上这层之后，同样的改动会让调用点直接编译失败。
 *
 * 【为什么不用代码生成器】
 * 生成整套客户端（openapi-fetch / orval）能解决同样的问题，代价是我们 HTTP 层的
 * 约定 —— 单飞刷新、`HttpError` 统一提示、重试 —— 都要按新客户端重写一遍，
 * 而且 `src/api/*.ts` 里那些解释语义的注释会被生成器整个抹掉
 * （「非成员返回 404 而不是 403」这类，比类型本身更值钱）。
 *
 * 只有 12 个请求函数，划不来。这层拿到了其中最要紧的那部分收益，且零运行时代价。
 */

/** 契约里出现过的全部路径，形如 `/api/v1/projects/{projectId}/members`。 */
type ContractPath = keyof paths

/** HTTP 方法。与 `paths[P]` 的键名一致（注意是 `delete` 而不是 `del`）。 */
export type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'

/**
 * 判断某条路径是否支持某个方法。
 *
 * `openapi-typescript` 对不存在的方法生成 `put?: never`，因此
 * `paths[P]['put']` 是 `undefined`，而存在的方法是 `operations[...]`。
 * 用元组包一层是为了**阻止条件类型在联合上分发** —— 不包的话
 * `never extends X` 会直接得到 `never`，判断永远为假。
 */
type Supports<P extends ContractPath, M extends HttpMethod> = M extends keyof paths[P]
  ? [paths[P][M]] extends [never | undefined]
    ? false
    : true
  : false

/** 支持某个方法的全部路径。 */
type PathsWith<M extends HttpMethod> = {
  [P in ContractPath]: Supports<P, M> extends true ? P : never
}[ContractPath]

/**
 * 把带占位符的契约路径变成能匹配**真实 URL** 的模板字面量类型：
 *
 * ```text
 * '/api/v1/projects/{projectId}/members'  →  `/api/v1/projects/${string}/members`
 * ```
 *
 * 调用点写的是插值后的具体字符串，直接拿 `keyof paths` 去卡是对不上的。
 *
 * 这会放宽到「任意非空片段」，也就是说 `/api/v1/projects/a/b/members` 也能通过。
 * 精确到不含斜杠需要更绕的递归类型，而**收益几乎为零**：真实的失败模式是
 * 静态片段被改名或拼错，那些这里全都拦得住。
 */
type Concrete<P extends string> = P extends `${infer Head}{${string}}${infer Tail}`
  ? `${Head}${string}${Concrete<Tail>}`
  : P

/**
 * 某个方法可用的真实 URL 类型。
 *
 * 用它标注 `url` 之后：路径不存在、路径写错、或者这条路径不支持该方法
 * （比如对着只有 GET 的路径发 POST），三种情况都会在编译期报错。
 */
export type ApiUrl<M extends HttpMethod> = Concrete<PathsWith<M> & string>
