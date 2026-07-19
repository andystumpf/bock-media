#!/usr/bin/env bash
# Run ON THE BOCK MEDIA SERVER, not on your phone/Mac.
# Opens OS firewall for router port-forwards and verifies the backend is listening.
#
# Usage (on server):
#   cd ~/bock-media
#   sudo bash scripts/configure-external-access.sh

set -euo pipefail

PORTS=(3001 3005)
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

log() { echo "[configure-external-access] $*"; }

open_firewall() {
    if command -v ufw >/dev/null 2>&1; then
        log "Configuring ufw for TCP ${PORTS[*]}..."
        for p in "${PORTS[@]}"; do
            ufw allow "${p}/tcp" comment "Bock Media" || true
        done
        ufw status verbose || true
        return
    fi
    if command -v firewall-cmd >/dev/null 2>&1; then
        log "Configuring firewalld..."
        for p in "${PORTS[@]}"; do
            firewall-cmd --permanent --add-port="${p}/tcp" || true
        done
        firewall-cmd --reload || true
        return
    fi
    log "No ufw/firewalld found — if connections still fail, check iptables/nftables manually."
}

check_listen() {
    log "Checking listeners on ${PORTS[*]}..."
    if command -v ss >/dev/null 2>&1; then
        ss -tlnp | grep -E ':3001|:3005' || true
    else
        netstat -tlnp 2>/dev/null | grep -E ':3001|:3005' || true
    fi
}

check_local_health() {
    log "Probing http://127.0.0.1:3001/api/health ..."
    if curl -sf -m 5 "http://127.0.0.1:3001/api/health" >/dev/null; then
        log "OK — backend responds on localhost:3001"
    else
        log "FAIL — nothing on localhost:3001. Is ourmedia.service running?"
        systemctl is-active ourmedia.service 2>/dev/null || true
        return 1
    fi
    log "Probing http://$(hostname -I | awk '{print $1}'):3001/api/health ..."
    local lan_ip
    lan_ip=$(hostname -I | awk '{print $1}')
    if curl -sf -m 5 "http://${lan_ip}:3001/api/health" >/dev/null; then
        log "OK — backend reachable on LAN IP ${lan_ip}:3001 (port-forward ready)"
    else
        log "WARN — localhost works but LAN IP does not."
        log "      gunicorn may be bound to 127.0.0.1 only. Use deploy/systemd/ourmedia.service.example:"
        log "      sudo cp ${REPO_DIR}/deploy/systemd/ourmedia.service.example /etc/systemd/system/ourmedia.service"
        log "      sudo systemctl daemon-reload && sudo systemctl restart ourmedia"
        return 1
    fi
}

install_service_if_needed() {
    if systemctl cat ourmedia.service >/dev/null 2>&1; then
        if systemctl cat ourmedia.service | grep -q '127.0.0.1:3001'; then
            log "ourmedia.service binds 127.0.0.1 — port-forward needs 0.0.0.0:3001"
            log "Install example unit:"
            log "  sudo cp ${REPO_DIR}/deploy/systemd/ourmedia.service.example /etc/systemd/system/ourmedia.service"
            log "  sudo systemctl daemon-reload && sudo systemctl restart ourmedia"
        fi
    else
        log "ourmedia.service not installed. Copy deploy/systemd/ourmedia.service.example and enable it."
    fi
}

main() {
    if [[ "$(id -u)" -ne 0 ]]; then
        log "Re-run with sudo: sudo bash $0"
        exit 1
    fi
    open_firewall
    install_service_if_needed
    check_listen
    check_local_health
    log "Done. From outside your network, test:"
    log "  curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer YOUR_TOKEN' http://YOUR_PUBLIC_IP:3001/api/health"
    log "Port 3005 only responds while Alexa browser login is active (Settings → Start browser login)."
}

main "$@"
