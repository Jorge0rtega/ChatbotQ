# Evaluación RAG

Este directorio contiene datasets versionados y la calibración reproducible del gate de similitud.

## Validación local sin proveedores

```bash
PYTHONPATH=evaluation python3 -m unittest evaluation/tests/test_calibrate.py
```

## Calibración con embeddings reales

```bash
export OPENAI_API_KEY='...'
python3 evaluation/calibrate.py \
  --dataset evaluation/datasets/v1 \
  --output evaluation/reports/v1-openai.json
```

El comando:

1. genera embeddings para conocimiento y preguntas aisladas;
2. valida cardinalidad, orden, dimensión y valores numéricos de los embeddings;
3. calcula similitud coseno y conserva los cinco mejores candidatos;
4. considera recuperada una respondible cuando su entrada esperada supera el umbral dentro del top 5;
5. evalúa el umbral inicial `0.70`;
6. selecciona el umbral con mejor F1 y exactitud;
7. guarda puntuaciones, ranking y métricas en JSON.

Las preguntas `follow_up` se reportan pero no se calibran como consultas aisladas. Se evaluarán cuando exista el componente de contexto conversacional.

Los reportes no contienen claves, pero sí resultados que deben conservarse como evidencia cuando se apruebe un umbral.

## Pruebas Java 8 de proveedores y pgvector

Desde `backend/`:

```bash
./mvnw -Dtest=OpenAiEmbeddingClientTest,GrokStreamingChatClientTest test
./mvnw -Dtest=PgVectorKnowledgeRetrieverTest test
```

La prueba de pgvector usa Testcontainers por defecto. También admite una base de pruebas ya migrada mediante:

- `CHATBOTQ_TEST_DB_URL`
- `CHATBOTQ_TEST_DB_USERNAME`
- `CHATBOTQ_TEST_DB_PASSWORD`

Nunca apuntar estas variables a producción. Los proyectos temporales se eliminan al terminar la prueba.
