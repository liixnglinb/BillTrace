const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const svg = fs.readFileSync('brand/icon.svg', 'utf8');
  const b = await chromium.launch();
  for (const size of [512, 192]) {
    const p = await b.newPage({ viewport: { width: size, height: size }, deviceScaleFactor: 1 });
    await p.setContent('<body style="margin:0">' + svg.replace('<svg ', '<svg width="' + size + '" height="' + size + '" ') + '</body>');
    await p.waitForTimeout(200);
    await p.screenshot({ path: 'brand/icon-' + size + '.png', omitBackground: true });
    await p.close();
  }
  await b.close();
  console.log('rendered:', fs.readdirSync('brand').join(', '));
})();
