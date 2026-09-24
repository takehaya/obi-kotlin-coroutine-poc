# Shortcuts for the demo stack. See PLAYGROUND.md for a guided walkthrough.
#
# The compose stack is managed from demo/ with `sudo -E docker compose`,
# matching the commands documented in README.md.

# JVM options that attach the coroutine agent to the frontend (see README.md).
AGENT_OPTS = -javaagent:/coroagent/coroagent.jar -Dobicoro.native=/coroagent/libcoroagent.so

# Output directories for the load scripts and their analyzers.
RESULTS ?= results
RESULTS_CONCURRENT ?= results-concurrent

.PHONY: build check-hooks up up-baseline up-cio up-epoll up-vt load analyze load-concurrent analyze-concurrent down clean

# Build the demo services, the agent jars, and the JNI library.
build:
	./build.sh

# Check that every agent hook still matches (no Docker needed; port 8080 must be free).
check-hooks:
	demo/check_hooks.sh

# Start the stack with the coroutine agent attached to the frontend.
up:
	cd demo && FRONTEND_JAVA_OPTS="$(AGENT_OPTS)" sudo -E docker compose up -d

# Start the stack without the agent: reproduces the baseline (split traces).
up-baseline:
	cd demo && FRONTEND_JAVA_OPTS="" sudo -E docker compose up -d

# Variant: frontend on the CIO server engine instead of Netty.
up-cio:
	cd demo && FRONTEND_JAVA_OPTS="$(AGENT_OPTS)" KTOR_ENGINE=cio sudo -E docker compose up -d --force-recreate

# Variant: Netty on the native epoll transport instead of NIO.
up-epoll:
	cd demo && FRONTEND_JAVA_OPTS="$(AGENT_OPTS)" NETTY_TRANSPORT=epoll sudo -E docker compose up -d --force-recreate

# Variant: control service on a virtual-thread-per-task executor.
up-vt:
	cd demo && FRONTEND_JAVA_OPTS="$(AGENT_OPTS)" JFRONT_EXECUTOR=virtual sudo -E docker compose up -d --force-recreate

# Drive the four sequential conditions and fetch traces from the Jaeger API.
load:
	demo/run_conditions.sh $(RESULTS)

# Summarize how spans grouped into traces, per condition.
analyze:
	python3 demo/analyze_jaeger.py $(RESULTS)

# Drive the same endpoints under concurrent load.
load-concurrent:
	demo/run_concurrent.sh $(RESULTS_CONCURRENT)

# Summarize the concurrent run (connected vs. orphaned spans).
analyze-concurrent:
	python3 demo/analyze_concurrent.py $(RESULTS_CONCURRENT)

# Stop and remove the containers (also discards Jaeger's in-memory traces).
down:
	cd demo && sudo -E docker compose down

# Remove build outputs and downloaded results.
clean:
	./gradlew --no-daemon clean
	rm -rf agent/dist demo/results*
