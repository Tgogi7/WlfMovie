# WlfMovie — Fix DNS del player (Opcion 1)

**Fecha:** 2026-11-04
**Objetivo:** Eliminar DoH (Cloudflare) del camino de reproduccion de ExoPlayer para que YetiPelis (y todos los providers) carguen tan rapido como en la test app.

---

## Que cambio (3 lineas + 1 import)

Solo se tocaron **2 archivos**, los 2 fragments del player. Nada mas.

### Archivo 1: `PlayerTvFragment.kt`

| Linea | Antes | Ahora |
|-------|-------|-------|
| 80 (import, nueva) | *(no existia)* | `import okhttp3.Dns` |
| 1914 | `.dns(DnsResolver.doh)` | `.dns(Dns.SYSTEM)` |
| 2139 | `val okHttpClient = NetworkClient.default` | `val okHttpClient = NetworkClient.systemDns` |

### Archivo 2: `PlayerMobileFragment.kt`

| Linea | Antes | Ahora |
|-------|-------|-------|
| 2413 | `val okHttpClient = NetworkClient.default.newBuilder()` | `val okHttpClient = NetworkClient.systemDns.newBuilder()` |

---

## Que NO cambio (importante)

- **Scraper/providers**: siguen usando `NetworkClient.default` (con DoH Cloudflare). Esto es correcto — el DoH les sirve para saltar bloqueos DNS de ISPs colombianos a sitios como yetipelis.com, gnula.lat, etc.
- **NetworkClient.kt**: no se modifico. `NetworkClient.systemDns` ya existia (linea 60: `val systemDns = buildClient(Dns.SYSTEM)`).
- **DnsResolver.kt**: no se modifico.
- **YetiExtractor.kt, YetiPelisProvider.kt**: no se tocaron.
- **Settings, TmdbProvider, Extractor**: no se tocaron.
- **Timeouts** (30s) y **browser headers** (Sec-Fetch-*): se mantienen. Si despues de este fix sigue lento, esos son los siguientes a cambiar (Opcion 2).

---

## Como revertir (si algo se rompe)

Si despues de aplicar este patch algun provider que antes funcionaba deja de funcionar, revertir es trivial — son 3 palabras:

### PlayerTvFragment.kt
1. **Linea 80**: borrar `import okhttp3.Dns`
2. **Linea 1914**: cambiar `.dns(Dns.SYSTEM)` de vuelta a `.dns(DnsResolver.doh)`
3. **Linea 2139**: cambiar `NetworkClient.systemDns` de vuelta a `NetworkClient.default`

### PlayerMobileFragment.kt
4. **Linea 2413**: cambiar `NetworkClient.systemDns.newBuilder()` de vuelta a `NetworkClient.default.newBuilder()`

Eso es todo. 4 ediciones y vuelves al estado anterior.

---

## Por que esto deberia arreglar la lentitud de YetiPelis

**Antes:** cada lookup DNS del player (master.m3u8, variant.m3u8, host de segmentos) viajaba a `cloudflare-dns.com` por HTTPS. Desde Colombia: 400-1100ms por host nuevo, o hasta 30s de timeout si Cloudflare estaba lento.

**Ahora:** cada lookup DNS del player usa el resolver del sistema operativo (Dns.SYSTEM). Latencia: 1-50ms por host. Mismo comportamiento que la test app.

**DoH se mantiene para scraping** (donde sirve) y **se elimina del player** (donde solo aporta latitud).

---

## Como probar

1. Reemplaza los 2 archivos en tu proyecto:
   - `app/src/main/java/com/mew/wlfmovie/fragments/player/PlayerTvFragment.kt`
   - `app/src/main/java/com/mew/wlfmovie/fragments/player/PlayerMobileFragment.kt`
2. Compila e instala.
3. Prueba "Off Campus" o cualquier serie que en la test app carga en 3s.
4. Si carga rapido → fix confirmado.
5. Si sigue lento → pasamos a Opcion 2 (timeouts 8s + quitar browser headers).
6. Si algun provider deja de funcionar → revertir (instrucciones arriba) y reportar cual.
