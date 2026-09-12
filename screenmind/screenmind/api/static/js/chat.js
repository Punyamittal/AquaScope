//  CHAT ASSISTANT (conversational chatbot)
// ══════════════════════════════════════════════════════════
let chatHistory = [];  // {role: 'user'|'assistant', content: string}
let chatBusy = false;
let chatContextRange = 'today'; // 'today' | '7d' | '30d' | 'all'

async function renderChat(el) {
  el.innerHTML = `
    <div class="chat-container" id="chat-root">
      <div class="chat-header">
        <div class="chat-header-title"><span>🧠</span> ScreenMind <span class="hint-trigger" id="chat-hint-trigger" style="display:none" title="">?</span></div>
        <button class="chat-new-btn" onclick="newChat()">+ New chat</button>
        <div class="chat-context-bar">
          <span class="chat-context-label">Context:</span>
          <div class="chat-context-pills">
            <button class="chat-context-pill${chatContextRange === 'today' ? ' active' : ''}" data-range="today">Today</button>
            <button class="chat-context-pill${chatContextRange === '7d' ? ' active' : ''}" data-range="7d">7 Days</button>
            <button class="chat-context-pill${chatContextRange === '30d' ? ' active' : ''}" data-range="30d">30 Days</button>
            <button class="chat-context-pill${chatContextRange === 'all' ? ' active' : ''}" data-range="all">All Time</button>
          </div>
        </div>
      </div>
      <div class="chat-messages" id="chat-messages">
        <div class="chat-welcome" id="chat-welcome">
          <div class="chat-welcome-icon">🧠</div>
          <h3>Hey! I'm ScreenMind</h3>
          <p>Chat with me about anything — or ask about your screen activity, emails, messages. I'll search your timeline when needed.</p>
          <div class="chat-suggestions" id="chat-suggestions">
            <button class="chat-suggest" onclick="chatSuggestion('What did aachii say?')">💬 What did aachii say?</button>
            <button class="chat-suggest" onclick="chatSuggestion('Tell me a joke')">😄 Tell me a joke</button>
            <button class="chat-suggest" onclick="chatSuggestion('What tabs do I have open?')">🔍 Open tabs?</button>
          </div>
        </div>
      </div>
      <div class="chat-input-bar" id="chat-input-bar">
        <input type="text" id="chat-input" placeholder="Type a message..." autocomplete="off">
        <button class="chat-send" id="chat-send" onclick="submitChat()">➤</button>
      </div>
    </div>`;

  // ── Event: context pill clicks ──
  document.querySelectorAll('.chat-context-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      chatContextRange = pill.dataset.range;
      document.querySelectorAll('.chat-context-pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
    });
  });

  $('#chat-input').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submitChat(); }
  });

  // Check model status on initial render
  try {
    const status = await api('/api/status');
    if (status.model) {
      _modelState.status = status.model.status;
      _modelState.activeModel = status.model.active_model;
      _modelState.modelDownloaded = status.model.model_downloaded;
      _modelState.download = status.model.download;
      _modelState.message = status.model.message || '';
    }
  } catch {}

  _updateChatLockState();

  if (_modelState.status === 'ready') {
    $('#chat-input').focus();
    // Hover tooltip hint (non-intrusive)
    try {
      const mdata = await api('/api/models');
      if (!mdata.is_top_model) {
        const t = document.getElementById('chat-hint-trigger');
        if (t) { t.style.display = 'inline-flex'; t.title = `Using ${mdata.active}. Upgrade to a larger model in Settings for better results.`; }
      }
    } catch {}
  }
}

let _chatWasLocked = false;

