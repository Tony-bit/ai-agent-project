# Credential Rotation Checklist

The repository previously contained real credentials. Removing values from the current working tree is not enough: every exposed credential must be rotated at the provider side, and Git history must be cleaned before publishing the repository.

## Rotate These Credentials

- Tushare token
- DeepSeek API key
- DashScope API key
- Zhipu API key
- JD Cloud OSS access key and secret key
- Langfuse public key and secret key
- MySQL host/user/password that were committed with the application config

## Current File Policy

- Real secrets must come from environment variables only.
- Keep `.env` files local and untracked.
- Commit only `.env.example` files with placeholder values.
- Do not paste API keys into tests, YAML, Markdown, shell scripts, or screenshots.

## History Cleanup

Run this only after the provider-side rotation is complete and after coordinating with anyone who has cloned the repository.

Recommended tool:

```powershell
git filter-repo --path ai-agent-study-app/src/main/resources/application.yml --path ai-agent-study-domain/src/main/resources/config.yml --path ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/OpenAiTest.java --path ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/OSSTest.java --path docker-compose/mem0/.env --invert-paths
```

Then force-push the cleaned branches and tags:

```powershell
git push --force --all
git push --force --tags
```

Ask every collaborator to reclone or hard-reset to the cleaned history. Old clones, forks, CI logs, release artifacts, and screenshots may still contain the exposed values, so rotation remains mandatory even after history cleanup.

## Post-Cleanup Verification

Scan current files:

```powershell
rg -n "sk-[A-Za-z0-9_-]{20,}|ak-[A-Za-z0-9_-]{10,}|SK[A-Za-z0-9]{20,}|jdbc:mysql://[0-9]|secret-key:|api-key: [A-Za-z0-9]" -g "!target/**" -g "!.git/**" .
```

Scan history after rewriting:

```powershell
git log --all -- ai-agent-study-app/src/main/resources/application.yml ai-agent-study-domain/src/main/resources/config.yml docker-compose/mem0/.env
```

Expected result: no reachable commits contain real credential values.
