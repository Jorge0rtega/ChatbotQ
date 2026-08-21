# ADR-001 — Monolito modular por paquetes con Clean Architecture

- Estado: aceptado
- Fecha: 2026-08-20

## Contexto

ChatbotQ debe desplegarse como un único WAR en WebLogic 12.2.1.4 y permanecer en Java 8 durante la primera etapa. Separar cada capacidad en proyectos Maven independientes aumentaría el build, el classloading y la operación sin aportar aislamiento de proceso necesario para el MVP.

A la vez, un único paquete organizado por capas globales (`controller`, `service`, `repository`) facilitaría dependencias cruzadas y convertiría el monolito en una bola de lodo.

## Decisión

El backend será un monolito modular organizado primero por capacidad funcional y después por Clean Architecture:

```text
com.chatbotq.<modulo>.domain
com.chatbotq.<modulo>.application.model
com.chatbotq.<modulo>.application.port
com.chatbotq.<modulo>.application.usecase
com.chatbotq.<modulo>.infrastructure.persistence
com.chatbotq.<modulo>.infrastructure.provider
com.chatbotq.<modulo>.web
```

Los paquetes se crean cuando contienen comportamiento real; no se generan carpetas vacías para fingir avance.

Componentes transversales de plataforma, como `observability`, pueden vivir directamente bajo `com.chatbotq`, pero no contienen reglas de negocio.

## Dirección de dependencias

```text
web ───────────────┐
                   ├──> application ───> domain
infrastructure ────┘
```

Reglas:

1. `domain` usa únicamente Java y no depende de Spring, Servlet, persistencia, proveedores ni web.
2. `application` no depende de `infrastructure`, `web`, Spring ni Servlet.
3. Los puertos top-level de `application.port` son interfaces.
4. Un módulo no consume directamente la infraestructura de otro módulo.
5. Los adaptadores implementan puertos; los casos de uso no conocen clases concretas de proveedor.
6. La comunicación entre módulos ocurre mediante contratos de aplicación explícitos, no accediendo repositorios ajenos.
7. `shared` solo se crea para conceptos realmente compartidos y estables; no funciona como cajón de sastre.

## Cumplimiento automático

`CleanArchitectureTest` usa ArchUnit y falla el build cuando:

- aplicación depende de adaptadores o frameworks;
- un puerto top-level no es interfaz;
- una clase externa intenta depender directamente de la infraestructura RAG.

Las reglas se ampliarán al aparecer `domain`, `web` y nuevos módulos. CI ejecuta estas pruebas dentro de `./mvnw clean verify`.

## Consecuencias

### Positivas

- Un solo WAR y un solo ciclo de despliegue.
- Dependencias internas verificables automáticamente.
- Proveedores y persistencia reemplazables.
- Menor complejidad que un multi-módulo o microservicios prematuros.

### Negativas

- El aislamiento es lógico, no de proceso.
- Las reglas ArchUnit deben crecer junto con el código.
- Las transacciones entre módulos siguen compartiendo base de datos y requieren disciplina explícita.

## Alternativas descartadas

- **Capas globales:** simples al inicio, pero facilitan acoplamiento transversal.
- **Multi-módulo Maven:** mejor aislamiento de compilación, pero coste innecesario para el tamaño y despliegue actuales.
- **Microservicios:** complejidad operativa y distribuida injustificada para el piloto.
