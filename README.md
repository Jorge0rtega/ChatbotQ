# ChatbotQ

Chatbot embebible multi-proyecto con recuperación vectorial, memoria conversacional temporal y transferencia a atención humana.

## Estado

Proyecto en construcción. La especificación funcional y el roadmap viven en el vault de Obsidian:

`/opt/obsidian/vault/chatbot/00 - ChatbotQ.md`

## Estructura prevista

- `backend/`: Spring Boot 2.7.18, Java 8, WAR desplegable.
- `frontend/`: Angular 22, panel administrativo y Web Component.
- `infra/`: PostgreSQL 15 + pgvector y configuración de desarrollo.
- `docs/`: ADR, migraciones versionadas y despliegue en WebLogic 12.2.1.4.

Documentos técnicos principales:

- [`docs/database-migrations.md`](docs/database-migrations.md)
- [`docs/adr/001-modular-clean-architecture.md`](docs/adr/001-modular-clean-architecture.md): módulos por capacidad y dependencias verificadas con ArchUnit.
- [`docs/weblogic-12.2.1.4.md`](docs/weblogic-12.2.1.4.md)
- [`docs/handoff-webhook-v1.md`](docs/handoff-webhook-v1.md): contrato firmado, idempotencia, reintentos y política SSRF.
- [`docs/provider-cost-estimate.md`](docs/provider-cost-estimate.md): escenarios de coste, presupuestos y entrega operativa de base de datos.
- [`evaluation/README.md`](evaluation/README.md): dataset y calibración reproducible del umbral RAG.

## Principios

- Clean Architecture dentro de un monolito modular.
- Aislamiento obligatorio por proyecto.
- TDD para comportamiento nuevo.
- Proveedores de embeddings, LLM y handoff reemplazables.
- Ningún secreto dentro del repositorio.

## Toolchain

- Java 8
- Maven 3.9.11 mediante Maven Wrapper
- Node.js 24.15.0
- npm 11.12.1
- Docker Compose para PostgreSQL 15 + pgvector

## Verificación local

```bash
# Backend: prueba y genera backend/target/chatbotq.war
cd backend
./mvnw clean verify

# Frontend: instala de forma reproducible, prueba y compila ambos proyectos
cd ../frontend
npm ci
npm test -- --watch=false
npm run build:all

# Infraestructura: crea el archivo local y levanta PostgreSQL
cd ..
cp .env.example .env
# Cambia POSTGRES_PASSWORD y no subas .env a Git
docker compose --env-file .env -f infra/compose.yaml up -d
```

El WAR está preparado como aplicación Spring Boot desplegable. La configuración específica de WebLogic se añadirá cuando se confirme la versión objetivo del servidor.

## CI

`.github/workflows/ci.yml` ejecuta pruebas y builds del backend y frontend en cada push a `main` y en pull requests; publica el WAR como artefacto.
