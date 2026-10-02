package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/** Intentionally exposes no update/delete helpers beyond what JpaRepository provides; the service never calls them. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(String entityType, Long entityId);
}
