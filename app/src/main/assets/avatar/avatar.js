/*
 * Neon avatar renderer.
 * Native taraf (Android) bu sayfayı WebView içinde açar ve window.avatar API'si ile kontrol eder:
 *   avatar.setState('idle'|'listening'|'thinking'|'talking'|'executing'|'confirming'|'error'|'micoff')
 *   avatar.setLevel(0..1)   // mikrofon / konuşma şiddeti
 *   avatar.replay()          // açılış animasyonunu yeniden oynat
 *   avatar.setSpeed(x)       // animasyon hızı
 *   avatar.setSound(bool)    // ambiyans sesleri
 *   avatar.bolt()            // yıldırım efekti
 * Tüm şekil model koordinatlarında (x: 0..1000, y: 0..1000) tanımlıdır.
 */
(() => {
  'use strict';
  const cv = document.getElementById('c');
  const ctx = cv.getContext('2d');
  const TAU = Math.PI * 2;

  // ---------- deterministik rastgele ----------
  let seed = 1337;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
  const rr = (a, b) => a + (b - a) * rnd();
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const ease = (t) => (t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t));
  const easeOut = (t) => 1 - Math.pow(1 - clamp(t, 0, 1), 3);

  // ---------- ekran dönüşümü ----------
  let W = 0, H = 0, DPR = 1, S = 1, OX = 0, OY = 0;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth; H = window.innerHeight;
    cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
    S = Math.min(W / 560, H / 1000);
    OX = W / 2 - 500 * S;
    OY = H - 985 * S;
    buildBackground();
    resizeBloom();
  }
  // bloom için küçük ara tuval
  const bc = document.createElement('canvas');
  const bctx = bc.getContext('2d');
  function resizeBloom() { bc.width = Math.max(1, Math.ceil(W / 5)); bc.height = Math.max(1, Math.ceil(H / 5)); }
  function bloom(strength) {
    bctx.globalCompositeOperation = 'copy';
    bctx.filter = 'blur(2px)';
    bctx.drawImage(cv, 0, 0, bc.width, bc.height);
    bctx.filter = 'none';
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'lighter';
    ctx.globalAlpha = strength;
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(bc, 0, 0, cv.width, cv.height);
    ctx.restore();
  }
  const sx = (x) => OX + x * S;
  const sy = (y) => OY + y * S;

  // ---------- geometri ----------
  function cubic(p0, p1, p2, p3, n, out) {
    for (let i = out.length ? 1 : 0; i <= n; i++) {
      const t = i / n, u = 1 - t;
      out.push([
        u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
        u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
      ]);
    }
    return out;
  }
  // segs: [p0, c1, c2, p1, c3, c4, p2, ...]
  function path(segs, n = 22) {
    const out = [];
    for (let i = 0; i + 3 < segs.length; i += 3) cubic(segs[i], segs[i + 1], segs[i + 2], segs[i + 3], n, out);
    return out;
  }
  const M = (s, x, y) => [500 + s * x, y];

  const COL = {
    violet: [176, 92, 255],
    pink: [236, 150, 255],
    white: [255, 236, 255],
    gold: [255, 196, 120],
    deep: [120, 40, 220],
  };

  const lines = [];   // {pts, cur, col, w, kind, len, phase}
  function addLine(pts, col, w, kind) {
    let len = 0;
    for (let i = 1; i < pts.length; i++) len += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    const l = { pts, cur: pts.map((p) => [p[0], p[1]]), col, w, kind, len, phase: rnd() };
    lines.push(l);
    return l;
  }

  let facePts = [], bodyPoly = [];
  function buildFigure() {
    seed = 1337;
    lines.length = 0;

    // Yüz (sivri çeneli oval maske)
    const fr = path([[500, 162], [542, 162], [568, 196], [567, 244], [566, 292], [532, 334], [500, 352]], 26);
    const fl = fr.map(([x, y]) => [1000 - x, y]).reverse();
    facePts = fr.concat(fl.slice(1));
    addLine(fr, COL.pink, 1.2, 'face');
    addLine(fr.map(([x, y]) => [1000 - x, y]), COL.pink, 1.2, 'face');

    // Gövde (her iki taraf için ayna)
    for (const s of [-1, 1]) {
      // boyun
      addLine(path([M(s, 30, 334), M(s, 31, 372), M(s, 33, 402), M(s, 40, 432)]), COL.pink, 1.2, 'body');
      // dış hat: omuz -> kol
      const outer = path([
        M(s, 40, 432), M(s, 80, 456), M(s, 140, 462), M(s, 182, 482),
        M(s, 222, 500), M(s, 236, 548), M(s, 234, 610),
        M(s, 230, 720), M(s, 238, 850), M(s, 244, 1000),
      ]);
      addLine(outer, COL.white, 1.8, 'body');
      if (s === -1) bodyPoly = outer.slice();
      else bodyPoly = bodyPoly.concat([[500, 1000]]).concat(outer.slice().reverse());
      // koltuk altı / iç kol
      addLine(path([M(s, 196, 590), M(s, 186, 690), M(s, 176, 820), M(s, 170, 1000)]), COL.violet, 1.3, 'body');
      // köprücük
      addLine(path([M(s, 14, 462), M(s, 64, 450), M(s, 118, 458), M(s, 170, 480)]), COL.pink, 1.2, 'body');
      // göğüs kalp çizgisi (merkezde birleşen)
      addLine(path([M(s, 0, 560), M(s, 30, 520), M(s, 110, 505), M(s, 170, 560)]), COL.violet, 1.6, 'body');
      // göğüs alt kavisleri (açık yay)
      addLine(path([M(s, 176, 590), M(s, 182, 680), M(s, 150, 760), M(s, 94, 772), M(s, 56, 778), M(s, 24, 752), M(s, 8, 716)]), COL.pink, 1.7, 'body');
      // V altın hatlar
      addLine(path([M(s, 44, 436), M(s, 50, 480), M(s, 22, 522), M(s, 0, 560)]), COL.gold, 1.3, 'gold');
      addLine(path([M(s, 214, 506), M(s, 160, 540), M(s, 104, 596), M(s, 66, 690)]), COL.gold, 1.1, 'gold');
      addLine(path([M(s, 226, 640), M(s, 204, 724), M(s, 196, 812), M(s, 206, 1000)]), COL.gold, 1.1, 'gold');
      addLine(path([M(s, 160, 790), M(s, 120, 826), M(s, 64, 846), M(s, 0, 872)]), COL.violet, 1.2, 'body');
      addLine(path([M(s, 156, 870), M(s, 128, 922), M(s, 92, 962), M(s, 70, 1000)]), COL.gold, 1.0, 'gold');
      // lif demetleri (videodaki yoğun ince ışık telleri)
      for (let k = 0; k < 9; k++) {
        const o = k / 8;
        addLine(path([
          M(s, 210 - o * 40, 500 + o * 20), M(s, 170 - o * 60, 530 + o * 30),
          M(s, 90 - o * 50, 560 + o * 60), M(s, 20 + o * 10, 600 + o * 90),
        ], 18), k % 3 ? COL.violet : COL.pink, 0.55, 'fiber');
        addLine(path([
          M(s, 226 - o * 20, 560 + o * 20), M(s, 214 - o * 26, 680 + o * 20),
          M(s, 200 - o * 30, 820), M(s, 206 - o * 30, 1000),
        ], 18), k % 4 ? COL.violet : COL.gold, 0.5, 'fiber');
        addLine(path([
          M(s, 150 - o * 70, 800 + o * 20), M(s, 120 - o * 60, 870 + o * 20),
          M(s, 80 - o * 50, 930), M(s, 60 - o * 40, 1000),
        ], 14), COL.violet, 0.45, 'fiber');
      }
    }
    addLine(path([[500, 460], [500, 490], [500, 530], [500, 560]]), COL.white, 1.3, 'body');
    addLine(path([[500, 872], [500, 915], [500, 955], [500, 1000]]), COL.violet, 1.0, 'body');

    // Saçlar: ayrımdan taç üzerinden yüzü çevreleyip omuzlara dökülür
    for (const s of [-1, 1]) {
      for (let i = 0; i < 64; i++) {
        const u = rnd(), v = rnd(), w = rnd();
        const len = rnd();
        const segs = [
          M(s, 1 + u * 10, 124 + u * 8), M(s, 40 + u * 30, 120 + u * 4), M(s, 96 + u * 34, 140 + u * 10),
          M(s, 84 + u * 40 + v * 8, 250),
          M(s, 88 + u * 46, 330), M(s, 104 + u * 64, 410), M(s, 118 + u * 80, 500 + v * 30),
        ];
        if (len > 0.15) segs.push(M(s, 130 + u * 86 + w * 10, 590), M(s, 126 + u * 96, 670), M(s, 112 + u * 112 + v * 24, 700 + v * 160));
        const c = rnd();
        const col = c < 0.12 ? COL.gold : c < 0.32 ? COL.white : c < 0.75 ? COL.pink : COL.violet;
        addLine(path(segs, 16), col, rr(0.45, 1.15), 'hair');
      }
      // alna doğru süpürülen perçem
      for (let i = 0; i < 10; i++) {
        const u = rnd();
        addLine(path([M(s, 4 + u * 10, 150), M(s, 30 + u * 20, 150), M(s, 56 + u * 10, 175), M(s, 66 + u * 6, 215 + u * 30)], 12), COL.pink, 0.5, 'hair');
      }
    }
  }

  // ---------- parçacıklar ----------
  let particles = [];
  const bodyPath = new Path2D();
  function buildParticles() {
    seed = 4242;
    bodyPath.moveTo(bodyPoly[0][0], bodyPoly[0][1]);
    for (const p of bodyPoly) bodyPath.lineTo(p[0], p[1]);
    bodyPath.closePath();
    const probe = document.createElement('canvas').getContext('2d');
    const total = lines.reduce((a, l) => a + l.len, 0);
    particles = [];
    const N = 1500;
    for (let i = 0; i < N; i++) {
      const p = { arm: rnd() < 0.5 ? 0 : 1, r: Math.pow(rnd(), 0.6), a0: rnd() * TAU, tw: rnd() * TAU, sz: rr(0.6, 1.7), d: rnd() * 0.6 };
      if (i < N * 0.72) {
        let k = rnd() * total, l = lines[0];
        for (const L of lines) { if ((k -= L.len) <= 0) { l = L; break; } }
        p.line = l; p.idx = Math.floor(rnd() * l.pts.length);
        p.jx = rr(-3, 3); p.jy = rr(-3, 3);
        p.col = rnd() < 0.2 ? COL.gold : l.col;
      } else {
        let x, y, n = 0;
        do { x = rr(260, 740); y = rr(430, 1000); n++; } while (!probe.isPointInPath(bodyPath, x, y) && n < 50);
        p.tx = x; p.ty = y; p.col = rnd() < 0.3 ? COL.gold : COL.pink;
      }
      particles.push(p);
    }
  }

  // ---------- arka plan ----------
  let stars = [];
  function buildBackground() {
    seed = 99;
    stars = [];
    for (let i = 0; i < 140; i++) stars.push({ x: rnd(), y: rnd(), s: rr(0.4, 1.4), p: rnd() * TAU });
  }

  function drawBackground(t, glow) {
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, '#05010c');
    g.addColorStop(1, '#0d0320');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);

    ctx.globalCompositeOperation = 'lighter';
    // duman
    const m = Math.max(W, H);
    for (let i = 0; i < 7; i++) {
      const x = W * (0.5 + 0.45 * Math.sin(t * 0.03 * (i + 1) + i * 1.7));
      const y = H * (0.5 + 0.4 * Math.cos(t * 0.025 * (i + 2) + i * 2.3));
      const r = m * (0.35 + 0.1 * Math.sin(t * 0.05 + i));
      const rg = ctx.createRadialGradient(x, y, 0, x, y, r);
      rg.addColorStop(0, `rgba(70,20,130,${0.10 * glow})`);
      rg.addColorStop(1, 'rgba(70,20,130,0)');
      ctx.fillStyle = rg;
      ctx.fillRect(0, 0, W, H);
    }
    // ipek kurdeleler
    for (let k = 0; k < 4; k++) {
      const baseY = H * (0.15 + k * 0.2);
      for (let j = 0; j < 7; j++) {
        ctx.beginPath();
        for (let i = 0; i <= 40; i++) {
          const x = (i / 40) * W * 1.2 - W * 0.1;
          const y = baseY + Math.sin(i * 0.18 + t * 0.15 + k * 2) * H * 0.08 + Math.sin(i * 0.07 - t * 0.1 + k) * H * 0.06 + j * 2.2;
          i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
        }
        ctx.strokeStyle = `rgba(200,170,255,${(0.025 + 0.012 * Math.sin(t * 0.5 + j + k)) * glow})`;
        ctx.lineWidth = 1;
        ctx.stroke();
      }
    }
    // yıldızlar
    for (const s of stars) {
      const a = 0.25 + 0.35 * Math.sin(t * 1.3 + s.p);
      ctx.fillStyle = `rgba(220,200,255,${a * glow})`;
      ctx.fillRect(s.x * W, s.y * H, s.s, s.s);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------- durum ----------
  const st = {
    state: 'idle', level: 0, lvl: 0, speed: 1, sound: true,
    t: 0, intro: 0, introDone: false, bright: 1, talkSim: 0,
  };
  const bolts = [];

  function makeBolt(x0, y0, x1, y1, depth) {
    let pts = [[x0, y0], [x1, y1]];
    let off = Math.hypot(x1 - x0, y1 - y0) * 0.18;
    for (let d = 0; d < depth; d++) {
      const np = [pts[0]];
      for (let i = 0; i < pts.length - 1; i++) {
        const a = pts[i], b = pts[i + 1];
        const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
        const nx = -(b[1] - a[1]), ny = b[0] - a[0];
        const nl = Math.hypot(nx, ny) || 1;
        const o = (Math.random() * 2 - 1) * off;
        np.push([mx + (nx / nl) * o, my + (ny / nl) * o], b);
      }
      pts = np; off *= 0.55;
    }
    return pts;
  }
  function spawnBolt(target) {
    const side = Math.random() < 0.5 ? -1 : 1;
    let x0, y0, x1, y1;
    if (target === 'orb') { x0 = 500; y0 = 560; x1 = 500 + rr(-1, 1) * 90; y1 = 560 + rr(-1, 1) * 90; }
    else {
      x0 = 500 + side * rr(380, 520); y0 = rr(120, 380);
      x1 = 500 + side * rr(150, 210); y1 = rr(420, 520);
    }
    bolts.push({ pts: makeBolt(x0, y0, x1, y1, 6), life: 0.35, age: 0 });
    zap();
  }

  // ---------- çizim yardımcıları ----------
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
  function strokePts(pts, from, to) {
    ctx.beginPath();
    ctx.moveTo(sx(pts[from][0]), sy(pts[from][1]));
    for (let i = from + 1; i <= to; i++) ctx.lineTo(sx(pts[i][0]), sy(pts[i][1]));
    ctx.stroke();
  }
  function glowLine(pts, col, w, a, from = 0, to = pts.length - 1) {
    if (to - from < 1 || a <= 0.003) return;
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.strokeStyle = rgba(col, 0.07 * a); ctx.lineWidth = w * 9 * S; strokePts(pts, from, to);
    ctx.strokeStyle = rgba(col, 0.22 * a); ctx.lineWidth = w * 3.2 * S; strokePts(pts, from, to);
    ctx.strokeStyle = rgba(COL.white, 0.75 * a); ctx.lineWidth = Math.max(0.6, w * 0.9 * S); strokePts(pts, from, to);
  }
  function glowDot(x, y, r, col, a) {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, rgba(COL.white, a));
    g.addColorStop(0.25, rgba(col, a * 0.6));
    g.addColorStop(1, rgba(col, 0));
    ctx.fillStyle = g;
    ctx.fillRect(x - r, y - r, r * 2, r * 2);
  }

  // ---------- intro zaman çizelgesi (saniye) ----------
  const T_ORB = 1.4, T_GAL = 3.4, T_MORPH = 5.6, T_RING = 5.2, T_LINES = 7.2;

  function updateLines(t) {
    const breathe = Math.sin(t * 1.25) * 2.2;
    for (const l of lines) {
      for (let i = 0; i < l.pts.length; i++) {
        const [x, y] = l.pts[i];
        let dx = 0, dy = 0;
        if (y > 400) dy = -breathe * clamp((1000 - y) / 600, 0, 1) * 0.6;
        if (l.kind === 'hair' && y > 240) {
          const k = (y - 240) / 500;
          dx = Math.sin(t * 0.7 + y * 0.012 + l.phase * 6) * 7 * k + Math.sin(t * 1.9 + l.phase * 20) * 1.5 * k;
          if (st.state === 'talking') dx += Math.sin(t * 6 + y * 0.03) * 1.2 * k;
        }
        l.cur[i][0] = x + dx; l.cur[i][1] = y + dy;
      }
    }
  }

  function particlePos(p) {
    if (p.line) { const c = p.line.cur[p.idx]; return [c[0] + p.jx, c[1] + p.jy]; }
    return [p.tx, p.ty];
  }

  function drawFigure(t, A) {
    const it = st.intro;
    const lineA = ease((it - (T_RING - 0.2)) / (T_LINES - T_RING)) * A;

    // gövde dolgusu (koyu, saydam)
    if (lineA > 0) {
      ctx.save();
      ctx.setTransform(DPR * S, 0, 0, DPR * S, DPR * OX, DPR * OY);
      const g = ctx.createLinearGradient(0, 420, 0, 1000);
      g.addColorStop(0, `rgba(12,2,26,${0.9 * lineA})`);
      g.addColorStop(1, `rgba(20,4,40,${0.8 * lineA})`);
      ctx.fillStyle = g;
      ctx.fill(bodyPath);
      ctx.beginPath();
      ctx.moveTo(facePts[0][0], facePts[0][1]);
      for (const p of facePts) ctx.lineTo(p[0], p[1]);
      ctx.closePath();
      ctx.fillStyle = `rgba(4,0,10,${0.96 * lineA})`;
      ctx.fill();
      ctx.restore();
    }

    ctx.globalCompositeOperation = 'lighter';

    // çizgiler — açılışta yukarıdan aşağı doğru "çizilir"
    if (lineA > 0) {
      const reveal = easeOut((it - T_RING) / (T_LINES - T_RING + 0.6));
      for (const l of lines) {
        const n = l.pts.length - 1;
        const to = Math.max(1, Math.floor(n * clamp(reveal * 1.3 - l.phase * 0.3, 0, 1)));
        let a = lineA;
        if (l.kind === 'hair') a *= 0.55 + 0.25 * Math.sin(t * 0.8 + l.phase * 9);
        if (l.kind === 'gold') a *= 0.8 + 0.2 * Math.sin(t * 1.4 + l.phase * 7);
        if (l.kind === 'fiber') a *= 0.45 + 0.2 * Math.sin(t * 1.1 + l.phase * 11);
        glowLine(l.cur, l.col, l.w, a, 0, to);
      }
    }

    // parçacıklar
    const cx = 500, cy = 560;
    for (const p of particles) {
      let x, y, a;
      if (it < T_ORB) continue;
      if (it < T_GAL + p.d) {
        const k = clamp((it - T_ORB) / (T_GAL - T_ORB), 0, 1);
        const r = p.r * (20 + k * 260);
        const ang = p.a0 * 0.15 + p.arm * Math.PI + p.r * 5.5 - it * 2.2;
        x = cx + Math.cos(ang) * r * 1.1; y = cy + Math.sin(ang) * r * 0.9 - k * p.r * 120;
        a = 0.9 * clamp((it - T_ORB) * 2, 0, 1);
      } else {
        const k = easeOut((it - T_GAL - p.d) / (T_MORPH - T_GAL));
        const r = p.r * 280;
        const ang = p.a0 * 0.15 + p.arm * Math.PI + p.r * 5.5 - (T_GAL + p.d) * 2.2;
        const gx = cx + Math.cos(ang) * r * 1.1, gy = cy + Math.sin(ang) * r * 0.9 - p.r * 120;
        const [tx, ty] = particlePos(p);
        x = gx + (tx - gx) * k; y = gy + (ty - gy) * k;
        a = 0.95 - 0.45 * ease((it - T_MORPH) / 2);
      }
      const tw = 0.55 + 0.45 * Math.sin(t * 3 + p.tw * 7);
      const s = p.sz * S * 1.6;
      ctx.fillStyle = rgba(p.col, a * tw * A);
      ctx.fillRect(sx(x) - s / 2, sy(y) - s / 2, s, s);
    }

    // enerji darbeleri çizgiler boyunca akar
    if (lineA > 0.5 && st.state !== 'micoff') {
      const spd = st.state === 'executing' ? 0.8 : st.state === 'thinking' ? 0.55 : st.state === 'talking' ? 0.35 : 0.12;
      for (const l of lines) {
        if ((l.kind === 'hair' && l.phase > 0.3) || (l.kind === 'fiber' && l.phase > 0.4)) continue;
        const u = (t * spd * (300 / Math.max(l.len, 100)) + l.phase) % 1;
        const c = l.cur[Math.floor(u * (l.pts.length - 1))];
        glowDot(sx(c[0]), sy(c[1]), 9 * S, l.col, 0.55 * lineA);
      }
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function drawFaceFx(t, A) {
    const it = st.intro;
    const a = ease((it - T_LINES + 1) / 1.2) * A;
    if (a <= 0) return;
    ctx.globalCompositeOperation = 'lighter';
    const fx = sx(500), fy = sy(250);
    // yüzde iç ışık
    const lv = st.lvl;
    if (st.state === 'talking') {
      const k = 0.35 + lv * 0.9;
      ctx.save();
      ctx.translate(fx, fy);
      ctx.scale(1, 5.5);
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, 22 * S);
      g.addColorStop(0, `rgba(255,255,255,${0.95 * k * a})`);
      g.addColorStop(0.35, `rgba(230,200,255,${0.5 * k * a})`);
      g.addColorStop(1, 'rgba(160,80,255,0)');
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(0, 0, 22 * S, 0, TAU); ctx.fill();
      ctx.restore();
    } else if (st.state === 'listening') {
      const k = 0.4 + 0.6 * lv;
      const g = ctx.createRadialGradient(fx, fy, 0, fx, fy, 70 * S);
      g.addColorStop(0, `rgba(170,90,255,${0.35 * k * a})`);
      g.addColorStop(1, 'rgba(170,90,255,0)');
      ctx.fillStyle = g; ctx.fillRect(fx - 80 * S, fy - 80 * S, 160 * S, 160 * S);
      for (let i = 0; i < 3; i++) {
        const ph = (t * 0.6 + i / 3) % 1;
        ctx.strokeStyle = `rgba(200,140,255,${(1 - ph) * 0.35 * k * a})`;
        ctx.lineWidth = 1.5 * S;
        ctx.beginPath(); ctx.arc(fx, fy + 20 * S, (110 + ph * 260) * S, 0, TAU); ctx.stroke();
      }
    } else if (st.state === 'thinking' || st.state === 'executing') {
      const ex = st.state === 'executing';
      for (let i = 0; i < 14; i++) {
        const ang = t * (ex ? 3.2 : 1.6) + (i / 14) * TAU;
        const x = fx + Math.cos(ang) * 120 * S, y = fy - 10 * S + Math.sin(ang) * 36 * S;
        glowDot(x, y, 10 * S, ex ? COL.gold : COL.violet, 0.5 * a * (0.5 + 0.5 * Math.sin(t * 4 + i)));
      }
    } else if (st.state === 'confirming') {
      const k = 0.5 + 0.5 * Math.sin(t * 3);
      ctx.strokeStyle = `rgba(255,196,120,${0.55 * k * a})`;
      ctx.lineWidth = 2.5 * S;
      ctx.beginPath(); ctx.ellipse(fx, fy - 10 * S, 130 * S, 150 * S, 0, 0, TAU); ctx.stroke();
      glowDot(fx, fy, 60 * S, COL.gold, 0.25 * k * a);
    } else if (st.state === 'error') {
      const k = Math.random() < 0.15 ? 0.3 : 1;
      const g = ctx.createRadialGradient(fx, fy, 0, fx, fy, 90 * S);
      g.addColorStop(0, `rgba(255,60,90,${0.45 * k * a})`);
      g.addColorStop(1, 'rgba(255,60,90,0)');
      ctx.fillStyle = g; ctx.fillRect(fx - 90 * S, fy - 90 * S, 180 * S, 180 * S);
    }
    // alında küçük parıltı
    glowDot(sx(512), sy(205), 14 * S, COL.pink, 0.25 * a * (0.6 + 0.4 * Math.sin(t * 2)));
    ctx.globalCompositeOperation = 'source-over';
  }

  function drawIntro(t) {
    const it = st.intro;
    ctx.globalCompositeOperation = 'lighter';
    // küre
    if (it < T_GAL + 0.6) {
      const grow = easeOut(it / 0.8);
      const fade = 1 - ease((it - T_GAL + 0.4) / 1.0);
      const r = (30 + 8 * Math.sin(t * 6)) * S * grow;
      const x = sx(500), y = sy(560);
      const g = ctx.createRadialGradient(x, y, 0, x, y, r * 7);
      g.addColorStop(0, `rgba(255,245,255,${fade})`);
      g.addColorStop(0.08, `rgba(220,170,255,${0.9 * fade})`);
      g.addColorStop(0.25, `rgba(150,70,255,${0.35 * fade})`);
      g.addColorStop(1, 'rgba(90,30,200,0)');
      ctx.fillStyle = g;
      ctx.fillRect(x - r * 7, y - r * 7, r * 14, r * 14);
      if (Math.random() < 0.06 * st.speed && it < T_GAL) spawnBolt('orb');
    }
    // halka parlaması
    const rk = (it - T_RING) / 1.1;
    if (rk > 0 && rk < 1) {
      const x = sx(500), y = sy(520);
      const r = (180 + 260 * easeOut(rk)) * S;
      const a = Math.sin(rk * Math.PI);
      ctx.strokeStyle = `rgba(240,220,255,${0.8 * a})`; ctx.lineWidth = 3 * S;
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.stroke();
      ctx.strokeStyle = `rgba(170,90,255,${0.25 * a})`; ctx.lineWidth = 22 * S;
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.stroke();
      const g = ctx.createRadialGradient(x, y, 0, x, y, r);
      g.addColorStop(0, `rgba(200,140,255,${0.25 * a})`);
      g.addColorStop(1, 'rgba(120,40,220,0)');
      ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function drawBolts(dt) {
    ctx.globalCompositeOperation = 'lighter';
    for (let i = bolts.length - 1; i >= 0; i--) {
      const b = bolts[i];
      b.age += dt;
      if (b.age > b.life) { bolts.splice(i, 1); continue; }
      const a = (1 - b.age / b.life) * (Math.random() < 0.3 ? 0.4 : 1);
      glowLine(b.pts, COL.violet, 1.6, a);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------- ses (WebAudio sentez) ----------
  let ac = null, pad = null;
  function audio() {
    if (!st.sound) return null;
    try {
      if (!ac) {
        ac = new (window.AudioContext || window.webkitAudioContext)();
        const g = ac.createGain(); g.gain.value = 0.0;
        const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = 420;
        [55, 55.6, 82.4].forEach((fr) => { const o = ac.createOscillator(); o.type = 'sawtooth'; o.frequency.value = fr; o.connect(f); o.start(); });
        f.connect(g); g.connect(ac.destination);
        pad = g;
      }
      if (ac.state === 'suspended') ac.resume();
    } catch (e) { return null; }
    return ac;
  }
  function zap() {
    const a = audio(); if (!a) return;
    const n = a.createBufferSource();
    const buf = a.createBuffer(1, a.sampleRate * 0.25, a.sampleRate);
    const d = buf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / d.length, 3);
    n.buffer = buf;
    const f = a.createBiquadFilter(); f.type = 'highpass'; f.frequency.value = 1800;
    const g = a.createGain(); g.gain.value = 0.08;
    n.connect(f); f.connect(g); g.connect(a.destination); n.start();
  }
  function updatePad() {
    if (!pad || !ac) return;
    const target = st.sound ? (st.state === 'micoff' ? 0.004 : 0.012 + st.lvl * 0.01) : 0;
    pad.gain.setTargetAtTime(target, ac.currentTime, 0.4);
  }

  // ---------- ana döngü ----------
  let last = performance.now(), nextBolt = 6;
  function frame(now) {
    let dt = Math.min(0.05, (now - last) / 1000); last = now;
    dt *= st.speed;
    st.t += dt;
    st.intro += dt;
    const t = st.t;

    if (!st.introDone && st.intro > T_LINES + 0.5) {
      st.introDone = true;
      try { window.Android && window.Android.onIntroDone(); } catch (e) {}
    }

    // seviye (konuşurken native seviye gelmiyorsa hece benzetimi)
    let target = st.level;
    if (st.state === 'talking') {
      st.talkSim += dt * 9;
      const sim = 0.45 + 0.35 * Math.sin(st.talkSim) * Math.sin(st.talkSim * 0.37 + 1) + 0.2 * Math.random();
      target = Math.max(target, clamp(sim, 0, 1));
    }
    st.lvl += (target - st.lvl) * Math.min(1, dt * 14);

    const wantB = st.state === 'micoff' ? 0.45 : st.state === 'idle' ? 0.88 + 0.12 * Math.sin(t * 1.2) : 1;
    st.bright += (wantB - st.bright) * Math.min(1, dt * 3);

    if (st.introDone && st.state !== 'micoff') {
      nextBolt -= dt;
      if (nextBolt <= 0) { spawnBolt(); nextBolt = st.state === 'talking' ? rr(2.5, 5) : rr(6, 12); }
    }

    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    drawBackground(t, 0.6 + 0.4 * st.bright);
    updateLines(t);
    const A = st.bright * (1 + 0.15 * st.lvl);
    drawIntro(t);
    drawFigure(t, A);
    drawFaceFx(t, A);
    drawBolts(dt);
    bloom(st.state === 'micoff' ? 0.25 : 0.55 + 0.25 * st.lvl);
    if (st.state === 'micoff') { ctx.fillStyle = 'rgba(5,0,12,0.25)'; ctx.fillRect(0, 0, W, H); }
    updatePad();
    requestAnimationFrame(frame);
  }

  // ---------- dış API ----------
  window.avatar = {
    setState(s) { st.state = s; if (s !== 'listening' && s !== 'talking') st.level = 0; },
    setLevel(v) { st.level = clamp(+v || 0, 0, 1); },
    replay() { st.intro = 0; st.introDone = false; bolts.length = 0; audio(); },
    skipIntro() { st.intro = T_LINES + 1; },
    setSpeed(v) { st.speed = clamp(+v || 1, 0.25, 4); },
    setSound(on) { st.sound = !!on; if (on) audio(); },
    bolt() { spawnBolt(); },
  };

  window.addEventListener('resize', resize);
  document.addEventListener('pointerdown', () => audio(), { once: true });
  buildFigure();
  buildParticles();
  resize();
  const q = new URLSearchParams(location.search);
  if (q.has('state')) st.state = q.get('state');
  if (q.has('t')) st.intro = st.t = parseFloat(q.get('t'));
  requestAnimationFrame((n) => { last = n; frame(n); });
})();
