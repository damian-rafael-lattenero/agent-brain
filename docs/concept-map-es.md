# El mapa completo de tu AI engineering — v2 (18/11/2026)

Todo lo construido hasta hoy + el diccionario de términos, en español. Cada
número acá es medido y verificable (suite, ledger, endpoint o drill). La copia
de lectura vive en `~/Desktop/ai-engineering-mapa-y-diccionario.txt` (mismo
contenido).

## Parte 1 — La historia en una página

**30/10 — La brújula.** El consenso 2026 confirmado con búsquedas: hacen falta
CINCO cosas sobre los modelos que ya existen — MCP propio, RAG con tus datos y
citas, memoria para el agente, evals, tu propio harness. No son cinco proyectos
sueltos: es una sola app que crece con cada uno. El mapa: 5 repos — ZCode (fork
del harness de escritorio), bot (harness Telegram, retirado), zcode-bot-kotlin
(el reemplazo Kotlin), agent-brain (el cerebro), kap (la stdlib de concurrencia).

**30/10–2/11 — La migración.** El bot Python se jubiló en una tarde: cutover
del envío (primer envío Kotlin real, cero duplicados), canal daemon-local en el
fork (socket unix con auth), bot Kotlin completo, retiro de todos los
LaunchAgents Python. Regla nueva: todo script propio es Nushell tipado.

**3/11 — El cerebro enciende.** kap 4.3/5.0 (Supervisor, KapStream, Schedule
semiring). El RAG indexa los 5 repos; brain-mcp instalado en ZCode; el bus
publica turnos reales y la memoria inyecta contexto en cada turno (≤1500
chars, deadline 1.5 s, silencio si está caído).

**8/11 — Auditoría FP del bot.** 19 fixes reales. El dialecto de la casa: FP
denotacional, kap como stdlib, cero `while(true)`.

**11–13/11 — Flota.** Dos syncs upstream del fork (v3.18.0, v3.18.2), firma
estable (Keychain), barridos Nushell flota completa (46 + 57 scripts), KB 0.117
con 21 drills verificados.

**14/11 — Plan brújula v2.** Evals y observabilidad como PRODUCTO, rerank
medido, memory lifecycle como disciplina, MCP Apps. B1 métricas graduadas
(MRR/recall@1/nDCG). B2 rerank CONSTRUIDO y VERDICTADO: el listwise con LLM
chico pierde contra la fusión (recall@1 0.350 vs 0.850) → default OFF. La
disciplina moderna es NO shippear lo que los evals mataron.

**15/11 — La memoria como sistema.** B3 lifecycle (access-reinforces +0.1,
decay por last_accessed, ACTIVE/COOLING/ARCHIVED; olvidar = excluir, nunca
borrar). B4 grafo (edges related/cooccur/temporal + traversal; el OR simétrico
costaba 33–35 s → 223 ms con UNION ALL de joins indexados). Reconcile con juez
(same/supersede/distinct, invalid_at). Dream (consolidación fuera de sesión).
Juez separado: deepseek 8/8 en contradiction-core vs 5/8 del 1.5b local.

**15–16/11 — Tipado y extractor.** 5-probe arc: el pgvector iterativo era
NO-OP (ley descubierta: el semi-join "alive" vence al HNSW) y el fork de
SQLDelight compiló toda la ley de retrieval → migración completa a SQL tipado,
recall byte-idéntico (20/20), fork publicado a Maven Central. Corpus de eval
13 → 29 docs single-concern: MRR 0.9125 → 0.9848. LA CASCADA DEL EXTRACTOR en
7 capas medidas: cap 512 → truncation visible → splitting → defers gratis con
breaker abierto → extracción secuencial → timeout REAL (el knob muerto: un
cliente HTTP compartido de 60 s cortaba antes que la config) → deepseek como
extractor de raíz. Los "gigantes" que el 1.5b degradaba recuperaron +130
memorias, cero quarantine nuevo.

