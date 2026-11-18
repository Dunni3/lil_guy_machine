'use strict';
// Mounts each <canvas data-sketch> and runs the ones on screen.

(function () {
  const live = [];
  const dpr = Math.min(2, window.devicePixelRatio || 1);

  for (const cv of document.querySelectorAll('canvas[data-sketch]')) {
    const def = SKETCHES[cv.dataset.sketch];
    if (!def) continue;
    cv.width = W * dpr; cv.height = H * dpr;
    const ctx = cv.getContext('2d');
    const S = { cv, ctx, dpr, t: 0, mouse: { x: W / 2, y: H / 2, in: false }, visible: false };
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    def.init && def.init(S);
    const local = e => { const r = cv.getBoundingClientRect(); return [(e.clientX - r.left) * W / r.width, (e.clientY - r.top) * H / r.height]; };
    cv.addEventListener('mousemove', e => {
      const [x, y] = local(e);
      Object.assign(S.mouse, { x, y, in: true });
      cv.style.cursor = def.hover && def.hover(S, x, y) ? 'pointer' : 'default';
    });
    cv.addEventListener('mouseleave', () => { S.mouse.in = false; });
    cv.addEventListener('click', e => { const [x, y] = local(e); def.click && def.click(S, x, y); });
    new IntersectionObserver(es => { S.visible = es[0].isIntersecting; }, { rootMargin: '80px' }).observe(cv);
    live.push({ def, S });
  }

  window.LIVE = Object.fromEntries(live.map(({ def, S }) => [S.cv.dataset.sketch, { def, S }])); // poke from the console

  let last = performance.now();
  function loop(now) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    for (const { def, S } of live) {
      if (!S.visible) continue;
      S.t += dt;
      S.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      S.ctx.globalAlpha = 1; S.ctx.globalCompositeOperation = 'source-over';
      try { def.frame(S, S.t, dt); } catch (err) { console.error(S.cv.dataset.sketch, err); S.visible = false; }
    }
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);
})();
