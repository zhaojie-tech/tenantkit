-- demo tenant seed: creates the per-tenant demo table payload (executed inside
-- the per-script transaction, ${tenantId} replaced before execution)
INSERT INTO demo_notes (tenant_id, note) VALUES ('${tenantId}', 'welcome to ${tenantId}');
INSERT INTO demo_notes (tenant_id, note) VALUES ('${tenantId}', 'provisioned by tenantkit');
