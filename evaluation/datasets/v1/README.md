# Dataset RAG v1

Dataset inicial, pequeño y deliberadamente legible, para validar recuperación y abstención antes de usar datos del primer proyecto.

## Archivos

- `knowledge.csv`: conocimiento activo de referencia.
- `questions.csv`: preguntas clasificadas como `answerable`, `unanswerable`, `ambiguous` y `follow_up`.

## Reglas

- `expected_knowledge_id` vacío significa que el sistema debe abstenerse sin invocar Grok.
- Las preguntas `follow_up` requieren el contexto indicado en `notes`; no deben evaluarse como consultas aisladas.
- Cualquier cambio semántico crea una nueva carpeta de versión. La versión v1 no se reescribe después de usarse como evidencia.
- Este dataset valida el pipeline, no sustituye un dataset real del proyecto piloto.

## Métricas iniciales

Para preguntas aisladas:

- **Recall respondible (`retrieval_recall`):** proporción de preguntas `answerable` cuya entrada esperada aparece en top 5 y supera el umbral.
- **Precisión del gate (`precision`):** proporción de consultas aceptadas que realmente son `answerable`, aunque el candidato esperado no sea recuperado.
- **Recall del gate (`gate_recall`):** proporción de consultas `answerable` para las que al menos un candidato supera el umbral.
- **Tasa de abstención correcta:** proporción de `unanswerable` y `ambiguous` rechazadas.
- **F1 de calibración:** media armónica entre precisión del gate y recall respondible; penaliza tanto aceptar ruido como perder la evidencia esperada.

El umbral candidato inicial es `0.70`; debe aprobarse únicamente después de ejecutar embeddings reales y guardar el reporte generado.
