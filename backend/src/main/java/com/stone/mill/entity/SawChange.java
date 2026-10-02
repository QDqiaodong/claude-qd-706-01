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
 * 模块八：逐片产出归属更正审计（只追加，不更新、不删除）。
 *
 * <p>REGISTER 交接登记 / CONFIRM 负责人确认实际 / REASSIGN 跨批改派 / VOID 作废误登。
 * 每次变更都保留变更前后的产出 JSON（beforeJson / afterJson）与必填原因，
 * 大板详情据此反查历次更正。
 */
@Data
@Entity
@Table(name = "mill_saw_change")
public class SawChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    /** REGISTER / CONFIRM / REASSIGN / VOID */
    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "from_batch_id")
    private Long fromBatchId;

    @Column(name = "to_batch_id")
    private Long toBatchId;

    @Column(name = "before_json", length = 2000)
    private String beforeJson;

    @Column(name = "after_json", length = 2000)
    private String afterJson;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
