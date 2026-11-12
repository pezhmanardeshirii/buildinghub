# BuildingHub

Starter backend for a building-management application.

## Prerequisites
- Java 21
- Maven 3.9+
- Docker Desktop

## Start PostgreSQL
```bash
docker compose up -d
```

## Run application
```bash
mvn spring-boot:run
```

The application runs at `http://localhost:8080`.

- Status endpoint: `GET http://localhost:8080/api/v1/status`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

Liquibase creates the initial `buildings` and `units` tables. Hibernate is configured with `ddl-auto: validate`; schema changes should be made through Liquibase.

Default local database credentials are intended for development only. Do not use them in production.
