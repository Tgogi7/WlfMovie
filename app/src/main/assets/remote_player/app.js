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
let pingTimer = null;
let lastReportedPosition = -1;
let videoLoaded = false;  // evita recargar el video al reconectar el WS
let currentVideoUrl = null;
let stopReceived = false;  // true cuando el mobile envió 'stop' intencionalmente
let initialLoadTimer = null;  // V6.4.2: espera breve al video_info del WS antes de cargar por /info
let hlsMediaErrorRecoveries = 0;  // V6.4.2: recuperaciones de mediaError ya intentadas
let loadedVideoKey = null;  // V6.4.3: 'title|url' del video ya cargado (evita recargas por reconexión del WS)

// V6.4.3: cache-buster — el navegador del PC puede tener respuestas viejas de
// builds anteriores guardadas en su caché (los builds pre-6.4.2 no mandaban
// headers anti-caché en /stream ni en los segmentos). Un URL ÚNICO por carga
// garanta que el manifest SIEMPRE llegue al celular, y como dentro del m3u8
// los segmentos ahora son relativos y versionados (?v=hash), tampoco pueden
// reponerse segmentos viejos desde la caché. Esto mata el "fantasma" de
// reproducciones que el celular nunca registró como conexión.
function cacheBust(url) {
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'cb=' + Date.now();
}

// ===== Init =====

async function init() {
    console.log('[WlfMovie Remote] init');

    try {
        const resp = await fetch(cacheBust('/info'));
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

        // V6.4.2: si hay WebSocket, el celular manda video_info apenas conecta
        // (~100-300ms). Esperarlo evita cargar el video DOS veces (la segunda
        // destruye el primer hls.js en plena carga — carrera que podia dejar
        // el SourceBuffer en estado "ended" y matar la reproduccion con
        // bufferAppendError). Si el WS no conecta en 1.5s, cargamos igual:
        // el video funciona, solo sin control remoto.
        if (info.wsUrl) {
            if (initialLoadTimer) clearTimeout(initialLoadTimer);
            initialLoadTimer = setTimeout(() => {
                initialLoadTimer = null;
                if (!videoLoaded && !currentVideoUrl) {
                    console.log('[WlfMovie Remote] video_info no llegó por WS — cargando desde /info');
                    loadVideo('/stream', position, isHls);
                }
            }, 1500);
        } else {
            // Sin wsUrl (no debería pasar): cargar directo como siempre.
            loadVideo('/stream', position, isHls);
        }

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
        // Ping cada 25s para mantener el WebSocket vivo (algunos routers cierran conexiones idle)
        startPing();
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
        console.log('[WlfMovie Remote] WebSocket cerrado (stopReceived=' + stopReceived + ')');
        stopPositionReporting();
        stopPing();
        // Si el mobile envió 'stop' intencionalmente, NO mostrar "conexión perdida"
        // — ya mostramos "Detenido desde el celular".
        // Si el video estaba cargado y NO fue stop intencional, es conexión perdida.
        if (!stopReceived && videoLoaded) {
            showConnectionLost();
        }
        // Resetear el flag para la próxima reconexión
        stopReceived = false;
        // Reconectar en 2s para escuchar si el móvil envía un nuevo video_info
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
        case 'video_info': {
            // El celular nos mandó la info del video.
            console.log('[WlfMovie Remote] video_info recibido:', msg);
            if (initialLoadTimer) { clearTimeout(initialLoadTimer); initialLoadTimer = null; }
            titleEl.textContent = msg.title || 'WlfMovie';
            const subtitle = msg.subtitle || '';
            subtitleEl.textContent = subtitle;
            subtitleEl.style.display = subtitle ? 'block' : 'none';

            // V6.4.3: GUARDA anti-tormenta-de-recargas.
            // El WS se reconecta solo (cada 2s) tras cualquier corte — hotspot
            // con power-save, pantalla del celu apagada, etc — y en CADA
            // reconexión el celular re-envía video_info. Antes eso destruía y
            // recargaba TODO el video en plena reproducción (se perdía el
            // buffer, se re-pedía /stream y la reproducción quedaba a merced
            // de la reconstrucción del índice). Ahora: si es el MISMO video,
            // ya está cargado, sano y sin salto grande de posición, NO se
            // recarga — la reconexión pasa totalmente invisible.
            const key = (msg.title || '') + '|' + (msg.url || '/stream');
            const newPositionSec = (msg.position || 0) / 1000;
            const driftSec = Math.abs(video.currentTime - newPositionSec);
            const errorVisible = !errorEl.classList.contains('hidden');
            const sameVideo = loadedVideoKey === key && videoLoaded && !errorVisible;
            if (sameVideo && driftSec < 60) {
                console.log('[WlfMovie Remote] mismo video ya cargado (drift=' +
                    driftSec.toFixed(1) + 's) — NO recargando (reconexión WS invisible)');
                lastReportedPosition = -1;
                break;
            }
            if (sameVideo && driftSec >= 60) {
                console.log('[WlfMovie Remote] mismo video pero posición saltó ' +
                    driftSec.toFixed(1) + 's — buscando sin recargar');
                video.currentTime = newPositionSec;
                lastReportedPosition = -1;
                break;
            }

            // Video NUEVO (o había error / nunca se cargó) → resetear y cargar
            loadedVideoKey = key;
            // Resetear posición reportada para que el sync empiece limpio
            lastReportedPosition = -1;

            // Ocultar cualquier error previo (estaba en modo "esperando video nuevo")
            hideError();
            bufferingEl.style.display = 'block';

            // Recargar el video con la nueva posición
            loadVideo(msg.url || '/stream', msg.position || 0, msg.isHls || false);
            break;
        }
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
        case 'stop': {
            // El celular detuvo el cast intencionalmente.
            // Mostrar mensaje y esperar a que llegue un video_info nuevo
            // (NO reintentar automáticamente).
            console.log('[WlfMovie Remote] stop desde mobile');
            stopReceived = true;  // flag para que ws.onclose no muestre "conexión perdida"
            video.pause();
            videoLoaded = false;
            loadedVideoKey = null;  // V6.4.3: el próximo video_info debe recargar
            // Destruir HLS para liberar recursos
            if (hls) {
                hls.destroy();
                hls = null;
            }
            video.removeAttribute('src');
            video.load();
            showStopMessage();
            break;
        }
    }
}

