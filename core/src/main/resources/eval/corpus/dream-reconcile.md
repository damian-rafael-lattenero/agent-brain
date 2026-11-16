# Dream pass and reconcile verdicts

Contradictions are not resolved at write time; a background reconcile pass
(documented as the dream pass) revisits near-duplicate pairs found in a cosine
similarity band and asks a chat judge for one word. `same` means the new claim
restates the old one: the old row is reinforced. `supersede` means the old
claim is now false — it is invalidated (never deleted, and never retrieved
again) while the new one stands. `distinct` means the two claims coexist and
an edge records how they touch. The case grades both the verdict word and the
state effect, because a right word that does not land on the store is still a
broken reconciliation.
