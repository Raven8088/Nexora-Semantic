-- Test-only: the DML application role used by the application datasource. Flyway runs as the container
-- superuser, which plays the schema owner role, and grants privileges to this role.
CREATE ROLE nexora_semantic_app LOGIN PASSWORD 'app-test-password';
