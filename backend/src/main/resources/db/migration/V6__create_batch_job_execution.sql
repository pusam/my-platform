CREATE TABLE batch_job_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name VARCHAR(200) NOT NULL,
    job_class VARCHAR(300) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3),
    duration_ms BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    error_message TEXT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_batch_job_name_started ON batch_job_execution (job_name, started_at DESC);
CREATE INDEX idx_batch_job_status ON batch_job_execution (status);
CREATE INDEX idx_batch_job_created ON batch_job_execution (created_at);
