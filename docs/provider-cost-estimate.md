# Estimación de costes de proveedores

Fecha de cálculo: 2026-08-20.

Esta estimación cubre únicamente OpenAI embeddings y generación con xAI/Grok. No incluye PostgreSQL, WebLogic, red, observabilidad, backups, impuestos ni variaciones cambiarias.

## Tarifas usadas

- OpenAI `text-embedding-3-small`: **USD 0.02 por 1 millón de tokens de entrada**.[2]
- xAI `grok-4.3`, prompts menores de 200k tokens: **USD 1.25 por 1 millón de tokens de entrada** y **USD 2.50 por 1 millón de tokens de salida**.[1]
- No se descuenta prompt caching ni Batch API; la estimación favorece prudencia sobre optimismo.

## Supuestos comunes

- 1,000 conversaciones.
- 3 preguntas por conversación: 3,000 consultas y embeddings de pregunta.
- El embedding del conocimiento se paga al crear o modificar una entrada, no en cada conversación.
- El gate RAG evita llamar a Grok cuando no existe evidencia suficiente.
- El límite funcional sigue siendo 6 mensajes o 4,000 tokens de contexto, lo que ocurra primero, y 600 tokens máximos de salida.

## Escenarios por 1,000 conversaciones

| Escenario | Supuestos principales | Embeddings | Entrada Grok | Salida Grok | Total USD |
|---|---|---:|---:|---:|---:|
| Esperado | 60% de consultas llegan a Grok; 1,800 tokens de entrada y 300 de salida por llamada | 0.006 | 4.050 | 1.350 | **5.406** |
| Conservador | Las 3,000 consultas llegan a Grok; 1,800 tokens de entrada y 300 de salida | 0.006 | 6.750 | 2.250 | **9.006** |
| Tope de política | Las 3,000 llegan a Grok; 4,500 tokens de entrada y 600 de salida | 0.030 | 16.875 | 4.500 | **21.405** |

Fórmula por componente:

```text
coste = tokens / 1,000,000 × tarifa
```

## Piloto previsto

Con 20 visitas diarias y una conversación por visita se estiman unas 600 conversaciones mensuales:

- esperado: **USD 3.24/mes**;
- conservador: **USD 5.40/mes**;
- tope de política: **USD 12.84/mes**.

Esto no es una factura garantizada: el coste real dependerá del porcentaje de preguntas que supere el gate, del tamaño de la evidencia y del historial enviado.

## Coste de cargar conocimiento

Como referencia, incrustar 10,000 entradas de 500 tokens cada una serían 5 millones de tokens: aproximadamente **USD 0.10** con `text-embedding-3-small`. Las reindexaciones completas deben ser explícitas; las ediciones normales regenerarán únicamente el embedding de la entrada modificada.

## Recomendación operativa

1. Presupuesto inicial de alerta: **USD 10/mes** durante el piloto.
2. Corte duro inicial: **USD 20/mes**, configurable y con autorización manual para elevarlo.
3. Registrar por proveedor y proyecto tokens de entrada, salida, modelo y coste estimado.
4. Alertas al 50%, 80% y 100% del presupuesto.
5. No reintentar automáticamente errores 4xx; limitar los reintentos de 429/5xx y contabilizarlos.
6. Mantener el gate antes de Grok y `max_tokens = 600` como límite, no como objetivo.
7. Recalcular con telemetría real tras las primeras 100 conversaciones.

## Base de datos reproducible y entrega final

El esquema actual se reconstruye desde cero con PostgreSQL 15 + pgvector, Docker Compose y las migraciones Flyway `V001`–`V005`. La prueba `DatabaseMigrationTest` ya valida ese proceso usando una base efímera real.

Al finalizar el proyecto se debe entregar y probar un procedimiento operativo, no un segundo esquema SQL divergente:

- `bootstrap-db` para crear una base vacía y ejecutar Flyway;
- `verify-db` para validar versión, extensiones y checksums;
- `backup-db` y `restore-db` para datos reales;
- variables de entorno documentadas y separadas para migración/runtime;
- smoke test posterior a restauración;
- ejecución ensayada en un entorno limpio antes del piloto.

Las migraciones Flyway seguirán siendo la fuente de verdad del esquema. Un dump final puede servir como backup o acelerador, pero nunca sustituirlas.

## Sources

[1] https://docs.x.ai/developers/models/grok-4.3 — Grok 4.3 | xAI Docs
[2] https://developers.openai.com/api/docs/models/text-embedding-3-small — text-embedding-3-small | OpenAI API
