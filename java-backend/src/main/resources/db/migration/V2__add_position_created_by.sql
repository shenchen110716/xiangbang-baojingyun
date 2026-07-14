ALTER TABLE work_positions ADD COLUMN created_by INTEGER REFERENCES users(id);
