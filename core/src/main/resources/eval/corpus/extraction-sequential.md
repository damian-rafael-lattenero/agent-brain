# Sequential extraction against one runner

The memory worker's extraction concurrency defaults to one turn at a time.
The chat runner behind the provider is a single process that serves requests
one by one, so firing a whole batch of extractions concurrently only queues
them behind each other — and each queued call burns its own timeout budget
while it waits, turning a healthy provider into a wall of transport timeouts
even though threads are never short. A bounded traverseSettled with
extraction_concurrency equals one keeps every budget honest: each turn gets
the full runner for its own generation, the queue drains in arrival order, and
the knob stays available for providers that genuinely scale out. The law is
pinned by an integration test that scripts a slow chat and asserts the
maximum observed concurrency is exactly one.
