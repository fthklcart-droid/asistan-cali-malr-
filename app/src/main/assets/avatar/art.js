/*
 * "ART" arayüzü — turkuaz kontur çizgili büst, turuncu parlayan yüz, parçacıklı dağlar.
 * neon (avatar.js) ile AYNI window.avatar API'sini sunar; native taraf farkı bilmez.
 */
(() => {
  'use strict';
  const cv = document.getElementById('c');
  const ctx = cv.getContext('2d');
  const TAU = Math.PI * 2;
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
  const rr = (a, b) => a + (b - a) * rnd();
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const ease = (t) => (t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t));
  const easeOut = (t) => 1 - Math.pow(1 - clamp(t, 0, 1), 3);

  const CYAN = [70, 215, 255], CYAN2 = [150, 240, 255], ORANGE = [255, 140, 40], AMBER = [255, 200, 90], RED = [255, 70, 90];
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;


  // ---------- uyarlanabilir kalite ----------
  // Kare süresi uzun kalırsa (zayıf GPU / yazılımla çizim) efektler kademeli azaltılır: 2 yüksek, 1 orta, 0 düşük.
  const Q = { level: 2, avg: 16, n: 0 };
  function trackQuality(ms) {
    // 1,5 saniyelik pencerelerde ortalama kare süresine bakılır (çok yavaş cihazda da hızlı tepki).
    Q.acc = (Q.acc || 0) + ms; Q.sum = (Q.sum || 0) + Math.min(ms, 1000); Q.n++;
    if (Q.acc < 1500 || Q.n < 3) return;
    const avg = Q.sum / Q.n;
    Q.acc = 0; Q.sum = 0; Q.n = 0;
    if (Q.level === 0) return;
    const next = avg > 120 ? 0 : avg > 40 ? Q.level - 1 : Q.level;
    if (next !== Q.level) { Q.level = next; if (next === 0) resize(); } // en düşükte çözünürlük 1x
  }

  let W = 0, H = 0, DPR = 1, S = 1, OX = 0, OY = 0;
  const sx = (x) => OX + x * S, sy = (y) => OY + y * S;

  // ---------- büst siluet genişliği (model koordinatı, merkez x=500) ----------
  const HEAD_CY = 340, HEAD_RX = 110, HEAD_RY = 152;
  function halfWidth(y) {
    let w = 0;
    const dy = (y - HEAD_CY) / HEAD_RY;
    if (Math.abs(dy) <= 1) {
      w = HEAD_RX * Math.sqrt(1 - dy * dy);
      if (dy > 0) w *= 1 - 0.38 * dy * dy;
    }
    if (y > 440) {
      // boyun -> eğimli omuzlar (yumuşak geçiş)
      const k = clamp((y - 535) / 210, 0, 1);
      let nw = 50 + (y - 440) * 0.08 + 262 * Math.pow(Math.sin(k * Math.PI / 2), 2.2);
      if (y > 745) nw += (y - 745) * 0.09;
      w = Math.max(w, nw);
    }
    return w;
  }

  // ---------- 1B gürültü ----------
  const perm = [];
  for (let i = 0; i < 512; i++) perm.push(rnd());
  function noise(x) {
    const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f);
    return perm[i & 511] * (1 - u) + perm[(i + 1) & 511] * u;
  }
  function fbm(x) { let a = 0, amp = 0.55, fr = 1; for (let o = 0; o < 5; o++) { a += amp * Math.abs(noise(x * fr) * 2 - 1); amp *= 0.5; fr *= 2.1; } return a; }

  // ---------- sahne verisi ----------
  let mountain = [], veins = [], bust = [], aura = [];
  function buildMountains() {
    seed = 21;
    mountain = []; veins = [];
    const layers = 11;
    for (let k = 0; k < layers; k++) {
      const n = 420;
      for (let i = 0; i < n; i++) {
        const u = i / (n - 1);
        const side = Math.abs(u - 0.5);
        const frame = 0.25 + 0.75 * ease((side - 0.08) / 0.3);
        const f = fbm(u * 5 + k * 3.7);
        const h = (0.25 + Math.pow(f, 1.6) * 1.2) * frame * (1 - k * 0.06);
        const y = 0.62 + k * 0.032 - h * 0.2;
        const cnt = 1 + Math.floor(rnd() * 3);
        for (let j = 0; j < cnt; j++) {
          mountain.push({ u: u + rr(-0.002, 0.002), v: y + rr(0, 0.03) * (j ? 1 : 0.15), k, a: rr(0.3, 1), p: rnd() * TAU, ridge: j === 0 });
        }
      }
    }
    for (let v = 0; v < 9; v++) {
      const left = v % 2 === 0;
      let u = left ? rr(0.0, 0.25) : rr(0.75, 1.0), y = rr(0.68, 0.9);
      const pts = [[u, y]];
      for (let s = 0; s < 40; s++) {
        u += (left ? 1 : -1) * rr(0.002, 0.008); y += rr(-0.01, 0.006);
        if (Math.abs(u - 0.5) < 0.17) break;
        pts.push([u, y]);
      }
      veins.push({ pts, p: rnd() });
    }
  }
  function buildBust() {
    seed = 99;
    bust = [];
    for (let y = HEAD_CY - HEAD_RY + 3; y <= 1000; y += 7) {
      const w = halfWidth(y);
      if (w < 4) continue;
      bust.push({ y, w, face: y > 255 && y < 430 });
    }
    aura = [];
    for (let i = 0; i < 700; i++) {
      const ang = rr(-Math.PI * 0.95, -Math.PI * 0.05);
      aura.push({ ang, r: rr(0, 1), sp: rr(0.04, 0.16), a: rr(0.2, 0.9), p: rnd() });
    }
  }

  // Montaj parçacıkları: alttaki ışık noktasından S eğrisiyle büst hatlarına akar.
  let swarm = [];
  function buildSwarm() {
    seed = 5;
    swarm = [];
    for (let i = 0; i < 1600; i++) {
      const row = bust[Math.floor(rnd() * bust.length)];
      const edge = rnd() < 0.6;
      const x = 500 + (edge ? (rnd() < 0.5 ? -1 : 1) * row.w : rr(-row.w, row.w));
      swarm.push({ tx: x, ty: row.y, d: rnd(), c: rnd() });
    }
  }

  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, Q.level === 0 ? 1 : 2);
    W = window.innerWidth; H = window.innerHeight;
    cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
    S = Math.min(W / 640, H / 1000);
    OX = W / 2 - 500 * S;
    OY = H - 1000 * S;
    bc.width = Math.max(1, Math.ceil(W / 5)); bc.height = Math.max(1, Math.ceil(H / 5));
  }
  const bc = document.createElement('canvas');
  const bctx = bc.getContext('2d');
  function bloom(k) {
    bctx.globalCompositeOperation = 'copy';
    bctx.filter = 'blur(2px)';
    bctx.drawImage(cv, 0, 0, bc.width, bc.height);
    bctx.filter = 'none';
    ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = k;
    ctx.drawImage(bc, 0, 0, cv.width, cv.height);
    ctx.restore();
  }

  // ---------- durum ----------
  const st = { state: 'idle', level: 0, lvl: 0, speed: 1, sound: true, t: 0, intro: 0, introDone: false, sim: 0, bright: 1 };
  const T_ASM = 4.2, T_REVEAL = 5.6;

  function drawMountains(t, A) {
    const reveal = ease((st.intro - T_ASM + 0.6) / 1.6);
    if (reveal <= 0) return;
    ctx.globalCompositeOperation = 'lighter';
    for (let mi = 0; mi < mountain.length; mi++) {
      if (Q.level === 0 && (mi % 3)) continue;
      const m = mountain[mi];
      const tw = 0.6 + 0.4 * Math.sin(t * 1.5 + m.p * 7);
      const a = m.a * tw * reveal * A * (m.ridge ? 1 : 0.4) * (1 - m.k * 0.05);
      const x = m.u * W, y = m.v * H + Math.sin(t * 0.4 + m.u * 9) * 1.5;
      const s = (m.ridge ? 1.6 : 1.1) * Math.max(1, S * 1.4);
      ctx.fillStyle = rgba(m.ridge ? CYAN2 : CYAN, a);
      ctx.fillRect(x, y, s, s);
    }
    for (const v of veins) {
      ctx.lineCap = 'round';
      ctx.beginPath();
      v.pts.forEach(([u, y], i) => (i ? ctx.lineTo(u * W, y * H) : ctx.moveTo(u * W, y * H)));
      ctx.strokeStyle = rgba(ORANGE, 0.12 * reveal * A); ctx.lineWidth = 6; ctx.stroke();
      ctx.strokeStyle = rgba(AMBER, 0.55 * reveal * A); ctx.lineWidth = 1.2; ctx.stroke();
      const k = (t * 0.15 + v.p) % 1;
      const p = v.pts[Math.floor(k * (v.pts.length - 1))];
      glow(p[0] * W, p[1] * H, 10, AMBER, 0.7 * reveal * A);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function glow(x, y, r, c, a) {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, rgba([255, 255, 255], a));
    g.addColorStop(0.3, rgba(c, a * 0.6));
    g.addColorStop(1, rgba(c, 0));
    ctx.fillStyle = g; ctx.fillRect(x - r, y - r, 2 * r, 2 * r);
  }

  function silhouettePath() {
    ctx.beginPath();
    let first = true;
    for (const r of bust) { const x = sx(500 - r.w), y = sy(r.y); first ? ctx.moveTo(x, y) : ctx.lineTo(x, y); first = false; }
    for (let i = bust.length - 1; i >= 0; i--) ctx.lineTo(sx(500 + bust[i].w), sy(bust[i].y));
    ctx.closePath();
  }

  function drawBust(t, A) {
    const rv = ease((st.intro - T_ASM + 0.3) / (T_REVEAL - T_ASM + 0.3));
    if (rv <= 0) return;
    const s = st.state, L = st.lvl;
    const faceCol = s === 'error' ? RED : s === 'listening' ? CYAN2 : s === 'confirming' ? AMBER : ORANGE;

    // koyu iç dolgu (dağları örter)
    silhouettePath();
    ctx.fillStyle = `rgba(0,4,8,${0.92 * rv})`;
    ctx.fill();

    ctx.globalCompositeOperation = 'lighter';
    // yüz ışığı
    const fx = sx(500), fy = sy(345);
    const fk = (s === 'talking' ? 0.55 + L * 0.7 : s === 'listening' ? 0.35 + L * 0.5 : s === 'micoff' ? 0.12 : 0.4 + 0.08 * Math.sin(t * 1.4)) * rv * A;
    const g = ctx.createRadialGradient(fx, fy, 0, fx, fy, 120 * S);
    g.addColorStop(0, rgba([255, 230, 170], 0.9 * fk));
    g.addColorStop(0.35, rgba(faceCol, 0.55 * fk));
    g.addColorStop(1, rgba(faceCol, 0));
    ctx.fillStyle = g; ctx.fillRect(fx - 130 * S, fy - 130 * S, 260 * S, 260 * S);

    // yatay kontur çizgileri
    const scanY = (s === 'thinking' || s === 'executing') ? 196 + ((t * (s === 'executing' ? 420 : 220)) % 820) : -1;
    for (const r of bust) {
      const n = 26;
      const bulge = r.y < 470 ? 10 : 18;
      ctx.beginPath();
      for (let i = 0; i <= n; i++) {
        const u = i / n * 2 - 1;
        const x = 500 + u * r.w * 0.985;
        let y = r.y + (1 - u * u) * bulge * 0.5;
        if (r.face && (s === 'talking' || s === 'listening')) y += Math.sin(u * 6 + t * 9 + r.y * 0.05) * (1.5 + 5 * L) * (1 - u * u);
        i ? ctx.lineTo(sx(x), sy(y)) : ctx.moveTo(sx(x), sy(y));
      }
      const inFace = r.face ? Math.max(0, 1 - Math.abs(r.y - 345) / 95) : 0;
      const scan = scanY > 0 ? Math.max(0, 1 - Math.abs(r.y - scanY) / 30) : 0;
      const col = inFace > 0.05 ? faceCol : CYAN;
      const base = r.y < 500 ? 0.34 : 0.2;
      ctx.strokeStyle = rgba(col, (base + 0.55 * inFace * fk + 0.6 * scan) * rv * A);
      ctx.lineWidth = Math.max(0.7, 1.1 * S);
      ctx.stroke();
    }

    // dış hat parlaması
    silhouettePath();
    ctx.lineJoin = 'round';
    ctx.strokeStyle = rgba(CYAN, 0.12 * rv * A); ctx.lineWidth = 16 * S; ctx.stroke();
    ctx.strokeStyle = rgba(CYAN, 0.35 * rv * A); ctx.lineWidth = 5 * S; ctx.stroke();
    ctx.strokeStyle = rgba(CYAN2, 0.95 * rv * A); ctx.lineWidth = Math.max(1, 1.6 * S); ctx.stroke();

    // boyun/göğüs damarları
    for (let b = 0; b < 5; b++) {
      ctx.beginPath();
      let x = 500 + (b - 2) * 6, y = 470;
      ctx.moveTo(sx(x), sy(y));
      seed = 300 + b;
      for (let i = 0; i < 22; i++) { x += rr(-7, 7) + (b - 2) * 1.5; y += 18; ctx.lineTo(sx(x), sy(y)); }
      const a = (0.45 + 0.35 * Math.sin(t * 2 + b)) * rv * A * (s === 'talking' ? 1 : 0.7);
      ctx.strokeStyle = rgba(ORANGE, 0.15 * a); ctx.lineWidth = 6 * S; ctx.stroke();
      ctx.strokeStyle = rgba(AMBER, 0.8 * a); ctx.lineWidth = Math.max(0.8, 1.2 * S); ctx.stroke();
    }

    // başın üstündeki parçacık aurası
    for (let ai = 0; ai < aura.length; ai++) {
      if (Q.level === 0 && (ai & 1)) continue;
      const p = aura[ai];
      const life = (p.r + t * p.sp * (s === 'talking' ? 2 : 1)) % 1;
      const rad = HEAD_RX * (1.02 + life * 0.9);
      const x = 500 + Math.cos(p.ang) * rad * 1.05;
      const y = HEAD_CY + Math.sin(p.ang) * HEAD_RY * (1.02 + life * 0.7);
      ctx.fillStyle = rgba(life < 0.5 ? CYAN2 : CYAN, p.a * (1 - life) * rv * A);
      const sz = Math.max(1, 1.5 * S);
      ctx.fillRect(sx(x), sy(y), sz, sz);
    }

    // dinlerken halkalar
    if (s === 'listening') {
      for (let i = 0; i < 3; i++) {
        const ph = (t * 0.7 + i / 3) % 1;
        ctx.strokeStyle = rgba(CYAN2, (1 - ph) * 0.35 * (0.5 + L));
        ctx.lineWidth = 1.5 * S;
        ctx.beginPath(); ctx.ellipse(fx, sy(HEAD_CY), (130 + ph * 220) * S, (160 + ph * 240) * S, 0, 0, TAU); ctx.stroke();
      }
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function drawIntro(t) {
    const it = st.intro;
    if (it > T_REVEAL + 0.5) return;
    ctx.globalCompositeOperation = 'lighter';
    const ox = sx(500), oy = sy(985);
    const fade = 1 - ease((it - T_ASM) / 1.2);
    glow(ox, oy, 70 * S, CYAN, 0.9 * fade);
    for (const p of swarm) {
      const k = clamp((it - p.d * 2.4) / 1.6, 0, 1);
      if (k <= 0) continue;
      const e = easeOut(k);
      // S eğrisi kontrol noktaları
      const c1x = 500 + (p.c - 0.5) * 900, c1y = 650 - p.c * 300;
      const u = 1 - e;
      const x = u * u * 500 + 2 * u * e * c1x + e * e * p.tx;
      const y = u * u * 985 + 2 * u * e * c1y + e * e * p.ty;
      const a = (k < 1 ? 0.9 : 0.5) * fade;
      ctx.fillStyle = rgba(p.c < 0.5 ? CYAN2 : CYAN, a);
      ctx.fillRect(sx(x), sy(y), Math.max(1, 1.6 * S), Math.max(1, 1.6 * S));
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  const LABEL = { idle: 'IDLE', listening: 'LISTENING', thinking: 'THINKING', talking: 'SPEAKING', executing: 'EXECUTING', confirming: 'CONFIRM?', error: 'ERROR', micoff: 'MIC OFF' };
  function drawHud() {
    ctx.font = `${Math.round(Math.max(10, 11 * Math.min(1.4, W / 500)))}px monospace`;
    ctx.textAlign = 'right';
    ctx.fillStyle = rgba(CYAN2, 0.85);
    const txt = st.intro < T_REVEAL
      ? `ASSEMBLING... ${Math.min(100, Math.floor((st.intro / T_REVEAL) * 100))}%`
      : `STATUS: ${LABEL[st.state] || st.state.toUpperCase()}`;
    ctx.fillText(txt.split('').join(' '), W - 18, H * 0.2);
    ctx.textAlign = 'left';
  }

  // ---------- ses ----------
  let ac = null, pad = null;
  function audio() {
    if (!st.sound) return null;
    try {
      if (!ac) {
        ac = new (window.AudioContext || window.webkitAudioContext)();
        const g = ac.createGain(); g.gain.value = 0;
        const f = ac.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = 600;
        [73.4, 110, 146.8].forEach((fr) => { const o = ac.createOscillator(); o.type = 'triangle'; o.frequency.value = fr; o.connect(f); o.start(); });
        f.connect(g); g.connect(ac.destination); pad = g;
      }
      if (ac.state === 'suspended') ac.resume();
    } catch (e) { return null; }
    return ac;
  }

  let last = performance.now();
  function frame(now) {
    // Düşük kalitede saniyede en fazla ~30 kare çizilir.
    if (Q.level === 0 && (Q.skip = !Q.skip)) { requestAnimationFrame(frame); return; }
    trackQuality(now - last);
    let dt = Math.min(0.05, (now - last) / 1000); last = now;
    dt *= st.speed; st.t += dt; st.intro += dt;
    const t = st.t;
    if (!st.introDone && st.intro > T_REVEAL + 0.3) {
      st.introDone = true;
      try { window.Android && window.Android.onIntroDone(); } catch (e) {}
    }
    let target = st.level;
    if (st.state === 'talking') {
      st.sim += dt * 9;
      target = Math.max(target, clamp(0.45 + 0.35 * Math.sin(st.sim) * Math.sin(st.sim * 0.37 + 1) + 0.2 * Math.random(), 0, 1));
    }
    st.lvl += (target - st.lvl) * Math.min(1, dt * 14);
    const wantB = st.state === 'micoff' ? 0.45 : 1;
    st.bright += (wantB - st.bright) * Math.min(1, dt * 3);

    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    ctx.fillStyle = '#000305';
    ctx.fillRect(0, 0, W, H);
    drawMountains(t, st.bright);
    drawBust(t, st.bright);
    drawIntro(t);
    if (Q.level === 2) bloom(0.5 + 0.3 * st.lvl);
    drawHud();
    if (pad && ac) pad.gain.setTargetAtTime(st.sound ? 0.01 + st.lvl * 0.01 : 0, ac.currentTime, 0.4);
    requestAnimationFrame(frame);
  }

  window.avatar = {
    setState(s) { st.state = s; if (s !== 'listening' && s !== 'talking') st.level = 0; },
    setLevel(v) { st.level = clamp(+v || 0, 0, 1); },
    replay() { st.intro = 0; st.introDone = false; audio(); },
    skipIntro() { st.intro = T_REVEAL + 1; },
    setSpeed(v) { st.speed = clamp(+v || 1, 0.25, 4); },
    setSound(on) { st.sound = !!on; if (on) audio(); },
    bolt() {},
  };

  window.addEventListener('resize', resize);
  document.addEventListener('pointerdown', () => audio(), { once: true });
  buildMountains();
  buildBust();
  buildSwarm();
  resize();
  const q = new URLSearchParams(location.search);
  if (q.has('state')) st.state = q.get('state');
  if (q.has('t')) st.intro = st.t = parseFloat(q.get('t'));
  requestAnimationFrame((n) => { last = n; frame(n); });
})();