function _updateChatLockState() {
  const messagesEl = document.getElementById('chat-messages');
  const inputBar = document.getElementById('chat-input-bar');
  if (!messagesEl || !inputBar) return;

  if (_modelState.status === 'ready') {
    const lockEl = document.getElementById('chat-locked');
    if (lockEl) {
      lockEl.remove();
      inputBar.classList.remove('disabled');
      const chatInput = document.getElementById('chat-input');
      if (chatInput) { chatInput.placeholder = 'Type a message...'; chatInput.disabled = false; }
      const sendBtn = document.getElementById('chat-send');
      if (sendBtn) sendBtn.disabled = false;
      if (!chatHistory.length && !messagesEl.querySelector('.chat-msg')) {
        messagesEl.innerHTML = `
          <div class="chat-welcome" id="chat-welcome">
            <div class="chat-welcome-icon">🧠</div>
            <h3>Hey! I'm ScreenMind</h3>
            <p>Chat with me about anything — or ask about your screen activity.</p>
            <div class="chat-suggestions">
              <button class="chat-suggest" onclick="chatSuggestion('What did aachii say?')">💬 What did aachii say?</button>
              <button class="chat-suggest" onclick="chatSuggestion('Tell me a joke')">😄 Tell me a joke</button>
              <button class="chat-suggest" onclick="chatSuggestion('What tabs do I have open?')">🔍 Open tabs?</button>
            </div>
          </div>`;
      }
    }
    _chatWasLocked = false;
    return;
  }

  // Locked — show witty message + Model Hub link only
  _chatWasLocked = true;
  inputBar.classList.add('disabled');
  const chatInput = document.getElementById('chat-input');
  if (chatInput) { chatInput.placeholder = 'Download a model to start chatting...'; chatInput.disabled = true; }
  const sendBtn = document.getElementById('chat-send');
  if (sendBtn) sendBtn.disabled = true;

  let lockEl = document.getElementById('chat-locked');
  if (!lockEl) {
    messagesEl.innerHTML = '';
    lockEl = document.createElement('div');
    lockEl.className = 'chat-locked';
    lockEl.id = 'chat-locked';
    messagesEl.appendChild(lockEl);
  }

  if (_modelState.status === 'downloading') {
    lockEl.innerHTML = `
      <div class="chat-locked-icon">🧠⏳</div>
      <h3 class="chat-locked-title">Downloading my brain...</h3>
      <p class="chat-locked-desc">Hang tight! I'm getting my neural pathways wired up.</p>
      <div style="display:flex;gap:12px;justify-content:center;align-items:center">
        <a href="#" onclick="openModelHub();return false" style="color:var(--accent);font-size:0.85rem">Open Model Hub</a>
        <a href="#" onclick="hubCancelDownload();return false" style="color:#f87171;font-size:0.85rem">Cancel</a>
      </div>
      <div class="chat-locked-hint">✓ Chat unlocks automatically once ready!</div>`;
  } else if (_modelState.status === 'starting') {
    lockEl.innerHTML = `
      <div class="chat-locked-icon">🧠⚡</div>
      <h3 class="chat-locked-title">Booting up my brain...</h3>
      <p class="chat-locked-desc">Model downloaded! Starting the server — this takes 30-60 seconds.</p>
      <div class="spinner" style="margin:12px auto"></div>
      <div class="chat-locked-hint">✓ Almost there!</div>`;
  } else if (_modelState.status === 'error') {
    lockEl.innerHTML = `
      <div class="chat-locked-icon">🧠🔌</div>
      <h3 class="chat-locked-title">Something went wrong</h3>
      <p class="chat-locked-desc">${_modelState.message || "Server couldn't start. Check GPU/VRAM."}</p>
      <div style="display:flex;gap:10px;justify-content:center;margin-top:8px">
        <a href="#" onclick="openModelHub();return false" style="color:var(--accent);font-size:0.85rem">Open Model Hub</a>
        <button class="btn btn-primary btn-sm" onclick="retryModelStart()">🔄 Retry</button>
      </div>`;
  } else {
    // no_model
    lockEl.innerHTML = `
      <div class="chat-locked-icon">🧠💤</div>
      <h3 class="chat-locked-title">I need my brain to think!</h3>
      <p class="chat-locked-desc">Download a model to unlock chat, search analysis, and AI features.</p>
      <button class="btn btn-primary" onclick="openModelHub()" style="margin-top:12px">Open Model Hub</button>
      <div class="chat-locked-hint">✓ Chat unlocks automatically once downloaded!</div>`;
  }
}

function _escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function _formatAnswer(text) {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/\n/g, '<br>');
}

