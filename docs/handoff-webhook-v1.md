# Contrato webhook de transferencia humana v1

Estado: candidato para aprobación del primer proyecto.

## Objetivo

Notificar a un sistema externo cuando una conversación solicita atención humana. ChatbotQ registra la solicitud antes de invocar el webhook; una falla externa nunca elimina la conversación.

## Transporte y endpoint

- Método: `POST`.
- Esquema: exclusivamente `https` en producción.
- `Content-Type: application/json; charset=utf-8`.
- Timeout total por intento: 5 segundos.
- El endpoint se configura por proyecto y se valida al guardarlo y antes de cada conexión.
- No se siguen redirecciones HTTP.

## Headers

```http
Content-Type: application/json; charset=utf-8
User-Agent: ChatbotQ-Webhook/1.0
X-ChatbotQ-Event: chatbot.handoff.requested.v1
X-ChatbotQ-Delivery: 5ed49c96-755c-43af-b9d7-0bbb91102c62
X-ChatbotQ-Timestamp: 1787288400
X-ChatbotQ-Signature: v1=hexadecimal-hmac-sha256
X-Correlation-ID: 7be068a4-9ba8-4f33-bfc8-178103b01352
Idempotency-Key: 5ed49c96-755c-43af-b9d7-0bbb91102c62
```

`X-ChatbotQ-Delivery` e `Idempotency-Key` contienen el mismo UUID estable durante todos los reintentos de una solicitud.

## Firma HMAC

Cada proyecto tiene un secreto independiente y rotatorio. El secreto nunca aparece en respuestas, navegador ni logs.

La firma se calcula sobre los bytes UTF-8 exactos enviados:

```text
signed_payload = X-ChatbotQ-Timestamp + "." + raw_request_body
signature = lowercase_hex(HMAC-SHA256(secret, signed_payload))
header = "v1=" + signature
```

El receptor debe:

1. rechazar timestamps con una diferencia mayor de 5 minutos;
2. recalcular la firma sobre el cuerpo crudo, antes de parsear JSON;
3. comparar firmas en tiempo constante;
4. deduplicar por `Idempotency-Key` durante al menos 24 horas.

## Solicitud

```json
{
  "schemaVersion": "1.0",
  "event": "chatbot.handoff.requested.v1",
  "deliveryId": "5ed49c96-755c-43af-b9d7-0bbb91102c62",
  "occurredAt": "2026-08-20T21:00:00Z",
  "project": {
    "id": "17a41029-382b-451c-aab6-f77059fc4b14",
    "name": "Proyecto ejemplo"
  },
  "conversation": {
    "id": "ec399d39-28a0-43c7-8242-79385ebd0adc",
    "startedAt": "2026-08-20T20:55:00Z",
    "lastActivityAt": "2026-08-20T21:00:00Z",
    "userQuestionCount": 3
  },
  "messages": [
    {
      "sequence": 1,
      "role": "USER",
      "content": "Necesito hablar con una persona",
      "createdAt": "2026-08-20T20:59:58Z"
    }
  ]
}
```

Reglas:

- Fechas en UTC, formato RFC 3339/ISO-8601.
- `messages` contiene como máximo los 6 mensajes recientes permitidos por la política conversacional.
- Cada `content` conserva texto plano y se limita a 16,000 caracteres.
- No se incluyen prompts del sistema, embeddings, candidatos RAG, secretos, direcciones internas ni stack traces.
- El orden de `messages` es ascendente por `sequence`.

## Respuesta exitosa

Cualquier `2xx` debe incluir JSON válido:

```json
{
  "accepted": true,
  "message": "Te pondremos en contacto con una persona.",
  "redirectUrl": "https://atencion.ejemplo.com/solicitudes/123",
  "externalReference": "SOL-123"
}
```

- `accepted` es obligatorio.
- `message`, `redirectUrl` y `externalReference` son opcionales.
- `message` se limita a 500 caracteres y se trata como texto plano.
- `redirectUrl`, cuando exista, debe usar `https` y superar la misma política SSRF/allowlist que el endpoint.
- Una respuesta repetida para la misma clave de idempotencia debe ser semánticamente equivalente.

Una respuesta `2xx` con `accepted: false` se registra como `REJECTED` y no se reintenta.

## Errores y reintentos

Se realizan como máximo 4 intentos totales: el inicial y 3 reintentos.

Se reintenta únicamente ante:

- error de conexión;
- timeout;
- HTTP `408` o `429`;
- HTTP `5xx`.

No se reintentan otros `4xx`, respuestas `2xx` con JSON inválido ni respuestas que excedan los límites.

Backoff recomendado: 1 s, 2 s y 4 s, con jitter de hasta 25%. Si existe `Retry-After` válido en un `429`, se respeta hasta un máximo configurable de 30 segundos.

Cada intento actualiza `attempt_count`, último código HTTP y error sanitizado. Al agotar intentos, la solicitud queda `FAILED`; la conversación permanece disponible.

## Política SSRF

Antes de cada conexión y después de resolver DNS:

- permitir únicamente hosts incluidos en la allowlist del proyecto;
- bloquear loopback, link-local, multicast, rangos privados, carrier-grade NAT y direcciones reservadas de IPv4/IPv6;
- bloquear puertos distintos de `443` salvo excepción administrativa explícita;
- bloquear URLs con credenciales embebidas;
- no seguir redirecciones;
- conectar únicamente a una dirección IP previamente validada para reducir DNS rebinding.

Las excepciones de desarrollo deben estar desactivadas en producción y nunca reutilizar secretos productivos.

## Estados internos

```text
PENDING  → ACCEPTED
PENDING  → REJECTED
PENDING  → FAILED
```

La solicitud se persiste como `PENDING` antes de la primera llamada. `ACCEPTED` y `REJECTED` son terminales. `FAILED` puede reintentarse manualmente conservando la misma clave de idempotencia o mediante una nueva entrega explícitamente trazada.

## Observabilidad y privacidad

Registrar:

- project ID, handoff ID, delivery ID y correlation ID;
- número de intento, latencia, resultado y código HTTP;
- nunca el secreto, firma completa, cuerpo íntegro ni contenido de mensajes.

La retención del evento y sus mensajes sigue la política de la conversación y del proyecto.

## Criterios de aceptación del receptor

El primer proyecto aprueba v1 cuando demuestra en un ambiente de prueba que:

1. valida HMAC y ventana temporal;
2. deduplica entregas repetidas;
3. devuelve el mismo resultado para una misma clave;
4. procesa el ejemplo exitoso;
5. responde de forma controlada a payload inválido;
6. acepta el timeout de 5 segundos y la política de reintentos;
7. confirma su host y puerto para la allowlist.
