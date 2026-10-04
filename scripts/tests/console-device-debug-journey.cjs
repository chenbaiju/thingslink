#!/usr/bin/env node
// D-193真实生产Console/后端，无接口结果拦截；固定双项目与生产消息摄入事实。
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(root, 'things-link-webapp/.playwright-browsers');
const { chromium, expect } = createRequire(path.join(root, 'things-link-webapp/package.json'))('@playwright/test');
const dist = path.join(root, 'things-link-console/dist');
let server, browser, evidencePage;
const mime = { '.html':'text/html', '.js':'application/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png', '.woff2':'font/woff2' };
(async () => {
  server = http.createServer(async (req,res) => {
    if(req.url.startsWith('/api/') || req.url.startsWith('/actuator/')) {
      const upstream = http.request(new URL(req.url,process.env.ACCESS_BACKEND),{method:req.method,headers:req.headers}, incoming=>{res.writeHead(incoming.statusCode,incoming.headers);incoming.pipe(res)});
      res.on('close',()=>upstream.destroy()); upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()});req.pipe(upstream);return;
    }
    let target=path.resolve(dist,'.'+new URL(req.url,'http://local').pathname);
    if(!target.startsWith(dist+path.sep))target=path.join(dist,'index.html');
    try {if(!(await fs.stat(target)).isFile())target=path.join(dist,'index.html')}catch{target=path.join(dist,'index.html')}
    res.writeHead(200,{'Content-Type':mime[path.extname(target)]??'application/octet-stream','Cache-Control':'no-store'});res.end(await fs.readFile(target));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin=`http://127.0.0.1:${server.address().port}`;
  browser=await chromium.launch({headless:true});const context=await browser.newContext();const page=await context.newPage();evidencePage=page;
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(origin+'/auth/login');await page.getByPlaceholder('请输入邮箱').fill(process.env.ACCESS_EMAIL);
  await page.getByPlaceholder('请输入密码',{exact:true}).fill(process.env.ACCESS_PASSWORD);
  const track=await page.locator('.drag_verify').boundingBox(),handle=await page.locator('.dv_handler').boundingBox();
  await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(track.x+track.width-2,handle.y+handle.height/2,{steps:12});await page.mouse.up();
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.locator('.project-switcher').click({timeout:15000});await Promise.all([page.waitForResponse(r=>r.url().includes('/system/menus')&&r.status()===200),page.locator('.project-switcher-item').filter({hasText:'集成测试项目'}).click()]);
  await expect(page.getByRole('navigation',{name:'breadcrumb'}).locator('li')).toHaveCount(1);
  await expect(page.getByRole('navigation',{name:'breadcrumb'})).toHaveText('概要');
  let bearer='';
  page.on('request', request=>{if(request.url().includes('/api/v1/')) bearer=request.headers().authorization||bearer});
  await page.locator('#app-sidebar').getByText('设备',{exact:true}).click();
  await page.locator('#app-sidebar').getByText('消息日志',{exact:true}).click();
  await expect(page.getByText('日志仅保留报文摘要')).toBeVisible();
  await expect(page.getByRole('navigation',{name:'breadcrumb'}).locator('li')).toHaveCount(2);
  const row=page.locator('.el-table__body tr').filter({hasText:'调试旅程设备'});
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('PROPERTY_REPORT');
  await page.locator('.el-form-item').filter({hasText:'消息类型'}).locator('.el-select__wrapper').click();
  await page.getByRole('option',{name:'属性上报',exact:true}).click();
  await page.getByRole('button',{name:'查询',exact:true}).click();
  await expect(row).toHaveCount(1);
  const detailResponse=page.waitForResponse(response=>response.url().includes('/devices/'+process.env.DEBUG_DEVICE+'/messages/')&&response.status()===200);
  await row.getByRole('button').filter({hasText:'42'}).click();
  const logId=new URL((await detailResponse).url()).pathname.split('/').at(-1);
  const detail=page.getByRole('dialog',{name:'消息详情'});
  await expect(detail).toBeVisible();
  await expect(detail.locator('.message-logs__summary')).toContainText('42');
  await expect(detail.locator('.message-logs__summary')).not.toContainText('accessToken');
  await expect(detail.locator('.message-logs__summary')).not.toContainText('987654321');
  await detail.locator('.el-radio-button').filter({hasText:'HEX'}).click();
  await expect(detail.locator('.message-logs__summary')).not.toHaveText('—');
  const hex=(await detail.locator('.message-logs__summary').innerText()).replace(/\s/g,'');
  assert.match(hex,/^[0-9a-f]+$/i);assert(!Buffer.from(hex,'hex').toString().includes('accessToken'));
  await detail.getByRole('button',{name:'关闭',exact:true}).click();
  await row.getByRole('button',{name:'连接诊断',exact:true}).click();
  const diagnostics=page.getByRole('dialog',{name:'接入连接诊断'});
  await expect(diagnostics).toContainText('HTTP');await expect(diagnostics).toContainText('配置版本');
  await expect(diagnostics).toContainText('会话代次');await expect(diagnostics).toContainText('离线');
  await diagnostics.getByRole('button',{name:'关闭',exact:true}).click();
  await page.locator('.project-switcher').click();
  await Promise.all([page.waitForResponse(r=>r.url().includes('/system/menus')&&r.status()===200),
    page.locator('.project-switcher-item').filter({hasText:'隔离空项目'}).click()]);
  const messageMenu=page.locator('#app-sidebar').getByText('消息日志',{exact:true});
  if(!await messageMenu.isVisible()) await page.locator('#app-sidebar').getByText('设备',{exact:true}).click();
  await messageMenu.click();
  await expect(page.getByText('当前筛选条件下没有消息日志')).toBeVisible();
  await expect(page.locator('.el-table__body tr')).toHaveCount(0);
  assert(bearer,'real login must yield a project bearer');
  for(const suffix of ['/messages','/messages/'+logId,'/access-diagnostics']) {
    const response=await fetch(process.env.ACCESS_BACKEND+'/api/v1/projects/'+process.env.DEBUG_OTHER_PROJECT+
      '/devices/'+process.env.DEBUG_DEVICE+suffix,{headers:{authorization:bearer}});
    assert.equal(response.status,404,'foreign device read must be hidden: '+suffix);
  }
  console.log('PASS overview single breadcrumb, message two-level breadcrumb; '+process.env.ACCESS_MODE+' real message JSON/HEX redaction, diagnostics, different-project isolation and foreign-device HTTP refusal');
  assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(root,'logs/verify/g3-local-2/browser-'+process.env.ACCESS_MODE+'.png'),fullPage:true});
  await context.close();
})().catch(async error=>{console.error(error);if(evidencePage)await evidencePage.screenshot({path:path.join(root,'logs/verify/g3-local-2/failure-'+process.env.ACCESS_MODE+'.png'),fullPage:true}).catch(()=>{});process.exitCode=1}).finally(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
