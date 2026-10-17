-- App-owned schema (DESIGN §6.5-4: the ledger table is migrated by the application's
-- own mechanism, not embedded in the kit). Executed by Spring Boot SQL init.

CREATE TABLE IF NOT EXISTS demo_notes (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id  VARCHAR(32) NOT NULL,
  note       VARCHAR(255) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tenantkit_seed_history (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id    VARCHAR(32)  NOT NULL,
  script_name  VARCHAR(255) NOT NULL,
  checksum     CHAR(64)     NOT NULL,
  executed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tenantkit_seed (tenant_id, script_name)
) ENGINE=InnoDB;
