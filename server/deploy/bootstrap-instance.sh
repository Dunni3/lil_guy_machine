#!/usr/bin/env bash
# Run once on a fresh Ubuntu 24.04 Lightsail instance (Small: 2 GB, its own box, not smarter-tv's)
# as the default user:
#   ssh ubuntu@<ip> 'bash -s' < server/deploy/bootstrap-instance.sh
set -euo pipefail

sudo apt-get update
sudo apt-get install -y ca-certificates curl ufw unattended-upgrades

# Docker Engine + compose plugin from Docker's apt repo.
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"

# Host firewall (also open 22/80/443 in the Lightsail firewall). Postgres is never published.
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw --force enable

# Swap, so an image build next to Postgres and the sim host (next phase) doesn't OOM.
if [[ ! -f /swapfile ]]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

mkdir -p ~/lilguy-server ~/backups
# Nightly pg_dump at 04:15 UTC, two weeks kept (server/deploy/backup.sh).
( crontab -l 2>/dev/null | grep -v lilguy-backup; echo "15 4 * * * \$HOME/lilguy-server/deploy/backup.sh >> \$HOME/backups/backup.log 2>&1 # lilguy-backup" ) | crontab -
echo "Done. Log out and back in (docker group), create ~/lilguy-server/.env from .env.example, then run 'make server-deploy' from the Mac."