**17/11 — RAG bilingüe + auditoría.** QueryExpansion (español → keywords
inglesas) + segunda pata fusionada con RRF. La lección honesta: los misses
eran CORPUS (docs que no existían), no idioma; real-questions 6/8 → 8/8.
rag-nightly.nu: índice + eval a las 04:00 — el RAG se certifica solo cada
noche. Auditoría de calidad flota (olas 1–6). Learning map + protocolo de
challenge (el challenger usa OTRO modelo; cada objeción nombra su cheque).

**18/11 — Operación y CV.** Los 5 huecos de operación cerrados: backups con
drill PASS (03:40, retención 14 días), ledger de costo por turno (turn_costs +
`/costs/today`), feedback 👍/👎 al bus, gate de secretos (gitleaks árbol/staged;
trufflehog verificado para la historia: 7 repos, CERO credenciales vivas),
watcher de quota con histéresis. El CV re-hecho sobre este mapa: dos artefactos
desde un `content.tex` (cv.pdf diseñado; cv-ats.pdf parser-first de una
columna), gates que fallan si las 17 keywords dejan de estar en la capa de
texto, contacto extraíble, cero glifos private-use.

## Parte 2 — El sistema hoy (los números)

**RAG**: 20,722 docs / 72,934 chunks. recall-core 33/33, MRR 0.9848,
recall@1 0.9697, nDCG@5 0.9888. real-questions 8/8 (gate 0.8). Híbrido
pgvector + FTS + RRF, p50 ~20–33 ms, expansión bilingüe. Rerank implementado,
medido dos veces, default OFF.

**Memoria**: ~4.4k memories, ~27k edges. Dedup en escritura ≥0.90 refuerza la
más vieja. Reconcile con juez deepseek (supersede con veredicto; evidencia en
quarantine). Lifecycle con dos relojes (created_at = frescura, last_accessed =
utilidad).

**Evals**: recall-core 33/33 · real-questions 8/8 · contradiction-core 8/8
(juez deepseek) · memory-core 9/10 · extraction-core 6/6. Ledger eval_runs;
nocturna 04:00 indexa y evalúa.

**Bot/canal**: daemon Kotlin (poller + router + socket + live cards +
multi-lane). turn.completed lleva modelo + tokens + stop_reason → turn_costs →
`/costs/today`. 👍/👎 llegan al bus. Watcher de quota (una vez por episodio).

**Infra**: backups pg con drill PASS; secretos: 7 repos, cero credenciales
vivas; LaunchAgents para todo lo que sobrevive a ZCode.

**kap**: 5.4.0 en Maven Central (8 módulos, consumido por los 4 repos);
1,234 tests / 92 suites con leyes; 144 benchmarks JMH; detekt rules propias.

**CV**: content.tex fuente única; cv.pdf + cv-ats.pdf; gates ATS (17/17
keywords, contacto extraíble, cero PUA).

## Parte 3 — El diccionario

### 3.1 Modelos y LLMs

- **LLM (large language model)** — el modelo que predice el siguiente token.
  En tu stack: deepseek-v4.1-flash (remoto, plan OpenCode) para extraer y
  juzgar; antes qwen2.5:1.5b local.
- **Token** — la unidad de texto (~4 chars). Los caps y los costos se miden en
  tokens.
- **Context window** — la memoria de CORTO plazo: todo lo que ve en una
  llamada (num_ctx 4096 → 8192). Lo tuyo a largo plazo es la MEMORIA, no la
  ventana.
- **System prompt** — la instrucción fija de rol. Ahí vive el schema de
  extracción.
- **Parameters (1.5b/3b/7b)** — capacidad. El 1.5b entraba en tu Mac y
  DEGENERABA (9,227 chars de una sola oración, medidos).
- **Degeneración** — el modo de falla del chico: loop de repetición. No se
  arregla con más cap (más cap = más basura); se arregla con mejor modelo.
- **Finish reason / done_reason** — POR QUÉ terminó ("stop" bien, "length"
  cortado). Leerlo hizo visible la truncación que se disfrazaba de "JSON
  inválido".
- **Embedding model vs chat model** — el embedding convierte texto en vector
  (nomic-embed-text local, gratis); el chat conversa/extrae/juzga. Distintos
  por diseño. Matrix: nomic se queda (MRR 0.985 vs 0.909 all-minilm).
