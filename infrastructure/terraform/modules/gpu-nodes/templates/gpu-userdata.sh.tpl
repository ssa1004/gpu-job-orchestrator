#!/bin/bash
set -euo pipefail

# GPU Node Initialization Script (GPU Workload Platform)
# This script runs on GPU node boot to prepare the instance for
# model optimization workloads.

# Verify NVIDIA driver is loaded
if ! nvidia-smi &>/dev/null; then
  echo "ERROR: NVIDIA driver not loaded. Check AMI compatibility."
  echo "Required: NVIDIA driver >= 530.x for CUDA 12.1 support"
  exit 1
fi

echo "NVIDIA Driver Info:"
nvidia-smi --query-gpu=driver_version,cuda_version --format=csv,noheader

%{ if enable_model_cache_volume }
# Mount model cache volume for faster model loading
DEVICE="/dev/xvdb"
MOUNT_PATH="${model_cache_mount_path}"

if [ -b "$DEVICE" ]; then
  # Format only if not already formatted
  if ! blkid "$DEVICE" &>/dev/null; then
    mkfs.ext4 -m 0 "$DEVICE"
  fi

  mkdir -p "$MOUNT_PATH"
  mount "$DEVICE" "$MOUNT_PATH"
  echo "$DEVICE $MOUNT_PATH ext4 defaults,nofail 0 2" >> /etc/fstab

  # Set permissions for Kubernetes pods
  chmod 777 "$MOUNT_PATH"
  echo "Model cache volume mounted at $MOUNT_PATH"
fi
%{ endif }

# Log GPU topology for debugging multi-GPU scheduling
nvidia-smi topo -m || true

# Set GPU persistence mode to reduce initialization latency
nvidia-smi -pm 1 || true

echo "GPU node initialization complete for cluster: ${cluster_name}"
