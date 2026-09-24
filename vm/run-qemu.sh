#!/bin/bash
# Boots an Ubuntu 24.04 cloud-image VM under KVM on host CPUs that nothing else should use,
# with this repository's measurement in mind. Needs qemu-system-x86_64, cloud-localds (from
# cloud-image-utils), /dev/kvm and an SSH public key.
#
# Usage: vm/run-qemu.sh
# Env:   VM_DIR=~/.cache/obi-bench-vm  HOST_CPUS=8-15 (host cores to pin the VM to)
#        VCPUS=8 MEM=16G DISK=40G SSH_PORT=2222 SSH_KEY=~/.ssh/id_ed25519.pub
# Then:  ssh -p 2222 ubuntu@localhost, clone the repo, vm/setup.sh, vm/measure.sh
set -eu
VM_DIR=${VM_DIR:-$HOME/.cache/obi-bench-vm}
VCPUS=${VCPUS:-8}
HOST_CPUS=${HOST_CPUS:-8-$((8 + VCPUS - 1))}
MEM=${MEM:-16G}
DISK=${DISK:-40G}
SSH_PORT=${SSH_PORT:-2222}
SSH_KEY=${SSH_KEY:-$(ls ~/.ssh/id_ed25519.pub ~/.ssh/id_rsa.pub 2>/dev/null | head -1)}
IMAGE_URL=https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img
[ -n "$SSH_KEY" ] && [ -f "$SSH_KEY" ] || { echo "set SSH_KEY to an SSH public key file" >&2; exit 1; }

mkdir -p "$VM_DIR"
cd "$VM_DIR"
[ -f base.img ] || curl -fL -o base.img "$IMAGE_URL"
[ -f disk.qcow2 ] || qemu-img create -q -f qcow2 -F qcow2 -b base.img disk.qcow2 "$DISK"
cat > user-data <<CLOUD
#cloud-config
users:
  - name: ubuntu
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys: ["$(cat "$SSH_KEY")"]
package_update: true
packages: [git]
CLOUD
cloud-localds seed.img user-data

echo "booting: $VCPUS vCPUs pinned to host CPUs $HOST_CPUS, $MEM, ssh -p $SSH_PORT ubuntu@localhost"
# taskset keeps the whole VM, including its vCPU threads, on HOST_CPUS. Pick cores other
# workloads on the host do not use, or the measurement inherits their noise.
exec taskset -c "$HOST_CPUS" qemu-system-x86_64 \
    -enable-kvm -cpu host -smp "$VCPUS" -m "$MEM" \
    -drive file=disk.qcow2,if=virtio -drive file=seed.img,if=virtio,format=raw \
    -netdev user,id=n0,hostfwd=tcp::"$SSH_PORT"-:22 -device virtio-net-pci,netdev=n0 \
    -nographic
