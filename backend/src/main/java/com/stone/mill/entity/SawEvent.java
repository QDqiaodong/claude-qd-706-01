package com.stone.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块八：锯切批次生命周期留痕（只追加）。
 * CREATE 开锯建立 / HANDOVER 交接待核 / CLOSE 收口固化。
 */
@Data
@Entity
@Table(name = "mill_saw_event")
public class SawEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** CREATE / HANDOVER / CLOSE */
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    @Column(name = "content", length = 500)
    private String content;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
