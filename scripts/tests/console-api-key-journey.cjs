#!/usr/bin/env node
// 真实生产Console/后端；唯一拦截是在后端成功后丢弃一次响应以验证未知结果恢复。
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(root, 'things-link-webapp/.playwright-browsers');
const { chromium, expect } = createRequire(path.join(root, 'things-link-webapp/package.json'))('@playwright/test');
const dist = path.join(root, 'things-link-console/dist');
let server, browser;
const mime = { '.html':'text/html', '.js':'application/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png', '.woff2':'font/woff2' };
(async () => {
  server = http.createServer(async (req,res) => {
    if(req.url.startsWith('/api/') || req.url.startsWith('/actuator/')) {
      const upstream = http.request(new URL(req.url,process.env.KEY_BACKEND),{method:req.method,headers:req.headers}, incoming=>{res.writeHead(incoming.statusCode,incoming.headers);incoming.pipe(res)});
      res.on('close',()=>upstream.destroy()); upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()});req.pipe(upstream);return;
    }
    let target=path.resolve(dist,'.'+new URL(req.url,'http://local').pathname);
    if(!target.startsWith(dist+path.sep))target=path.join(dist,'index.html');
    try {if(!(await fs.stat(target)).isFile())target=path.join(dist,'index.html')}catch{target=path.join(dist,'index.html')}
    res.writeHead(200,{'Content-Type':mime[path.extname(target)]??'application/octet-stream','Cache-Control':'no-store'});res.end(await fs.readFile(target));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin=`http://127.0.0.1:${server.address().port}`;
  browser=await chromium.launch({headless:true});const context=await browser.newContext();const page=await context.newPage();
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(origin+'/auth/login');await page.getByPlaceholder('请输入邮箱').fill(process.env.KEY_EMAIL);
  await page.getByPlaceholder('请输入密码',{exact:true}).fill(process.env.KEY_PASSWORD);
  const track=await page.locator('.drag_verify').boundingBox(),handle=await page.locator('.dv_handler').boundingBox();
  await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(track.x+track.width-2,handle.y+handle.height/2,{steps:12});await page.mouse.up();
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.locator('.project-switcher').click({timeout:15000});await page.locator('.project-switcher-item').filter({hasText:'集成测试项目'}).click();
  await page.waitForResponse(r=>r.url().includes('/system/menus')&&r.status()===200);
  await page.locator('#app-sidebar').getByText('项目',{exact:true}).click();await page.locator('#app-sidebar').getByText('API Key',{exact:true}).click();
  async function issue(name) {
    await page.getByRole('button',{name:'签发 Key',exact:true}).click();
    const dialog=page.getByRole('dialog',{name:'签发 API Key',exact:true});
    await dialog.getByLabel('Key 名称',{exact:true}).fill(name);await dialog.getByLabel('来源 IP/CIDR',{exact:true}).fill('127.0.0.1/32');
    await dialog.getByRole('button',{name:'确认签发',exact:true}).click();
  }
  async function readSecret() {
    const dialog=page.getByRole('dialog',{name:'仅本次展示完整 Key',exact:true});
    await expect(dialog).toBeVisible();const value=await dialog.getByLabel('完整 API Key',{exact:true}).inputValue();
    assert.match(value,/^tcak1\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/);
    const stored=await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]));assert(!stored.includes(value),'秘密不得持久化');
    await dialog.getByRole('button',{name:'已保存，关闭',exact:true}).click();await expect(page.getByLabel('完整 API Key',{exact:true})).toHaveValue('');return value;
  }
  async function status(secret) {return (await fetch(process.env.KEY_BACKEND+'/api/open/v1/devices',{headers:{'X-Api-Key':secret}})).status}
  await issue('browser-first');const first=await readSecret();assert.equal(await status(first),200);
  let issueRequests=0;
  await page.route('**/api/v1/projects/*/api-keys',async route=>{
    if(route.request().method()!=='POST')return route.continue();
    issueRequests++;const response=await route.fetch();assert.equal(response.status(),201);await response.dispose();await route.abort('failed');
  });
  await issue('browser-lost');await expect(page.getByTestId('pending-operation')).toBeVisible();
  await expect(page.getByRole('button',{name:'确认签发',exact:true})).toBeDisabled();
  await page.getByRole('dialog',{name:'签发 API Key',exact:true}).getByRole('button',{name:'关闭填写窗口',exact:true}).click();
  await page.getByRole('button',{name:'查询操作结果',exact:true}).click();await expect(page.getByTestId('recovered-key')).toContainText('browser-lost');
  assert.equal(issueRequests,1);await expect(page.getByRole('dialog',{name:'仅本次展示完整 Key',exact:true})).not.toBeVisible();
  await page.unroute('**/api/v1/projects/*/api-keys');
  const row=page.locator('.el-table__body tr').filter({hasText:'browser-first'});
  await row.getByRole('button',{name:'轮换',exact:true}).click();const rotate=page.getByRole('dialog',{name:'轮换 API Key',exact:true});
  await rotate.getByLabel('Key 名称',{exact:true}).fill('browser-rotated');await rotate.getByRole('button',{name:'确认签发',exact:true}).click();
  const replacement=await readSecret();assert.equal(await status(first),401);assert.equal(await status(replacement),200);
  const rotated=page.locator('.el-table__body tr').filter({hasText:'browser-rotated'});
  await rotated.getByRole('button',{name:'撤销',exact:true}).click();await page.getByRole('button',{name:'确定',exact:true}).click();
  await expect(rotated).toContainText('REVOKED');assert.equal(await status(replacement),401);
  await page.reload();await expect(page.getByRole('heading',{name:'项目 API Key',exact:true})).toBeVisible();
  const stored=await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]));assert(!stored.includes(first)&&!stored.includes(replacement));
  assert.deepEqual(errors,[]);console.log('PASS real browser: first secret, lost response recovery, rotation, revocation, reload and no persisted secret');
})().catch(error=>{console.error(error.message);process.exitCode=1}).finally(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
