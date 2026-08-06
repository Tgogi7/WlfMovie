/* WlfMovie Remote Player — Lógica del reproductor web */

const video = document.getElementById('video');
const titleEl = document.getElementById('title');
const subtitleEl = document.getElementById('subtitle');
const currentTimeEl = document.getElementById('current-time');
const durationEl = document.getElementById('duration');
const progressBar = document.getElementById('progress-bar');
const progressPlayed = document.getElementById('progress-played');
const progressBuffered = document.getElementById('progress-buffered');
const progressThumb = document.getElementById('progress-thumb');
const playPauseBtn = document.getElementById('play-pause');
const fullscreenBtn = document.getElementById('fullscreen');
const bufferingEl = document.getElementById('buffering');
const errorEl = document.getElementById('error');
const errorMessageEl = document.getElementById('error-message');
const retryBtn = document.getElementById('retry-btn');
const app = document.getElementById('app');

// State
let hls = null;
let hideUiTimer = null;
let ws = null;
let positionReportTimer = null;
let lastReportedPosition = -1;

// ===== Init =====

async function init() {
    console.log('[WlfMovie Remote] init');

    try {
        const resp = await fetch('/info');
        const info = await resp.json();
        console.log('[WlfMovie Remote] info:', info);

        if (info.error) {
            titleEl.textContent = 'Esperando datos del celular...';
            subtitleEl.textContent = '';
            subtitleEl.style.display = 'none';
            setTimeout(init, 1000);
            return;
        }

        titleEl.textContent = info.title || 'WlfMovie';
        const subtitle = info.subtitle || '';
        subtitleEl.textContent = subtitle;
        subtitleEl.style.display = subtitle ? 'block' : 'none';

        const position = info.position || 0;
        const duration = info.duration || 0;
        const isHls = info.isHls || false;

        // Cargar el video
        loadVideo('/stream', position, isHls);

        if (duration > 0) {
            durationEl.textContent = formatTime(duration);
        }

        // Conectar WebSocket para control bidireccional
        if (info.wsUrl) {
            connectWebSocket(info.wsUrl);
        } else {
            console.warn('[WlfMovie Remote] no hay wsUrl en /info');
        }
    } catch (err) {
        console.error('[WlfMovie Remote] error fetching /info:', err);
        titleEl.textContent = 'No se pudo conectar con el celular';
        subtitleEl.textContent = 'Verificá que la app siga abierta';
        setTimeout(init, 2000);
        return;
    }

    setupControls();
    setupKeyboard();
    showControls();
}

// ===== WebSocket =====

function connectWebSocket(url) {
    console.log('[WlfMovie Remote] connectWebSocket:', url);
    if (ws) {
        try { ws.close(); } catch (_) {}
        ws = null;
    }

    try {
        ws = new WebSocket(url);
    } catch (err) {
        console.error('[WlfMovie Remote] WebSocket error:', err);
        setTimeout(() => connectWebSocket(url), 2000);
        return;
    }

    ws.onopen = () => {
        console.log('[WlfMovie Remote] WebSocket conectado');
        // Avisar al celular que estamos listos
        wsSend({ type: 'ready' });
        // Arrancar el reporte de posición cada 10s
        startPositionReporting();
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            console.log('[WlfMovie Remote] ws msg:', msg);
            handleServerMessage(msg);
        } catch (err) {
            console.error('[WlfMovie Remote] ws parse error:', err);
        }
    };

    ws.onclose = () => {
        console.log('[WlfMovie Remote] WebSocket cerrado');
        stopPositionReporting();
        // Reconectar en 2s (el service del celular sigue vivo)
        setTimeout(() => {
            if (ws === null || ws.readyState === WebSocket.CLOSED) {
                connectWebSocket(url);
            }
        }, 2000);
    };

    ws.onerror = (err) => {
        console.error('[WlfMovie Remote] WebSocket error:', err);
    };
}

