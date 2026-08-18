# Despliegue en WebLogic 12.2.1.4

ChatbotQ genera `backend/target/chatbotq.war` para Oracle WebLogic Server 12c 12.2.1.4.

## Compatibilidad

- Java objetivo: 8.
- Servlet: 3.1.
- Descriptor estándar: `WEB-INF/web.xml`.
- Descriptor WebLogic: `WEB-INF/weblogic.xml`.
- Context root predeterminado: `/chatbotq`.
- Tomcat está en alcance `provided` y queda en `WEB-INF/lib-provided`, no en el classpath normal del contenedor.

## Configuración requerida

Las credenciales no se empaquetan en el WAR. Deben inyectarse como variables del proceso o mediante la estrategia de secretos del ambiente:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- posteriormente `OPENAI_API_KEY` y `XAI_API_KEY`

Ejemplo de URL:

```text
jdbc:postgresql://postgres-host:5432/chatbotq
```

## Base de datos

Al iniciar, Flyway valida checksums y aplica migraciones pendientes. El usuario de despliegue necesita permisos DDL mientras se ejecuten migraciones. Para producción puede separarse un usuario de migración y desactivar Flyway en el runtime después de aplicarlas mediante el pipeline.

La creación de `pgcrypto` y `vector` requiere privilegios suficientes en la primera instalación. Si el DBA controla extensiones, debe aplicar `V001` previamente y Flyway conservará el historial del resto.

## Verificación previa

```bash
cd backend
./mvnw clean verify
jar tf target/chatbotq.war | grep 'WEB-INF/\(web.xml\|weblogic.xml\)'
```

## Pendiente para el ambiente real

- Confirmar si WebLogic usará datasource JNDI en lugar de URL JDBC directa.
- Definir dominio, cluster, managed servers y política de rollout.
- Ejecutar una prueba de despliegue contra una instancia 12.2.1.4 representativa.
