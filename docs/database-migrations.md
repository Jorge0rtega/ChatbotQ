# Migraciones de base de datos

ChatbotQ usa Flyway y scripts SQL versionados en:

`backend/src/main/resources/db/migration/`

## Reglas

1. Cada cambio de esquema se entrega en un archivo nuevo: `VNNN__descripcion.sql`.
2. Una migración aplicada en cualquier ambiente es **inmutable**. Nunca se edita; la corrección se hace en una migración posterior.
3. Los scripts deben poder ejecutarse mediante Flyway y conservar SQL PostgreSQL legible.
4. No se usan `DROP`, pérdida de datos ni conversiones destructivas sin plan de respaldo, migración gradual y aprobación explícita.
5. Cambios incompatibles siguen expandir/migrar/contraer: agregar primero, migrar datos, actualizar aplicación y retirar después.
6. Flyway valida checksums antes de iniciar la aplicación.
7. No usar `flyway repair` para esconder discrepancias; primero se investiga la causa.

## Baseline inicial

- `V001`: extensiones `pgcrypto` y `vector`.
- `V002`: administradores, proyectos, orígenes y roles.
- `V003`: conocimiento y embeddings de 1536 dimensiones.
- `V004`: conversaciones, mensajes y trazas RAG.
- `V005`: handoff, importaciones y consumo de proveedores.

## Aplicación

Con variables `DB_URL`, `DB_USERNAME` y `DB_PASSWORD`, Spring Boot aplica migraciones al arrancar. En CI, `DatabaseMigrationTest` crea PostgreSQL + pgvector real mediante Testcontainers, migra desde cero y valida el esquema.

## Réplica manual controlada

Flyway es el mecanismo preferido. Si un equipo debe revisar SQL antes de producción, se entregan exactamente estos mismos archivos y se ejecutan en orden; no se mantiene un segundo conjunto de scripts divergente.
