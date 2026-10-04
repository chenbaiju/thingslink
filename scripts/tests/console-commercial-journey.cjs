#!/usr/bin/env node
// G3-LOCAL-7b：真实默认供给、生产Console与HTTP，商业变更仅由测试控制口调用模拟服务。
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
// 商业旅程使用被测 Console 自身的测试依赖；允许独立构建目录留存当轮证据。
process.env.PLAYWRIGHT_BROWSERS_PATH ??= path.join(root, 'things-link-console/.playwright-browsers');
const { chromium, expect } = createRequire(path.join(root, 'things-link-console/package.json'))('@playwright/test');
const dist = process.env.CONSOLE_TEST_DIST || path.join(root, 'things-link-console/dist');
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
  // 可指定已安装的同版本 Chromium，避免把额外浏览器介质下载当成业务前置。
  browser=await chromium.launch({headless:true,executablePath:process.env.CONSOLE_CHROMIUM_EXECUTABLE});const context=await browser.newContext();const page=await context.newPage();evidencePage=page;
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto(origin+'/auth/login');await page.getByPlaceholder('请输入邮箱').fill(process.env.ACCESS_EMAIL);
  await page.getByPlaceholder('请输入密码',{exact:true}).fill(process.env.ACCESS_PASSWORD);
  const track=await page.locator('.drag_verify').boundingBox(),handle=await page.locator('.dv_handler').boundingBox();
  await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(track.x+track.width-2,handle.y+handle.height/2,{steps:12});await page.mouse.up();
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.locator('.project-switcher').click({timeout:15000});await Promise.all([page.waitForResponse(r=>r.url().includes('/system/menus')&&r.status()===200),page.locator('.project-switcher-item').filter({hasText:'商业旅程项目'}).click()]);
  let bearer='';
  page.on('request',request=>{if(request.url().includes('/api/v1/')) bearer=request.headers().authorization||bearer});
  async function settings(target) {
    const menu=target.locator('#app-sidebar').getByText('项目设置',{exact:true});
    if(!await menu.isVisible()) await target.locator('#app-sidebar').getByText('项目',{exact:true}).click();
    await menu.click(); await expect(target.locator('.plan-summary')).toBeVisible();
  }
  async function check(target,code,limit) {
    await expect(target.locator('.plan-summary')).toContainText('（'+code+'）');
    await expect(target.locator('[data-testid="plan-effective-limits"]')).toContainText(limit.toLocaleString('en-US')+' COUNT');
    await expect(target.locator('.plan-summary')).not.toContainText('运行时实际绑定的档位与订阅锁定的修订版不一致');
  }
  async function device(n,status) {
    assert(bearer,'actual project bearer required');
    const response=await page.request.post(origin+'/api/v1/projects/'+process.env.COMMERCIAL_PROJECT+'/devices',{
      headers:{authorization:bearer},data:{deviceKey:'commercial-browser-'+n,name:'商业旅程设备'+n}});
    assert.equal(response.status(),status,await response.text());
    if(status===429) assert.equal((await response.json()).code,30035);
  }
  await settings(page);await check(page,'FREE',3);
  for(let n=1;n<=3;n++) await device(n,201);
  await device(4,429);
  let enterpriseLimit;
  for(const [index,code] of ['STANDARD','ENTERPRISE','ENTERPRISE'].entries()) {
    const response=await fetch(process.env.COMMERCIAL_CONTROL,{method:'POST'});
    assert.equal(response.status,200,await response.clone().text());
    const result=await response.json();assert.equal(result.stage,index+1);
    if(index===1)enterpriseLimit=result.limit;
    if(index===2)assert.equal(result.limit,enterpriseLimit,'pending downgrade must not narrow active rights');
    await page.reload();await settings(page);await check(page,code,result.limit);
    if(index===0)await device(4,201);
    console.log('PASS stage '+result.stage+' '+code+' effective device limit '+result.limit);
  }
  const second=await context.newPage();await second.goto(origin);await settings(second);await check(second,'ENTERPRISE',enterpriseLimit);
  assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(root,'logs/verify/g3-local-7b/browser.png'),fullPage:true});
  console.log('PASS real Console FREE quota refusal, simulated purchase/upgrade, deferred downgrade, refresh and second-page consistency');
  await context.close();
})().catch(async error=>{console.error(error);if(evidencePage)await evidencePage.screenshot({path:path.join(root,'logs/verify/g3-local-7b/failure.png'),fullPage:true}).catch(()=>{});process.exitCode=1}).finally(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
