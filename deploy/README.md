# Deploy & ops templates

Self-host and NAS deployment artifacts live here so the repo root stays focused on
application source (`server.py`, `bock_*.py`, mobile apps).

## systemd

Copy and edit the units under [`systemd/`](systemd/), then install to
`/etc/systemd/system/`:

```bash
cp deploy/systemd/ourmedia.service.example ourmedia.service   # edit paths + OURMEDIA_SKILL_ID
sudo cp ourmedia.service \
  deploy/systemd/ourmedia-stack.target \
  deploy/systemd/ourmedia-health.service.example \
  deploy/systemd/ourmedia-health.timer \
  /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ourmedia-stack.target ourmedia-health.timer
```

See the root [`README.md`](../README.md) **Full setup** section for tunnel units and
Alexa skill configuration.

## Hosted demo

[`render.yaml`](../render.yaml) at the repo root is the Render Blueprint for the
public demo instance.
