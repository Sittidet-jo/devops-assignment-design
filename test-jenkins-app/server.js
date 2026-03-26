import path from 'path';
import express from 'express';

const app = express();
const buildDir = path.join(process.cwd(), 'build');

app.get('/ready', (_req, res) => res.type('text').send('ok\n'));
app.get('/live',  (_req, res) => res.type('text').send('ok\n'));

app.use(express.static(buildDir));

// fallback: ทุก path ที่ไม่ใช่ /ready หรือ /live
app.get(/^\/(?!ready$|live$).*/, (_req, res) => {
  res.sendFile(path.join(buildDir, 'index.html'));
});

app.listen(8080, () => console.log('listening on 8080'));
