# sysmind-agent

Spring Boot 4 agent service for SysMind. It is configured with Spring AI for OpenAI-compatible chat models and with SysMind settings for calling the MCP backend.

The default local setup runs the agent on `http://localhost:4000`, targets LM Studio or another OpenAI-compatible server on `http://localhost:1234`, and calls the MCP backend on `http://localhost:8080/mcp`.

## Requirements

- Java 25
- Maven wrapper included in this repository
- Optional: LM Studio or another OpenAI-compatible chat endpoint
- Optional: `sysmind-mcp` running locally for MCP tool access

## Configuration

Main configuration lives in `src/main/resources/application.yaml`.

Useful environment variables:

```env
LM_STUDIO_BASE_URL=http://localhost:1234
LM_STUDIO_API_KEY=lm-studio
LM_STUDIO_MODEL=local-model
OPENAI_BASE_URL=http://localhost:1234
OPENAI_API_KEY=lm-studio
OPENAI_MODEL=local-model
AGENT_PORT=4000
MCP_BACKEND_URL=http://localhost:8080
MCP_ENDPOINT=/mcp
TOOL_TIMEOUT=10s
AGENT_TIMEOUT=60s
MAX_TOOL_CALLS_PER_USER_REQUEST=3
```

`LM_STUDIO_*` values take precedence over the matching `OPENAI_*` fallback values. `MCP_BACKEND_URL` should be the backend origin, while `MCP_ENDPOINT` supplies the MCP path. `MCP_ENDPOINT_PATH` remains supported as a fallback.

## Development

Run locally:

```bash
./mvnw spring-boot:run
```

The agent listens on `http://localhost:4000` by default.

Run tests:

```bash
./mvnw test
```

Build the jar:

```bash
./mvnw clean package
```

## Workspace

From the root `sysmind` repository, initialize this service with the other submodules:

```bash
./bootstrap-submodules.sh
```

Typical local flow:

```bash
cd ../sysmind-mcp
./mvnw spring-boot:run

cd ../sysmind-agent
./mvnw spring-boot:run
```

The root Compose stack does not run this service yet; it currently runs `sysmind-ui`, `sysmind-mcp`, Chroma, and nginx.
