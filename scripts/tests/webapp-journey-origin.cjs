// 真实旅程独立Origin，不能把另一地址族上的开发Vite误认为本次测试进程。
const net = require('node:net');
const value = process.env.WEBAPP_JOURNEY_PORT ?? '3007';
if (!/^[1-9][0-9]{0,4}$/.test(value) || Number(value) > 65535) throw new Error('非法WebApp旅程端口');
const port = Number(value);
async function reservePortCheck() {
  for (const host of ['127.0.0.1', '::1']) {
    const probe = net.createServer();
    try {
      await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen({ port, host, exclusive: true }, resolve); });
    } catch (error) {
      if (host === '::1' && ['EAFNOSUPPORT', 'EADDRNOTAVAIL'].includes(error.code)) continue;
      throw new Error('WebApp旅程端口已占用或不可绑定，请设置独立WEBAPP_JOURNEY_PORT');
    } finally { if (probe.listening) await new Promise(resolve => probe.close(resolve)); }
  }
}
module.exports = { port, origin: `http://localhost:${port}`, reservePortCheck };