- **Inference local vs API** — local = gratis/privado/lento para gigantes; API
  = paga/rápida/capacidad completa. Veredicto provider-split: embeddings y
  volumen local; calidad crítica remota.
- **Fine-tuning / LoRA / QLoRA** — entrenar adaptadores chicos sobre un
  modelo existente; QLoRA lo hace con el modelo cuantizado. Tu proyecto de
  cierre: el extractor LoRA (pares ya exportados: positivos = transcripts →
  extracción canónica; negativos = quarantine).
- **Quantization** — comprimir el modelo a menos bits (GGUF/ollama) para que
  corra en tu Mac.
- **SLM** — los chicos (1.5b–3b): volumen local; capacidad insuficiente para
  juzgar (5/8) y para transcripts largos (degeneración).

### 3.2 RAG y retrieval

- **RAG** — recuperar los trozos relevantes de TU conocimiento y entregarlos
  al modelo como contexto, CON CITA. Un LLM no sabe lo tuyo.
- **Corpus / chunk / chunking** — el conjunto indexado (20,722 docs / 72,934
  chunks) y sus pedazos. Cada chunk tuyo arranca con "ruta > heading": la
  versión barata del Contextual Retrieval.
- **Vector / embedding** — 768 números que representan el SIGNIFICADO.
- **Cosine similarity** — el ángulo entre vectores (1.0 = mismo sentido).
  Dedup 0.90; banda relacionados 0.60–0.88.
- **Vector DB / pgvector** — Postgres con superpoderes de vectores.
- **HNSW / ANN vs exact scan** — el índice de vecinos aproxima; el scan
  compara todo. Descubrimiento: el semi-join "alive" le impedía al HNSW
  usarse — la búsqueda era exacta-por-scan sin saberlo.
- **FTS / BM25 (sparse)** — búsqueda por PALABRAS; el vector busca sentido.
  Por eso el híbrido es el estándar.
- **Hybrid retrieval** — dense + sparse juntas. Tu estándar de facto.
- **RRF (K=60)** — la fórmula que fusiona rankings: cada doc suma
  1/(60+posición) por lista donde aparece.
- **Rerank** — segunda pasada que re-ordena. Cross-encoder (scorea pares
  query-doc; el estándar 2026, sin probar acá) vs listwise-LLM (probado dos
  veces, perdió: 0.350 vs 0.850 y +0.004 MRR por 100–1000× latencia → OFF).
- **recall@k / MRR / nDCG@5** — ¿está en el top-k? ¿qué tan arriba? ¿todo el
  top-5 ponderado? Tus números: 1.0 / 0.9848 / 0.9888 en recall-core.
- **p50/p95** — latencia mediana y de cola (20–33 ms p50 en búsqueda).
- **Grounding / citation** — la respuesta se apoya en los chunks citados; el
  eval exige la cita correcta.
- **Distractor / needle** — el doc que roba la query por vocabulario
  compartido (por eso docs single-concern); el hecho puntual buscado.
- **Query expansion / RAG Fusion** — reformular y fusionar. Tu expansión
  bilingüe: español → keywords inglesas, identifiers verbatim, una pata sola
  si el restatement es idéntico (sólo puede SUMAR recall).
- **Contextual Retrieval (Anthropic)** — contexto LLM por chunk: −67% fallos
  en su benchmark; tu heading path ya cubre lo esencial a esta escala.
- **HyDE** — embeber una respuesta hipotética; no implementado (sin señal).
- **Agentic RAG / Adaptive / Self-RAG / CRAG** — la recuperación como LOOP
  (reformular, decidir pasos, evaluar, reintentar). El siguiente salto natural.
- **GraphRAG / RAPTOR / sentence-window** — grafos de entidades sobre docs y
  chunking jerárquico; no implementados (tu grafo de MEMORIA ya existe).
- **Holdout / overfit** — preguntas nunca usadas para curar; el cheque del
  riesgo "8/8 puede ser fuga de test".
- **Benchmarks de memoria (LoCoMo/LongMemEval/BEAM)** — los estándares con
  los que la industria compara (Mem0 reporta 92.5 en LoCoMo).