/**
 * Muestra mensaje de "stop desde mobile" — sin botón de reintentar.
 * El usuario tiene que abrir la URL de nuevo o esperar a que el móvil
 * envíe un nuevo video_info.
 */
function showStopMessage() {
    errorMessageEl.textContent = 'Detenido desde el celular';
    errorEl.querySelector('p').textContent = '';
    retryBtn.style.display = 'none';
    errorEl.classList.remove('hidden');
    bufferingEl.style.display = 'none';
}

/**
 * Muestra error de conexión perdida — con botón reintentar.
 * Se usa cuando el WebSocket se cae por timeout o la IP cambió.
 */
function showConnectionLost() {
    errorMessageEl.textContent = 'Conexión perdida';
    errorEl.querySelector('p').textContent = 'Verificá que el celular siga abierto';
    retryBtn.style.display = '';
    retryBtn.textContent = 'Reintentar';
    errorEl.classList.remove('hidden');
    bufferingEl.style.display = 'none';
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

// Ping cada 25s para mantener el WebSocket vivo (evita que routers/servers cierren la conexión idle)
function startPing() {
    stopPing();
    pingTimer = setInterval(() => {
        wsSend({ type: 'ping' });
    }, 25000);
}

function stopPing() {
    if (pingTimer) {
        clearInterval(pingTimer);
        pingTimer = null;
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
    hlsMediaErrorRecoveries = 0;

    bufferingEl.style.display = 'block';
    hideError();

    currentVideoUrl = url;
    // V6.4.3: URL única por carga → el manifest NUNCA puede salir de la
    // caché del navegador (los builds viejos no mandaban anti-caché y el PC
    // podía estar reponiendo un m3u8 de días atrás sin que el celular lo supiera).
    const loadUrl = cacheBust(url);
    const useHls = isHls || url.includes('.m3u8');

    if (useHls && Hls.isSupported()) {
        console.log('[WlfMovie Remote] usando hls.js');
        hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
        });
        hls.loadSource(loadUrl);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            console.log('[WlfMovie Remote] manifest parsed, ready to play');
            videoLoaded = true;
            if (startPosition > 0) {
                video.currentTime = startPosition / 1000;
            }
            video.play().catch(err => console.warn('[WlfMovie Remote] autoplay blocked:', err));
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.error('[WlfMovie Remote] HLS fatal error:', data);
                // V6.4.2: los mediaError (bufferAppendError, decoder, MSE)
                // suelen ser recuperables con recoverMediaError() — intentarlo
                // hasta 2 veces antes de rendirse. El caso tipico: un append
                // que llega cuando el MediaSource ya estaba "ended".
                if (data.type === Hls.ErrorTypes.MEDIA_ERROR && hlsMediaErrorRecoveries < 2) {
                    hlsMediaErrorRecoveries++;
                    console.warn('[WlfMovie Remote] intentando recuperar el decoder (' + hlsMediaErrorRecoveries + '/2)...');
                    try {
                        hls.recoverMediaError();
                        return;
                    } catch (e) {
                        console.warn('[WlfMovie Remote] recoverMediaError falló:', e);
                    }
                }
                showError('Error de carga del video: ' + (data.details || data.type));
            }
        });
    } else if (useHls && video.canPlayType('application/vnd.apple.mpegurl')) {
        console.log('[WlfMovie Remote] usando HLS nativo (Safari)');
        video.src = loadUrl;
        video.addEventListener('loadedmetadata', () => {
            videoLoaded = true;
            if (startPosition > 0) {
                video.currentTime = startPosition / 1000;
            }
            video.play().catch(err => console.warn('[WlfMovie Remote] autoplay blocked:', err));
        }, { once: true });
    } else {
        console.log('[WlfMovie Remote] usando video nativo');
        video.src = loadUrl;
        video.addEventListener('loadedmetadata', () => {
            videoLoaded = true;
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
    // Reconectar WebSocket — cuando se reconecte, el móvil enviará video_info
    // con la posición correcta y se recargará el video desde ahí.
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
        console.log('[WlfMovie Remote] retry: WebSocket ya está conectado, esperando video_info');
    } else {
        console.log('[WlfMovie Remote] retry: reconectando WebSocket');
        // Forzar reconexión — buscar la URL del WS desde /info
        fetch('/info').then(r => r.json()).then(info => {
            if (info.wsUrl) {
                connectWebSocket(info.wsUrl);
            }
        }).catch(err => console.error('[WlfMovie Remote] retry error:', err));
    }
});

// Start
console.log('[WlfMovie Remote] app.js V6.4.3');
init();
