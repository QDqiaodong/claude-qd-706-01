package com.stone.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 五个模块的入参。 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------- 荒料 ----------------

    @Data
    public static class BlockSaveReq {

        @NotBlank(message = "荒料编号不能为空")
        private String blockCode;

        private String quarry;

        @NotBlank(message = "石种不能为空")
        private String stoneType;

        @NotNull(message = "长不能为空")
        private Integer lengthCm;

        @NotNull(message = "宽不能为空")
        private Integer widthCm;

        @NotNull(message = "高不能为空")
        private Integer heightCm;

        private LocalDate inboundDate;
        private BigDecimal purchasePrice;
        private String supplier;
        private String remark;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "状态不能为空")
        private String status;

        private String remark;
    }

    // ---------------- 大板 ----------------

    @Data
    public static class SlabSaveReq {

        @NotBlank(message = "大板编号不能为空")
        private String slabCode;

        @NotNull(message = "所属荒料不能为空")
        private Long blockId;

        @NotNull(message = "长不能为空")
        private Integer lengthCm;

        @NotNull(message = "宽不能为空")
        private Integer widthCm;

        /** 可空：默认 2.0 cm */
        private BigDecimal thicknessCm;

        @NotBlank(message = "表面处理不能为空")
        private String finish;

        @NotBlank(message = "质量等级不能为空")
        private String grade;

        private String location;
        private String remark;
    }

    // ---------------- 加工工单 ----------------

    @Data
    public static class OrderSaveReq {

        @NotBlank(message = "工单号不能为空")
        private String orderNo;

        @NotNull(message = "荒料不能为空")
        private Long blockId;

        @NotBlank(message = "锯切方式不能为空")
        private String sawType;

        @NotNull(message = "计划板数不能为空")
        private Integer planCount;

        private String team;
        private LocalDate startDate;
        private String remark;
    }

    @Data
    public static class ProgressReq {

        /** 本次完成的板数 */
        private Integer delta = 1;
    }

    // ---------------- 排板 ----------------

    @Data
    public static class RelayoutReq {

        /** 参与重排的大板 id，留空表示取该荒料全部大板 */
        private List<Long> slabIds;
    }

    // ---------------- 出库发货 ----------------

    @Data
    public static class DeliverySaveReq {

        @NotBlank(message = "发货单号不能为空")
        private String deliveryNo;

        @NotBlank(message = "客户不能为空")
        private String customer;

        private String contact;
        private String phone;
        private String plateNo;
        private String driver;
        private LocalDate deliveryDate;
        private String remark;
    }

    @Data
    public static class DeliveryItemReq {

        @NotNull(message = "大板不能为空")
        private Long slabId;

        /** 可空：默认 1 片 */
        private Integer quantity;

        @NotNull(message = "单价不能为空")
        private BigDecimal unitPrice;
    }

    // ---------------- 质量隔离 ----------------

    @Data
    public static class QualityHoldCreateReq {

        @NotNull(message = "大板不能为空")
        private Long slabId;

        @NotBlank(message = "缺陷类型不能为空")
        private String defectType;

        @NotBlank(message = "严重程度不能为空")
        private String severity;

        private String affectPosition;

        /** 发现环节可空（客户事后补录时可能填客户反馈） */
        private String foundStage;

        private String siteNote;

        private String disposeAdvice;

        private String reporter;
    }

    /** 送复检：仅追加意见，状态 隔离中 → 待复检 */
    @Data
    public static class SubmitRecheckReq {

        private String note;

        private String operator;
    }

    /**
     * 复检结论（追加留痕，不覆盖最初缺陷与历次意见）：
     * PASS 合格放行（待复检→已放行，解除发货挂起）；
     * REWORK 返工观察（状态留在待复检，只追加一条复检意见）；
     * SCRAP 报废（待复检→已报废，从未发运发货单移除明细并重算金额）。
     */
    @Data
    public static class RecheckReq {

        @NotBlank(message = "复检结论不能为空：PASS / REWORK / SCRAP")
        private String conclusion;

        private String opinion;

        private String operator;
    }

    // ---------------- 锯切批次交接与产出谱系 ----------------

    /** 开锯建立批次：冻结工单（荒料/锯切方式随之快照）与排板顺序 */
    @Data
    public static class BatchCreateReq {

        @NotNull(message = "加工工单不能为空")
        private Long orderId;

        /** 尺寸超差阈值 cm，可空默认 2 */
        private Integer toleranceCm;

        private String operator;
    }

    /**
     * 交接逐片登记：
     * clientToken 为现场扫码幂等令牌，断网重试 / 重复扫码同 token 只产生一片；
     * FINISHED / REMNANT 必须给大板编号（不存在且在本批荒料上则自动建档）；
     * SAW_LOSS 必须在 note 填写损耗原因，否则挂「未解释锯耗」差异。
     */
    @Data
    public static class OutputRegisterReq {

        @NotBlank(message = "现场扫码令牌不能为空（断网重试靠它幂等）")
        private String clientToken;

        @NotBlank(message = "产出类型不能为空：FINISHED / REMNANT / SAW_LOSS")
        private String outputType;

        private String slabCode;

        /** 对应排板顺序号（计划外余料可空） */
        private Integer seqNo;

        private Integer actualLengthCm;
        private Integer actualWidthCm;
        private java.math.BigDecimal thicknessCm;

        /** 返切来源大板 id（可空：普通荒料锯切） */
        private Long sourceSlabId;

        private String operator;

        private String producedAt;

        /** 现场备注（锯耗原因） */
        private String note;
    }

    /** 交接：班组完成逐片登记，批次进入差异待核 */
    @Data
    public static class HandoverReq {

        private String operator;
        private String note;
    }

    /** 差异逐项核清：必须填原因 */
    @Data
    public static class DiffResolveReq {

        @NotBlank(message = "核清方式不能为空：CONFIRM / REASSIGN / VOID")
        private String resolution;

        @NotBlank(message = "必须填写核清原因，差异不允许无说明关闭")
        private String reason;

        private String operator;
    }

    /**
     * 跨批次更正归属：把误登到本批的产出改派到真正归属批次（保留先到规则，不静默覆盖）。
     * 目标批次下可同时更正顺序号。
     */
    @Data
    public static class ReassignReq {

        @NotNull(message = "目标批次不能为空")
        private Long targetBatchId;

        private Integer targetSeqNo;

        @NotBlank(message = "改派原因不能为空")
        private String reason;

        private String operator;
    }

    /** 更正登记内容（实际尺寸 / 顺序 / 锯耗原因），用于 FIX 核清 */
    @Data
    public static class OutputCorrectReq {

        private Integer seqNo;
        private Integer actualLengthCm;
        private Integer actualWidthCm;
        private java.math.BigDecimal thicknessCm;
        private String note;

        @NotBlank(message = "更正原因不能为空")
        private String reason;

        private String operator;
    }

    /** 作废误登产出：必须填原因；已被后续批次返切引用的产出禁止作废 */
    @Data
    public static class OutputVoidReq {

        @NotBlank(message = "作废原因不能为空")
        private String reason;

        private String operator;
    }

    /** 收口批次：只有差异全部核清才放行 */
    @Data
    public static class BatchCloseReq {

        private String operator;
        private String remark;
    }
}
