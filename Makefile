# Shortcuts for the demo stack. See PLAYGROUND.md for a guided walkthrough.
#
# The compose stack is managed from demo/ with `sudo -E docker compose`,
# matching the commands documented in README.md.

# JVM options that attach the coroutine agent to the frontend (see README.md).
AGENT_OPTS = -javaagent:/coroagent/coroagent.jar -Dobicoro.native=/coroagent/libcoroagent.so

.PHONY: build up up-baseline load analyze down clean

# Build the demo services, the agent jars, and the JNI library.
build:
	./build.sh

# Start the stack with the coroutine agent attached to the frontend.
up:
	cd demo && FRONTEND_JAVA_OPTS="$(AGENT_OPTS)" sudo -E docker compose up -d

# Start the stack without the agent: reproduces the baseline (split traces).
up-baseline:
	cd demo && FRONTEND_JAVA_OPTS="" sudo -E docker compose up -d

# Drive the four sequential conditions and fetch traces from the Jaeger API.
load:
	demo/run_conditions.sh

# Summarize how spans grouped into traces, per condition.
analyze:
	python3 demo/analyze_jaeger.py results

# Stop and remove the containers (also discards Jaeger's in-memory traces).
down:
	cd demo && sudo -E docker compose down

# Remove build outputs and downloaded results.
clean:
	./gradlew --no-daemon clean
	rm -rf agent/dist demo/results demo/results-concurrent
