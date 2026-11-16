(function () {
  'use strict';

  var bridge = window.KimboxTerm;
  var host = document.getElementById('terminal');
  var statusEl = document.getElementById('status');
  var keysEl = document.getElementById('keys');

  var ended = false;
  var hintTimer = 0;
  var fitTimer = 0;
  var term = null;

  function showPersistent(text) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.hidden = false;
    statusEl.classList.add('persist');
  }

  function showHint(text) {
    if (!statusEl || ended) return;
    statusEl.textContent = text;
    statusEl.hidden = false;
    statusEl.classList.remove('persist');
    if (hintTimer) clearTimeout(hintTimer);
    hintTimer = setTimeout(function () {
      if (!ended) statusEl.hidden = true;
    }, 1800);
  }

  if (!window.Terminal || !window.FitAddon || !bridge || !host || !statusEl || !keysEl) {
    showPersistent('终端组件加载失败，请关闭本页重试');
    if (keysEl) keysEl.classList.add('disabled');
    return;
  }

  try {
    term = new window.Terminal({
      cursorBlink: true,
      cursorStyle: 'block',
      fontFamily: 'Menlo, Consolas, "DejaVu Sans Mono", monospace',
      fontSize: 14,
      lineHeight: 1.15,
      scrollback: 2000,
      scrollOnUserInput: true,
      convertEol: false,
      allowTransparency: false,
      rightClickSelectsWord: true,
      theme: {
        background: '#111114',
        foreground: '#e6e6e6',
        cursor: '#f4f4f5',
        cursorAccent: '#111114',
        selectionBackground: '#3a3a44',
        black: '#1b1b1f',
        red: '#f07178',
        green: '#c3e88d',
        yellow: '#ffcb6b',
        blue: '#82aaff',
        magenta: '#c792ea',
        cyan: '#89ddff',
        white: '#d0d0d0',
        brightBlack: '#5c5c66',
        brightRed: '#ff8b92',
        brightGreen: '#d7ffa6',
        brightYellow: '#ffe082',
        brightBlue: '#9cc4ff',
        brightMagenta: '#dbb0ff',
        brightCyan: '#a7f0ff',
        brightWhite: '#ffffff'
      },
      // OSC 8 链接（kimi login 的登录地址）交给系统浏览器，WebView 不自己开新窗口
      linkHandler: {
        activate: function (event, text) {
          if (ended) return;
          try { bridge.openUrl(text); } catch (e) {}
        }
      }
    });
    var fitAddon = new window.FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(host);
  } catch (e) {
    showPersistent('终端初始化失败，请关闭本页重试');
    keysEl.classList.add('disabled');
    return;
  }

  function end(text) {
    ended = true;
    if (fitTimer) clearTimeout(fitTimer);
    if (hintTimer) clearTimeout(hintTimer);
    showPersistent(text);
    keysEl.classList.add('disabled');
    try { term.blur(); } catch (e) {}
  }

  function fit() {
    if (ended) return;
    try { fitAddon.fit(); } catch (e) {}
  }

  function scheduleFit() {
    if (ended) return;
    if (fitTimer) clearTimeout(fitTimer);
    fitTimer = setTimeout(fit, 120);
  }

  function doCopy() {
    var text = '';
    var all = false;
    try { text = term.getSelection(); } catch (e) {}
    if (!text) {
      try {
        term.selectAll();
        text = term.getSelection();
        all = true;
      } catch (e) {}
    }
    if (!text) {
      showHint('没有可复制的内容');
      return;
    }
    var ok = false;
    try { ok = bridge.copy(text); } catch (e) {}
    if (all) {
      try { term.clearSelection(); } catch (e) {}
    }
    showHint(ok ? (all ? '已复制全部输出' : '已复制选中文字') : '复制失败');
  }

  function doPaste() {
    var text = '';
    try { text = bridge.paste(); } catch (e) {}
    if (!text) {
      showHint('剪贴板为空或无法读取');
      return;
    }
    try { term.paste(text); } catch (e) {}
    try { term.focus(); } catch (e) {}
    showHint('已粘贴');
  }

  function sendInput(data) {
    var accepted = false;
    try { accepted = bridge.write(data); } catch (e) {}
    if (!accepted) showHint('输入未发送：终端未就绪或输入队列已满');
    return accepted;
  }

  var SEQS = {
    esc: '\x1b',
    tab: '\t',
    ctrlc: '\x03',
    left: '\x1b[D',
    up: '\x1b[A',
    down: '\x1b[B',
    right: '\x1b[C'
  };

  function activate(button) {
    if (ended) return;
    var action = button.getAttribute('data-action');
    if (action === 'copy') {
      doCopy();
      return;
    }
    if (action === 'paste') {
      doPaste();
      return;
    }
    var seq = SEQS[button.getAttribute('data-key')];
    if (seq) {
      try { sendInput(seq); } catch (e) {}
      try { term.focus(); } catch (e) {}
    }
  }

  var buttons = keysEl.querySelectorAll('button');
  for (var i = 0; i < buttons.length; i++) {
    (function (button) {
      var pointerHandled = false;
      button.addEventListener('pointerdown', function (e) {
        e.preventDefault();
        pointerHandled = true;
        activate(button);
        setTimeout(function () { pointerHandled = false; }, 500);
      });
      button.addEventListener('click', function (e) {
        e.preventDefault();
        if (pointerHandled) return;
        activate(button);
      });
    })(buttons[i]);
  }

  term.onData(function (data) {
    if (ended) return;
    try { sendInput(data); } catch (e) {}
  });

  term.onResize(function (size) {
    try { bridge.resize(size.cols, size.rows); } catch (e) {}
  });

  term.attachCustomKeyEventHandler(function (e) {
    if (e.type !== 'keydown') return true;
    if (!(e.ctrlKey || e.metaKey)) return true;
    var key = (e.key || '').toLowerCase();
    if (e.shiftKey && key === 'v') {
      doPaste();
      return false;
    }
    if (e.shiftKey && key === 'c') {
      doCopy();
      return false;
    }
    // 有选区时 Ctrl+C 复制；没有选区时保持原来的 SIGINT 语义
    if (!e.shiftKey && key === 'c' && term.hasSelection()) {
      doCopy();
      return false;
    }
    return true;
  });

  window.__kimboxTerm = {
    output: function (b64, seq) {
      var acked = false;
      function ack() {
        if (acked) return;
        acked = true;
        try { bridge.ack(seq); } catch (e) {}
      }
      if (ended) {
        ack();
        return;
      }
      var bytes;
      try {
        var bin = window.atob(b64);
        bytes = new Uint8Array(bin.length);
        for (var j = 0; j < bin.length; j++) bytes[j] = bin.charCodeAt(j);
      } catch (e) {
        ack();
        return;
      }
      try {
        // xterm 的 UTF-8 解码器跨 write 调用保留半个汉字，直接传字节即可
        term.write(bytes, ack);
      } catch (e) {
        ack();
      }
    },
    exit: function (code) {
      end('终端已结束（退出码 ' + code + '）。关闭本页后重开可新建会话。');
    },
    failed: function (message) {
      end(String(message || '终端启动失败'));
    },
    focus: function () {
      if (!ended) {
        try { term.focus(); } catch (e) {}
      }
    }
  };

  if (window.ResizeObserver) {
    new window.ResizeObserver(scheduleFit).observe(host);
  }
  window.addEventListener('resize', scheduleFit);
  window.addEventListener('orientationchange', scheduleFit);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', scheduleFit);
  }
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) scheduleFit();
  });

  fit();
  try { bridge.resize(term.cols, term.rows); } catch (e) {}
  try { bridge.ready(); } catch (e) {}
})();
