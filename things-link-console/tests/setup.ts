// 钉死测试进程时区为非 UTC，确保 dayjs `.local()` 的时区转换被真实校验，
// 而不是在 CI 默认 UTC 下「碰巧通过」（D-043/A6-01：formatTime/formatDate/formatRelative）。
// 必须在任何测试文件（及其 import 的 dayjs 首次调用）之前执行。
process.env.TZ = 'Asia/Shanghai'
