# Hoja de ruta — El libro de tu AI Engineering (v5)

> **Cómo usarla.** Marcá con una X cada ficha que termines. La lista está en orden de estudio: cada
> ficha usa solo lo que apareció antes. Una ficha de tema nuevo está pensada para 10 a 20 minutos.
> Si te enganchaste, seguí con la siguiente: están encadenadas a propósito.
> Las respuestas de todas las preguntas de recuperación están en la parte G, nunca dentro de la ficha.

## Las ocho paradas de cada ficha

1. **El ajá**: la idea en una frase y su equivalente en tus lenguajes.
2. **Qué denota · en Haskell · por qué existe.**
3. **En tus lenguajes**: Kotlin, Nushell, TypeScript, con la diferencia y la trampa.
4. **📍 En tu sistema**: el archivo real donde vive.
5. **✍️ Hoja de papel**: copiar la firma, completar la ley.
6. **Drill**: el test que tenés que hacer pasar.
7. **Preguntas de recuperación**: tres, de memoria.
8. **Cierre**: resumen de tres líneas y checkpoint.

## El mapa: 53 fichas

### A — El mapa y las palabras de base (7 fichas)
Necesita: nada.

- [ ] A0.1 API, puerto, socket
- [ ] A0.2 JSON, schema, tipos
- [ ] A0.3 Índice, cola, latencia
- [ ] A0.4 Prompt, modelo, token
- [ ] A1 Las cinco piezas: MCP, RAG, memoria, evals, harness
- [ ] A2 Cómo se hablan: quién le habla a quién
- [ ] A3 La historia en dos páginas

### B — Los conceptos (25 fichas)
Haskell es la fuente. Cada ficha termina en Kotlin, Nushell y TypeScript. Necesita: A0.

Funciones y valores:

- [ ] B01 Función pura y transparencia referencial
- [ ] B02 Datos inmutables
- [ ] B03 Funciones de primera clase y de orden superior
- [ ] B04 Currificación y aplicación parcial
- [ ] B05 Composición

Tipos y recursión:

- [ ] B06 Tipos suma y tipos producto
- [ ] B07 `Maybe` y `Either`
- [ ] B08 Pattern matching y exhaustividad
- [ ] B09 Recursión y folds

Abstracciones:

- [ ] B10 Type classes
- [ ] B11 `Functor`
- [ ] B12 `Applicative`
- [ ] B13 `Monad`
- [ ] B14 `Semigroup` y `Monoid`
- [ ] B15 `Foldable` y `Traversable`

El lenguaje:

- [ ] B16 Laziness
- [ ] B17 `newtype`
- [ ] B18 Higher-kinded types
- [ ] B19 Parametricidad
- [ ] B20 `IO` y los efectos en el borde
- [ ] B21 Funciones totales
- [ ] B22 Leyes como propiedades (QuickCheck)

Aplicado a tu código:

- [ ] B23 Free monad y tagless final (`Kap`)
- [ ] B24 Streaming y backpressure (`KapStream`)
- [ ] B25 Diseño denotacional: forma, denotación, ecuaciones, homomorfismo

### C — Tu sistema por dentro (8 fichas)
Necesita: A1, A2.

- [ ] C1 El loop del agente: qué pasa cuando apretás Enter
- [ ] C2 El bus: qué viaja y quién lo publica
- [ ] C3 Un turno se vuelve memoria: el worker de extracción
- [ ] C4 El ciclo de vida de la memoria
- [ ] C5 Embeddings desde cero: vector, coseno, 768, HNSW
- [ ] C6 El viaje de una pregunta por el RAG
- [ ] C7 El ciclo nocturno: la máquina se audita sola
- [ ] C8 MCP: qué es y qué expone el brain

### D — El harness como capa de leyes (8 fichas)
Necesita: C1.

- [ ] D1 Las tres memorias: KB, memorias del brain, auto-memoria de ZCode
- [ ] D2 Hook: qué es, cuándo corre, qué puede hacer
- [ ] D3 Skill: el procedimiento
- [ ] D4 La escalera: hecho, skill, hook
- [ ] D5 Gate y veredicto: permitir, guiar, preguntar, bloquear
- [ ] D6 El oráculo: compilar es la verdad
- [ ] D7 El juez de rumbo: el Pedido, el ledger, los disparadores
- [ ] D8 Corrección, propuesta, promoción

### E — Los números (3 fichas)
Necesita: C6, C7.

- [ ] E1 recall, MRR, nDCG y el gate
- [ ] E2 Los tres números nuevos: primera pasada, canon, rumbo
- [ ] E3 El ledger: por qué no se puede adulterar

### F — El método (2 fichas)
Necesita: todo lo anterior.

- [ ] F1 Las leyes, con sus recibos
- [ ] F2 Las trampas del mes

### G — Respuestas
- [ ] G Clave de todas las preguntas de recuperación y del examen final

## Dónde vive cada pieza

| Parte | Archivo canónico | Idioma |
|---|---|---|
| esta hoja | `agent-brain/docs/book/00-hoja-de-ruta.md` | español |
| las fichas | `agent-brain/docs/book/<id>-<tema>.md` | español, código y términos en inglés |
| los drills | `~/Projects/canonical-drills` | un módulo por lenguaje |
| el plan que ordena todo | `zcode-bot-kotlin/docs/plan-denotational-engine.md` | inglés |

El v4 anterior (`agent-brain/docs/ai-engineering-mapa-y-diccionario.md`) queda como registro histórico.