function wsSend(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

function handleServerMessage(msg) {
    switch (msg.type) {
        case 'video_info':
            // El celular nos mandó la info del video (cuando reconectamos)
            titleEl.textContent = msg.title || 'WlfMovie';
            const subtitle = msg.subtitle || '';
            subtitleEl.textContent = subtitle;
            subtitleEl.style.display = subtitle ? 'block' : 'none';
            // Recargar el video si cambió
            if (video.src === '' || !video.src.includes('/stream')) {
                loadVideo(msg.url || '/stream', msg.position || 0, msg.isHls || false);
            }
            break;
        case 'play':
            video.play().catch(err => console.warn('[WlfMovie Remote] play blocked:', err));
            break;
        case 'pause':
            video.pause();
            break;
        case 'seek':
            if (typeof msg.position === 'number') {
                video.currentTime = msg.position / 1000;
            }
            break;
        case 'stop':
            // El celular nos pide que cerremos
            video.pause();
            showError('El celular detuvo la reproducción');
            break;
    }
}

// ===== Reporte de posición cada 10s =====

function startPositionReporting() {
    stopPositionReporting();
    positionReportTimer = setInterval(() => {
        if (!video.duration || !isFinite(video.duration)) return;
        const position = Math.floor(video.currentTime * 1000);
        const duration = Math.floor(video.duration * 1000);
        const isPlaying = !video.paused && !video.ended;

        // Solo reportar si cambió (para no spamear)
        if (position !== lastReportedPosition) {
            lastReportedPosition = position;
            wsSend({
                type: 'position',
                position: position,
                duration: duration,
                isPlaying: isPlaying
            });
            console.log(`[WlfMovie Remote] reportando pos=${position}ms, playing=${isPlaying}`);
        }
    }, 10000); // 10 segundos
}

function stopPositionReporting() {
    if (positionReportTimer) {
        clearInterval(positionReportTimer);
        positionReportTimer = null;
    }
}

// Reportar inmediatamente en eventos importantes
function reportPositionNow() {
    if (!video.duration || !isFinite(video.duration)) return;
    const position = Math.floor(video.currentTime * 1000);
    const duration = Math.floor(video.duration * 1000);
    const isPlaying = !video.paused && !video.ended;
    wsSend({
        type: 'position',
        position: position,
        duration: duration,
        isPlaying: isPlaying
    });
    lastReportedPosition = position;
}

// ===== Video loading =====

function loadVideo(url, startPosition, isHls) {
    if (hls) {
        hls.destroy();
        hls = null;
    }

    bufferingEl.style.display = 'block';
    hideError();

    const useHls = isHls || url.includes('.m3u8');

    if (useHls && Hls.isSupported()) {
        console.log('[WlfMovie Remote] usando hls.js');
        hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
        });
        hls.loadSource(url);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            console.log('[WlfMovie Remote] manifest parsed, ready to play');
            if (startPosition > 0) {
                video.currentTime = startPosition / 1000;
            }
            video.play().catch(err => console.warn('[WlfMovie Remote] autoplay blocked:', err));
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.error('[WlfMovie Remote] HLS fatal error:', data);
                showError('Error de carga del video: ' + (data.details || data.type));
            }
        });
    } else if (useHls && video.canPlayType('application/vnd.apple.mpegurl')) {
        console.log('[WlfMovie Remote] usando HLS nativo (Safari)');
        video.src = url;
        video.addEventListener('loadedmetadata', () => {
            if (startPosition > 0) {
                video.currentTime = startPosition / 1000;
            }
            video.play().catch(err => console.warn('[WlfMovie Remote] autoplay blocked:', err));
        }, { once: true });
    } else {
        console.log('[WlfMovie Remote] usando video nativo');
        video.src = url;
        video.addEventListener('loadedmetadata', () => {
            if (startPosition > 0) {
                video.currentTime = startPosition / 1000;
            }
            video.play().catch(err => console.warn('[WlfMovie Remote] autoplay blocked:', err));
        }, { once: true });
    }

    // Listeners
    video.addEventListener('waiting', () => {
        bufferingEl.style.display = 'block';
    });
    video.addEventListener('playing', () => {
        bufferingEl.style.display = 'none';
    });
    video.addEventListener('canplay', () => {
        bufferingEl.style.display = 'none';
    });
    video.addEventListener('timeupdate', updateTime);
    video.addEventListener('durationchange', () => {
        if (video.duration && isFinite(video.duration)) {
            durationEl.textContent = formatTime(video.duration * 1000);
        }
    });
    video.addEventListener('progress', updateBuffered);
    video.addEventListener('play', () => {
        playPauseBtn.textContent = '⏸';
        reportPositionNow();
    });
    video.addEventListener('pause', () => {
        playPauseBtn.textContent = '▶';
        reportPositionNow();
    });
    video.addEventListener('seeked', () => {
        reportPositionNow();
    });
    video.addEventListener('ended', () => {
        console.log('[WlfMovie Remote] video ended');
        wsSend({ type: 'ended' });
        reportPositionNow();
    });
    video.addEventListener('error', (e) => {
        console.error('[WlfMovie Remote] video error:', e);
        showError('Error de reproducción');
    });
}

