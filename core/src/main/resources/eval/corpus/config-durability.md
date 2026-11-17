# Configuration durability: files beat launchd environments

A LaunchAgent's environment variables are not durable configuration: a deploy
that rewrites or bootstraps the job can drop them silently, and a service
whose judge provider lived only in that environment falls back to its primary
chat without a single error in the log. The durable stores are ordinary files
in the service's base directory read at startup by the config loader: a
config.json pinning provider, base URL and model per leg, and a mode-0600 key
file resolved after the environment so an exported variable cannot be lost by
accident. Files survive plist surgery, deploys and reboots, and the falling
back is explicit in code (environment first, file second) rather than implied
by an empty default.
