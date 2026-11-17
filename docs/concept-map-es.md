# El mapa conceptual de tu brain (en español)

El diccionario que pediste: cada término, qué es, por qué existe en TU sistema,
y dónde vive. Leído dos veces, la arquitectura deja de ser "algo perdido".

## El flujo completo (una vuelta del ciclo)

1. Una conversación (ZCode, el bot, una sesión) termina → el **bus** registra el
   turno (`turn.completed`) en el archivo y en Postgres.
2. El **worker** del brain la agarra de la cola: pending = turno terminado sin
   marca (`memory_at IS NULL`) y con intentos bajo el cap (5).
3. La **extracción**: el chat model (deepseek-v4.1-flash) lee el transcript y
   devuelve un JSON con grupos tipados: facts, decisions, preferences, summary.
4. El **dedup**: si el hecho ya existe (similitud coseno ≥ 0.90), la memoria
   vieja se REINFORZA en vez de insertar otra copia.
5. El **embedding**: el texto se convierte en un VECTOR (768 números con
   nomic-embed-text corriendo en ollama) y se guarda en Postgres/pgvector.
6. La búsqueda futura: tu pregunta → vector + full-text search (FTS) → **RRF**
   fusiona los dos rankings en uno.
7. El **reconcile** (dream): el juez compara memorias parecidas y decide
   same / supersede / distinct — las claims viejas se invalidan con veredicto.

## El diccionario

- **Vector / embedding** — una lista de 768 números que representa el
  SIGNIFICADO de un texto. Dos textos parecidos → vectores apuntando a
  direcciones parecidas. No es la palabra: es el sentido.
- **Coseno / similitud** — el ángulo entre dos vectores. 1.0 = misma dirección
  (mismo significado), 0 = nada que ver. El dedup usa 0.90 como frontera; la
  banda de "relacionados pero distintos" es 0.60–0.88.
- **pgvector / HNSW** — la extensión de Postgres que guarda vectores y los
  busca rápido. HNSW es un grafito de vecinos: no compara contra todos, salta
  por el grafito hacia los más cercanos.
- **FTS / tsquery** — full-text search: buscar por PALABRAS (el otro half del
  hybrid search; el vector busca sentido, las palabras buscan coincidencia
  literal).
- **RRF (Reciprocal Rank Fusion)** — la fórmula que fusiona dos rankings: cada
  documento suma 1/(60 + posición) por cada lista donde aparece. K=60.
- **RAG (Retrieval-Augmented Generation)** — recuperar los trozos relevantes de
  TU conocimiento y entregarlos al modelo como contexto antes de responder. Por
  qué existe: un LLM no sabe lo tuyo. Por qué no mem0/Letta/Zep: ya medido en
  el runbook §11.3 — su valor real (grafos de entidades, validez temporal) no
  hace falta a tu escala, y el swap es un módulo, no una reescritura.
- **ollama** — el servidor que corre modelos LOCALES en tu Mac. Ahí vivían
  qwen2.5:1.5b (el chico de extracción) y nomic-embed-text (embeddings — sigue
  ahí, corre gratis en tu GPU).
- **qwen2.5:1.5b vs deepseek** — el 1.5b tiene 1.5 mil millones de parámetros
  (por eso entraba en tu Mac) y DEGENERA: en transcripts largos repite una
  frase hasta llenar el cap (medido: 9,227 chars de una sola oración).
  deepseek-v4.1-flash es el modelo remoto del plan OpenCode (10 USD, toneladas
  de tokens) — no degenera, y el costo por memoria es insignificante a tu
  volumen.
- **knob** — una perilla de configuración (`chat_timeout_s`,
  `extraction_concurrency`). El bug del knob muerto: `chat_timeout_s` decía
  120/240 pero el CLIENTE HTTP compartido cortaba a los 60 s antes de que la
  perilla hablara. Configurar sin verificar quién corta primero = config muerta.
- **El juez / judge** — la SEGUNDA opinión. deepseek con contexto fresco que
  juzga pares de memorias (reconcile) y ahora tus decisiones. La independencia
  es por CONTEXTO y por rol, no por marca.
- **Eval gates / ledger / los números** — un gate es la nota mínima para pasar
  (recall 0.9, real-questions 0.8). El ledger es la tabla `eval_runs` donde cada
  corrida deja su fila: fecha, suite, passed/total, ok. Los números que no se
  pueden adulterar = el resultado medido sobre preguntas con respuesta conocida:
  o el doc correcto aparece en el top-5 o no. Un número no se puede adular.
- **HTTP client (por qué dos)** — cada llamada HTTP tiene un reloj de corte.
  Un solo reloj de 60 s entre llamadas de 2 s (embeddings) y de 130 s
  (extracción gigante) mata a las largas. El chat tiene el suyo: 240 s.
- **kap** — tu librería de concurrencia (Kap, Process, Signal, Meter,
  traverseSettled). El worker es un Process; el batch es un traverseSettled
  acotado; los contadores del ciclo van por el Meter SPI a Prometheus.
- **colima** — la VM de Docker en tu Mac. Dentro corren: Postgres (pgvector),
  Redpanda (kafka opcional), Prometheus y Grafana (los dashboards de /metrics).
- **El bus** — la cinta de eventos: cada cosa que pasa (turnos, tool calls)
  queda como evento con id; el brain la consume (el tailer) y guarda.

## Los 3 riesgos que el juez encontró (lo que no podías nombrar — y ahora sí)

1. **deepseek de raíz sin fallback**: si el relay cae, la cola crece SILENCIOSA
   (el deferral ya no quema intentos = los fallos dejaron de ser ruidosos).
   Cheque falsable: inyectar una caída de 60 min y medir si alguna alarma
   salta; ledger de coste por memoria 7 días.
2. **8/8 puede ser overfit**: las preguntas que crecieron el corpus son las que
   el gate ahora puntúa (fuga de test). Cheque: un holdout de 50 preguntas
   nunca usadas para curar; si el recall cae bajo el gate, el 8/8 era del
   corpus, no del sistema.
3. **El dialecto postgres forkeado**: si SQLDelight no reproduce la fusión RRF
   byte a byte contra raw JDBC, el core no está cubierto. Cheque: test de
   conformidad + medir la divergencia del fork en 2 releases.

## Tu plan de estudio (en orden)

1. Leé este mapa dos veces (hoy y mañana).
2. La cadena de la cascada en el RAG: `rag_query` "extraction cascade breaker
   timeout" — los 5 docs nuevos cuentan cada capa con su medición.
3. En 3 días: las mismas 6 preguntas del examen, de memoria. La diferencia es
   tu curva de aprendizaje medida.