// ===== Controles =====

function setupControls() {
    playPauseBtn.addEventListener('click', togglePlay);
    fullscreenBtn.addEventListener('click', toggleFullscreen);

    progressBar.addEventListener('click', (e) => {
        if (!video.duration || !isFinite(video.duration)) return;
        const rect = progressBar.getBoundingClientRect();
        const pct = (e.clientX - rect.left) / rect.width;
        video.currentTime = pct * video.duration;
    });

    let isDragging = false;
    progressBar.addEventListener('mousedown', (e) => {
        isDragging = true;
        seekTo(e);
    });
    document.addEventListener('mousemove', (e) => {
        if (isDragging) seekTo(e);
    });
    document.addEventListener('mouseup', () => {
        if (isDragging) {
            isDragging = false;
            reportPositionNow();
        }
    });

    document.addEventListener('mousemove', showControls);
    document.addEventListener('click', showControls);
    video.addEventListener('play', showControls);
}

function seekTo(e) {
    if (!video.duration || !isFinite(video.duration)) return;
    const rect = progressBar.getBoundingClientRect();
    const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    video.currentTime = pct * video.duration;
    progressPlayed.style.width = (pct * 100) + '%';
    progressThumb.style.left = (pct * 100) + '%';
}

function togglePlay() {
    if (video.paused) {
        video.play();
    } else {
        video.pause();
    }
}

function toggleFullscreen() {
    if (!document.fullscreenElement) {
        app.requestFullscreen().catch(err => console.warn('[WlfMovie Remote] fullscreen error:', err));
    } else {
        document.exitFullscreen();
    }
}

// ===== Atajos teclado =====

function setupKeyboard() {
    document.addEventListener('keydown', (e) => {
        switch (e.key) {
            case ' ':
                e.preventDefault();
                togglePlay();
                showControls();
                break;
            case 'f':
            case 'F':
                toggleFullscreen();
                showControls();
                break;
            case 'ArrowLeft':
                e.preventDefault();
                video.currentTime = Math.max(0, video.currentTime - 10);
                showControls();
                break;
            case 'ArrowRight':
                e.preventDefault();
                video.currentTime = Math.min(video.duration || 0, video.currentTime + 10);
                showControls();
                break;
        }
    });
}

// ===== UI helpers =====

function updateTime() {
    if (!isFinite(video.currentTime)) return;
    const currentMs = video.currentTime * 1000;
    currentTimeEl.textContent = formatTime(currentMs);

    if (video.duration && isFinite(video.duration)) {
        const pct = (video.currentTime / video.duration) * 100;
        progressPlayed.style.width = pct + '%';
        progressThumb.style.left = pct + '%';
    }
}

function updateBuffered() {
    if (!video.buffered || !video.duration || !isFinite(video.duration)) return;
    const buffered = video.buffered;
    if (buffered.length > 0) {
        const end = buffered.end(buffered.length - 1);
        const pct = (end / video.duration) * 100;
        progressBuffered.style.width = pct + '%';
    }
}

function formatTime(ms) {
    if (!ms || ms < 0 || !isFinite(ms)) return '00:00:00';
    const totalSec = Math.floor(ms / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    return [h, m, s].map(n => String(n).padStart(2, '0')).join(':');
}

function showControls() {
    app.classList.remove('hide-ui');
    clearTimeout(hideUiTimer);
    hideUiTimer = setTimeout(() => {
        if (!video.paused) {
            app.classList.add('hide-ui');
        }
    }, 3000);
}

function showError(message) {
    errorMessageEl.textContent = message;
    errorEl.classList.remove('hidden');
    bufferingEl.style.display = 'none';
}

function hideError() {
    errorEl.classList.add('hidden');
}

retryBtn.addEventListener('click', () => {
    hideError();
    loadVideo('/stream', video.currentTime ? video.currentTime * 1000 : 0, true);
});

// Start
init();
