// Existing-stack UI acceptance. No mocked responses, imports or dependency install.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../.runtime/tmp/frontend-qa-tools/node_modules/playwright-core');

async function main() {
  const root = path.resolve(__dirname, '..');
  const reportDir = process.env.RAG_AUDIT_DIR;
  if (!reportDir || !path.resolve(reportDir).startsWith(root + path.sep)) throw new Error('Project audit directory required');
  const temp = fs.mkdtempSync(path.join(root, '.runtime/tmp/context-ui-'));
  const profile = path.join(temp, 'profile');
  const config = Object.fromEntries(fs.readFileSync(path.join(root, '.runtime/local.properties'), 'utf8').split(/\r?\n/)
    .filter(line => line && !line.startsWith('#')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  const run = { executed_at: new Date().toISOString(), scope: 'Actual browser, Spring, MySQL and external model; workflow, not accuracy', checks: [], errors: [], cleanup: {}, temp_dir: temp };
  let context, token, cid;
  function check(name, passed, detail) { run.checks.push({ name, passed: !!passed, ...(detail ? { detail } : {}) }); }
  try {
    context = await chromium.launchPersistentContext(profile, {
      executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true,
      viewport: { width: 1440, height: 1000 },
      env: { ...process.env, TEMP: temp, TMP: temp, LOCALAPPDATA: path.join(temp, 'local'), APPDATA: path.join(temp, 'roaming') },
      args: ['--disable-background-networking', '--disable-component-update', '--disable-sync', '--no-first-run', '--disable-breakpad', '--disable-crash-reporter', '--disk-cache-dir=' + path.join(temp, 'cache')],
    });
    const page = context.pages()[0];
    page.on('pageerror', error => run.errors.push(error.name));
    let generalRequests = 0, dialogs = 0;
    page.on('dialog', async dialog => { dialogs++; await dialog.dismiss(); });
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/general-answer')) generalRequests++; });
    await page.goto('http://127.0.0.1:5173/login');
    await page.locator('#username').fill('admin');
    await page.locator('#password').fill(config.LOCAL_ADMIN_PASSWORD);
    const login = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    token = (await (await login).json()).data.accessToken;
    await page.waitForURL('**/chat');
    async function api(method, suffix, data) {
      const response = await context.request.fetch('http://127.0.0.1:8080/api/v1' + suffix,
        { method, headers: { Authorization: 'Bearer ' + token }, ...(data ? { data } : {}) });
      return { status: response.status(), data: response.status() === 204 ? null : (await response.json()).data };
    }
    const question = process.env.RAG_AUDIT_QUESTION || '请比较 B 树和二叉搜索树的查找过程及适用场景。';
    await page.getByRole('textbox', { name: '输入课程问题' }).fill(question);
    const created = page.waitForResponse(r => r.url().endsWith('/conversations') && r.request().method() === 'POST');
    const answered = page.waitForResponse(r => /\/conversations\/\d+\/messages$/.test(r.url()) && r.request().method() === 'POST', { timeout: 70000 });
    await page.getByRole('button', { name: '发送问题', exact: true }).click();
    cid = (await (await created).json()).data.id;
    run.conversation_id = cid;
    const response = await answered;
    check('browser_generate_http', response.status() === 200);
    if (response.status() !== 200) throw new Error('Generation HTTP ' + response.status());
    const answer = (await response.json()).data.assistantMessage;
    run.answer = answer;
    await page.locator('.message.assistant .citation-anchor').first().waitFor();
    const numbers = await page.locator('.message.assistant .citation-anchor').evaluateAll(nodes => nodes.map(n => Number(n.dataset.citation)));
    check('rendered_citation_numbers_match_saved', numbers.length > 0 && numbers.every(n => answer.citations.some(c => c.number === n)));
    check('array_zero_not_a_citation_button', await page.locator('.citation-anchor[data-citation="0"]').count() === 0);
    check('multiple_original_chunks_per_claim', /(?:\[\d+\]){2,}/.test(answer.content));
    const readback = await api('GET', '/messages/' + answer.id + '/citations');
    check('browser_citation_api_matches_saved', readback.status === 200 && JSON.stringify(readback.data) === JSON.stringify(answer.citations));
    for (const citation of answer.citations) {
      await page.locator('.message.assistant .inline-citations button').filter({ hasText: '[' + citation.number + ']' }).first().click();
      const card = page.locator('.evidence-pane .evidence-item[data-source="' + citation.number + '"]');
      await card.waitFor();
      check('source_' + citation.number + '_original_and_active', await card.locator('blockquote').textContent() === citation.quote && (await card.getAttribute('class')).includes('is-active'));
    }
    await page.screenshot({ path: path.join(temp, 'desktop.png'), fullPage: true });
    run.desktop_screenshot = path.join(temp, 'desktop.png');
    check('desktop_no_body_horizontal_overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const groundText = await page.locator('.message.assistant .answer-content').first().innerText();
    const generalResponse = page.waitForResponse(r => r.url().endsWith('/general-answer'), { timeout: 70000 });
    await page.getByRole('button', { name: '大模型回答', exact: true }).first().click();
    const gr = await generalResponse;
    check('general_http', gr.status() === 200);
    if (gr.status() === 200) {
      run.general = (await gr.json()).data.assistantMessage;
      await page.locator('.message.assistant').nth(1).waitFor();
      check('one_click_one_request_no_dialog', generalRequests === 1 && dialogs === 0 && await page.locator('.el-message-box:visible').count() === 0);
      check('general_separate_empty_citations', run.general.answerOrigin === 'GENERAL' && run.general.citations.length === 0);
      check('original_answer_unchanged', await page.locator('.message.assistant .answer-content').first().innerText() === groundText);
    }
    await page.getByRole('button', { name: '历史会话', exact: true }).click();
    await page.locator('.history-item').filter({ hasText: question.slice(0, 40) }).first().click();
    await page.locator('.el-dialog:visible').waitFor({ state: 'hidden' });
    await page.locator('.message.assistant').first().getByText('查看来源', { exact: true }).click();
    await page.locator('.evidence-pane .evidence-item').first().waitFor();
    check('history_citations_loaded', await page.locator('.evidence-pane .evidence-item').count() === answer.citations.length);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.message.assistant .inline-citations button').first().click();
    await page.locator('.el-drawer:visible').waitFor();
    check('mobile_source_drawer', await page.locator('.el-drawer:visible .evidence-item').count() === answer.citations.length);
    // Wait for drawer animation and measure the visible source, not just DOM presence.
    await page.waitForFunction(() => {
      const drawer = document.querySelector('.el-drawer');
      if (!drawer) return false;
      const box = drawer.getBoundingClientRect();
      return box.left >= 0 && box.right <= innerWidth + 1 && box.width > 250;
    });
    check('mobile_drawer_inside_viewport', await page.locator('.el-drawer:visible').evaluate(node => {
      const box = node.getBoundingClientRect();
      return box.left >= 0 && box.right <= innerWidth + 1;
    }));
    await page.screenshot({ path: path.join(temp, 'mobile.png'), fullPage: true, animations: 'disabled' });
    run.mobile_screenshot = path.join(temp, 'mobile.png');
    check('mobile_no_body_horizontal_overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    check('no_page_errors', run.errors.length === 0);
  } catch (error) {
    // Never serialize requests, headers, login payloads or raw browser errors.
    run.error = { type: error.name, stage: run.checks.at(-1)?.name ?? 'initialization' };
  } finally {
    if (context && token && cid) {
      const response = await context.request.delete('http://127.0.0.1:8080/api/v1/conversations/' + cid, { headers: { Authorization: 'Bearer ' + token } });
      const readback = await context.request.get('http://127.0.0.1:8080/api/v1/conversations/' + cid, { headers: { Authorization: 'Bearer ' + token } });
      run.cleanup.conversation_deleted = response.status() === 204 && readback.status() === 404;
      check('own_conversation_logically_deleted', run.cleanup.conversation_deleted);
    }
    if (context) await context.close();
    // Screenshots remain until visual inspection; parent cleans this owned temp directory.
    run.status = !run.error && run.checks.every(c => c.passed) ? 'PASSED' : 'FAILED';
    fs.writeFileSync(path.join(reportDir, 'browser.json'), JSON.stringify(run, null, 2));
    console.log(JSON.stringify({ status: run.status, checks: run.checks, error: run.error, temp_dir: temp }));
  }
  process.exitCode = run.status === 'PASSED' ? 0 : 1;
}
main().catch(error => { console.error(error.name); process.exitCode = 1; });
