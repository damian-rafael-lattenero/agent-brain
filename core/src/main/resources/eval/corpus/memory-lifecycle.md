# Memory lifecycle and decay

Every memory carries a salience and a last-accessed timestamp, and its
life-weight decays exponentially with a configurable half-life (thirty days by
default): one half-life of silence halves the weight, so remembering an
established fact keeps it alive. Two floors classify it: at or above 0.5 the
memory is ACTIVE, between the floors it COOLS, and below the forget floor of
0.125 — roughly three half-lives without access — it is forgotten and the
retrieval laws skip it entirely. Reinforcement refreshes salience and
last-accessed, which is how a memory the user keeps repeating survives its own
decay.
