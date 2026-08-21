# ADR-002 — Tokens administrativos en almacenamiento del navegador

- Estado: aceptado para MVP
- Fecha: 2026-08-20

## Contexto

El panel administrativo necesita login propio, JWT de acceso corto y refresh token rotatorio/revocable. Para reducir complejidad inicial del frontend se decidió no usar cookies HttpOnly en el MVP.

## Decisión

- El frontend almacenará access y refresh token en almacenamiento del navegador.
- El access token será un JWT de vida corta.
- El refresh token será opaco, aleatorio y rotatorio.
- PostgreSQL almacenará únicamente el hash SHA-256 del refresh token, nunca el token original.
- Cada rotación invalidará el refresh token anterior.
- La reutilización de un token ya rotado revocará toda su familia de sesiones.
- Logout revocará la sesión/familia correspondiente.
- Los tokens nunca se incluirán en URL, logs, métricas ni mensajes de error.

## Consecuencias

### Ventajas

- Integración inicial más simple con el panel.
- No requiere CSRF basado en cookies para `refresh` y `logout`.
- Contrato REST directo mediante cuerpos JSON y `Authorization: Bearer`.

### Riesgos

Un XSS exitoso puede leer y exfiltrar ambos tokens. Esta estrategia es menos resistente que mantener el refresh token en una cookie `HttpOnly`.

## Mitigaciones obligatorias

- CSP estricta sin `unsafe-inline` ni scripts de terceros no aprobados.
- No renderizar HTML arbitrario; Markdown siempre sanitizado.
- Access token corto y refresh token con expiración limitada.
- Rotación en cada refresh y detección de reutilización.
- Rate limiting en login y refresh.
- Bloqueo progresivo por intentos de login.
- Evitar persistir tokens en Redux DevTools, trazas, errores o telemetría.
- Pruebas negativas de XSS y exposición de secretos antes del piloto.

## Revisión

Reevaluar cookies HttpOnly antes de producción pública o si el análisis de amenazas incrementa la probabilidad/impacto de XSS.