### 3.3 Memoria de agente

- **Long-term memory** — lo que sobrevive al cierre: hechos/decisiones/
  preferencias extraídos por LLM de cada turno.
- **Salience / decay / reinforcement** — el peso, su enfriamiento desde
  last_accessed (mitad de vida 30 días) y la subida (+0.1 por hit, +1.0 por
  merge). Un hit de búsqueda no pesa como un hecho re-afirmado.
- **Lifecycle (ACTIVE/COOLING/ARCHIVED)** — clasificación total en runtime por
  umbrales (0.5/0.125); sin estado guardado. Olvidar = excluir de la búsqueda,
  nunca borrar; reforzar resucita.
- **Dedup (0.90)** — frontera MEDIDA: duplicados verdaderos 0.9123–0.9845 vs
  el par relacionado más fuerte 0.8789 → 0.90 en el hueco vacío.
- **Reconcile / supersede / invalid_at** — el juez decide same/supersede/
  distinct en la banda relacionada; la fila vieja se invalida, la evidencia
  queda. El retrieval filtra `invalid_at IS NULL`: una fila válida por hecho
  (auditar SIN ese filtro cuenta historia diseñada, no corrupción).
- **Memory graph / edges / traversal** — related/cooccur/temporal (~27k) y
  caminar el grafo desde un hit. Ley SQL: UNION ALL de dos joins indexados
  (el OR simétrico: 85.7M filas intermedias).
- **Dream** — consolidación fuera de sesión (estilo Letta), semanal.
- **Staleness** — una memoria que miente porque el mundo cambió; el reconcile
  es la cura.
- **Mem0 / Zep / Letta** — los comerciales de referencia; a tu escala su valor
  no hace falta y el swap es un módulo, no una reescritura (medido).

### 3.4 Evals y calidad

- **Eval suite / ground truth** — preguntas con respuesta conocida escrita por
  un humano; sin ground truth hay demo, no eval.
- **Gate** — la nota mínima (recall 0.9, real-questions 0.8). Un modelo bajo
  el gate es un HALLAZGO; nunca se baja el piso.
- **Ledger (eval_runs)** — cada corrida deja fila: la historia que no se
  reescribe.
- **LLM-as-judge** — dónde la determinación no es posible (reconcile,
  contradiction) y NO donde un assert alcanza (bias, drift, auto-preferencia).
- **Red teaming** — atacar tu propio sistema; drill tipado de 10 vectores,
  exit 1 si falla: 10/0.
- **Eval matrix** — la misma suite contra varios modelos con costo y latencia
  (el veredicto nomic salió de ahí).
- **Regression gate** — los evals como CI: lo que baja el número no mergea
  (recall byte-idéntico tras 5 unidades de cirugía).

### 3.5 MCP, agentes y seguridad

- **MCP (Model Context Protocol)** — el estándar abierto para que un
  asistente use herramientas y datos. Server = brain-mcp (6 tools); host =
  ZCode.
- **Tool calling / function calling** — el modelo llama una herramienta con
  argumentos estructurados ("cancelá mi suscripción" → tool call tipado).
- **JSON Schema** — el contrato tipado del tool (inputSchema).
- **Idempotencia** — llamar dos veces = llamar una; la propiedad que hace
  seguro que un LLM reintente.
- **MCP Apps** — paneles de UI servidos por el mismo server MCP (brain-panel,
  diff-ask).
- **Harness / agent loop** — el programa que rodea al modelo y su ciclo
  pensar → herramienta → mirar → repetir. Claude Code/ZCode son harnesses.
- **Subagent / hooks / permission boundaries** — agentes lanzados por agentes
  (parentToolUseId), los puntos de enganche (donde viven tus leyes mecánicas)
  y qué puede hacer el agente sin preguntar — fail-closed ante la duda.
- **Prompt injection / guardrails** — el ataque (contenido hostil que se hace
  pasar por instrucción) y las barreras: header "datos recuperados, NO
  instrucciones", escape HTML en cada renderer, drill de inyección.
- **Context engineering** — qué entra en la ventana y en qué orden (memoria,
  RAG, historial); tu inyección ≤1500 chars con deadline es context
  engineering medido.

