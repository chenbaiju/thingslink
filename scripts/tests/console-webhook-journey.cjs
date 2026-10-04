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
  await page.locator('#app-sidebar').getByText('项目',{exact:true}).click();await page.locator('#app-sidebar').getByText('Webhook',{exact:true}).click();
  async function create(name) {
    await page.getByRole('button',{name:'创建订阅',exact:true}).click();
    const dialog=page.getByRole('dialog',{name:'创建 Webhook',exact:true});
    await dialog.getByLabel('Webhook 名称',{exact:true}).fill(name);
    await dialog.getByLabel('目标 HTTPS',{exact:true}).fill('https://receiver.example.com/events');
    await dialog.getByRole('button',{name:'保存订阅',exact:true}).click();
  }
  async function secret() {
    const dialog=page.getByRole('dialog',{name:'仅本次展示签名秘密',exact:true});await expect(dialog).toBeVisible();
    const value=await dialog.getByLabel('签名秘密',{exact:true}).inputValue();assert.equal(Buffer.from(value,'base64').length,32);
    const stored=await page.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]));assert(!stored.includes(value));
    await dialog.getByRole('button',{name:'已保存，关闭',exact:true}).click();await expect(dialog.getByLabel('签名秘密',{exact:true})).toHaveValue('');return value;
  }
  await create('browser-first');const first=await secret();
  let requests=0;
  await page.route('**/api/v1/projects/*/webhooks',async route=>{
    if(route.request().method()!=='POST')return route.continue();requests++;const response=await route.fetch();assert.equal(response.status(),201);await response.dispose();await route.abort('failed');
  });
  await create('browser-lost');await expect(page.getByTestId('pending-operation')).toBeVisible();
  const form=page.getByRole('dialog',{name:'创建 Webhook',exact:true});await expect(form.getByRole('button',{name:'关闭填写窗口',exact:true})).toBeEnabled();
  await form.getByRole('button',{name:'关闭填写窗口',exact:true}).click();const pending=await page.getByTestId('pending-operation').textContent();
  await page.reload();await expect(page.getByTestId('pending-operation')).toHaveText(pending);
  await page.getByRole('button',{name:'查询操作结果',exact:true}).click();await expect(page.getByTestId('recovered-operation')).toContainText('原操作已完成');assert.equal(requests,1);
  await expect(page.getByRole('dialog',{name:'仅本次展示签名秘密',exact:true})).not.toBeVisible();await page.unroute('**/api/v1/projects/*/webhooks');
  const row=page.locator('.el-table__body tr').filter({hasText:'browser-first'});
  for(const action of ['暂停','恢复','轮换']) {
    await row.getByRole('button',{name:action,exact:true}).click();await page.getByRole('button',{name:'确定',exact:true}).click();
    if(action==='轮换'){const replacement=await secret();assert.notEqual(replacement,first)}
    else await expect(row).toContainText(action==='暂停'?'PAUSED':'ACTIVE');
  }
  await row.getByRole('button',{name:'编辑',exact:true}).click();const edit=page.getByRole('dialog',{name:'编辑 Webhook',exact:true});
  await edit.getByLabel('Webhook 名称',{exact:true}).fill('browser-edited');await edit.getByRole('button',{name:'保存订阅',exact:true}).click();
  const edited=page.locator('.el-table__body tr').filter({hasText:'browser-edited'});await expect(edited).toBeVisible();
  await edited.getByRole('button',{name:'撤销',exact:true}).click();await page.getByRole('button',{name:'确定',exact:true}).click();await expect(edited).toContainText('REVOKED');
  await page.getByRole('button',{name:'刷新投递',exact:true}).click();const delivery=page.locator('.el-table__body tr').filter({hasText:process.env.WEBHOOK_DELIVERY});await expect(delivery).toContainText('DEAD');
  await delivery.getByRole('button',{name:'详情',exact:true}).click();const detail=page.getByRole('dialog',{name:'Webhook 投递详情',exact:true});await expect(detail).toContainText('PERMANENT');await expect(detail).toContainText('https://receiver.example.com/events');await detail.getByRole('button',{name:'关闭此对话框'}).click();
  await delivery.getByRole('button',{name:'恢复投递',exact:true}).click();await page.getByRole('button',{name:'确定',exact:true}).click();await expect(delivery).toContainText('READY');
  await page.getByRole('button',{name:'刷新事件',exact:true}).click();await expect(page.locator('.el-table__body tr').filter({hasText:process.env.WEBHOOK_REJECTED})).toContainText('OVERSIZE');
  await page.reload();await expect(page.getByRole('heading',{name:'项目 Webhook',exact:true})).toBeVisible();
  await page.locator('.project-switcher').click();const menus=page.waitForResponse(r=>r.url().includes('/system/menus')&&r.status()===200);await page.locator('.project-switcher-item').filter({hasText:'隔离测试项目'}).click();await menus;
  await page.locator('#app-sidebar').getByText('项目',{exact:true}).click();await page.locator('#app-sidebar').getByText('Webhook',{exact:true}).click();await expect(page.getByRole('heading',{name:'项目 Webhook',exact:true})).toBeVisible();await expect(page.locator('.el-table__body tr').filter({hasText:'browser-lost'})).toHaveCount(0);
  assert.deepEqual(errors,[]);console.log('PASS real Webhook browser: first secret, lost response reload recovery, lifecycle, rotation, delivery recovery, rejection query and project isolation');
})().catch(error=>{console.error(error.stack);process.exitCode=1}).finally(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