function _addMessage(role, content, sources) {
  const messagesEl = $('#chat-messages');
  // Remove welcome if present
  const welcome = document.getElementById('chat-welcome');
  if (welcome) welcome.remove();

  const isUser = role === 'user';
  const avatar = isUser ? '👤' : '🧠';
  const contentHtml = isUser ? _escapeHtml(content) : _formatAnswer(content);

  let sourcesHtml = '';
  if (sources && sources.length) {
    sourcesHtml = '<div class="chat-sources">' +
      '<div class="chat-sources-label">📎 Based on ' + sources.length + ' activities:</div>' +
      '<div class="chat-sources-list">' + sources.map(function(s) {
        return '<a class="chat-source-chip" onclick="openModal(\'' + s.screenshot_url + '\', ' + s.id + ')" title="' + _escapeHtml(s.summary || '') + '">' +
          '<span class="chat-source-time">' + new Date(s.timestamp).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}) + '</span> ' +
          _escapeHtml(s.app_name) + '</a>';
      }).join('') + '</div></div>';
  }

  const msgEl = document.createElement('div');
  msgEl.className = 'chat-msg ' + role;
  msgEl.innerHTML = `
    <div class="chat-msg-avatar">${avatar}</div>
    <div class="chat-msg-bubble">${contentHtml}${sourcesHtml}</div>`;
  messagesEl.appendChild(msgEl);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function _showTyping() {
  const messagesEl = $('#chat-messages');
  const typingEl = document.createElement('div');
  typingEl.className = 'chat-typing';
  typingEl.id = 'chat-typing';
  typingEl.innerHTML = `
    <div class="chat-msg-avatar" style="background:rgba(139,92,246,0.15);color:var(--accent)">🧠</div>
    <div style="display:flex;flex-direction:column;gap:4px">
      <div class="chat-typing-dots">
        <div class="chat-typing-dot"></div>
        <div class="chat-typing-dot"></div>
        <div class="chat-typing-dot"></div>
      </div>
      <div class="chat-progress-steps" id="chat-progress"></div>
    </div>`;
  messagesEl.appendChild(typingEl);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function _hideTyping() {
  const el = document.getElementById('chat-typing');
  if (el) el.remove();
}

window.chatSuggestion = function(q) {
  $('#chat-input').value = q;
  submitChat();
};

window.newChat = function() {
  chatHistory = [];
  const messagesEl = $('#chat-messages');
  messagesEl.innerHTML = `
    <div class="chat-welcome" id="chat-welcome">
      <div class="chat-welcome-icon">🧠</div>
      <h3>Hey! I'm ScreenMind</h3>
      <p>Chat with me about anything — or ask about your screen activity.</p>
      <div class="chat-suggestions">
        <button class="chat-suggest" onclick="chatSuggestion('What did aachii say?')">💬 What did aachii say?</button>
        <button class="chat-suggest" onclick="chatSuggestion('Tell me a joke')">😄 Tell me a joke</button>
        <button class="chat-suggest" onclick="chatSuggestion('What tabs do I have open?')">🔍 Open tabs?</button>
      </div>
    </div>`;
  $('#chat-input').value = '';
  $('#chat-input').focus();
};

window.submitChat = async function() {
  const input = $('#chat-input');
  const question = input.value.trim();
  if (!question || chatBusy) return;
  chatBusy = true;

  // Add user message
  _addMessage('user', question);
  chatHistory.push({ role: 'user', content: question });
  input.value = '';
  input.disabled = true;
  $('#chat-send').disabled = true;

  // Show typing indicator
  _showTyping();

  try {
    // Timeout: abort SSE stream if no response within 120s
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 120000);

    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, history: chatHistory.slice(0, -1), context_range: chatContextRange }),
      signal: controller.signal,
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let finalData = null;
    let lastEventTime = Date.now();

    while (true) {
      // Per-chunk timeout: if no data for 60s, the stream is stalled
      const readPromise = reader.read();
      const chunkTimeout = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('Stream stalled')), 60000)
      );

      let result;
      try {
        result = await Promise.race([readPromise, chunkTimeout]);
      } catch (stall) {
        reader.cancel();
        break;
      }

      const { done, value } = result;
      if (done) break;
      lastEventTime = Date.now();
      buffer += decoder.decode(value, { stream: true });

      const lines = buffer.split('\n');
      buffer = lines.pop();

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        try {
          const event = JSON.parse(line.slice(6));
          if (event.type === 'progress') {
            const progressEl = document.getElementById('chat-progress');
            if (progressEl) {
              const step = document.createElement('div');
              step.className = 'chat-progress-step';
              step.textContent = event.step;
              progressEl.appendChild(step);
              $('#chat-messages').scrollTop = $('#chat-messages').scrollHeight;
            }
          } else if (event.type === 'answer') {
            finalData = event;
          }
        } catch (parseErr) {}
      }
    }

    clearTimeout(timeoutId);
    _hideTyping();

    if (finalData && finalData.answer) {
      _addMessage('assistant', finalData.answer, finalData.sources);
      chatHistory.push({ role: 'assistant', content: finalData.answer });
    } else {
      _addMessage('assistant', 'Hmm, I didn\'t get a response. Try asking again!');
    }
  } catch (err) {
    _hideTyping();
    if (err.name === 'AbortError') {
      _addMessage('assistant', 'Request timed out — the model might be busy. Try again in a moment.');
    } else {
      _addMessage('assistant', 'Sorry, something went wrong: ' + err.message);
    }
  }

  input.disabled = false;
  $('#chat-send').disabled = false;
  chatBusy = false;
  input.focus();
};


// ══════════════════════════════════════════════════════════
