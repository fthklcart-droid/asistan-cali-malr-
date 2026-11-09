/*
 * 3B holografik katman — NEON ve ART avatarlarının ÜSTÜNE çizilir, ikisinin çizimini değiştirmez.
 *  - Sahne 3B eğilme: cihaz eğimi (deviceorientation) / parmak sürükleme / boşta hafif salınım -> CSS perspective
 *  - Başın çevresinde derinlik sıralı, perspektifle izdüşürülen yörünge halkaları (öndeki parlak, arkadaki soluk)
 *  - Altta holografik projektör: zemin düzleminde dönen halkalar, ışık konisi, 3B yükselen parçacıklar
 *  - Konuşurken zeminden yayılan 3B dalga halkaları, düşünürken hızlanan kesişen halkalar
 *  - Tarama çizgileri, süpürme bandı ve durum değişiminde kısa "glitch" geçişi
 * Avatar betiği window.avatar.anchor() ile baş konumunu verir; yoksa ekran oranlarından tahmin edilir.
 * window.avatar.setHolo(false) katmanı ve eğilmeyi kapatır.
 */
(() => {
  'use strict';
  const stage = document.getElementById('stage');
  const main = document.getElementById('c');
  const cv = document.getElementById('h');
  if (!stage || !cv) return;
  const ctx = cv.getContext('2d');
  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, k) => a + (b - a) * k;
  let seed = 4242;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;

  const q = new URLSearchParams(location.search);
  let enabled = q.get('holo') !== '0';

  // Durum renkleri (r,g,b)
  const COLORS = {
    idle: [80, 220, 255], listening: [61, 255, 176], thinking: [170, 120, 255], talking: [110, 235, 255],
    executing: [140, 110, 255], confirming: [255, 196, 90], error: [255, 70, 90], micoff: [90, 110, 140],
  };
  const SPIN = { idle: 0.25, listening: 0.45, thinking: 1.6, talking: 0.6, executing: 1.1, confirming: 0.35, error: 0.15, micoff: 0.08 };

  const st = {
    state: 'idle', level: 0, lvl: 0, t: 0,
    col: COLORS.idle.slice(), spin: 0.25, ringPhase: 0,
    // eğilme (derece)
    tiltX: 0, tiltY: 0, wantX: 0, wantY: 0,
    orient: null, orientBase: null, drag: null,
    glitch: 0, nextGlitch: 9, flicker: 1,
    waves: [], nextWave: 0,
  };

  let W = 0, H = 0, DPR = 1;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, quality() === 0 ? 1 : 2);
    W = window.innerWidth; H = window.innerHeight;
    cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
    buildScan();
  }
  function quality() { try { return window.avatar && window.avatar.quality ? window.avatar.quality() : 2; } catch (e) { return 2; } }

  // ---------- tarama çizgisi deseni ----------
  let scanPattern = null;
  function buildScan() {
    const p = document.createElement('canvas');
    p.width = 4; p.height = 4;
    const g = p.getContext('2d');
    g.fillStyle = 'rgba(0,0,0,0)'; g.fillRect(0, 0, 4, 4);
    g.fillStyle = 'rgba(140,230,255,0.07)'; g.fillRect(0, 0, 4, 1);
    scanPattern = ctx.createPattern(p, 'repeat');
  }

  // ---------- 3B yardımcılar ----------
  // Nokta (x,y,z) önce kendi ekseni etrafında, sonra sahne eğimine göre döndürülür ve perspektifle izdüşürülür.
  const FOCAL = 700;
  function rotX(p, a) { const c = Math.cos(a), s = Math.sin(a); return [p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c]; }
  function rotY(p, a) { const c = Math.cos(a), s = Math.sin(a); return [p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c]; }
  function rotZ(p, a) { const c = Math.cos(a), s = Math.sin(a); return [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]]; }
  function project(p, cx, cy) {
    const k = FOCAL / (FOCAL + p[2]);
    return [cx + p[0] * k, cy + p[1] * k, k, p[2]];
  }
  const rgba = (c, a) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a})`;

  // ---------- yerleşim ----------
  function anchor() {
    try {
      const a = window.avatar && window.avatar.anchor && window.avatar.anchor();
      if (a && isFinite(a.x)) return a;
    } catch (e) {}
    return { x: W / 2, y: H * 0.32, r: Math.min(W, H) * 0.16, base: H };
  }

  // ---------- yörünge halkaları ----------
  const RINGS = [
    { k: 1.55, ax: 1.18, az: 0.18, dir: 1, seg: 0.62, dots: true },
    { k: 1.85, ax: 1.32, az: -0.42, dir: -1, seg: 0.38, dots: false },
    { k: 2.2, ax: 0.95, az: 0.75, dir: 1, seg: 0.8, dots: true },
  ];
  function drawRings(A, a, sceneRX, sceneRY) {
    const Ql = quality();
    const N = Ql === 2 ? 96 : Ql === 1 ? 64 : 40;
    const thinking = st.state === 'thinking' || st.state === 'executing';
    const pts = [];
    RINGS.forEach((r, ri) => {
      let R = a.r * r.k * (1 + 0.08 * st.lvl * Math.sin(st.t * 6 + ri));
      if (st.state === 'listening') R *= 1 + 0.12 * st.lvl;
      const spin = st.ringPhase * r.dir * (1 + ri * 0.35);
      const ax = r.ax + (thinking ? 0.35 * Math.sin(st.t * 0.9 + ri * 2) : 0.08 * Math.sin(st.t * 0.3 + ri));
      const az = r.az + (thinking ? st.t * 0.5 * r.dir : 0);
      for (let i = 0; i < N; i++) {
        const u = i / N;
        // halka yayları: segment dışındaki noktalar atlanır (kesik veri halkası görünümü)
        const segOn = ((u * 7 + ri * 0.13 + st.t * 0.05) % 1) < r.seg;
        let p = [Math.cos(u * TAU + spin) * R, 0, Math.sin(u * TAU + spin) * R];
        p = rotX(p, ax); p = rotZ(p, az);
        p = rotX(p, sceneRX); p = rotY(p, sceneRY);
        const pr = project(p, a.x, a.y);
        pts.push({ x: pr[0], y: pr[1], k: pr[2], z: pr[3], on: segOn, ri, dot: r.dots && i % 4 === 0 });
      }
    });
    // Ardışık noktalar arasındaki parçalar derinliğe göre sıralanıp çizilir: öndeki kalın/parlak, arkadaki ince/soluk.
    const segs = [];
    for (let i = 0; i < pts.length; i++) {
      const p1 = pts[i], p2 = pts[(i + 1) % N === 0 ? i + 1 - N : i + 1];
      if (!p1.on || !p2.on) continue;
      segs.push([p1, p2, (p1.z + p2.z) / 2]);
    }
    segs.sort((s1, s2) => s2[2] - s1[2]);
    ctx.globalCompositeOperation = 'lighter';
    ctx.lineCap = 'round';
    const span = a.r * 2.4;
    for (const [p1, p2, z] of segs) {
      const front = clamp(0.5 - z / span, 0, 1); // 0 arka .. 1 ön
      const alpha = A * (0.18 + 0.75 * front);
      ctx.strokeStyle = rgba(st.col, alpha);
      ctx.lineWidth = (0.7 + 2.2 * front) * p1.k * (1 + st.lvl * 0.4);
      ctx.beginPath(); ctx.moveTo(p1.x, p1.y); ctx.lineTo(p2.x, p2.y); ctx.stroke();
      if (Ql === 2 && front > 0.55) {
        ctx.strokeStyle = rgba(st.col, alpha * 0.16);
        ctx.lineWidth *= 5;
        ctx.beginPath(); ctx.moveTo(p1.x, p1.y); ctx.lineTo(p2.x, p2.y); ctx.stroke();
      }
    }
    // düğüm noktaları
    for (const p of pts) {
      if (!p.dot) continue;
      const front = clamp(0.5 - p.z / span, 0, 1);
      const r = (1.6 + 2.6 * front) * p.k * (1 + st.lvl * 0.6);
      ctx.fillStyle = rgba([255, 255, 255], A * (0.25 + 0.7 * front));
      ctx.beginPath(); ctx.arc(p.x, p.y, r * 0.6, 0, TAU); ctx.fill();
      ctx.fillStyle = rgba(st.col, A * 0.22 * front);
      ctx.beginPath(); ctx.arc(p.x, p.y, r * 2.8, 0, TAU); ctx.fill();
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------- projektör ----------
  const PARTS = [];
  function buildParticles() {
    PARTS.length = 0;
    for (let i = 0; i < 140; i++) PARTS.push({ ang: rnd() * TAU, rad: Math.sqrt(rnd()), h: rnd(), v: 0.04 + rnd() * 0.09, s: 0.6 + rnd() * 1.4 });
  }
  function drawProjector(A, a, sceneRX, sceneRY) {
    const Ql = quality();
    const baseR = Math.min(W * 0.42, a.r * 2.6);
    const baseY = Math.min(H - baseR * 0.22 - 6, (a.base || H) - 14);
    const cx = a.x;
    // ışık konisi: iç içe yumuşak katmanlar (sert kenar yok), yukarı doğru söner
    const topY = a.y - a.r * 1.4;
    ctx.globalCompositeOperation = 'lighter';
    const layers = quality() === 0 ? 2 : 4;
    for (let L = 0; L < layers; L++) {
      const w = (L + 1) / layers;                // dar -> geniş
      const g = ctx.createLinearGradient(0, baseY, 0, topY);
      const k = 0.05 * A * (1.1 - w * 0.6) * (1 + 0.6 * st.lvl);
      g.addColorStop(0, rgba(st.col, k));
      g.addColorStop(0.6, rgba(st.col, k * 0.35));
      g.addColorStop(1, rgba(st.col, 0));
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.moveTo(cx - baseR * 0.45 * w, baseY);
      ctx.lineTo(cx - baseR * 1.1 * w, topY);
      ctx.lineTo(cx + baseR * 1.1 * w, topY);
      ctx.lineTo(cx + baseR * 0.45 * w, baseY);
      ctx.closePath(); ctx.fill();
    }
    ctx.globalCompositeOperation = 'source-over';

    // zemin düzleminde dönen halkalar (3B daireler -> elips)
    ctx.globalCompositeOperation = 'lighter';
    const floorTilt = 1.28 + sceneRX * 0.6;
    const rings = [[0.55, 1, 0.5], [0.8, -1, 0.75], [1.0, 1, 0.35]];
    rings.forEach(([k, dir, seg], idx) => {
      const R = baseR * k;
      const N = Ql === 0 ? 36 : 72;
      ctx.lineWidth = idx === 0 ? 2 : 1.2;
      ctx.strokeStyle = rgba(st.col, (0.55 - idx * 0.12) * A);
      ctx.beginPath();
      let pen = false;
      for (let i = 0; i <= N; i++) {
        const u = i / N;
        const on = ((u * 5 + st.t * 0.08 * dir) % 1 + 1) % 1 < seg;
        let p = [Math.cos(u * TAU + st.ringPhase * 0.4 * dir) * R, 0, Math.sin(u * TAU + st.ringPhase * 0.4 * dir) * R];
        p = rotX(p, floorTilt); p = rotY(p, sceneRY);
        const pr = project(p, cx, baseY);
        if (on) { if (pen) ctx.lineTo(pr[0], pr[1]); else { ctx.moveTo(pr[0], pr[1]); pen = true; } } else pen = false;
      }
      ctx.stroke();
    });
    // merkez yayıcı
    const eg = ctx.createRadialGradient(cx, baseY, 0, cx, baseY, baseR * 0.5);
    eg.addColorStop(0, rgba(st.col, 0.5 * A));
    eg.addColorStop(1, rgba(st.col, 0));
    ctx.fillStyle = eg;
    ctx.beginPath(); ctx.ellipse(cx, baseY, baseR * 0.5, baseR * 0.12, 0, 0, TAU); ctx.fill();

    // konuşma dalgaları: zeminden genişleyen halkalar
    for (const w of st.waves) {
      const R = baseR * (0.3 + w.age * 1.3);
      const alpha = (1 - w.age) * 0.5 * A * w.amp;
      if (alpha <= 0.01) continue;
      ctx.strokeStyle = rgba(st.col, alpha);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.ellipse(cx, baseY - w.age * 8, R, R * Math.cos(floorTilt) * 1.1 + 2, 0, 0, TAU);
      ctx.stroke();
    }

    // yükselen 3B parçacıklar (silindir içinde)
    const n = Ql === 2 ? PARTS.length : Ql === 1 ? 80 : 40;
    const height = baseY - topY;
    const speed = st.state === 'talking' ? 1.8 : st.state === 'thinking' ? 1.4 : 1;
    for (let i = 0; i < n; i++) {
      const p = PARTS[i];
      p.h += p.v * st.dt * speed;
      if (p.h > 1) { p.h -= 1; p.ang = rnd() * TAU; }
      const r = baseR * (0.25 + 0.75 * p.rad) * (1 - p.h * 0.25);
      let v = [Math.cos(p.ang + st.t * 0.3) * r, -p.h * height, Math.sin(p.ang + st.t * 0.3) * r];
      v = rotY(v, sceneRY);
      const pr = project(v, cx, baseY);
      const fade = Math.sin(p.h * Math.PI);
      const alpha = A * fade * (pr[3] < 0 ? 0.85 : 0.35);
      ctx.fillStyle = rgba(st.col, alpha);
      ctx.fillRect(pr[0], pr[1], p.s * pr[2], p.s * pr[2] * 2.2);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------- tarama / glitch ----------
  function drawScan(A) {
    if (quality() >= 1 && scanPattern) {
      ctx.fillStyle = scanPattern;
      ctx.globalAlpha = A;
      ctx.fillRect(0, 0, W, H);
      ctx.globalAlpha = 1;
    }
    // aşağı süpüren parlak bant
    const period = st.state === 'thinking' ? 2.2 : 5.5;
    const ph = (st.t % period) / period;
    const y = -60 + ph * (H + 120);
    const g = ctx.createLinearGradient(0, y - 40, 0, y + 6);
    g.addColorStop(0, rgba(st.col, 0));
    g.addColorStop(0.85, rgba(st.col, 0.07 * A));
    g.addColorStop(1, rgba(st.col, 0.22 * A));
    ctx.fillStyle = g;
    ctx.fillRect(0, y - 40, W, 46);
  }
  function drawGlitch() {
    if (st.glitch <= 0 || !main || !main.width) return;
    const k = main.width / W;
    const slices = 3 + ((st.t * 30) | 0) % 3;
    ctx.globalCompositeOperation = 'lighter';
    for (let i = 0; i < slices; i++) {
      const y = rnd() * H, h = 4 + rnd() * 26, dx = (rnd() - 0.5) * 28 * st.glitch;
      try { ctx.globalAlpha = 0.55 * st.glitch; ctx.drawImage(main, 0, y * k, main.width, h * k, dx, y, W, h); } catch (e) {}
      ctx.globalAlpha = 1;
      ctx.fillStyle = i % 2 ? `rgba(255,40,140,${0.12 * st.glitch})` : `rgba(40,240,255,${0.12 * st.glitch})`;
      ctx.fillRect(0, y, W, h);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  // ---------- eğilme girdileri ----------
  window.addEventListener('deviceorientation', (e) => {
    if (e.beta == null || e.gamma == null) return;
    if (!st.orientBase) st.orientBase = { b: e.beta, g: e.gamma };
    // taban yavaşça güncel duruşa kayar: telefonu nasıl tutarsan tut, ani hareketler eğilme olarak görünür
    st.orientBase.b = lerp(st.orientBase.b, e.beta, 0.01);
    st.orientBase.g = lerp(st.orientBase.g, e.gamma, 0.01);
    st.orient = { b: e.beta - st.orientBase.b, g: e.gamma - st.orientBase.g };
  });
  window.addEventListener('pointerdown', (e) => { st.drag = { x: e.clientX, y: e.clientY, dx: 0, dy: 0 }; });
  window.addEventListener('pointermove', (e) => { if (st.drag) { st.drag.dx = e.clientX - st.drag.x; st.drag.dy = e.clientY - st.drag.y; } });
  const endDrag = () => { st.drag = null; };
  window.addEventListener('pointerup', endDrag);
  window.addEventListener('pointercancel', endDrag);

  function updateTilt(dt) {
    const MAX = 9;
    let tx = 3.2 * Math.sin(st.t * 0.37), ty = 2.2 * Math.sin(st.t * 0.23 + 1);
    if (st.orient) { tx += clamp(st.orient.g, -25, 25) * 0.3; ty += clamp(-st.orient.b, -25, 25) * 0.3; }
    if (st.drag) { tx += st.drag.dx / W * 30; ty -= st.drag.dy / H * 30; }
    st.wantX = clamp(tx, -MAX, MAX); st.wantY = clamp(ty, -MAX, MAX);
    const k = Math.min(1, dt * (st.drag ? 10 : 3));
    st.tiltX = lerp(st.tiltX, st.wantX, k);
    st.tiltY = lerp(st.tiltY, st.wantY, k);
    if (enabled) {
      const z = 1.07 + 0.01 * Math.sin(st.t * 0.8) + st.lvl * 0.012;
      stage.style.transform = `perspective(900px) rotateX(${st.tiltY.toFixed(2)}deg) rotateY(${st.tiltX.toFixed(2)}deg) scale(${z.toFixed(4)})`;
      stage.style.opacity = st.flicker.toFixed(3);
    }
  }

  // ---------- ana döngü ----------
  let last = performance.now();
  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now;
    st.dt = dt; st.t += dt;
    st.lvl += (st.level - st.lvl) * Math.min(1, dt * 12);
    if (st.state === 'talking' && st.level < 0.05) st.lvl = Math.max(st.lvl, 0.35 + 0.25 * Math.sin(st.t * 8));
    const want = COLORS[st.state] || COLORS.idle;
    for (let i = 0; i < 3; i++) st.col[i] = lerp(st.col[i], want[i], Math.min(1, dt * 4));
    st.spin = lerp(st.spin, SPIN[st.state] ?? 0.25, Math.min(1, dt * 2));
    st.ringPhase += st.spin * dt * (1 + st.lvl);

    // dalgalar
    st.nextWave -= dt;
    if (st.state === 'talking' && st.nextWave <= 0 && st.lvl > 0.3) { st.waves.push({ age: 0, amp: st.lvl }); st.nextWave = 0.45; }
    if (st.state === 'listening' && st.nextWave <= 0 && st.lvl > 0.25) { st.waves.push({ age: 0, amp: st.lvl * 0.7 }); st.nextWave = 0.6; }
    for (const w of st.waves) w.age += dt * 0.7;
    while (st.waves.length && st.waves[0].age >= 1) st.waves.shift();

    // titreme ve glitch
    st.nextGlitch -= dt;
    if (st.nextGlitch <= 0 && st.state !== 'micoff') { st.glitch = 0.6; st.nextGlitch = 8 + rnd() * 10; }
    st.glitch = Math.max(0, st.glitch - dt * 4);
    st.flicker = st.glitch > 0 ? 0.88 + 0.12 * rnd() : lerp(st.flicker, 1, Math.min(1, dt * 8));

    updateTilt(dt);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    ctx.clearRect(0, 0, W, H);
    if (enabled) {
      const a = anchor();
      const A = st.state === 'micoff' ? 0.35 : st.state === 'error' ? 0.9 + 0.1 * Math.sin(st.t * 20) : 0.85;
      const sceneRX = -st.tiltY * Math.PI / 180 * 1.8, sceneRY = st.tiltX * Math.PI / 180 * 1.8;
      drawProjector(A, a, sceneRX, sceneRY);
      drawRings(A, a, sceneRX, sceneRY);
      drawScan(A);
      drawGlitch();
    }
    requestAnimationFrame(frame);
  }

  function hook() {
    const av = window.avatar;
    if (!av) { setTimeout(hook, 40); return; }
    const setState = av.setState, setLevel = av.setLevel;
    av.setState = (s) => {
      if (s !== st.state) { st.glitch = Math.max(st.glitch, s === 'error' ? 1 : 0.7); }
      st.state = s;
      if (s !== 'listening' && s !== 'talking') st.level = 0;
      return setState.call(av, s);
    };
    av.setLevel = (v) => { st.level = clamp(+v || 0, 0, 1); return setLevel.call(av, v); };
    av.setHolo = (on) => {
      enabled = !!on;
      if (!enabled) { stage.style.transform = 'none'; stage.style.opacity = '1'; ctx.clearRect(0, 0, cv.width, cv.height); }
    };
    if (q.has('state')) st.state = q.get('state');
    if (!enabled) av.setHolo(false);
  }

  window.addEventListener('resize', resize);
  buildParticles();
  resize();
  hook();
  requestAnimationFrame((n) => { last = n; frame(n); });
})();
