# AIEng-MCPServer

A Spring Boot Product REST API, plus a Spring AI MCP server that lets an AI client (Claude Code or Claude Desktop) manage those products through MCP tools.

```
Claude Code / Desktop ──stdio (MCP)──▶ product-mcp-server ──HTTP + Basic auth──▶ product-backend ──▶ H2 (in-memory)
```

| Module | What it is |
|--------|------------|
| `product-backend/` | REST service with CRUD on `Product` at `/api/products`. H2 in-memory, Bean Validation, RFC 7807 errors, HTTP Basic security. |
| `product-mcp-server/` | Spring AI MCP server (stdio) that exposes the backend's 5 endpoints as MCP tools and calls the backend with `RestClient`. |

## Requirements

- JDK 21 or newer (the build targets release 21)
- Nothing else: Maven comes from the wrapper (`mvnw` / `mvnw.cmd`)

## Build and test

From the repo root:

```bash
./mvnw test        # Windows: mvnw.cmd test
./mvnw package     # builds both jars into */target/
```

Jars:
- `product-backend/target/product-backend-0.0.1-SNAPSHOT.jar`
- `product-mcp-server/target/product-mcp-server-0.0.1-SNAPSHOT.jar`

## Run the backend

```bash
java -jar product-backend/target/product-backend-0.0.1-SNAPSHOT.jar
# another port: add --server.port=9090
```

It listens on `http://localhost:8080`. Data lives in memory and resets on every restart. Three products are seeded (ids 1–3: Wireless Mouse, Mechanical Keyboard, USB-C Hub), and new ids start at 4.

### Product

| Field | Type | Rules |
|-------|------|-------|
| `id` | Long | Set by the server |
| `name` | String | Required, max 100, trimmed |
| `description` | String | Optional, max 500, trimmed; blank becomes `null` |
| `price` | Decimal | Required, > 0 |
| `quantity` | Integer | Required, >= 0 |

Duplicate names are allowed. JSON typing is strict: `"price": "10.00"` or `"quantity": 1.5` is rejected.

### Endpoints

| Method | Path | Auth | Success |
|--------|------|------|---------|
| `POST` | `/api/products` | none | 201 + product |
| `GET` | `/api/products` | none | 200 + array (all, not paginated) |
| `GET` | `/api/products/{id}` | none | 200 + product |
| `PUT` | `/api/products/{id}` | none | 200 + product (full replace, no upsert) |
| `DELETE` | `/api/products/{id}` | `ADMIN` | 204 |

### Users (in-memory, HTTP Basic)

| Username | Password | Role |
|----------|----------|------|
| `admin` | `admin123` | `ADMIN` |
| `user` | `user123` | `USER` |

### Errors

Every error is an RFC 7807 `ProblemDetail`:

| Status | When |
|--------|------|
| 400 | Validation or JSON type failure. The body has an `errors` map (field → message) with every invalid field. |
| 401 | `DELETE` without credentials, or wrong credentials on any endpoint |
| 403 | `DELETE` by a non-admin (checked before the lookup, so an unknown id still gets 403) |
| 404 | Unknown id, including ids <= 0 |

### Try it

```bash
curl http://localhost:8080/api/products

curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Webcam","description":"1080p","price":1999.00,"quantity":5}'

curl -X DELETE -u admin:admin123 http://localhost:8080/api/products/4
```

## Run the MCP server

The MCP server is a stdio process. Your AI client starts it, so you don't run it by hand. The backend must already be running.

It is configured with environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `PRODUCT_API_BASE_URL` | `http://localhost:8080` | Backend URL |
| `PRODUCT_API_USERNAME` | empty | Basic auth username |
| `PRODUCT_API_PASSWORD` | empty | Basic auth password |

Basic auth is sent only when both username and password are set. Use the `admin` credentials if you want `delete_product` to work. Logs go to `<java.io.tmpdir>/product-mcp-server.log` so stdout stays clean for the protocol.

### Tools

| Tool | Backend call | Returns |
|------|--------------|---------|
| `list_products` | `GET /api/products` | JSON array |
| `get_product` | `GET /api/products/{id}` | Product JSON |
| `create_product` | `POST /api/products` | Product JSON |
| `update_product` | `PUT /api/products/{id}` | Product JSON |
| `delete_product` | `DELETE /api/products/{id}` | `Product <id> deleted` |

Input goes to the backend unchanged, so the backend does all validation. Backend errors come back as tool errors (`isError: true`) with the text `<status> <reason>: <detail>`, for example `403 Forbidden: Access Denied`. Validation errors add the fields, sorted by name: `400 Bad Request: Request has invalid fields (name: must not be blank; price: must be greater than 0)`.

### Claude Code

```bash
claude mcp add products --scope local \
  -e PRODUCT_API_BASE_URL=http://localhost:8080 \
  -e PRODUCT_API_USERNAME=admin \
  -e PRODUCT_API_PASSWORD=admin123 \
  -- java -jar /absolute/path/to/product-mcp-server/target/product-mcp-server-0.0.1-SNAPSHOT.jar
```

`--scope local` keeps the credentials out of the repo. Start a new Claude Code session, run `/mcp` to check that `products` is connected with 5 tools, then ask something like "list all products".

### Claude Desktop

Add this to `claude_desktop_config.json` and restart Claude Desktop:

```json
{
  "mcpServers": {
    "products": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/product-mcp-server/target/product-mcp-server-0.0.1-SNAPSHOT.jar"],
      "env": {
        "PRODUCT_API_BASE_URL": "http://localhost:8080",
        "PRODUCT_API_USERNAME": "admin",
        "PRODUCT_API_PASSWORD": "admin123"
      }
    }
  }
}
```

## How this project was built

It was built with Spec-Driven Development: docs first, then tests, then code, one step per session.

- `CLAUDE.md`: project rules and domain decisions
- `docs/requirements.md`: requirements (`FR-*`, `NFR-*`, `EC-*`, `AC-*`)
- `docs/modules/<module>/spec.md`, `test.md`, `plan.md` for `product`, `security` and `mcp-server`
- `sessions/session-NN-*/conversation.txt`: a log of every working session

Every test names its `test.md` ID (for example `PRODUCT-T03`).
