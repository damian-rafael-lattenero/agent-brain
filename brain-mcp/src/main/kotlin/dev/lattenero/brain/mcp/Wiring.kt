package dev.lattenero.brain.mcp

import kap.evalGraph

/**
 * The ONE startup graph both transports begin from: the tool registry and the
 * brain's liveness probe as two parallel branches of the typed KAP wiring, so
 * a dead brain is known before the first tool call and logged once.
 */
internal suspend fun wire(http: HttpBrainClient): ToolWiring = kap(::ToolWiring)
    .with { registry from BrainTools.all }
    .with { brainReady from brainReady(http) }
    .evalGraph()
