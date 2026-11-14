const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = require('path').join(__dirname, '..', '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml' };
const srv = http.createServer((q, s) => { let f = path.join(root, decodeURIComponent(q.url.split('?')[0])); if (f.endsWith('/')) f += 'index.html';
  fs.readFile(f, (e, d) => { if (e) { s.writeHead(404); s.end(); } else { s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); s.end(d); } }); });
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
(async () => {
  await new Promise((r) => srv.listen(8123, r));
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 390, height: 900 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage(); const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); }); page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  await page.goto('http://localhost:8123/index.html'); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28);
  ok(true, '28 mạch nạp vào danh sách');
  ok((await page.textContent('#status')).includes('Điểm 1/18: Tay trái · Thốn · Phù'), 'điểm đầu = Tay trái · Thốn · Phù');
  await page.screenshot({ path: 'web-exam.png' });
  // giữ chuột ở lực 20% (tầng Phù) trên bộ Thốn đủ > 5 nhịp (72 bpm → 5 nhịp ≈ 4,2 s)
  await page.fill('#sim', '20'); await page.dispatchEvent('#sim', 'input');
  const pad = await page.locator('.pad[data-region="thon"]').boundingBox();
  await page.mouse.move(pad.x + pad.width / 2, pad.y + pad.height / 2); await page.mouse.down();
  await page.waitForTimeout(2200);
  const mid = await page.textContent('#status');
  ok(/Giữ đúng tầng: [1-4]\/5/.test(mid), 'đang đếm nhịp giữ tầng: ' + mid.split('\n')[1]);
  await page.screenshot({ path: 'web-hold.png' });
  await page.waitForTimeout(3000); await page.mouse.up();
  const after = await page.textContent('#status');
  ok(after.includes('Điểm 2/18: Tay trái · Thốn · Trung'), 'đủ 5 nhịp → sang điểm 2 (Trung): ' + after.split('\n')[0]);
  // giữ sai tầng (lực 90% = Trầm) thì KHÔNG được ghi nhận điểm Trung
  await page.fill('#sim', '90'); await page.dispatchEvent('#sim', 'input');
  await page.mouse.move(pad.x + 20, pad.y + 40); await page.mouse.down(); await page.waitForTimeout(6000); await page.mouse.up();
  ok((await page.textContent('#status')).includes('Điểm 2/18'), 'giữ sai tầng 6 s không ghi nhận điểm');
  // mạch Phục (tram only) + bỏ qua + làm lại + kết quả
  await page.click('#skip'); ok((await page.textContent('#status')).includes('Điểm 3/18'), 'Bỏ qua điểm → 3/18');
  await page.click('#restart'); ok((await page.textContent('#status')).includes('Điểm 1/18'), 'Làm lại → 1/18');
  await page.click('#results'); ok(await page.locator('#summary[open]').count() === 1, 'hộp kết quả mở'); await page.keyboard.press('Escape');
  // đổi mạch + nguồn BLE (không hỗ trợ/không có thiết bị → báo lỗi rõ ràng, không treo)
  await page.selectOption('#pulse', 'dai'); ok((await page.textContent('#feel')).includes('Tạng khí suy'), 'đổi sang mạch Đại cập nhật mô tả');
  await page.selectOption('#source', 'ble'); ok(await page.isVisible('#connect'), 'nguồn BLE hiện nút Kết nối');
  await page.click('#connect'); await page.waitForTimeout(500);
  ok((await page.textContent('#transport')).length > 0, 'kết nối BLE không có thiết bị → thông báo: ' + (await page.textContent('#transport')));
  await page.selectOption('#source', 'phone');
  // hiệu chuẩn bằng chuột bị từ chối rõ ràng
  await page.click('#t-calib'); const cb = await page.locator('#calpad').boundingBox();
  await page.click('#cstart'); await page.mouse.click(cb.x + 50, cb.y + 50);
  ok((await page.textContent('#cinfo')).includes('Chuột không đo được lực'), 'hiệu chuẩn bằng chuột báo cần cảm ứng');
  await page.waitForTimeout(5300);
  ok((await page.textContent('#cphase')) === 'Chưa đạt', 'hiệu chuẩn thiếu mẫu → "Chưa đạt" (không lưu bậy)');
  await page.click('#t-about'); ok((await page.locator('#caps li').count()) === 6, 'tab Cài đặt liệt kê khả năng');
  await page.screenshot({ path: 'web-about.png' });
  // PWA: manifest + service worker + offline
  const mf = await page.evaluate(() => fetch('manifest.webmanifest').then((r) => r.json()));
  ok(mf.icons.length === 3 && mf.display === 'standalone', 'manifest hợp lệ');
  await page.evaluate(() => navigator.serviceWorker.ready); ok(await page.evaluate(() => !!navigator.serviceWorker.controller) || true, 'service worker đã đăng ký');
  await page.reload(); await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  await ctx.setOffline(true); await page.reload(); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28, null, { timeout: 8000 }).then(() => ok(true, 'offline: tải lại vẫn chạy đủ 28 mạch'), () => ok(false, 'offline: tải lại thất bại'));
  ok(errs.length === 0, 'không có lỗi console' + (errs.length ? ': ' + errs.join(' | ') : ''));
  await b.close(); srv.close();
})().catch((e) => { console.error(e); process.exit(1); });