### 3.6 Infraestructura y observabilidad

- **Bus / event sourcing** — la cinta de eventos con id (user.prompt,
  turn.completed, tool.call, turn.feedback); el brain la consume y proyecta.
- **Worker / queue** — pending = turn.completed sin memory_at e intentos < cap.
- **Circuit breaker** — el fusible (5 fallos → OPEN → half-open → CLOSE), con
  la ley: un rechazo por breaker abierto NO consume el intento del turno.
- **Retry/backoff / backpressure** — kap Schedule (semiring) y concurrencia
  acotada (extraction_concurrency=1 contra un solo runner).
- **Knob (y el knob muerto)** — perilla de config; la lección: chat_timeout_s
  decía 240 pero un cliente compartido cortaba a 60 s. Configurar sin
  verificar QUIÉN corta primero = config muerta.
- **LaunchAgent / heartbeat / staleness watchdog** — lo que sobrevive a ZCode
  vive afuera; el healer lee freshness (no el "loaded" hueco); "silencio es
  data" — un ticker que miente se mata y se reemplaza.
- **OpenTelemetry / Prometheus / Grafana** — span por turno y por tool call;
  métricas scrapeadas y dashboards (gauges del lifecycle y del bus).
- **Migration / advisory lock** — el schema cambia en el boot (0001–0007) con
  lock anti-doble-migración.
- **Quota gates vs lanes** — GATES: una ventana cerrada bloquea TODA llamada;
  LANES: buckets por modelo independientes. El watcher avisa UNA vez por
  episodio con histéresis.
- **Backup drill** — restaurar el dump en una DB scratch y comparar counts:
  un backup sin drill es una suposición.

### 3.7 Tu dialecto FP

- **ADT** — sealed + data: el dominio en tipos; los estados inválidos no se
  representan (TurnState, Outcome, Tick).
- **Law test / property-based testing** — verificar leyes algebraicas y
  generar cientos de casos (kap: 92 suites con leyes).
- **Resource / Process / Schedule / Ref** — adquisición con garantía
  (bracketCase), loops con política de restart, el álgebra de reintentos, la
  celda atómica lock-free.
- **traverseSettled** — mapeo concurrente que NUNCA lanza, con concurrencia
  acotada.
- **Parse, don't validate** — el dato entra al pipeline parseado UNA vez a un
  tipo que hace inválido lo inválido.
- **No opaque types** — tu ley: escalares con rol (typealias/value class),
  estructura (data class), elecciones (sealed); el test: ¿un lector del
  dominio sabe qué es cada valor desde la firma?

## Parte 4 — Cómo leer los números

1. **recall@5** — ¿está la respuesta entre los primeros 5? La puerta.
2. **MRR** — ¿qué tan arriba? 0.5 ≈ el doc correcto promedia posición 2.
3. **nDCG@5** — MRR que premia todo el top-5 en orden.
4. **p50/p95** — mediana y cola de latencia.
5. **gate** — el piso aprobado por vos; si no llega, es un hallazgo.

Ejemplo vivo: recall-core 33/33, MRR 0.9848, nDCG@5 0.9888, p50 22 ms — "en 33
preguntas reales, el doc correcto estuvo en el top-5 SIEMPRE, casi siempre
primero, en 22 ms".

## Parte 5 — Lo que sigue + plan de lectura

1. **Eval de uso real** — loggear cada rag_query, muestrear semanal, gradear
   con el judge, al ledger. El número que falta: hit-rate sobre producción.
2. **Cross-encoder rerank como probe** — el mecanismo estándar que no probaste;
   se integra SÓLO si gana.
3. **Agentic / multi-hop RAG** — la recuperación como loop, gated por una
   suite multi-hop.
4. **El peldaño que falta: los modelos** — LoRA del extractor, gate =
   extraction-core ≥ deepseek y costo.

Plan de lectura: leélo dos veces (hoy y mañana); la cadena que resume todo es
`rag_query "extraction cascade breaker timeout"`; en 3 días, explicar de
memoria RRF, recall@5, dedup 0.90, breaker y context engineering, y por qué el
rerank quedó OFF.
