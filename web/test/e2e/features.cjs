const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = require('path').join(__dirname, '..', '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml' };
const srv = http.createServer((q, s) => { let f = path.join(root, decodeURIComponent(q.url.split('?')[0])); if (f.endsWith('/')) f += 'index.html';
  fs.readFile(f, (e, d) => { if (e) { s.writeHead(404); s.end(); } else { s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); s.end(d); } }); });
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
const T = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  await new Promise((r) => srv.listen(8125, r));
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 390, height: 900 }, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => { window.__vib = []; navigator.vibrate = (p) => { window.__vib.push(Array.isArray(p) ? p.slice() : [p]); return true; }; });
  const page = await ctx.newPage(); const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); }); page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  await page.goto('http://localhost:8125/index.html'); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28);
  const pad = (r) => page.locator(`.pad[data-region="${r}"]`).boundingBox();
  const setSim = async (v) => { await page.fill('#sim', String(v)); await page.dispatchEvent('#sim', 'input'); };

  // --- Khám 18 điểm + rung thật được gọi với mẫu xung kép
  await setSim(20); let p = await pad('thon');
  await page.mouse.move(p.x + p.width / 2, p.y + p.height / 2); await page.mouse.down(); await T(2500);
  const vib = await page.evaluate(() => window.__vib);
  ok(vib.length >= 2, `navigator.vibrate được gọi mỗi nhịp (${vib.length} lần)`);
  ok(vib.every((v) => v.length % 2 === 1 && v[0] > 0 && v[0] <= 90), 'mẫu rung hợp lệ: ' + JSON.stringify(vib[0]));
  ok(vib.some((v) => v.length === 3), 'mạch Phù có xung kép (chủ ba + sóng dội)');
  ok((await page.textContent('#raw')).includes('lực chuẩn hóa'), 'thông số thô hiện khi chạm: ' + (await page.textContent('#raw')).split('\n')[0]);
  await T(2600); await page.mouse.up();
  ok((await page.textContent('#status')).includes('Điểm 2/18'), 'đủ 5 nhịp → điểm 2');
  const stop = await page.evaluate(() => window.__vib.at(-1));
  ok(stop.length === 1 && stop[0] === 0, 'nhả tay → hủy rung (vibrate(0))');

  // --- Hoàn tất bằng bỏ qua → lưu lịch sử (1 điểm đạt) + CSV
  for (let i = 0; i < 17; i++) await page.click('#skip');
  ok(await page.locator('#summary[open]').count() === 1, 'xong 18 điểm → hiện hộp kết quả');
  const csvText = await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.history_v1'))[0].csv);
  ok(csvText.split('\n').filter(Boolean).length === 19 && csvText.includes(',phu,left,thon,phu,1,'), 'lịch sử lưu CSV 18 điểm, điểm 1 đạt');
  await page.keyboard.press('Escape');

  // --- Tự do
  await page.selectOption('#mode', 'free'); ok(!(await page.isVisible('#examrow')), 'tự do: ẩn nút khám 18 điểm');
  await setSim(60); p = await pad('quan'); await page.mouse.move(p.x + 30, p.y + 30); await page.mouse.down(); await T(400);
  ok((await page.textContent('#status')).includes('Quan: Trung'), 'tự do: báo tầng theo lực: ' + (await page.textContent('#status')));
  await page.mouse.up();

  // --- Nguồn lực: kéo dọc (đỉnh = mạnh)
  await page.click('#t-about'); await page.selectOption('#forcesrc', 'drag'); await page.click('#t-exam');
  ok(!(await page.isVisible('#simrow')), 'kéo dọc: ẩn thanh lực thủ công');
  p = await pad('xich'); await page.mouse.move(p.x + p.width / 2, p.y + p.height * 0.15); await page.mouse.down(); await T(300);
  ok((await page.getAttribute('.pad[data-region="xich"]', 'data-depth')) === '3', 'kéo dọc: gần đỉnh → Trầm');
  await page.mouse.move(p.x + p.width / 2, p.y + p.height * 0.9, { steps: 20 }); await T(300);
  ok((await page.getAttribute('.pad[data-region="xich"]', 'data-depth')) === '1', 'kéo xuống đáy → Phù');
  await page.mouse.up(); await page.click('#t-about'); await page.selectOption('#forcesrc', 'auto'); await page.click('#t-exam');

  // --- Kiểu rung & cường độ & thử rung
  await page.click('#t-about');
  await page.click('#vibtest'); ok((await page.evaluate(() => window.__vib.at(-1)))[0] === 300, 'nút Thử rung gọi vibrate([300])');
  ok((await page.textContent('#vibinfo')).includes('Đã gửi lệnh rung'), 'thử rung có hướng dẫn nếu không cảm thấy');
  await page.selectOption('#vibmode', 'single'); await page.fill('#vibgain', '200'); await page.dispatchEvent('#vibgain', 'input');
  await page.click('#vibpulse'); const sp = await page.evaluate(() => window.__vib.at(-1));
  ok(sp.length === 1 && sp[0] > 30, 'xung đơn + gain 2×: ' + JSON.stringify(sp));
  await page.selectOption('#vibmode', 'pwm'); await page.click('#vibpulse'); const pw = await page.evaluate(() => window.__vib.at(-1));
  ok(pw.length > 3, 'PWM cho nhiều đoạn: ' + JSON.stringify(pw));
  await page.reload(); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28);
  await page.click('#t-about'); ok((await page.inputValue('#vibmode')) === 'pwm' && (await page.inputValue('#vibgain')) === '200', 'cài đặt được lưu qua lần mở lại');
  ok((await page.locator('#history li button').count()) === 1, 'lịch sử hiện 1 phiên với nút Tải CSV');
  const dl = page.waitForEvent('download'); await page.click('#history li button'); const d = await dl;
  ok(d.suggestedFilename().endsWith('.csv'), 'tải CSV: ' + d.suggestedFilename());
  await page.selectOption('#vibmode', 'pulse2'); await page.click('#t-exam');

  // --- BPM theo mạch
  await page.selectOption('#mode', 'exam'); await page.selectOption('#pulse', 'tat');
  ok((await page.inputValue('#bpm')) === '135' && (await page.getAttribute('#bpm', 'min')) === '122', 'thanh nhịp theo dải của mạch Tật (122–150, mặc định 135)');

  // --- Đố mạch
  await page.selectOption('#mode', 'quiz'); ok(!(await page.isVisible('#pulselbl')) && (await page.isVisible('#quiz')), 'đố mạch: ẩn tên mạch, hiện ô đáp án');
  ok((await page.textContent('#feel')).includes('giấu'), 'đố mạch: mô tả không lộ đáp án');
  await page.click('#guessbtn'); const qi = await page.textContent('#quizinfo');
  ok(/Đáp án: Mạch/.test(qi) && /Phiên này: [01]\/1/.test(qi), 'chốt đáp án → lộ đáp án + điểm: ' + qi.split('\n')[0].slice(0, 40));
  ok(await page.isDisabled('#guessbtn'), 'không chốt được lần 2');
  await page.click('#nextround'); ok(!(await page.isDisabled('#guessbtn')) && (await page.textContent('#quizinfo')) === '', 'vòng mới');
  ok(await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.quiz_v1')).total) === 1, 'điểm đố mạch lưu cục bộ');

  // --- Hiệu chuẩn: thông số thô + nút kéo dọc khi máy không báo lực
  await page.click('#t-calib'); const cb = await page.locator('#calpad').boundingBox();
  await page.mouse.move(cb.x + 40, cb.y + 40); await page.mouse.down(); await page.mouse.up();
  ok((await page.textContent('#craw')).includes('pressure'), 'hiệu chuẩn hiện thông số thô: ' + (await page.textContent('#craw')).slice(0, 50));

  ok(errs.length === 0, 'không có lỗi console' + (errs.length ? ': ' + errs.join(' | ') : ''));
  await b.close(); srv.close();
})().catch((e) => { console.error('E2E LỖI', e); process.exit(1); });
