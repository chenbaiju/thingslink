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
  await page.locator('#app-sidebar').getByText('设备',{exact:true}).click();
  await page.locator('#app-sidebar').getByText('所有设备',{exact:true}).click();
  const row=page.locator('.el-table__body tr').filter({hasText:'配置旅程设备'});
  await row.getByRole('button',{name:'详情',exact:true}).click();
  const panel=page.getByTestId('access-configuration');
  await expect(panel.getByTestId('access-current')).toContainText('配置版本 9007199254740993');
  if(process.env.ACCESS_MODE==='ARCHIVED') {
    const marker=path.join(root,'logs/verify/s14-r8d-2d-6c-4');
    await fs.writeFile(path.join(marker,'archive-request'),'selected');
    const deadline=Date.now()+15000;let ready=false;
    while(Date.now()<deadline){try{await fs.access(path.join(marker,'archive-ready'));ready=true;break}catch{await new Promise(r=>setTimeout(r,50))}}
    assert(ready,'owned Java fixture must archive after actual token issuance');
    await panel.getByRole('button',{name:'重新读取配置'}).click();
  }
  if(process.env.ACCESS_MODE!=='OWNER') {
    await expect(panel.getByTestId('access-readonly')).toBeVisible();
    await expect(panel.getByRole('button',{name:'保存接入配置'})).toHaveCount(0);
    await expect(panel.getByRole('switch')).toBeDisabled();
    console.log('PASS '+process.env.ACCESS_MODE+' real backend read-only configuration');
  } else {
    let writes=0;const versions=[];
    page.on('request',request=>{if(request.url().endsWith('/access-config')&&request.method()==='PUT'){
      writes++;const body=request.postDataJSON();assert.equal(typeof body.expectedConfigVersion,'string');versions.push(body.expectedConfigVersion);
    }});
    const save=()=>panel.getByRole('button',{name:'保存接入配置',exact:true}).click();
    const state=panel.getByTestId('access-current');
    const select=async value=>{await panel.locator('.el-select').click();await page.getByRole('option',{name:value,exact:true}).click()};
    await save();await expect(panel).toContainText('接入配置已保存');await expect(state).toContainText('9007199254740993');
    await select('TCP');await save();await expect(state).toContainText('当前协议 TCP');await expect(state).toContainText('配置版本 9007199254740994');
    await panel.locator('.el-switch').click();await save();await expect(state).toContainText('已禁用');await expect(state).toContainText('配置版本 9007199254740995');
    await expect(panel).toContainText('恢复启用后设备需要重新连接或认证');
    await panel.locator('.el-switch').click();await save();await expect(state).toContainText('已启用');await expect(state).toContainText('配置版本 9007199254740996');
    let competitor=false;
    await page.route('**/access-config',async route=>{
      if(route.request().method()!=='PUT'||competitor)return route.continue();
      competitor=true;const request=route.request(),body=request.postDataJSON();
      const response=await fetch(process.env.ACCESS_BACKEND+new URL(request.url()).pathname,{method:'PUT',headers:{authorization:request.headers().authorization,'content-type':'application/json'},body:JSON.stringify({...body,protocol:'COAP'})});
      assert.equal(response.status,200);assert.equal((await response.json()).configVersion,'9007199254740997');
      await route.continue();
    });
    await select('HTTP');await save();await expect(panel).toContainText('配置已被修改');await expect(state).toContainText('当前协议 COAP');await expect(state).toContainText('配置版本 9007199254740997');
    assert.equal(writes,5);await page.unroute('**/access-config');
    let dropped=false;
    await page.route('**/access-config',async route=>{
      if(route.request().method()!=='PUT'||dropped)return route.continue();
      dropped=true;const response=await route.fetch();assert.equal(response.status(),200);await response.dispose();await route.abort('failed');
    });
    await select('MQTT');await save();await expect(panel).toContainText('保存未获成功确认');await expect(state).toContainText('当前协议 MQTT');await expect(state).toContainText('配置版本 9007199254740998');
    assert.equal(writes,6);assert.deepEqual(versions,['9007199254740993','9007199254740993','9007199254740994','9007199254740995','9007199254740996','9007199254740997']);
    await page.unroute('**/access-config');
    console.log('PASS OWNER real HTTP read/save/noop/disable/restore/conflict/lost-response; six browser PUTs, no automatic replay; exact bigint versions');
  }
  assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(root,'logs/verify/s14-r8d-2d-6c-4/browser-'+process.env.ACCESS_MODE+'.png'),fullPage:true});
  await context.close();
})().catch(async error=>{console.error(error);if(evidencePage)await evidencePage.screenshot({path:path.join(root,'logs/verify/s14-r8d-2d-6c-4/failure-'+process.env.ACCESS_MODE+'.png'),fullPage:true}).catch(()=>{});process.exitCode=1}).finally(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
