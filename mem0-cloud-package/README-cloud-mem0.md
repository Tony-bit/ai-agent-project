# Mem0 cloud deploy package

## Upload

Upload this directory or `mem0-cloud-package.zip` to your server, for example:

```bash
/opt/ai-agent
```

The final server layout should be:

```text
/opt/ai-agent/
  docker-compose.yml
  .env
  mem0/
    Dockerfile
    main.py
    requirements.txt
    .env
    vendor/
      mem0/
    history/
      history.db
```

## Configure

Edit root `.env` before starting:

```bash
cd /opt/ai-agent
vi .env
```

Replace:

- `DEEPSEEK_API_KEY`
- `AI_DASHSCOPE_API_KEY`
- `POSTGRES_PASSWORD`

This package includes your customized local mem0 Python source under
`mem0/vendor/mem0`. The Docker image copies it over the pip-installed mem0
package during build.

## Start

```bash
cd /opt/ai-agent
docker compose up -d --build
docker logs -f mem0
```

## Verify

```bash
curl http://127.0.0.1:8000/
```

If your Java app calls this server remotely, set the Java mem0 base URL to:

```text
http://your-server-ip:8000
```

For production, restrict port `8000` by security group or firewall to only your Java app's IP.
