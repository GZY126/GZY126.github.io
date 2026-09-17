/* =========================================================
   SimpleDrawBot 作品页 · 交互与动画
   绘制坐标来自项目真实产出：assets/cat_strokes.json
   ========================================================= */

(function () {
  'use strict';

  /* ---------- 工具 ---------- */
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  /* ---------- 1. 数字滚动 ---------- */
  function animateCount(el) {
    const target = parseInt(el.dataset.count, 10);
    if (isNaN(target)) return;
    const dur = 1400, start = performance.now();
    function tick(now) {
      const p = Math.min((now - start) / dur, 1);
      const eased = 1 - Math.pow(1 - p, 3);
      el.textContent = Math.round(target * eased).toLocaleString('en-US');
      if (p < 1) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  }

  /* ---------- 2. 滚动揭示 ---------- */
  const io = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        e.target.classList.add('in');
        $$('[data-count]', e.target).forEach(animateCount);
        io.unobserve(e.target);
      }
    });
  }, { threshold: 0.12 });

  function initReveal() {
    ['#demo .demo-wrap', '#pipeline .flow', '#pipeline .fallback',
     '#pipeline .cv-step', '#pipeline .cv-pipeline',
     '#debug .tl-item', '#prompts .prompt-box', '#modules .mod-group',
     '#modules .nested-json', '#stack .stack-card', '#stack .sys-perm',
     '#reflect .rf-card'].forEach(sel => {
      $$(sel).forEach((el, i) => {
        el.classList.add('reveal');
        el.style.transitionDelay = (i % 4) * 70 + 'ms';
        io.observe(el);
      });
    });
    // hero 统计立即执行
    setTimeout(() => $$('.hero-stats [data-count]').forEach(animateCount), 260);
  }

  /* ---------- 3. 演示流程 ---------- */
  const panels = {
    input: $('#uiInput'),
    image: $('#uiImage'),
    contour: $('#uiContour'),
    canvas: $('#uiCanvas')
  };
  const stepEls = $$('#steps .step');
  const fab = $('#fab');
  const inputText = $('#uiInputText');
  const strokeInfo = $('#strokeInfo');
  const progress = $('#drawProgress');
  const pen = $('#pen');
  const canvas = $('#drawCanvas');
  const ctx = canvas.getContext('2d');
  const speedInput = $('#speed');

  let strokes = [];          // 归一化笔画（点云）
  let flat = [];             // 展平后的绘制点序列
  let fitted = [];           // 映射到画布的点序列
  let playing = false;
  let runToken = 0;          // 用于中断
  const DOT_R = 2.6;         // 点半径（画布内像素）

  function showPanel(name) {
    Object.entries(panels).forEach(([k, el]) => {
      el.classList.toggle('show', k === name);
    });
  }

  function setStep(i) {
    stepEls.forEach((el, k) => el.classList.toggle('active', k === i));
  }

  /* 展开点云并适配画布
     注：原始数据为轮廓采样点（左右边缘交替），
     因此采用「点云渲染」而非折线连接，否则会连成横线 */
  function fitStrokes() {
    const W = canvas.width, H = canvas.height;
    let minX = 1, minY = 1, maxX = 0, maxY = 0;
    strokes.forEach(s => s.forEach(([x, y]) => {
      if (x < minX) minX = x; if (x > maxX) maxX = x;
      if (y < minY) minY = y; if (y > maxY) maxY = y;
    }));
    const bw = Math.max(maxX - minX, 1e-6);
    const bh = Math.max(maxY - minY, 1e-6);
    const pad = 0.07;
    const scale = Math.min(W * (1 - pad * 2) / bw, H * (1 - pad * 2) / bh);
    const offX = (W - bw * scale) / 2 - minX * scale;
    const offY = (H - bh * scale) / 2 - minY * scale;

    fitted = strokes.map(s =>
      s.map(([x, y]) => [x * scale + offX, y * scale + offY]));
    // 展平并记录每个点所属笔画，便于显示进度
    flat = [];
    fitted.forEach((s, si) => s.forEach(p => flat.push({ p, si })));
  }

  function clearCanvas() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
  }

  function drawDot(pt) {
    ctx.beginPath();
    ctx.arc(pt[0], pt[1], DOT_R, 0, Math.PI * 2);
    ctx.fill();
  }

  /* 点云逐点绘制（真实坐标驱动） */
  async function drawAll(token) {
    clearCanvas();
    ctx.fillStyle = '#111827';
    const total = flat.length;
    let done = 0;
    let lastStroke = -1;
    const t0 = performance.now();
    pen.classList.add('on');

    const mStroke = $('#mStroke'), mPoints = $('#mPoints'), mState = $('#mState');
    mState.classList.remove('done');
    mState.textContent = '绘制中…';

    const perFrame = Math.max(2, Math.round(parseInt(speedInput.value, 10) * 1.6));

    for (let i = 0; i < total; i += perFrame) {
      if (token !== runToken) return;
      const slice = flat.slice(i, i + perFrame);
      slice.forEach(o => drawDot(o.p));
      const last = slice[slice.length - 1];
      movePen(last.p);
      done += slice.length;
      progress.style.width = Math.min(done / total * 100, 100).toFixed(1) + '%';
      if (last.si !== lastStroke) {
        lastStroke = last.si;
        strokeInfo.textContent = (lastStroke + 1) + ' / ' + fitted.length;
        mStroke.textContent = (lastStroke + 1) + ' / ' + fitted.length;
      }
      mPoints.textContent = done.toLocaleString('en-US') + ' / ' +
        total.toLocaleString('en-US');
      await sleep(16);
    }

    strokeInfo.textContent = fitted.length + ' / ' + fitted.length;
    mStroke.textContent = fitted.length + ' / ' + fitted.length;
    mPoints.textContent = total.toLocaleString('en-US') + ' / ' +
      total.toLocaleString('en-US');
    const sec = ((performance.now() - t0) / 1000).toFixed(1);
    mState.textContent = '✓ 已完成';
    mState.title = '耗时 ' + sec + 's';
    mState.classList.add('done');
    progress.style.width = '100%';
    pen.classList.remove('on');
  }

  function movePen(pt) {
    const box = canvas.getBoundingClientRect();
    const sx = box.width / canvas.width;
    const sy = box.height / canvas.height;
    const frame = canvas.parentElement.getBoundingClientRect();
    pen.style.left = (pt[0] * sx + (box.left - frame.left)) + 'px';
    pen.style.top = (pt[1] * sy + (box.top - frame.top)) + 'px';
  }

  /* 打字机 */
  async function typeText(text, token) {
    inputText.textContent = '';
    for (const ch of text) {
      if (token !== runToken) return;
      inputText.textContent += ch;
      await sleep(150);
    }
  }

  /* 主流程 */
  async function runDemo() {
    if (playing) return;
    playing = true;
    const token = ++runToken;
    $('#btnPlay').textContent = '⏸ 演示中…';
    progress.style.width = '0%';
    strokeInfo.textContent = '0 / ' + (fitted.length || 5);
    // 重置数据面板
    const mS = $('#mStroke'), mP = $('#mPoints'), mT = $('#mState');
    if (mS) mS.textContent = '—';
    if (mP) mP.textContent = '—';
    if (mT) { mT.textContent = '待开始'; mT.classList.remove('done'); }

    // 01 输入
    setStep(0); showPanel('input'); fab.classList.remove('show');
    await typeText('小猫', token);
    if (token !== runToken) return finish();
    await sleep(700);
    if (token !== runToken) return finish();

    // 02 AI 生成
    setStep(1); showPanel('image');
    await sleep(2600);
    if (token !== runToken) return finish();

    // 03 轮廓提取
    setStep(2); showPanel('contour');
    await sleep(2600);
    if (token !== runToken) return finish();

    // 04 笔画转换
    setStep(3); showPanel('canvas'); clearCanvas();
    fab.classList.add('show');
    await sleep(1200);
    if (token !== runToken) return finish();

    // 05 自动绘制
    setStep(4);
    await drawAll(token);
    if (token !== runToken) return finish();

    finish();
  }

  function finish() {
    playing = false;
    $('#btnPlay').textContent = '▶ 播放演示';
  }

  function stopDemo() {
    runToken++;
    playing = false;
    pen.classList.remove('on');
    $('#btnPlay').textContent = '▶ 播放演示';
  }

  /* ---------- 4. Prompt 切换 ---------- */
  const promptData = [
    {
      t: 'v1 · 英文「涂色书风格」',
      d: '最初尝试：用英文 Prompt 描述为 children\'s coloring book 风格。问题：生成结果带有灰阶填充与噪点，二值化后轮廓断裂，提取出的笔画过于破碎，无法形成连贯笔迹。',
      m: [['轮廓断裂', 'bad'], ['灰度噪点多', 'bad'], ['❌ 不可用', '']]
    },
    {
      t: 'v2 · 中文「简笔画」直译',
      d: '改用中文描述，试图让模型更贴合国内简笔画语义。效果略有改善，但线条粗细不均，细线在二值化时容易丢失。',
      m: [['线条粗细不均', 'bad'], ['细线易丢失', 'bad'], ['⚠️ 部分可用', '']]
    },
    {
      t: 'v3 · 技术线稿描述',
      d: '改用 technical line art / white background 等技术性描述，强调纯白背景与黑色描边。背景干净度明显提升，轮廓提取成功率上升。',
      m: [['背景干净', 'good'], ['轮廓完整', 'good'], ['⚠️ 细节偏多', '']]
    },
    {
      t: 'v4 · 涂鸦风格',
      d: '尝试 doodle 风格以获得更随意的线条。但涂鸦风格常带断笔与装饰性细节，导致轮廓数量暴增，绘制时间过长。',
      m: [['断笔多', 'bad'], ['轮廓数量暴增', 'bad'], ['⚠️ 绘制过慢', '']]
    },
    {
      t: 'v5 · 一笔画（one-line）',
      d: '尝试 one line drawing 让模型输出单笔连续线条，理论上最适合自动绘制。但复杂图形难以用一笔画表达，形状失真明显。',
      m: [['笔画数最少', 'good'], ['形状失真', 'bad'], ['⚠️ 简单图形可用', '']]
    },
    {
      t: 'v6 · 混合策略（最终）',
      d: '综合前五轮结论，最终采用「纯白背景 + 高对比度描边 + 负面词约束（排除灰阶、阴影、渐变）」的组合策略。这一版在轮廓完整度与笔画数量之间取得平衡，成为项目的默认 Prompt。',
      m: [['轮廓完整', 'good'], ['对比度充足', 'good'], ['✅ 默认方案', 'good']]
    }
  ];

  function bindPrompts() {
    const tabs = $$('#promptTabs .pt');
    const img = $('#promptImg');
    const title = $('#promptTitle');
    const desc = $('#promptDesc');
    const meta = $('#promptDesc').nextElementSibling;

    tabs.forEach((tab, i) => {
      tab.addEventListener('click', () => {
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        const d = promptData[i];
        img.src = 'assets/prompt_v' + (i + 1) + '.jpg';
        title.textContent = d.t;
        desc.textContent = d.d;
        meta.innerHTML = d.m.map(([txt, cls]) =>
          '<span class="pm ' + cls + '">' + txt + '</span>').join('');
      });
    });
  }

  /* ---------- 5. 时钟 ---------- */
  function tickClock() {
    const el = $('#clock');
    if (!el) return;
    const d = new Date();
    el.textContent = d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0');
  }

  /* ---------- 初始化 ---------- */
  async function init() {
    initReveal();
    bindPrompts();
    tickClock();
    setInterval(tickClock, 30000);
    clearCanvas();

    // 加载真实点云坐标
    try {
      const res = await fetch('assets/cat_points.json');
      const data = await res.json();
      strokes = data.strokes || [];
      fitStrokes();
      strokeInfo.textContent = '0 / ' + fitted.length;
    } catch (e) {
      console.warn('坐标加载失败，使用内置兜底数据', e);
      strokes = [[[0.2, 0.5], [0.3, 0.4], [0.45, 0.35], [0.6, 0.4], [0.7, 0.5]]];
      fitStrokes();
    }

    // 控件
    $('#btnPlay').addEventListener('click', () => {
      if (playing) stopDemo(); else runDemo();
    });
    $('#btnReplay').addEventListener('click', () => {
      stopDemo();
      setTimeout(runDemo, 60);
    });

    // 步骤点击
    stepEls.forEach((el, i) => {
      el.addEventListener('click', () => {
        stopDemo();
        setStep(i);
        if (i === 0) showPanel('input');
        else if (i === 1) showPanel('image');
        else if (i === 2) showPanel('contour');
        else { showPanel('canvas'); clearCanvas(); fab.classList.add('show'); }
      });
    });

    // 平滑滚动（兼容 sticky nav）
    $$('.nav-links a').forEach(a => {
      a.addEventListener('click', ev => {
        ev.preventDefault();
        const t = document.querySelector(a.getAttribute('href'));
        if (t) window.scrollTo({ top: t.offsetTop - 60, behavior: 'smooth' });
      });
    });
    $$('a.btn[href^="#"]').forEach(a => {
      a.addEventListener('click', ev => {
        ev.preventDefault();
        const t = document.querySelector(a.getAttribute('href'));
        if (t) window.scrollTo({ top: t.offsetTop - 60, behavior: 'smooth' });
      });
    });
  }

  document.addEventListener('DOMContentLoaded', init);
})();
