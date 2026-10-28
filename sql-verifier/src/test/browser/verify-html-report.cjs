// 开发验证：需要 Node.js、playwright 与浏览器；验证器运行包自身无需这些依赖。
// node verify-html-report.cjs <运行包启动脚本> <隔离输出目录>
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const {pathToFileURL}=require('node:url');
const {chromium}=require('playwright');
const launcher=path.resolve(process.argv[2]),base=path.resolve(process.argv[3]);
function generate(kind,data){const dir=path.join(base,kind);fs.mkdirSync(dir,{recursive:true});const input=path.join(dir,'verification.json');fs.writeFileSync(input,JSON.stringify(data));execFileSync(launcher,['--report',input]);return pathToFileURL(path.join(dir,'verification.html')).href;}
const side={status:'SUCCESS',executeMillis:2,readMillis:1,readRows:101,exhausted:true,hasMore:false,rows:Array.from({length:101},(_,i)=>['900719925474099312345','1234567890123456789.1250',null,'','null',' 空格 ',i+1])};
const query={rows:30,summary:{selectedQueries:120,matchedQueries:40,differentQueries:40,inconclusiveQueries:40},queries:Array.from({length:120},(_,i)=>({id:'query.'+String(i+1).padStart(3,'0'),sequence:i+1,sql:'SELECT ? /* '+'离线文本 '.repeat(60)+'</script><script>window.__htmlAttack=1</script> */',parameters:[],source:side,target:side,comparison:{status:['MATCH','DIFFERENT','INCONCLUSIVE'][i%3],scope:i===0?'PREFIX':'FULL',observedStatus:'MATCH',differenceCount:0,differences:[]}}))};
const queryUrl=generate('query',query),emptyUrl=generate('empty',{queries:[],summary:{selectedQueries:0}});
const largeUrl=generate('large',{queries:Array.from({length:10000},(_,i)=>({id:'large.'+(i+1),sequence:i+1,source:{status:'SUCCESS'},target:{status:'SUCCESS'},comparison:{status:'MATCH',scope:'FULL'}})),summary:{selectedQueries:10000}});
const dmlUrl=generate('dml',{mode:'DML_ROLLBACK',runStatus:'STOPPED',stopReason:'恢复读取未确认',summary:{failed:1,cleanupFailures:1},cases:[{id:'cleanup.case',sequence:1,verification:'FAIL',source:{execution:'SUCCESS',cleanup:'UNKNOWN',rollback:'SUCCESS',restoration:'INCONCLUSIVE',errors:[{stage:'RESTORE',queryStage:'READ',message:'恢复读取错误'}]},target:{execution:'NOT_EXECUTED',cleanup:'PASS',restoration:'NOT_REQUIRED'}}]});
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.SQL_REPORT_BROWSER?{executablePath:process.env.SQL_REPORT_BROWSER}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],network=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});
  await page.goto(queryUrl);assert.equal(await page.locator('#entry-rows tr').count(),50);assert.equal(await page.locator('#entry-rows tr').first().locator('td').first().textContent(),'2');
  await page.locator('#index-pager button').nth(1).click();assert.equal(await page.locator('#entry-rows tr').count(),50);
  await page.locator('#index-pager button').nth(1).click();assert.equal(await page.locator('#entry-rows tr').count(),20);assert.equal(await page.locator('#index-pager button').nth(1).isDisabled(),true);
  await page.locator('#reset').click();await page.locator('#conclusion').selectOption('MATCH');assert.equal(await page.locator('#entry-rows tr').count(),40);
  await page.locator('#scope').selectOption('FULL');assert.match(await page.locator('#match-count').textContent(),/39$/);
  await page.locator('#search').fill('query.002');assert.equal(await page.locator('#entry-rows tr').count(),0);assert.equal(await page.locator('#no-entries').textContent(),'没有匹配条目');
  await page.locator('#reset').click();await page.locator('#search').fill('SELECT');assert.equal(await page.locator('#entry-rows tr').count(),0);
  await page.locator('#reset').click();await page.locator('#sort').selectOption('sequence');assert.equal(await page.locator('#entry-rows tr').first().locator('td').first().textContent(),'1');
  await page.screenshot({path:path.join(base,'query-home.png'),fullPage:true});
  await page.locator('#conclusion').selectOption('MATCH');await page.locator('#scope').selectOption('PREFIX');assert.match(await page.locator('#entry-rows').textContent(),/前 30 条一致/);
  await page.locator('#entry-rows a').click();assert.equal(await page.locator('h1').textContent(),'query.001');
  assert.equal(await page.evaluate(()=>window.__htmlAttack),undefined);assert.equal(await page.locator('.array-content tbody tr').count(),0);
  const source=page.locator('section').filter({has:page.getByRole('heading',{name:'源侧执行与原始行',exact:true})});
  const rows=source.locator('details[data-array]');await rows.locator('summary').click();await rows.locator('tbody tr').first().waitFor();assert.equal(await rows.locator('tbody tr').count(),50);
  assert.match(await rows.locator('tbody').textContent(),/900719925474099312345/);assert.match(await rows.locator('tbody').textContent(),/1234567890123456789.1250/);
  await rows.locator('.pager button').nth(1).click();assert.equal(await rows.locator('tbody tr').count(),50);await rows.locator('.pager button').nth(1).click();assert.equal(await rows.locator('tbody tr').count(),1);
  const copy=page.locator('button[data-copy]');await copy.locator('..').locator('summary').click();
  await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.__copiedText=text;throw Error('测试复制不可用');}},configurable:true});});
  await copy.click();assert.equal(await page.evaluate(()=>window.__copiedText),query.queries[0].sql);assert.match(await copy.locator('..').locator('.copy-note').textContent(),/手工复制/);
  await page.screenshot({path:path.join(base,'query-detail.png'),fullPage:true});
  await page.getByRole('link',{name:'返回运行摘要'}).click();assert.equal(page.url(),queryUrl);
  await page.goto(emptyUrl);assert.match(await page.locator('#no-entries').textContent(),/没有条目/);
  await page.goto(dmlUrl);assert.match(await page.locator('main').textContent(),/运行已停止 STOPPED/);await page.locator('#cleanup').selectOption('UNKNOWN');assert.equal(await page.locator('#entry-rows tr').count(),1);
  await page.locator('#entry-rows a').click();assert.match(await page.locator('section').first().textContent(),/清理结果.*未知 UNKNOWN/s);
  const stages=await page.locator('h2').allTextContents();assert(stages.indexOf('前置检查')<stages.indexOf('写入执行'));assert(stages.indexOf('无条件回滚')<stages.indexOf('恢复检查'));
  await page.screenshot({path:path.join(base,'dml-detail.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.goto(queryUrl);assert(await page.locator('h1').isVisible());await page.screenshot({path:path.join(base,'mobile-home.png'),fullPage:true});
  await page.goto(largeUrl);assert.equal(await page.locator('#entry-rows tr').count(),50);assert.match(await page.locator('#match-count').textContent(),/10000$/);await page.locator('#search').fill('large.10000');assert.equal(await page.locator('#entry-rows tr').count(),1);
  await page.goto(queryUrl);
  const moved=path.join(base,'移动后的报告');fs.renameSync(path.join(base,'query'),moved);await page.goto(pathToFileURL(path.join(moved,'verification.html')).href);await page.locator('#entry-rows a').first().click();assert(await page.locator('h1').isVisible());await page.getByRole('link',{name:'返回运行摘要'}).click();assert.equal(page.url(),pathToFileURL(path.join(moved,'verification.html')).href);
  assert.deepEqual(errors,[]);assert.deepEqual(network,[]);console.log('浏览器验证通过：筛选交集、万条索引 DOM、索引与行分页、精确值、复制降级、转义、DML 停止、空报告、移动、离线零网络与移动视口。');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
