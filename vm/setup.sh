#!/bin/bash
# Prepares a fresh Ubuntu 24.04 machine (VM or cloud instance) for vm/measure.sh:
# installs Docker, JDK 21, gcc, python3 and bpftrace, checks the kernel, and builds the demo.
# Run it from a clone of this repository; it uses sudo.
set -eu
cd "$(dirname "$0")/.."

major=$(uname -r | cut -d. -f1); minor=$(uname -r | cut -d. -f2)
if [ "$major" -lt 5 ] || { [ "$major" -eq 5 ] && [ "$minor" -lt 8 ]; }; then
    echo "kernel $(uname -r) is older than 5.8; OBI needs 5.8+ with BTF" >&2; exit 1
fi
[ -e /sys/kernel/btf/vmlinux ] || { echo "no /sys/kernel/btf/vmlinux: OBI needs a BTF-enabled kernel" >&2; exit 1; }

sudo apt-get update -q
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q \
    docker.io docker-compose-v2 openjdk-21-jdk-headless gcc python3 bpftrace curl git
sudo systemctl enable --now docker

# Pull the images once so the first measurement does not include download time.
(cd demo && sudo docker compose pull -q)
sudo docker pull -q python:3.12-slim   # the open-loop load generator's container

./build.sh
demo/check_hooks.sh
echo "ready: run vm/measure.sh"
