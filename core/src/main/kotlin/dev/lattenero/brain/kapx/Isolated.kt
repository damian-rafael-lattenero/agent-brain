package dev.lattenero.brain.kapx

import kap.Kap
import kotlinx.coroutines.coroutineScope

/**
 * KAP launches the children of `traverse` / `with` on the CALLER's scope (the
 * `CoroutineScope` receiver of `execute`). Inside `traverseSettled`, `settled`
 * or `recover` that means a failing grandchild fails the settled coroutine
 * itself through structured concurrency, and `awaitAll` rethrows even though
 * `runCatching` returned a `Result` (finding from the indexer, KAP 4.0.0;
 * pinned by `KapFindingsTest`). Running the graph in its own `coroutineScope`
 * gives failures a boundary to be converted at.
 */
fun <A> Kap<A>.isolated(): Kap<A> = Kap { coroutineScope { with(this@isolated) { execute() } } }
