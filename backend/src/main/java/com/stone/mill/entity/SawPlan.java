package com.stone.mill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块八：批次冻结的锯切排板顺序（开锯时从排板方案复制，建立后不可变）。
 *
 * <p>计划无对应产出 = 缺号（MISSING_NO）；实际尺寸对照 planLength/planWidth 判超差；
 * 同一 seqNo 出现两片 LIVE 产出 = 重复号（DUP_NO）。
 */
@Data
@Entity
@Table(name = "mill_saw_plan")
public class SawPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "plan_length_cm", nullable = false)
    private Integer planLengthCm;

    @Column(name = "plan_width_cm", nullable = false)
    private Integer planWidthCm;

    @Column(name = "layout_item_id")
    private Long layoutItemId;

    @Column(name = "slab_code", length = 30)
    private String slabCode;
}
