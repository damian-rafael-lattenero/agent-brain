# Embedding providers and dimensions

The default provider is Ollama with the `nomic-embed-text` model, which produces
768-dimensional vectors and runs locally at no cost. The alternative is Z.ai's
`embedding-3`, which accepts 256, 512, 1024 or 2048 dimensions through the
`dimensions` request field and needs an API key. Because pgvector columns have a
fixed dimension, the database remembers the dimension it was created with and
refuses a provider that disagrees.
